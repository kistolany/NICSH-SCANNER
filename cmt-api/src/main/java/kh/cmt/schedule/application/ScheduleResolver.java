package kh.cmt.schedule.application;

import com.fasterxml.jackson.annotation.JsonUnwrapped;
import java.time.Clock;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZonedDateTime;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import kh.cmt.people.api.PeopleQueries;
import kh.cmt.schedule.api.DaySchedules;
import kh.cmt.schedule.api.PersonLeave;
import kh.cmt.schedule.api.ShiftTemplateQueries;
import kh.cmt.schedule.api.SpecialDays;
import kh.cmt.schedule.api.WeeklySchedules;
import kh.cmt.shared.security.CurrentUser;
import kh.cmt.shared.web.ApiException;
import kh.cmt.shared.web.ErrorCode;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * "Today's schedule" (step 5.4): the one place that decides what a person works on a day (see {@link DayResolution}
 * for the rule). Used by the app's Today card and My schedule now, and by the scan, attendance and reports later.
 */
@Service
@Transactional(readOnly = true)
public class ScheduleResolver implements DaySchedules {

    /** How far ahead My schedule lists upcoming changes and holidays. */
    static final int UPCOMING_DAYS = 60;

    private final PeopleQueries people;
    private final SpecialDays specialDays;
    private final WeeklySchedules weekly;
    private final ShiftTemplateQueries templates;
    private final ObjectProvider<PersonLeave> leave;
    private final Clock clock;

    ScheduleResolver(PeopleQueries people, SpecialDays specialDays, WeeklySchedules weekly, ShiftTemplateQueries templates,
            ObjectProvider<PersonLeave> leave, Clock clock) {
        this.people = people;
        this.specialDays = specialDays;
        this.weekly = weekly;
        this.templates = templates;
        this.leave = leave;
        this.clock = clock;
    }

    @Override
    public ScheduledDay forPerson(UUID personId, LocalDate date) {
        return forPerson(personId, people.departmentOfPerson(personId), date);
    }

    @Override
    public List<ScheduledDay> forPerson(UUID personId, LocalDate from, LocalDate to) {
        if (to.isBefore(from)) {
            return List.of();
        }
        UUID department = people.departmentOfPerson(personId);
        Map<LocalDate, SpecialDays.HolidayDay> holidays = specialDays.holidaysIn(department, from, to);
        Map<LocalDate, SpecialDays.ChangeDay> changes = specialDays.dateChangesIn(personId, department, from, to);
        Map<LocalDate, WeeklySchedules.Week> weeks = weekly.weeksOf(personId, from, to);
        PersonLeave leaves = leave.getIfAvailable();
        Map<LocalDate, PersonLeave.LeaveDay> away = leaves == null ? Map.of() : leaves.leavesIn(personId, from, to);
        Map<UUID, Optional<ShiftTemplateQueries.TemplateInfo>> found = new HashMap<>();
        List<ScheduledDay> days = new ArrayList<>();
        for (LocalDate d = from; !d.isAfter(to); d = d.plusDays(1)) {
            days.add(DayResolution.resolve(d, Optional.ofNullable(holidays.get(d)), Optional.ofNullable(away.get(d)),
                    Optional.ofNullable(changes.get(d)), Optional.ofNullable(weeks.get(d)),
                    id -> found.computeIfAbsent(id, templates::find)));
        }
        return days;
    }

    private ScheduledDay forPerson(UUID personId, UUID department, LocalDate date) {
        PersonLeave leaves = leave.getIfAvailable();
        return DayResolution.resolve(date,
                specialDays.holidayOn(department, date),
                leaves == null ? Optional.empty() : leaves.leaveOn(personId, date),
                specialDays.dateChangeOn(personId, department, date),
                weekly.weekOf(personId, date),
                templates::find);
    }

    /** The day as the weekly schedule has it: no holiday, date change or leave. */
    private ScheduledDay usualDay(UUID personId, LocalDate date) {
        return DayResolution.resolve(date, Optional.empty(), Optional.empty(), Optional.empty(), weekly.weekOf(personId, date),
                templates::find);
    }

    // ------------------------------------------------------------------ the app (the signed-in person)

    /** The Today card: the day, and the server's clock (school time) so the app's timers do not trust the phone. */
    public record MyDay(@JsonUnwrapped ScheduledDay day, OffsetDateTime serverTime) {
    }

    /**
     * My schedule: the week (Monday to Sunday) around {@code date} (today when left out) as it will really be
     * ({@code days}), the same week from the weekly schedule alone ({@code usual}: the grouped rows "Monday – Friday ·
     * …"), and from today on, within {@value #UPCOMING_DAYS} days, the days that differ from the usual week
     * ({@code upcoming}: date changes and holidays, the app's "Upcoming changes").
     */
    public record MyWeek(LocalDate weekStart, LocalDate weekEnd, LocalDate today, List<ScheduledDay> days, List<ScheduledDay> usual,
            List<ScheduledDay> upcoming) {
    }

    public MyDay myToday() {
        ZonedDateTime now = ZonedDateTime.now(clock);
        return new MyDay(forPerson(me(), now.toLocalDate()), now.toOffsetDateTime().truncatedTo(ChronoUnit.SECONDS));
    }

    public MyWeek myWeek(LocalDate date) {
        UUID person = me();
        UUID department = people.departmentOfPerson(person);
        LocalDate today = LocalDate.now(clock);
        LocalDate monday = (date == null ? today : date).with(DayOfWeek.MONDAY);
        LocalDate sunday = monday.plusDays(6);
        List<ScheduledDay> days = new ArrayList<>();
        List<ScheduledDay> usual = new ArrayList<>();
        for (LocalDate d = monday; !d.isAfter(sunday); d = d.plusDays(1)) {
            days.add(forPerson(person, department, d));
            usual.add(usualDay(person, d));
        }
        List<ScheduledDay> upcoming = specialDays.specialDates(person, department, today, today.plusDays(UPCOMING_DAYS)).stream()
                .map(d -> forPerson(person, department, d)).toList();
        return new MyWeek(monday, sunday, today, days, usual, upcoming);
    }

    /** The signed-in app user's person (404 when the login is not linked to one of the school's people). */
    private UUID me() {
        CurrentUser user = CurrentUser.require();
        if (user.personId() == null || !people.isActive(user.personId())) {
            throw new ApiException(ErrorCode.NOT_FOUND);
        }
        return user.personId();
    }
}
