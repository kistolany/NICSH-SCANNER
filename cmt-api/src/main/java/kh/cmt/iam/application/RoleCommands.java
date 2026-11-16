package kh.cmt.iam.application;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import java.time.LocalTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import kh.cmt.shared.i18n.LocalizedName;
import kh.cmt.shared.security.ScopeType;

/** What the apps send to create and change roles. */
public final class RoleCommands {

    private RoleCommands() {
    }

    /**
     * New custom role: a name (English required), a description, and the modules it may open. The role starts with
     * View only in those modules; Create / Edit / Delete / Approve / Export are set in the permission table afterwards.
     * {@code scope} is optional (all school when left out); so is {@code sensitiveFields} (national ID and phone
     * numbers when left out).
     */
    public record Create(@NotNull @Valid LocalizedName name, @Valid LocalizedName description, List<String> viewModules,
            Scope scope, SensitiveFields sensitiveFields) {
    }

    /** What the role's users may see: every group must be sent, true or false. {@code version} when changing a role. */
    public record SensitiveFields(Boolean salaryBank, Boolean nationalId, Boolean phone, Boolean idScans, Long version) {
    }

    /** New name / description of a custom role. */
    public record Update(@NotNull @Valid LocalizedName name, @Valid LocalizedName description, Long version) {
    }

    /**
     * Record scope of a custom role. {@code departmentIds} is needed for SELECTED_DEPARTMENTS (active departments of
     * the school) and ignored for the other types. {@code version} is needed when changing an existing role.
     */
    public record Scope(ScopeType type, List<UUID> departmentIds, Long version) {
    }

    /** Approval limits: leave up to N days (null = no limit), old corrections, own requests, escalation role code. */
    public record Limits(Integer leaveMaxDays, Boolean oldCorrections, Boolean ownRequests, String escalateTo, Long version) {
    }

    /**
     * Login rules: session timeout (15 to 720 minutes), allowed hours ("HH:mm", both or neither), allowed ISO weekdays
     * (1 = Monday), allowed IP addresses or CIDR ranges (empty = anywhere).
     */
    public record Login(Integer sessionTimeoutMinutes, LocalTime allowedFrom, LocalTime allowedTo, List<Integer> allowedDays,
            List<String> allowedIps, Long version) {
    }

    /** Require two-step verification for the role. */
    public record TwoFactor(Boolean required, Long version) {
    }

    /** The whole permission table of a custom role: module key -> action keys. Replaces what the role had. */
    public record Permissions(@NotNull Map<String, List<String>> permissions, Long version) {
    }
}
