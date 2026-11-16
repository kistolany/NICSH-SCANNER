package kh.cmt.people.infrastructure;

import java.util.Collection;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * Whole-school facts about people, in plain SQL: they must not depend on the caller's record scope (is a Staff ID or
 * national ID already used, who supervises whom, department of a person while a scoped query is being prepared, how
 * many people use a department...).
 */
@Repository
public class PersonDirectory {

    /** Who a person is, for showing a supervisor or a link. */
    public record PersonRef(UUID id, String staffId, String nameKm, String nameEn, String nameAr, boolean inactive, UUID photoFileId) {
    }

    private final JdbcClient jdbc;

    public PersonDirectory(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /** Any person of the school (inactive ones too) except {@code exceptId} has this Staff ID (case-insensitive)? */
    public boolean staffIdTaken(UUID schoolId, String staffId, UUID exceptId) {
        return jdbc.sql("select count(*) from person where school_id = :s and lower(staff_id) = lower(:id) and id <> :except")
                .param("s", schoolId).param("id", staffId).param("except", orNone(exceptId))
                .query(Integer.class).single() > 0;
    }

    public boolean nationalIdTaken(UUID schoolId, String nationalId, UUID exceptId) {
        return jdbc.sql("select count(*) from person where school_id = :s and upper(national_id) = upper(:n) and id <> :except")
                .param("s", schoolId).param("n", nationalId).param("except", orNone(exceptId))
                .query(Integer.class).single() > 0;
    }

    public boolean isActive(UUID schoolId, UUID personId) {
        return jdbc.sql("select count(*) from person where school_id = :s and id = :id and not inactive")
                .param("s", schoolId).param("id", personId).query(Integer.class).single() > 0;
    }

    /** The supervisor of a person (null if none or no such person). */
    public UUID supervisorOf(UUID personId) {
        return jdbc.sql("select supervisor_id from person where id = :id").param("id", personId)
                .query((rs, n) -> rs.getObject(1, UUID.class)).optional().orElse(null);
    }

    public Optional<UUID> departmentOf(UUID personId) {
        return jdbc.sql("select department_id from person where id = :id and department_id is not null").param("id", personId)
                .query(UUID.class).optional();
    }

    public Map<UUID, PersonRef> refs(Collection<UUID> ids) {
        Map<UUID, PersonRef> found = new HashMap<>();
        if (ids.isEmpty()) {
            return found;
        }
        jdbc.sql("select id, staff_id, name_km, name_en, name_ar, inactive, photo_file_id from person where id in (:ids)").param("ids", ids)
                .query((rs, n) -> found.put(rs.getObject("id", UUID.class), new PersonRef(rs.getObject("id", UUID.class),
                        rs.getString("staff_id"), rs.getString("name_km"), rs.getString("name_en"), rs.getString("name_ar"),
                        rs.getBoolean("inactive"), rs.getObject("photo_file_id", UUID.class))))
                .list();
        return found;
    }

    /** Employment start dates of these people (those without one are left out). */
    public Map<UUID, java.time.LocalDate> startDates(Collection<UUID> ids) {
        Map<UUID, java.time.LocalDate> found = new HashMap<>();
        jdbc.sql("select id, start_date from person where id in (:ids) and start_date is not null").param("ids", ids)
                .query((rs, n) -> found.put(rs.getObject("id", UUID.class), rs.getObject("start_date", java.time.LocalDate.class)))
                .list();
        return found;
    }

    /** Every active person of the school with their department. */
    public java.util.List<kh.cmt.people.api.PeopleQueries.PersonPlace> activePeople(UUID schoolId) {
        return jdbc.sql("select id, department_id from person where school_id = :s and not inactive").param("s", schoolId)
                .query((rs, n) -> new kh.cmt.people.api.PeopleQueries.PersonPlace(rs.getObject("id", UUID.class),
                        rs.getObject("department_id", UUID.class)))
                .list();
    }

    /** Expired documents (expiry date before {@code today}) per person, for these people. */
    public Map<UUID, Integer> expiredDocuments(Collection<UUID> personIds, java.time.LocalDate today) {
        Map<UUID, Integer> counts = new HashMap<>();
        if (personIds.isEmpty()) {
            return counts;
        }
        jdbc.sql("""
                        select person_id, count(*) as n from person_document
                        where person_id in (:ids) and expiry_date < :today group by person_id""")
                .param("ids", personIds).param("today", today)
                .query((rs, n) -> counts.put(rs.getObject("person_id", UUID.class), rs.getInt("n"))).list();
        return counts;
    }

    /** Whose photo or document a file is (whole school, whatever the caller's scope). */
    public record FileOwner(UUID personId, String documentType) {
    }

    public Optional<FileOwner> ownerOfFile(UUID fileId) {
        Optional<FileOwner> photo = jdbc.sql("select id from person where photo_file_id = :f").param("f", fileId)
                .query(UUID.class).optional().map(p -> new FileOwner(p, null));
        if (photo.isPresent()) {
            return photo;
        }
        return jdbc.sql("select person_id, type from person_document where file_id = :f").param("f", fileId)
                .query((rs, n) -> new FileOwner(rs.getObject("person_id", UUID.class), rs.getString("type"))).optional();
    }

    /** Active people per department or position ({@code column} = department_id | position_id). */
    public Map<UUID, Long> countsBy(UUID schoolId, String column) {
        if (!column.equals("department_id") && !column.equals("position_id")) {
            throw new IllegalArgumentException(column);
        }
        Map<UUID, Long> counts = new HashMap<>();
        jdbc.sql("select " + column + " as k, count(*) as n from person where school_id = :s and not inactive and "
                        + column + " is not null group by " + column)
                .param("s", schoolId)
                .query((rs, n) -> counts.put(rs.getObject("k", UUID.class), rs.getLong("n")))
                .list();
        return counts;
    }

    public long countActive(UUID schoolId, Set<UUID> departments) {
        if (departments == null) {
            return jdbc.sql("select count(*) from person where school_id = :s and not inactive")
                    .param("s", schoolId).query(Long.class).single();
        }
        if (departments.isEmpty()) {
            return 0;
        }
        return jdbc.sql("select count(*) from person where school_id = :s and not inactive and department_id in (:d)")
                .param("s", schoolId).param("d", departments).query(Long.class).single();
    }

    private static UUID orNone(UUID id) {
        return id == null ? new UUID(0, 0) : id;
    }
}
