package kh.cmt.schedule.domain;

import java.time.LocalDate;
import java.util.Collection;
import java.util.Comparator;
import java.util.Optional;
import java.util.UUID;

/**
 * Which weekly schedule applies to a person on a day: the most specific level that has a version in force — the
 * person's own, else their department's, else everyone's. A level is in force with its newest version that started
 * by that day; an "inherit" version means the level has nothing of its own (from then on), so the next level decides.
 */
public final class WeekResolution {

    private WeekResolution() {
    }

    /** The version that applies, from all versions of the school; empty when no level has a schedule. */
    public static Optional<WeeklySchedule> forPerson(Collection<WeeklySchedule> all, UUID personId, UUID departmentId, LocalDate day) {
        Optional<WeeklySchedule> own = inForce(all, WeeklySchedule.Target.PERSON, personId, day);
        if (own.isPresent() && !own.get().isInherit()) {
            return own;
        }
        if (departmentId != null) {
            Optional<WeeklySchedule> department = inForce(all, WeeklySchedule.Target.DEPARTMENT, departmentId, day);
            if (department.isPresent() && !department.get().isInherit()) {
                return department;
            }
        }
        return inForce(all, WeeklySchedule.Target.EVERYONE, null, day);
    }

    /** What applies to a whole level on a day (a department falls back to everyone; everyone has no fallback). */
    public static Optional<WeeklySchedule> forTarget(Collection<WeeklySchedule> all, WeeklySchedule.Target target, UUID id, LocalDate day) {
        return switch (target) {
            case EVERYONE -> inForce(all, target, null, day);
            case DEPARTMENT -> forPerson(all, null, id, day);
            case PERSON -> throw new IllegalArgumentException("use forPerson with the person's department");
        };
    }

    /** The newest version of this target that started by {@code day} (inherit ones included). */
    public static Optional<WeeklySchedule> inForce(Collection<WeeklySchedule> all, WeeklySchedule.Target target, UUID id, LocalDate day) {
        return all.stream()
                .filter(v -> v.getTargetType() == target && java.util.Objects.equals(idOf(v), id) && !v.getEffectiveFrom().isAfter(day))
                .max(Comparator.comparing(WeeklySchedule::getEffectiveFrom));
    }

    public static UUID idOf(WeeklySchedule v) {
        return switch (v.getTargetType()) {
            case EVERYONE -> null;
            case DEPARTMENT -> v.getDepartmentId();
            case PERSON -> v.getPersonId();
        };
    }
}
