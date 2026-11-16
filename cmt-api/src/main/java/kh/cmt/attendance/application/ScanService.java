package kh.cmt.attendance.application;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import kh.cmt.approvals.api.Approvals;
import kh.cmt.attendance.api.AttendanceRecorded;
import kh.cmt.attendance.domain.AttendanceRecord;
import kh.cmt.attendance.domain.ScanEvent;
import kh.cmt.attendance.infrastructure.AttendanceRecordRepository;
import kh.cmt.attendance.infrastructure.ScanEventRepository;
import kh.cmt.qrlocation.api.Locations;
import kh.cmt.schedule.api.DaySchedules;
import kh.cmt.shared.events.Outbox;
import kh.cmt.shared.security.CurrentUser;
import kh.cmt.shared.web.ApiException;
import kh.cmt.shared.web.ErrorCode;
import lombok.AccessLevel;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The scan (step 7.2): the staff app scanned a gate QR. In one transaction: the {@link ScanChecks} (phone, QR, place)
 * → the day ("today's schedule": a working day with shifts not on leave) → check-in or check-out → the scan event, the
 * record and the outbox event {@link AttendanceRecorded}.
 * <p>
 * <b>Which action:</b> a shift checked in and not out → this scan checks it out (a second scan within
 * {@link #MIN_GAP} of the check-in is "already checked in"); else the first shift not yet scanned that has not ended →
 * check-in (so with two shifts, scanning after shift 1 ended without a check-in starts shift 2); nothing left → "already
 * checked out" (or no shift any more). A check-in before the template's "check-in opens" time (that many minutes before
 * the shift starts) → {@code TOO_EARLY}. Leaving early from a shift that asks for a reason → {@code EARLY_REASON_REQUIRED}
 * with a ticket; the app sends the reason with it ({@link #early}) and the check-out counts at the time of that scan; the
 * reason goes to the approvals inbox as an EARLY_REASON request (approved = excused, step 10.1).
 * <p>
 * Times are the server's (the phone's clock is stored, not trusted). <b>Every scan is stored</b>, refused ones too (their
 * code is the result), so refusals are returned ({@link ScanViews.Refused}), not thrown: the transaction commits. The
 * same idempotency key again answers what it answered the first time.
 */
@Service
@RequiredArgsConstructor(access = AccessLevel.PACKAGE)
public class ScanService {

    static final Duration MIN_GAP = Duration.ofMinutes(10);
    static final Duration TICKET_TTL = Duration.ofMinutes(15);
    static final String EARLY_REASON_REQUIRED = "EARLY_REASON_REQUIRED";
    private static final String CHECKED_IN = "CHECKED_IN";
    private static final String CHECKED_OUT = "CHECKED_OUT";

    private final ScanEventRepository scans;
    private final AttendanceRecordRepository records;
    private final ScanChecks checks;
    private final Locations locations;
    private final DaySchedules days;
    private final Outbox outbox;
    private final Approvals approvals;
    private final ScanMapper mapper;
    private final Clock clock;

    // ------------------------------------------------------------------ the scan

    @Transactional
    public ScanViews.Outcome scan(ScanCommands.Scan c, String keyHeader, String appVersion) {
        CurrentUser user = CurrentUser.require();
        UUID person = me(user);
        String key = idempotencyKey(c, keyHeader);
        if (c.qrToken() == null || c.qrToken().isBlank()) {
            throw validation("qrToken", "NotBlank");
        }
        Optional<ScanEvent> earlier = scans.findByIdempotencyKey(key);
        if (earlier.isPresent()) {
            return replay(earlier.get());   // sent again (a lost answer): the same outcome
        }
        ScanDraft d = new ScanDraft(user, person, key, c, appVersion, clock.instant());
        Optional<ScanViews.Refused> refused = checks.inOrder().stream()
                .map(check -> check.check(d))
                .flatMap(Optional::stream)
                .findFirst();   // the checks run one by one and stop at the first refusal
        return refused.isPresent() ? refuse(d, refused.get()) : onTheDay(d);
    }

    /** The phone, QR and place are fine: check in or out of today's shift. */
    private ScanViews.Outcome onTheDay(ScanDraft d) {
        Instant now = d.getNow();
        ZoneId zone = clock.getZone();
        LocalDate today = LocalDate.ofInstant(now, zone);
        DaySchedules.ScheduledDay day = days.forPerson(d.getPerson(), today);
        List<DaySchedules.Shift> shifts = day.shifts().stream().filter(s -> !s.onLeave())
                .sorted(Comparator.comparingInt(DaySchedules.Shift::position)).toList();
        if (day.kind() != DaySchedules.Kind.WORK || shifts.isEmpty()) {
            return refuse(d, ErrorCode.NO_SHIFT_TODAY, Map.of("kind", day.kind().name()));
        }
        List<AttendanceRecord> todays = records.findByPersonIdAndWorkDateOrderByShiftIdx(d.getPerson(), today);
        Optional<AttendanceRecord> open = todays.stream().filter(r -> r.getInAt() != null && r.getOutAt() == null).findFirst();
        return open.isPresent() ? checkOut(d, open.get(), day, todays) : checkIn(d, shifts, day, todays);
    }

    private ScanViews.Outcome checkOut(ScanDraft d, AttendanceRecord r, DaySchedules.ScheduledDay day, List<AttendanceRecord> todays) {
        Instant now = d.getNow();
        ZoneId zone = clock.getZone();
        d.setKind(ScanEvent.Kind.CHECK_OUT);
        d.setShiftIdx(r.getShiftIdx());
        d.setRecordId(r.getId());
        if (Duration.between(r.getInAt(), now).compareTo(MIN_GAP) < 0) {
            return refuse(d, ErrorCode.ALREADY_CHECKED_IN, Map.of("at", r.getInAt(), "shiftIdx", r.getShiftIdx()));
        }
        if (r.needsEarlyReason(now, zone)) {
            Instant shiftEnd = r.getWorkDate().atTime(r.getPlannedEnd()).atZone(zone).toInstant();
            Map<String, Object> details = new LinkedHashMap<>();
            details.put("shiftIdx", r.getShiftIdx());
            details.put("twoShifts", day.shifts().size() > 1);
            details.put("shiftEnd", shiftEnd);
            details.put("now", now);
            details.put("earlyMinutes", (int) Duration.between(now, shiftEnd).toMinutes());
            ScanEvent asked = scans.saveAndFlush(new ScanEvent(d.facts(EARLY_REASON_REQUIRED), stored(details)));
            return new ScanViews.Refused(ErrorCode.EARLY_REASON_REQUIRED, withTicket(asked));
        }
        ScanEvent event = save(d.facts(ScanEvent.ACCEPTED));
        r.checkOut(now, d.gate(), event.getId(), null, zone);
        records.saveAndFlush(r);
        recorded(r, ScanEvent.Kind.CHECK_OUT, now);
        return new ScanViews.Done(view(r, CHECKED_OUT, now, day, todays));
    }

    /** The first shift not scanned yet that has not ended; nothing left → already checked out (or no shift any more). */
    private ScanViews.Outcome checkIn(ScanDraft d, List<DaySchedules.Shift> shifts, DaySchedules.ScheduledDay day, List<AttendanceRecord> todays) {
        Instant now = d.getNow();
        ZoneId zone = clock.getZone();
        LocalDate today = LocalDate.ofInstant(now, zone);
        Optional<DaySchedules.Shift> next = shifts.stream()
                .filter(s -> todays.stream().noneMatch(r -> r.getShiftIdx() == s.position()))
                .filter(s -> now.isBefore(today.atTime(s.end()).atZone(zone).toInstant()))
                .findFirst();
        if (next.isEmpty()) {
            Optional<AttendanceRecord> last = todays.stream().filter(r -> r.getOutAt() != null).max(Comparator.comparing(AttendanceRecord::getOutAt));
            if (last.isEmpty()) {
                return refuse(d, ErrorCode.NO_SHIFT_TODAY, Map.of("kind", "ENDED"));
            }
            d.setShiftIdx(last.get().getShiftIdx());
            return refuse(d, ErrorCode.ALREADY_CHECKED_OUT, Map.of("at", last.get().getOutAt(), "shiftIdx", last.get().getShiftIdx()));
        }
        DaySchedules.Shift s = next.get();
        d.setKind(ScanEvent.Kind.CHECK_IN);
        d.setShiftIdx(s.position());
        LocalTime opensAt = s.checkInOpensAt();
        if (opensAt != null && now.isBefore(today.atTime(opensAt).atZone(zone).toInstant())) {
            Map<String, Object> details = new LinkedHashMap<>();
            details.put("shiftIdx", s.position());
            details.put("opensAt", opensAt.toString());       // "06:45", school time
            details.put("shiftStart", s.start().toString());
            return refuse(d, ErrorCode.TOO_EARLY, details);
        }
        AttendanceRecord.Plan plan = new AttendanceRecord.Plan(s.position(), s.name(), s.start(), s.end(), s.graceMinutes(),
                s.earlyCheckOutNeedsReason());
        AttendanceRecord r;
        try {
            r = records.saveAndFlush(AttendanceRecord.checkedIn(d.getPerson(), today, plan, now, d.gate(), null, AttendanceRecord.Source.SCAN, zone));
        } catch (DataIntegrityViolationException parallel) {
            throw new ApiException(ErrorCode.ALREADY_CHECKED_IN);   // two scans at the same moment: the other one won
        }
        d.setRecordId(r.getId());
        ScanEvent event = save(d.facts(ScanEvent.ACCEPTED));
        r.checkInScan(event.getId());
        records.saveAndFlush(r);
        recorded(r, ScanEvent.Kind.CHECK_IN, now);
        return new ScanViews.Done(view(r, CHECKED_IN, now, day, records.findByPersonIdAndWorkDateOrderByShiftIdx(d.getPerson(), today)));
    }

    // ------------------------------------------------------------------ the reason for leaving early

    /**
     * The app's "Leave early?" sheet: the reason for the scan that asked for it ({@code ticket}, valid
     * {@link #TICKET_TTL}). The check-out counts at the time of that scan. Sending it again answers the same.
     */
    @Transactional
    public ScanViews.Outcome early(ScanCommands.Early c) {
        CurrentUser user = CurrentUser.require();
        UUID person = me(user);
        UUID ticket;
        try {
            ticket = UUID.fromString(c.ticket() == null ? "" : c.ticket().trim());
        } catch (IllegalArgumentException notATicket) {
            throw validation("ticket", "NotBlank");
        }
        String reason = c.reason() == null ? "" : c.reason().trim();
        if (reason.isEmpty() || reason.length() > 50) {
            throw validation("reason", reason.isEmpty() ? "NotBlank" : "Size");
        }
        String note = c.note() == null || c.note().isBlank() ? null : c.note().trim();
        if (note != null && note.length() > 240) {
            throw validation("note", "Size");
        }
        String key = "early-" + ticket;
        Optional<ScanEvent> earlier = scans.findByIdempotencyKey(key);
        if (earlier.isPresent()) {
            return replay(earlier.get());
        }
        ScanEvent asked = scans.findById(ticket)
                .filter(e -> e.getPersonId().equals(person) && EARLY_REASON_REQUIRED.equals(e.getResult()) && e.getRecordId() != null)
                .orElseThrow(() -> new ApiException(ErrorCode.NOT_FOUND));
        if (Duration.between(asked.getReceivedAt(), clock.instant()).compareTo(TICKET_TTL) > 0) {
            throw new ApiException(ErrorCode.CONFLICT).with("reason", "TICKET_EXPIRED");   // scan again
        }
        AttendanceRecord r = records.findById(asked.getRecordId()).orElseThrow(() -> new ApiException(ErrorCode.NOT_FOUND));
        if (r.getOutAt() != null) {
            return new ScanViews.Refused(ErrorCode.ALREADY_CHECKED_OUT, Map.of("at", r.getOutAt(), "shiftIdx", r.getShiftIdx()));
        }
        String why = note == null ? reason : reason + ": " + note;
        ScanEvent event = save(new ScanEvent.Facts(person, user.userId(), user.deviceId(), key, ScanEvent.Source.ONLINE, asked.getClientTime(),
                clock.instant(), null, asked.getGateId(), asked.getQrVersion(), null, null, null, asked.getDistanceMeters(), null, null, null, null,
                ScanEvent.Kind.CHECK_OUT, ScanEvent.ACCEPTED, r.getId(), r.getShiftIdx(), why));
        r.checkOut(asked.getReceivedAt(), asked.getGateId(), event.getId(), why, clock.getZone());
        records.saveAndFlush(r);
        if (r.getEarlyMinutes() > 0) {
            approvals.request(new Approvals.Request(Approvals.Type.EARLY_REASON, LateReasonService.SUBJECT, r.getId(), person, why,
                    earlyDetails(r, reason, note)));
        }
        recorded(r, ScanEvent.Kind.CHECK_OUT, asked.getReceivedAt());
        return done(r, CHECKED_OUT, asked.getReceivedAt());
    }

    /** What the inbox card shows: "Early 90 min · Check-out 15:30". */
    private static Map<String, Object> earlyDetails(AttendanceRecord r, String code, String note) {
        Map<String, Object> d = new LinkedHashMap<>();
        d.put("workDate", r.getWorkDate().toString());
        d.put("shiftIdx", r.getShiftIdx());
        if (r.getShiftName() != null) {
            d.put("shiftName", r.getShiftName());
        }
        d.put("shiftEnd", r.getPlannedEnd().toString());
        d.put("checkOutAt", r.getOutAt().toString());
        d.put("earlyMinutes", r.getEarlyMinutes());
        d.put("reasonCode", code);
        if (note != null) {
            d.put("note", note);
        }
        return d;
    }

    // ------------------------------------------------------------------ helpers

    /** The answer to a scan sent again: what was answered the first time. */
    private ScanViews.Outcome replay(ScanEvent e) {
        if (!e.getPersonId().equals(CurrentUser.require().personId())) {
            throw new ApiException(ErrorCode.CONFLICT);   // another person's key
        }
        if (e.accepted() && e.getRecordId() != null) {
            AttendanceRecord r = records.findById(e.getRecordId()).orElseThrow(() -> new ApiException(ErrorCode.NOT_FOUND));
            return e.getKind() == ScanEvent.Kind.CHECK_OUT ? done(r, CHECKED_OUT, r.getOutAt()) : done(r, CHECKED_IN, r.getInAt());
        }
        ErrorCode code = ErrorCode.valueOf(e.getResult());
        return new ScanViews.Refused(code, code == ErrorCode.EARLY_REASON_REQUIRED ? withTicket(e) : e.getDetails());
    }

    /** A record answered as done, with the day as it stands now. */
    private ScanViews.Done done(AttendanceRecord r, String result, Instant time) {
        DaySchedules.ScheduledDay day = days.forPerson(r.getPersonId(), r.getWorkDate());
        return new ScanViews.Done(view(r, result, time, day, records.findByPersonIdAndWorkDateOrderByShiftIdx(r.getPersonId(), r.getWorkDate())));
    }

    /** The early check-out question: its ticket (the scan that asked) first, then what was stored with it. */
    private static Map<String, Object> withTicket(ScanEvent asked) {
        Map<String, Object> details = new LinkedHashMap<>();
        details.put("ticket", asked.getId());
        details.putAll(asked.getDetails());
        return details;
    }

    private ScanViews.Refused refuse(ScanDraft d, ScanViews.Refused refused) {
        return refuse(d, refused.code(), refused.details());
    }

    private ScanViews.Refused refuse(ScanDraft d, ErrorCode code, Map<String, Object> details) {
        save(d.facts(code.name()), stored(details));
        return new ScanViews.Refused(code, stored(details));
    }

    /** Details as stored (and so as answered, the first time and again): times and ids as text. */
    private static Map<String, Object> stored(Map<String, Object> details) {
        Map<String, Object> out = new LinkedHashMap<>();
        details.forEach((k, v) -> out.put(k, v instanceof Instant || v instanceof UUID ? v.toString() : v));
        return out;
    }

    private ScanEvent save(ScanEvent.Facts facts) {
        return save(facts, Map.of());
    }

    private ScanEvent save(ScanEvent.Facts facts, Map<String, Object> details) {
        try {
            return scans.saveAndFlush(new ScanEvent(facts, details));
        } catch (DataIntegrityViolationException sameKey) {
            throw new ApiException(ErrorCode.CONFLICT);   // the same key at the same moment
        }
    }

    private void recorded(AttendanceRecord r, ScanEvent.Kind kind, Instant at) {
        outbox.add(AttendanceRecorded.TYPE, mapper.recorded(r, kind, at));
    }

    private ScanViews.Result view(AttendanceRecord r, String result, Instant time, DaySchedules.ScheduledDay day, List<AttendanceRecord> todays) {
        boolean out = CHECKED_OUT.equals(result);
        ScanViews.NextShift next = !out ? null : day.shifts().stream()
                .filter(s -> !s.onLeave() && s.position() > r.getShiftIdx())
                .filter(s -> todays.stream().noneMatch(x -> x.getShiftIdx() == s.position()))
                .findFirst().map(s -> new ScanViews.NextShift(s.position(), s.name(), s.start())).orElse(null);
        UUID gateId = out ? r.getOutGateId() : r.getInGateId();
        ScanViews.Gate gate = gateId == null ? null : locations.gate(gateId).map(g -> new ScanViews.Gate(g.id(), g.name())).orElse(null);
        int workedToday = todays.stream().mapToInt(AttendanceRecord::getWorkedMinutes).sum();
        return mapper.result(r, result, time, day.shifts().size() > 1, workedToday, next, gate);
    }

    private static String idempotencyKey(ScanCommands.Scan c, String header) {
        String key = c.idempotencyKey() != null && !c.idempotencyKey().isBlank() ? c.idempotencyKey().trim() : header == null ? "" : header.trim();
        if (key.isEmpty() || key.length() > 64) {
            throw validation("idempotencyKey", key.isEmpty() ? "NotBlank" : "Size");
        }
        return key;
    }

    private static UUID me(CurrentUser user) {
        if (user.personId() == null) {
            throw new ApiException(ErrorCode.NOT_FOUND);
        }
        return user.personId();
    }

    private static ApiException validation(String field, String rule) {
        return new ApiException(ErrorCode.VALIDATION_ERROR).with("errors", List.of(Map.of("field", field, "rule", rule)));
    }
}
