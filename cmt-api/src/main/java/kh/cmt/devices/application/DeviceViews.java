package kh.cmt.devices.application;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import kh.cmt.shared.i18n.LocalizedName;

/** Admin Web › Devices and the app's "My device". */
public final class DeviceViews {

    private DeviceViews() {
    }

    public record Person(UUID id, String staffId, LocalizedName name, String role, UUID departmentId) {
    }

    /**
     * One row: the person, whether they have an app login, their phone, and {@code status} LINKED | WAITING (a change
     * request is open) | NONE (no phone linked) | BLOCKED.
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record Row(Person person, boolean hasLogin, Device device, String status, Block block, UUID requestId) {
    }

    /** {@code outdated}: older than the school's minimum app version; {@code idle}: not used for 7 days or more. */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record Device(String name, String platform, String os, String appVersion, boolean outdated, Instant linkedSince,
            Instant lastSeenAt, boolean idle, Boolean rooted, boolean hasKey) {
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record Block(String reason, UUID by, String byName, Instant at) {
    }

    /** The cards above the table: linked, waiting, no device, blocked, resets (and approved changes) this month. */
    public record Counts(long linked, long waiting, long none, long blocked, long resetsThisMonth) {
    }

    public record Page(List<Row> items, int page, int size, long total, Counts counts, String minAppVersion) {
    }

    /** A change request. {@code status} pending | seen | approved | rejected | cancelled. */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record Request(UUID id, Person person, String status, String reason, String oldDevice, NewDevice newDevice, Instant createdAt,
            Instant seenAt, Instant decidedAt, UUID decidedBy, String decidedByName, String rejectionReason) {
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record NewDevice(String name, String platform) {
    }

    /** A history entry. {@code kind} RESET | CHANGE_APPROVED | AUTO_REBIND | BLOCKED | UNBLOCKED | LINKED. */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record Event(UUID id, Person person, String kind, String oldDevice, String newDevice, String reason, UUID by, String byName,
            Instant at) {
    }

    public record History(List<Event> items, int page, int size) {
    }

    /** Device settings. {@code autoRebindDays} null (off) | 7 | 30; {@code minAppVersion} null = no minimum. */
    @JsonInclude(JsonInclude.Include.ALWAYS)
    public record Settings(Boolean oneDevice, Boolean changeRequests, Integer autoRebindDays, String minAppVersion, Boolean blockRooted,
            Boolean blockMockLocation, Long version) {
    }

    /** The app's Profile › My device. */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record MyDevice(String name, String platform, String os, String appVersion, Instant linkedSince, Instant lastSeenAt) {
    }

    /** POST /app/device/register: what the linked phone says about itself (all optional). */
    public record Register(String deviceName, String platform, String osVersion, String appVersion, String publicKey, Boolean rooted) {
    }

    /** {@code reason}: the chip picked in the dialog ("Lost phone"…), required to block / reject. */
    public record Reason(String reason) {
    }
}
