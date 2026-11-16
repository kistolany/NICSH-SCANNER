package kh.cmt.attendance.infrastructure;

import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/** {@code attendance_day_close}: the school days the end-of-day job has closed. */
@Repository
@RequiredArgsConstructor
public class DayCloseStore {

    private final JdbcClient jdbc;

    /** Every school (the job runs for each). */
    public List<UUID> schools() {
        return jdbc.sql("select id from school order by id").query(UUID.class).list();
    }

    public Optional<LocalDate> lastClosed(UUID school) {
        return jdbc.sql("select max(work_date) from attendance_day_close where school_id = :s").param("s", school)
                .query(LocalDate.class).optional();
    }

    public boolean closed(UUID school, LocalDate day) {
        return jdbc.sql("select count(*) from attendance_day_close where school_id = :s and work_date = :d").param("s", school).param("d", day)
                .query(Long.class).single() > 0;
    }

    /** Closed (again): the counts of the latest run. */
    public void save(UUID school, LocalDate day, Instant at, int people, int absent, int missing) {
        jdbc.sql("""
                insert into attendance_day_close (school_id, work_date, closed_at, people, absent, missing)
                values (:s, :d, :at, :people, :absent, :missing)
                on conflict (school_id, work_date) do update set closed_at = excluded.closed_at, people = excluded.people,
                    absent = attendance_day_close.absent + excluded.absent, missing = attendance_day_close.missing + excluded.missing""")
                .param("s", school).param("d", day).param("at", OffsetDateTime.ofInstant(at, ZoneOffset.UTC))
                .param("people", people).param("absent", absent).param("missing", missing)
                .update();
    }
}
