package kh.cmt.attendance.application;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import kh.cmt.approvals.api.Approvals;
import kh.cmt.attendance.domain.AttendanceRecord;
import kh.cmt.attendance.infrastructure.AttendanceRecordRepository;
import kh.cmt.files.api.FilePurpose;
import kh.cmt.files.api.Files;
import kh.cmt.schedule.api.DaySchedules;
import kh.cmt.shared.security.CurrentUser;
import kh.cmt.shared.web.ApiException;
import kh.cmt.shared.web.ErrorCode;
import lombok.AccessLevel;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * "Request correction" (step 7.6): a check-in or check-out time of one shift is wrong or missing, the person asks for
 * another one. Sent to the approvals inbox as a CORRECTION request; the admin's decision applies it to the record (phase
 * 10). One request per person, day, shift and field ({@link #subject}): sending again while it waits changes it; once
 * decided → {@code ALREADY_DECIDED}.
 * <p>
 * Checks: the day is not in the future and the person had that shift (planned, or a record of it) →
 * {@code NOT_A_WORKING_DAY}; the time is not later than now, a check-out comes after the check-in and a check-in before
 * the check-out, and it differs from what was recorded; a reason; an attachment is an uploaded ATTACHMENT (it is taken by
 * the request; sending again with another one lets the old one go).
 */
@Service
@RequiredArgsConstructor(access = AccessLevel.PACKAGE)
public class CorrectionService {

    static final String SUBJECT = "ATTENDANCE_SHIFT_TIME";
    private static final DateTimeFormatter HH_MM = DateTimeFormatter.ofPattern("HH:mm");

    enum Field { CHECK_IN, CHECK_OUT }

    private final AttendanceRecordRepository records;
    private final DaySchedules schedules;
    private final Approvals approvals;
    private final Files files;
    private final Clock clock;

    /** What a correction is about: one person, day, shift and field — so one open request for each. */
    static UUID subject(UUID person, LocalDate date, int shiftIdx, Field field) {
        return UUID.nameUUIDFromBytes(("correction|" + person + "|" + date + "|" + shiftIdx + "|" + field).getBytes(StandardCharsets.UTF_8));
    }

    @Transactional
    public Approvals.Sent request(RecordCommands.Correction c) {
        UUID person = CurrentUser.require().personId();
        if (person == null) {
            throw new ApiException(ErrorCode.NOT_FOUND);
        }
        ZoneId zone = clock.getZone();
        Instant now = clock.instant();
        Input in = read(c, LocalDate.ofInstant(now, zone));
        int shiftIdx = in.shiftIdx();

        DaySchedules.ScheduledDay day = schedules.forPerson(person, in.date());
        Optional<AttendanceRecord> record = records.findByPersonIdAndWorkDateAndShiftIdx(person, in.date(), shiftIdx);
        Optional<DaySchedules.Shift> planned = day.kind() == DaySchedules.Kind.WORK
                ? day.shifts().stream().filter(s -> s.position() == shiftIdx && !s.onLeave()).findFirst() : Optional.empty();
        if (planned.isEmpty() && record.isEmpty()) {
            throw new ApiException(ErrorCode.NOT_A_WORKING_DAY).with("kind", day.kind().name());
        }

        Instant requested = in.date().atTime(in.time()).atZone(zone).toInstant();
        Instant recorded = record.map(r -> in.field() == Field.CHECK_IN ? r.getInAt() : r.getOutAt()).orElse(null);
        if (requested.isAfter(now)) {
            throw validation("time", "Future");
        }
        if (in.field() == Field.CHECK_OUT && record.map(AttendanceRecord::getInAt).filter(at -> !requested.isAfter(at)).isPresent()) {
            throw validation("time", "BeforeCheckIn");
        }
        if (in.field() == Field.CHECK_IN && record.map(AttendanceRecord::getOutAt).filter(at -> !requested.isBefore(at)).isPresent()) {
            throw validation("time", "AfterCheckOut");
        }
        if (recorded != null && recorded.truncatedTo(ChronoUnit.MINUTES).equals(requested)) {
            throw validation("time", "Unchanged");
        }

        UUID subject = subject(person, in.date(), shiftIdx, in.field());
        UUID previousFile = approvals.latest(Approvals.Type.CORRECTION, subject).filter(v -> v.status() == Approvals.Status.PENDING)
                .map(v -> v.details() == null ? null : v.details().get("attachmentFileId")).map(Object::toString).map(UUID::fromString).orElse(null);
        if (c.attachmentFileId() != null && !c.attachmentFileId().equals(previousFile)) {
            files.attach(c.attachmentFileId(), FilePurpose.ATTACHMENT);
        }

        Map<String, Object> details = new LinkedHashMap<>();
        details.put("date", in.date().toString());
        details.put("shiftIdx", shiftIdx);
        planned.map(DaySchedules.Shift::name).or(() -> record.map(AttendanceRecord::getShiftName)).ifPresent(n -> details.put("shiftName", n));
        details.put("field", in.field().name());
        details.put("recordedTime", recorded == null ? null : HH_MM.format(recorded.atZone(zone)));
        details.put("requestedTime", HH_MM.format(in.time()));
        details.put("requestedAt", requested.toString());
        record.ifPresent(r -> details.put("recordId", r.getId().toString()));
        if (c.attachmentFileId() != null) {
            details.put("attachmentFileId", c.attachmentFileId().toString());
        }
        Approvals.Sent sent = approvals.request(new Approvals.Request(Approvals.Type.CORRECTION, SUBJECT, subject, person, in.reason(), details));
        if (previousFile != null && !Objects.equals(previousFile, c.attachmentFileId())) {
            files.discard(previousFile);   // sent again without it, or with another one
        }
        return sent;
    }

    private record Input(LocalDate date, int shiftIdx, Field field, LocalTime time, String reason) {
    }

    /** All the request's own problems at once, as {@code {field, rule}}. */
    private static Input read(RecordCommands.Correction c, LocalDate today) {
        List<Map<String, String>> errors = new ArrayList<>();
        LocalDate date = null;
        if (c.date() == null || c.date().isBlank()) {
            errors.add(error("date", "NotNull"));
        } else {
            try {
                date = LocalDate.parse(c.date().trim());
                if (date.isAfter(today)) {
                    errors.add(error("date", "Future"));
                }
            } catch (DateTimeParseException unreadable) {
                errors.add(error("date", "Pattern"));
            }
        }
        int shiftIdx = c.shiftIdx() == null ? 1 : c.shiftIdx();
        if (shiftIdx != 1 && shiftIdx != 2) {
            errors.add(error("shiftIdx", "OneOf"));
        }
        Field field = null;
        try {
            field = Field.valueOf(c.field() == null ? "" : c.field().trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException unknown) {
            errors.add(error("field", "OneOf"));
        }
        LocalTime time = null;
        try {
            time = LocalTime.parse(c.time() == null ? "" : c.time().trim(), HH_MM);
        } catch (DateTimeParseException unreadable) {
            errors.add(error("time", "Pattern"));
        }
        String reason = c.reason() == null ? "" : c.reason().trim();
        if (reason.isEmpty() || reason.length() > LateReasonService.MAX_REASON) {
            errors.add(error("reason", reason.isEmpty() ? "NotBlank" : "Size"));
        }
        if (!errors.isEmpty()) {
            throw new ApiException(ErrorCode.VALIDATION_ERROR).with("errors", errors);
        }
        return new Input(date, shiftIdx, field, time, reason);
    }

    private static Map<String, String> error(String field, String rule) {
        return Map.of("field", field, "rule", rule);
    }

    private static ApiException validation(String field, String rule) {
        return new ApiException(ErrorCode.VALIDATION_ERROR).with("errors", List.of(error(field, rule)));
    }
}
