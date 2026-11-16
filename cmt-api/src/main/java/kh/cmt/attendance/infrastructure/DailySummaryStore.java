package kh.cmt.attendance.infrastructure;

import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/** {@code attendance_daily_summary}: one row per person and day, what reports read (step 12.1's table). */
@Repository
@RequiredArgsConstructor
public class DailySummaryStore {

    public record Summary(UUID schoolId, UUID personId, LocalDate date, String status, int requiredMin, int workedMin, int lateMin, int earlyMin,
            boolean absent, boolean leave, boolean closed) {
    }

    private final JdbcClient jdbc;

    public void save(Summary s, Instant at) {
        jdbc.sql("""
                insert into attendance_daily_summary (school_id, person_id, work_date, status, required_min, worked_min, late_min, early_min,
                    absent, leave, closed, updated_at)
                values (:school, :person, :date, :status, :required, :worked, :late, :early, :absent, :leave, :closed, :at)
                on conflict (person_id, work_date) do update set status = excluded.status, required_min = excluded.required_min,
                    worked_min = excluded.worked_min, late_min = excluded.late_min, early_min = excluded.early_min, absent = excluded.absent,
                    leave = excluded.leave, closed = attendance_daily_summary.closed or excluded.closed, updated_at = excluded.updated_at""")
                .param("school", s.schoolId()).param("person", s.personId()).param("date", s.date()).param("status", s.status())
                .param("required", s.requiredMin()).param("worked", s.workedMin()).param("late", s.lateMin()).param("early", s.earlyMin())
                .param("absent", s.absent()).param("leave", s.leave()).param("closed", s.closed())
                .param("at", OffsetDateTime.ofInstant(at, ZoneOffset.UTC))
                .update();
    }

    /** The person had nothing that day (any more): no row. */
    public void remove(UUID personId, LocalDate date) {
        jdbc.sql("delete from attendance_daily_summary where person_id = :p and work_date = :d").param("p", personId).param("d", date).update();
    }

    public Optional<Summary> find(UUID personId, LocalDate date) {
        return jdbc.sql("""
                select school_id, person_id, work_date, status, required_min, worked_min, late_min, early_min, absent, leave, closed
                from attendance_daily_summary where person_id = :p and work_date = :d""")
                .param("p", personId).param("d", date)
                .query((rs, n) -> new Summary(rs.getObject(1, UUID.class), rs.getObject(2, UUID.class), rs.getObject(3, LocalDate.class),
                        rs.getString(4), rs.getInt(5), rs.getInt(6), rs.getInt(7), rs.getInt(8), rs.getBoolean(9), rs.getBoolean(10),
                        rs.getBoolean(11)))
                .optional();
    }
}
