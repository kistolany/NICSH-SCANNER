package kh.cmt.attendance.application;

import java.io.OutputStream;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.YearMonth;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.Predicate;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import java.util.stream.Stream;
import kh.cmt.attendance.application.DayHistory.Status;
import kh.cmt.attendance.domain.AttendanceRecord;
import kh.cmt.attendance.infrastructure.AttendanceRecordRepository;
import kh.cmt.attendance.infrastructure.ScanEventRepository;
import kh.cmt.attendance.api.AttendanceRecorded;
import kh.cmt.org.api.OrgKind;
import kh.cmt.org.api.OrgQueries;
import kh.cmt.people.api.PeopleQueries;
import kh.cmt.schedule.api.DaySchedules;
import kh.cmt.shared.events.Outbox;
import kh.cmt.shared.export.ExcelExporter;
import kh.cmt.shared.export.ExportColumn;
import kh.cmt.shared.security.CurrentUser;
import kh.cmt.shared.security.PermissionAction;
import kh.cmt.shared.security.PermissionModule;
import kh.cmt.shared.security.RequirePermission;
import kh.cmt.shared.web.ApiException;
import kh.cmt.shared.web.ErrorCode;
import lombok.AccessLevel;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The admin web's Staff attendance page (step 7.7): the day (one row per person who works that day, two lines on a
 * two-shift day, flags Manual / Edited / Offline-synced), the month grid (person × day + present / late / absent /
 * hours — every cell is the day view's status of that day, so the totals are the day records counted), editing a
 * record ("Edited by admin", reason required), entering a shift nobody scanned ("Manual"), and the Excel export. The
 * people are the ones the caller may see (record scope); a day follows the same rule as the staff app's history
 * ({@link DayHistory}), plus, for today, "Not yet" and "Outside area" for those who have not scanned.
 */
@Service
@RequiredArgsConstructor(access = AccessLevel.PACKAGE)
public class AdminAttendanceService {

    /** The status chips of the day view, in the design's order. */
    static final List<Status> CHIPS = List.of(Status.ON_TIME, Status.LATE, Status.EARLY, Status.ABSENT, Status.LEAVE, Status.NOT_YET,
            Status.OUTSIDE_AREA, Status.MISSING, Status.HOLIDAY);
    private static final Set<Status> PRESENT = Set.of(Status.ON_TIME, Status.LATE, Status.EARLY, Status.MISSING);
    private static final DateTimeFormatter HH_MM = DateTimeFormatter.ofPattern("HH:mm");

    /** The page's filters: department, role (staff | teacher | admin), search (name or Staff ID), status chip. */
    public record Filter(UUID department, String role, String q, String status) {

        boolean keeps(PeopleQueries.PersonCard p) {
            String query = q == null ? "" : q.trim().toLowerCase(Locale.ROOT);
            return (department == null || department.equals(p.departmentId()))
                    && (role == null || role.isBlank() || role.equalsIgnoreCase(p.role()))
                    && (query.isEmpty() || Objects.toString(p.staffId(), "").toLowerCase(Locale.ROOT).contains(query)
                            || p.name() != null && Stream.of(p.name().km(), p.name().en(), p.name().ar())
                                    .anyMatch(n -> n != null && n.toLowerCase(Locale.ROOT).contains(query)));
        }
    }

    private final PeopleQueries people;
    private final AttendanceRecordRepository records;
    private final ScanEventRepository scans;
    private final DaySchedules schedules;
    private final ShiftViews shiftViews;
    private final ScanMapper events;
    private final Outbox outbox;
    private final OrgQueries org;
    private final ExcelExporter excel;
    private final Clock clock;

    // ------------------------------------------------------------------ the day

    @RequirePermission(module = PermissionModule.ATTENDANCE, action = PermissionAction.VIEW)
    @Transactional(readOnly = true)
    public AdminAttendanceViews.Day day(LocalDate date, Filter f) {
        LocalDate day = date == null ? today() : date;
        Predicate<Status> chip = chip(f.status());
        List<Row> all = dayRows(day, f);
        Map<String, Integer> counts = new LinkedHashMap<>();
        counts.put("ALL", all.size());
        CHIPS.forEach(s -> counts.put(s.name(), (int) all.stream().filter(r -> r.day().status() == s).count()));
        List<Row> shown = all.stream().filter(r -> chip.test(r.day().status())).toList();
        ShiftViews.Batch views = shiftViews.batch(shown.stream().map(r -> new ShiftViews.PersonDay(r.person().id(), r.day())).toList());
        return new AdminAttendanceViews.Day(day, counts, shown.stream().map(r -> row(views, r)).toList());
    }

    /** One person's day. */
    private record Row(PeopleQueries.PersonCard person, DayHistory.Day day) {
    }

    private List<Row> dayRows(LocalDate day, Filter f) {
        List<PeopleQueries.PersonCard> persons = people.visibleActive().stream().filter(f::keeps).toList();
        Map<UUID, List<AttendanceRecord>> byPerson = records.findByWorkDateBetween(day, day).stream()
                .collect(Collectors.groupingBy(AttendanceRecord::getPersonId));
        LocalDate today = today();
        Instant now = clock.instant();
        ZoneId zone = clock.getZone();
        Set<UUID> outside = day.equals(today) ? scans.peopleWithResult(day.atStartOfDay(zone).toInstant(),
                day.plusDays(1).atStartOfDay(zone).toInstant(), "OUTSIDE_AREA") : Set.of();
        List<Row> rows = new ArrayList<>();
        for (PeopleQueries.PersonCard p : persons) {
            DaySchedules.ScheduledDay schedule = schedules.forPerson(p.id(), day);
            status(today, now, zone, schedule, byPerson.getOrDefault(p.id(), List.of()), outside.contains(p.id()))
                    .ifPresent(d -> rows.add(new Row(p, d)));
        }
        return rows;
    }

    /** The day's status for the admin: the history's rule, and for today Not yet / Outside area / Absent once over. */
    private static Optional<DayHistory.Day> status(LocalDate today, Instant now, ZoneId zone, DaySchedules.ScheduledDay schedule,
            List<AttendanceRecord> records, boolean triedOutside) {
        return DayHistory.of(today, schedule, records)
                .or(() -> schedule.date().equals(today) && records.isEmpty() ? DayHistory.todayWithoutScan(schedule, now, zone, triedOutside)
                        : Optional.empty());
    }

    private static AdminAttendanceViews.DayRow row(ShiftViews.Batch views, Row r) {
        DayHistory.Day d = r.day();
        List<HistoryViews.Shift> shifts = views.shifts(new ShiftViews.PersonDay(r.person().id(), d));
        List<AttendanceRecord> recs = d.lines().stream().map(DayHistory.Line::record).filter(Objects::nonNull).toList();
        List<String> flags = new ArrayList<>();
        if (recs.stream().anyMatch(x -> x.getSource() == AttendanceRecord.Source.MANUAL)) {
            flags.add("MANUAL");
        }
        if (recs.stream().anyMatch(x -> x.getEditedBy() != null && x.getSource() != AttendanceRecord.Source.MANUAL)) {
            flags.add("EDITED");
        }
        if (recs.stream().anyMatch(x -> x.getSource() == AttendanceRecord.Source.OFFLINE)) {
            flags.add("OFFLINE");
        }
        DaySchedules.ScheduledDay s = d.schedule();
        return new AdminAttendanceViews.DayRow(person(r.person()), d.status().name(), shifts, d.workedMinutes(), d.lateMinutes(), d.earlyMinutes(),
                ShiftViews.gateOf(shifts), flags, s.holiday() == null ? null : s.holiday().name(), s.label());
    }

    // ------------------------------------------------------------------ the month

    @RequirePermission(module = PermissionModule.ATTENDANCE, action = PermissionAction.VIEW)
    @Transactional(readOnly = true)
    public AdminAttendanceViews.Month month(YearMonth month, Filter f) {
        YearMonth m = month == null ? YearMonth.from(today()) : month;
        List<LocalDate> dates = IntStream.rangeClosed(1, m.lengthOfMonth()).mapToObj(m::atDay).toList();
        return new AdminAttendanceViews.Month(m, dates, monthRows(m, f).stream().map(r -> r.view()).toList());
    }

    /** One person's month: the day view's status of each day (null = nothing that day). */
    private record MonthLine(PeopleQueries.PersonCard person, List<DayHistory.Day> days, Map<LocalDate, DayHistory.Day> byDate, YearMonth month) {

        AdminAttendanceViews.MonthRow view() {
            List<String> cells = IntStream.rangeClosed(1, month.lengthOfMonth()).mapToObj(month::atDay)
                    .map(d -> byDate.containsKey(d) ? byDate.get(d).status().name() : null).toList();
            return new AdminAttendanceViews.MonthRow(AdminAttendanceService.person(person), cells, (int) days.stream().filter(d -> PRESENT.contains(d.status())).count(),
                    (int) days.stream().filter(d -> d.status() == Status.LATE).count(),
                    (int) days.stream().filter(d -> d.status() == Status.ABSENT).count(),
                    days.stream().mapToInt(DayHistory.Day::workedMinutes).sum());
        }
    }

    private List<MonthLine> monthRows(YearMonth m, Filter f) {
        LocalDate first = m.atDay(1);
        LocalDate today = today();
        LocalDate last = m.atEndOfMonth().isAfter(today) ? today : m.atEndOfMonth();
        List<PeopleQueries.PersonCard> persons = people.visibleActive().stream().filter(f::keeps).toList();
        Map<UUID, Map<LocalDate, List<AttendanceRecord>>> recs = last.isBefore(first) ? Map.of()
                : records.findByWorkDateBetween(first, last).stream()
                        .collect(Collectors.groupingBy(AttendanceRecord::getPersonId, Collectors.groupingBy(AttendanceRecord::getWorkDate)));
        Instant now = clock.instant();
        ZoneId zone = clock.getZone();
        Set<UUID> outside = !last.isBefore(first) && last.equals(today) ? scans.peopleWithResult(today.atStartOfDay(zone).toInstant(),
                today.plusDays(1).atStartOfDay(zone).toInstant(), "OUTSIDE_AREA") : Set.of();
        List<MonthLine> lines = new ArrayList<>();
        for (PeopleQueries.PersonCard p : persons) {
            Map<LocalDate, List<AttendanceRecord>> mine = recs.getOrDefault(p.id(), Map.of());
            List<DayHistory.Day> days = new ArrayList<>();
            for (DaySchedules.ScheduledDay s : schedules.forPerson(p.id(), first, last)) {
                status(today, now, zone, s, mine.getOrDefault(s.date(), List.of()), outside.contains(p.id())).ifPresent(days::add);
            }
            lines.add(new MonthLine(p, days, days.stream().collect(Collectors.toMap(DayHistory.Day::date, d -> d)), m));
        }
        return lines;
    }

    // ------------------------------------------------------------------ changes

    /** Changes a record's times: "Edited by admin" with the reason. */
    @RequirePermission(module = PermissionModule.ATTENDANCE, action = PermissionAction.EDIT)
    @Transactional
    public HistoryViews.Shift edit(UUID recordId, RecordCommands.EditRecord c) {
        AttendanceRecord r = records.findById(recordId).orElseThrow(() -> new ApiException(ErrorCode.NOT_FOUND));
        Times t = times(c.checkIn(), c.checkOut(), c.reason(), r.getWorkDate(), c.version() == null);
        if (c.version() != r.getVersion()) {
            throw new ApiException(ErrorCode.VERSION_CONFLICT);
        }
        Instant now = clock.instant();
        r.edit(t.in(), t.out(), CurrentUser.require().userId(), t.reason(), now, clock.getZone());
        records.saveAndFlush(r);
        outbox.add(AttendanceRecorded.TYPE, events.changed(r, "EDITED", now));
        return shiftOf(r);
    }

    /** Enters a shift nobody scanned ("Manual"), e.g. a check-in the gate missed. */
    @RequirePermission(module = PermissionModule.ATTENDANCE, action = PermissionAction.EDIT)
    @Transactional
    public HistoryViews.Shift create(RecordCommands.NewRecord c) {
        if (c.personId() == null || people.visible(c.personId()).filter(PeopleQueries.VisiblePerson::active).isEmpty()) {
            throw new ApiException(ErrorCode.NOT_FOUND);
        }
        LocalDate date;
        try {
            date = LocalDate.parse(c.date() == null ? "" : c.date().trim());
        } catch (DateTimeParseException unreadable) {
            throw validation(List.of(error("date", c.date() == null ? "NotNull" : "Pattern")));
        }
        if (date.isAfter(today())) {
            throw validation(List.of(error("date", "Future")));
        }
        Times t = times(c.checkIn(), c.checkOut(), c.reason(), date, false);
        int shiftIdx = c.shiftIdx() == null ? 1 : c.shiftIdx();
        DaySchedules.ScheduledDay day = schedules.forPerson(c.personId(), date);
        DaySchedules.Shift s = day.kind() != DaySchedules.Kind.WORK ? null
                : day.shifts().stream().filter(x -> x.position() == shiftIdx && !x.onLeave()).findFirst().orElse(null);
        if (s == null) {
            throw new ApiException(ErrorCode.NOT_A_WORKING_DAY).with("kind", day.kind().name());
        }
        if (records.findByPersonIdAndWorkDateAndShiftIdx(c.personId(), date, shiftIdx).isPresent()) {
            throw new ApiException(ErrorCode.CONFLICT).with("reason", "RECORD_EXISTS");   // change it instead
        }
        Instant now = clock.instant();
        AttendanceRecord r;
        try {
            r = records.saveAndFlush(AttendanceRecord.manual(c.personId(), date,
                    new AttendanceRecord.Plan(s.position(), s.name(), s.start(), s.end(), s.graceMinutes(), s.earlyCheckOutNeedsReason()),
                    t.in(), t.out(), CurrentUser.require().userId(), t.reason(), now, clock.getZone()));
        } catch (DataIntegrityViolationException sameMoment) {
            throw new ApiException(ErrorCode.CONFLICT).with("reason", "RECORD_EXISTS");
        }
        outbox.add(AttendanceRecorded.TYPE, events.changed(r, "MANUAL", now));
        return shiftOf(r);
    }

    private HistoryViews.Shift shiftOf(AttendanceRecord r) {
        DayHistory.Day d = DayHistory.of(today(), schedules.forPerson(r.getPersonId(), r.getWorkDate()), List.of(r)).orElseThrow();
        DayHistory.Line line = d.lines().stream().filter(l -> l.record() == r).findFirst().orElseThrow();
        ShiftViews.PersonDay pd = new ShiftViews.PersonDay(r.getPersonId(), new DayHistory.Day(d.date(), d.status(), d.schedule(), List.of(line)));
        return shiftViews.batch(List.of(pd)).shifts(pd).getFirst();
    }

    private record Times(Instant in, Instant out, String reason) {
    }

    /** The edit form's checks, all problems at once. */
    private Times times(String checkIn, String checkOut, String reason, LocalDate date, boolean noVersion) {
        List<Map<String, String>> errors = new ArrayList<>();
        ZoneId zone = clock.getZone();
        Instant now = clock.instant();
        Instant in = null;
        Instant out = null;
        if (checkIn == null || checkIn.isBlank()) {
            errors.add(error("checkIn", "NotNull"));
        } else {
            in = at(date, checkIn, zone);
            if (in == null) {
                errors.add(error("checkIn", "Pattern"));
            } else if (in.isAfter(now)) {
                errors.add(error("checkIn", "Future"));
            }
        }
        if (checkOut != null && !checkOut.isBlank()) {
            out = at(date, checkOut, zone);
            if (out == null) {
                errors.add(error("checkOut", "Pattern"));
            } else if (out.isAfter(now)) {
                errors.add(error("checkOut", "Future"));
            } else if (in != null && !out.isAfter(in)) {
                errors.add(error("checkOut", "BeforeCheckIn"));
            }
        }
        String why = reason == null ? "" : reason.trim();
        if (why.isEmpty() || why.length() > LateReasonService.MAX_REASON) {
            errors.add(error("reason", why.isEmpty() ? "NotBlank" : "Size"));
        }
        if (noVersion) {
            errors.add(error("version", "NotNull"));
        }
        if (!errors.isEmpty()) {
            throw validation(errors);
        }
        return new Times(in, out, why);
    }

    private static Instant at(LocalDate date, String hhmm, ZoneId zone) {
        try {
            return date.atTime(LocalTime.parse(hhmm.trim(), HH_MM)).atZone(zone).toInstant();
        } catch (DateTimeParseException unreadable) {
            return null;
        }
    }

    // ------------------------------------------------------------------ export

    /** What an export is: the day (one line per shift) or the month grid, with the page's filters. */
    public record Export(String view, LocalDate date, YearMonth month, Filter filter, UUID personId) {

        public String fileName(String staffId) {
            String who = staffId == null ? "" : "_" + staffId;
            return "month".equals(view) ? "attendance_" + month + who + ".xlsx" : "attendance_" + date + who + ".xlsx";
        }
    }

    @RequirePermission(module = PermissionModule.ATTENDANCE, action = PermissionAction.EXPORT)
    @Transactional(readOnly = true)
    public String export(Export e, OutputStream out) {
        Filter f = e.filter();
        Predicate<PeopleQueries.PersonCard> who = p -> e.personId() == null || p.id().equals(e.personId());
        ZoneId zone = clock.getZone();
        String staffId = e.personId() == null ? null
                : people.visibleActive().stream().filter(p -> p.id().equals(e.personId())).map(PeopleQueries.PersonCard::staffId).findFirst()
                        .orElseThrow(() -> new ApiException(ErrorCode.NOT_FOUND));
        if ("month".equals(e.view())) {
            YearMonth m = e.month() == null ? YearMonth.from(today()) : e.month();
            List<MonthLine> lines = monthRows(m, f).stream().filter(l -> who.test(l.person())).toList();
            Map<UUID, String> depts = departments(lines.stream().map(l -> l.person().departmentId()).toList());
            List<ExportColumn<MonthLine>> cols = new ArrayList<>(List.of(ExportColumn.of("Staff ID", l -> l.person().staffId()),
                    ExportColumn.of("Name", l -> nameOf(l.person())), ExportColumn.of("Department", l -> depts.get(l.person().departmentId()))));
            for (int d = 1; d <= m.lengthOfMonth(); d++) {
                LocalDate date = m.atDay(d);
                cols.add(ExportColumn.of(String.valueOf(d), l -> l.byDate().containsKey(date) ? label(l.byDate().get(date).status()) : null));
            }
            cols.add(ExportColumn.of("Present", l -> l.view().present()));
            cols.add(ExportColumn.of("Late", l -> l.view().late()));
            cols.add(ExportColumn.of("Absent", l -> l.view().absent()));
            cols.add(ExportColumn.of("Hours", l -> hours(l.view().workedMinutes())));
            excel.write(m.toString(), cols, lines, out);
        } else {
            LocalDate day = e.date() == null ? today() : e.date();
            Predicate<Status> chip = chip(f.status());
            List<Row> rows = dayRows(day, f).stream().filter(r -> who.test(r.person()) && chip.test(r.day().status())).toList();
            Map<UUID, String> depts = departments(rows.stream().map(r -> r.person().departmentId()).toList());
            List<ExportLine> lines = rows.stream().flatMap(r -> r.day().lines().stream().map(l -> new ExportLine(r, l, l.record()))).toList();
            List<ExportColumn<ExportLine>> cols = List.of(
                    ExportColumn.of("Staff ID", x -> x.row().person().staffId()),
                    ExportColumn.of("Name", x -> nameOf(x.row().person())),
                    ExportColumn.of("Department", x -> depts.get(x.row().person().departmentId())),
                    ExportColumn.of("Day status", x -> label(x.row().day().status())),
                    ExportColumn.of("Shift", x -> shiftName(x.line())),
                    ExportColumn.of("Check-in", x -> time(x.record(), true, zone)),
                    ExportColumn.of("Check-out", x -> time(x.record(), false, zone)),
                    ExportColumn.of("Hours", x -> x.record() == null ? null : hours(x.record().getWorkedMinutes())),
                    ExportColumn.of("Late (min)", x -> x.record() == null ? null : x.record().getLateMinutes()),
                    ExportColumn.of("Early (min)", x -> x.record() == null ? null : x.record().getEarlyMinutes()),
                    ExportColumn.of("Shift status", x -> label(x.line().status().name())),
                    ExportColumn.of("Edited by admin", x -> x.record() != null && x.record().getEditedBy() != null ? x.record().getEditReason() : null));
            excel.write(day.toString(), cols, lines, out);
        }
        return e.fileName(staffId);
    }

    /** One line of the day export: a shift of a person's day. */
    private record ExportLine(Row row, DayHistory.Line line, AttendanceRecord record) {
    }

    /** Department id → its English name, for the export; people without a department get none (a lookup of null is null). */
    private Map<UUID, String> departments(Collection<UUID> ids) {
        Map<UUID, String> names = new HashMap<>();
        List<UUID> known = ids.stream().filter(Objects::nonNull).distinct().toList();
        if (!known.isEmpty()) {
            org.labels(OrgKind.DEPARTMENT, known).values()
                    .forEach(l -> names.put(l.id(), l.name() == null ? null : l.name().en()));
        }
        return names;
    }

    private static String time(AttendanceRecord r, boolean in, ZoneId zone) {
        Instant at = r == null ? null : in ? r.getInAt() : r.getOutAt();
        return at == null ? null : HH_MM.format(at.atZone(zone));
    }

    private static String shiftName(DayHistory.Line l) {
        String times = HH_MM.format(l.start()) + "–" + HH_MM.format(l.end());
        return l.name() == null ? times : l.name() + " " + times;
    }

    private static String hours(int minutes) {
        return minutes / 60 + ":" + String.format(Locale.ROOT, "%02d", minutes % 60);
    }

    private static String label(Status s) {
        return label(s.name());
    }

    /** The design's words for a status, for the export (the page has its own, in the reader's language). */
    private static String label(String status) {
        return switch (status) {
            case "ON_TIME" -> "On time";
            case "LATE" -> "Late";
            case "EARLY" -> "Early";
            case "ABSENT" -> "Absent";
            case "LEAVE" -> "Leave";
            case "NOT_YET" -> "Not yet";
            case "OUTSIDE_AREA" -> "Outside area";
            case "MISSING" -> "Missing check-out";
            case "HOLIDAY" -> "Holiday";
            default -> status;
        };
    }

    // ------------------------------------------------------------------ helpers

    private static Predicate<Status> chip(String status) {
        if (status == null || status.isBlank() || status.equalsIgnoreCase("ALL")) {
            return s -> true;
        }
        try {
            Status wanted = Status.valueOf(status.trim().toUpperCase(Locale.ROOT));
            return s -> s == wanted;
        } catch (IllegalArgumentException unknown) {
            throw validation(List.of(error("status", "OneOf")));
        }
    }

    private static AdminAttendanceViews.Person person(PeopleQueries.PersonCard p) {
        return new AdminAttendanceViews.Person(p.id(), p.staffId(), p.name(), p.role(), p.departmentId());
    }

    private static String nameOf(PeopleQueries.PersonCard p) {
        return p.name() == null ? p.staffId() : Objects.requireNonNullElse(p.name().en(), p.name().km());
    }

    private LocalDate today() {
        return LocalDate.ofInstant(clock.instant(), clock.getZone());
    }

    private static Map<String, String> error(String field, String rule) {
        return Map.of("field", field, "rule", rule);
    }

    private static ApiException validation(List<Map<String, String>> errors) {
        return new ApiException(ErrorCode.VALIDATION_ERROR).with("errors", errors);
    }
}
