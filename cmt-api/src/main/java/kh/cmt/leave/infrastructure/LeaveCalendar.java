package kh.cmt.leave.infrastructure;

import java.time.LocalDate;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import kh.cmt.schedule.api.PersonLeave;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * Approved leave as the schedule resolver asks for it (step 9.2), in plain SQL: whole school, whatever the caller's
 * record scope (the scan, the end-of-day job and reports all ask about people). A whole-day request covers every day of
 * its range; a half day covers its date with {@code half} AM | PM.
 */
@Repository
class LeaveCalendar implements PersonLeave {

    private final JdbcClient jdbc;

    LeaveCalendar(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public Optional<LeaveDay> leaveOn(UUID personId, LocalDate day) {
        return Optional.ofNullable(leavesIn(personId, day, day).get(day));
    }

    @Override
    public Map<LocalDate, LeaveDay> leavesIn(UUID personId, LocalDate from, LocalDate to) {
        Map<LocalDate, LeaveDay> out = new HashMap<>();
        jdbc.sql("""
                        select r.id, t.code, r.start_date, r.end_date, r.half from leave_request r join leave_type t on t.id = r.leave_type_id
                        where r.person_id = :p and r.status = 'APPROVED' and r.start_date <= :to and r.end_date >= :from
                        order by r.created_at""")
                .param("p", personId).param("from", from).param("to", to)
                .query((rs, n) -> {
                    UUID id = rs.getObject("id", UUID.class);
                    String code = rs.getString("code");
                    String half = rs.getString("half");
                    LocalDate s = rs.getObject("start_date", LocalDate.class);
                    LocalDate e = rs.getObject("end_date", LocalDate.class);
                    for (LocalDate d = s.isBefore(from) ? from : s; !d.isAfter(e) && !d.isAfter(to); d = d.plusDays(1)) {
                        LeaveDay mine = new LeaveDay(id, code, half == null, Set.of(), half);
                        LeaveDay there = out.get(d);
                        // the morning and the afternoon of one date approved apart: the whole day
                        out.put(d, there == null ? mine : new LeaveDay(there.id(), there.type(), true, Set.of(), null));
                    }
                    return id;
                })
                .list();
        return out;
    }
}
