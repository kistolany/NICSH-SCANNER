package kh.cmt.leave.application;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import kh.cmt.leave.api.MyLeaveBalances;
import kh.cmt.shared.i18n.LocalizedName;

/** What the leave pages answer (step 9.1). */
public final class LeaveViews {

    private LeaveViews() {
    }

    /** A leave type; {@code attachment} NONE | OVER_ONE_DAY | ALWAYS. */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record Type(UUID id, String code, LocalizedName name, BigDecimal daysPerYear, boolean halfDays, String attachment, String color,
            boolean inactive, Instant deletedAt, String deleteReason, long version) {
    }

    /** GET /admin/leave-balances: the active types (the columns) and one row per person the caller may see. */
    public record Table(int year, List<Type> types, List<Row> rows) {
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record Person(UUID id, String staffId, LocalizedName name, String role, UUID departmentId, LocalDate startDate) {
    }

    /** One person's balances, in the order of {@code types}. */
    public record Row(Person person, List<Cell> balances) {
    }

    /**
     * {@code entitled} the type's days for the year (pro-rata in the start year), {@code adjusted} the admins' ±,
     * {@code total} = entitled + adjusted, {@code used} approved leave, {@code left} = total − used, {@code pending} the
     * days of requests still waiting (a new request may take at most left − pending).
     */
    public record Cell(UUID typeId, BigDecimal entitled, BigDecimal adjusted, BigDecimal total, BigDecimal used, BigDecimal left,
            BigDecimal pending) {
    }

    /** GET /admin/leave-balances/{personId}: the person's row and the adjustments of the year, newest first. */
    public record PersonBalances(int year, List<Type> types, Row row, List<Adjustment> adjustments) {
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record Adjustment(UUID id, UUID typeId, BigDecimal days, String reason, Instant at, UUID by, String byName) {
    }

    /** GET /app/leave/balances. */
    public record Mine(int year, List<MyLeaveBalances.Balance> balances) {
    }

    // ------------------------------------------------------------------ requests (step 9.2)

    /**
     * A leave request: {@code type} the type's code; {@code half} AM | PM (null = whole days); {@code workingDates} the
     * dates counted; {@code status} PENDING | APPROVED | REJECTED | CANCELLED; {@code comment} the admin's note.
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record Request(UUID id, UUID typeId, String type, LocalizedName typeName, String color, LocalDate startDate, LocalDate endDate,
            String half, BigDecimal days, List<LocalDate> workingDates, String reason, Attachment attachment, boolean shortNotice, String status,
            Instant requestedAt, Instant decidedAt, String comment) {
    }

    public record Attachment(UUID id, String name, String mime, long size) {
    }

    /** GET /app/leave/requests/{id}: the request and what happened to it, oldest first. */
    public record RequestDetail(Request request, List<Event> timeline) {
    }

    /** {@code event} SENT | APPROVED | REJECTED | CANCELLED; {@code byName} who decided (office users). */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record Event(String event, Instant at, String byName, String comment) {
    }

    /**
     * GET /app/leave/preview: the days the request would take ({@code dates} counted), what may still be taken of the type
     * this year ({@code left} = left − waiting requests) and after it ({@code after}); {@code enough}; {@code overlaps}: a
     * waiting or approved request already covering some of it; {@code attachmentRequired} by the type's rule.
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record Preview(UUID typeId, LocalDate startDate, LocalDate endDate, String half, BigDecimal days, List<LocalDate> dates,
            BigDecimal left, BigDecimal after, boolean enough, UUID overlaps, boolean shortNotice, boolean attachmentRequired) {
    }
}
