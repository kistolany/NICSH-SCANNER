package kh.cmt.devices.application;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import kh.cmt.devices.domain.AppVersions;
import kh.cmt.devices.infrastructure.AuthDeviceStore;
import kh.cmt.devices.infrastructure.AuthDeviceStore.AccountDevice;
import kh.cmt.iam.api.AccountNames;
import kh.cmt.people.api.PeopleQueries;
import kh.cmt.people.api.PeopleQueries.PersonCard;
import kh.cmt.shared.security.CurrentUser;
import kh.cmt.shared.security.PermissionAction;
import kh.cmt.shared.security.PermissionModule;
import kh.cmt.shared.security.PersonLogins;
import kh.cmt.shared.security.RequirePermission;
import kh.cmt.shared.security.SchoolContext;
import kh.cmt.shared.web.ApiException;
import kh.cmt.shared.web.ErrorCode;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Admin Web › Devices (step 6.3): each person's phone, change requests, reset / block / unblock, history and the
 * school's device settings. The phones live in the auth server's tables (it links a phone at the first login and
 * refuses other phones); this module shows them and makes the admin's changes.
 * <p>
 * <b>Reset</b> unlinks the phone: the next phone the person logs in on is linked. <b>Approve</b> a change request links
 * the phone that asked. <b>Block</b> stops that phone for every account until it is unblocked (the person can still be
 * reset to a new phone). Reset, block and approve sign the person out everywhere.
 * <p>
 * Permissions: People view (lists, history, settings), People edit (reset, block, unblock, settings), Approvals approve
 * (approve / reject a change request). Only people within the caller's record scope are shown or changed.
 */
@Service
@Transactional
public class DeviceService {

    static final Duration IDLE = Duration.ofDays(7);
    static final int MAX_SIZE = 500;
    static final Set<String> HISTORY_KINDS = Set.of("RESET", "CHANGE_APPROVED", "AUTO_REBIND", "BLOCKED", "UNBLOCKED");
    static final Set<String> RESET_KINDS = Set.of("RESET", "CHANGE_APPROVED", "AUTO_REBIND");

    private final AuthDeviceStore store;
    private final PeopleQueries people;
    private final PersonLogins logins;
    private final AccountNames names;
    private final SchoolContext schoolContext;
    private final Clock clock;

    DeviceService(AuthDeviceStore store, PeopleQueries people, PersonLogins logins, AccountNames names, SchoolContext schoolContext,
            Clock clock) {
        this.store = store;
        this.people = people;
        this.logins = logins;
        this.names = names;
        this.schoolContext = schoolContext;
        this.clock = clock;
    }

    // ------------------------------------------------------------------ the table

    /** Filters of the Devices table (all optional). */
    public record Filter(String tab, String q, UUID department, String platform, String status, String version, boolean idle7) {
    }

    @Transactional(readOnly = true)
    @RequirePermission(module = PermissionModule.PEOPLE, action = PermissionAction.VIEW)
    public DeviceViews.Page list(Filter f, int page, int size) {
        if (page < 0 || size < 1 || size > MAX_SIZE) {
            throw validation("size", "Range");
        }
        UUID school = school();
        List<PersonCard> cards = people.visibleActive();
        Map<UUID, AccountDevice> devices = store.ofPeople(school, cards.stream().map(PersonCard::id).toList());
        AuthDeviceStore.Policy policy = store.policy(school);
        Map<UUID, String> blockers = names.names(devices.values().stream().map(AccountDevice::blockedBy).filter(Objects::nonNull).toList());
        Instant now = clock.instant();
        List<DeviceViews.Row> all = cards.stream().map(c -> row(c, devices.get(c.id()), policy, blockers, now)).toList();

        String q = f.q() == null ? "" : f.q().trim().toLowerCase(Locale.ROOT);
        List<DeviceViews.Row> shown = all.stream()
                .filter(r -> !"none".equals(f.tab()) || r.status().equals("NONE"))
                .filter(r -> q.isEmpty() || matches(r, q))
                .filter(r -> f.department() == null || f.department().equals(r.person().departmentId()))
                .filter(r -> f.platform() == null || f.platform().isBlank()
                        || (r.device() != null && f.platform().equalsIgnoreCase(r.device().platform())))
                .filter(r -> f.status() == null || f.status().isBlank() || r.status().equalsIgnoreCase(f.status()))
                .filter(r -> f.version() == null || f.version().isBlank()
                        || (r.device() != null && ("old".equals(f.version()) == r.device().outdated())))
                .filter(r -> !f.idle7() || (r.device() != null && r.device().idle()))
                .toList();
        int from = Math.min(page * size, shown.size());
        int to = Math.min(from + size, shown.size());
        Instant monthStart = LocalDate.now(clock).withDayOfMonth(1).atStartOfDay(clock.getZone()).toInstant();
        DeviceViews.Counts counts = new DeviceViews.Counts(count(all, "LINKED"), count(all, "WAITING"), count(all, "NONE"), count(all, "BLOCKED"),
                store.countEvents(school, RESET_KINDS, monthStart));
        return new DeviceViews.Page(shown.subList(from, to), page, size, shown.size(), counts, policy.minAppVersion());
    }

    private DeviceViews.Row row(PersonCard c, AccountDevice d, AuthDeviceStore.Policy policy, Map<UUID, String> blockers, Instant now) {
        DeviceViews.Person person = person(c);
        if (d == null) {
            return new DeviceViews.Row(person, false, null, "NONE", null, null);
        }
        DeviceViews.Device device = d.deviceId() == null ? null
                : new DeviceViews.Device(d.name(), d.platform(), d.os(), d.appVersion(),
                        d.appVersion() != null && AppVersions.older(d.appVersion(), policy.minAppVersion()), d.boundAt(), d.lastSeenAt(),
                        d.lastSeenAt() == null || d.lastSeenAt().isBefore(now.minus(IDLE)), d.rooted(), d.hasPublicKey());
        DeviceViews.Block block = d.blocked() ? new DeviceViews.Block(d.blockReason(), d.blockedBy(), blockers.get(d.blockedBy()), d.blockedAt()) : null;
        String status = d.blocked() ? "BLOCKED" : d.openRequestId() != null ? "WAITING" : d.deviceId() == null ? "NONE" : "LINKED";
        return new DeviceViews.Row(person, true, device, status, block, d.openRequestId());
    }

    private static boolean matches(DeviceViews.Row r, String q) {
        var n = r.person().name();
        return contains(n.en(), q) || contains(n.km(), q) || contains(n.ar(), q) || contains(r.person().staffId(), q)
                || (r.device() != null && contains(r.device().name(), q));
    }

    private static boolean contains(String s, String q) {
        return s != null && s.toLowerCase(Locale.ROOT).contains(q);
    }

    private static long count(List<DeviceViews.Row> rows, String status) {
        return rows.stream().filter(r -> r.status().equals(status)).count();
    }

    // ------------------------------------------------------------------ change requests

    /** {@code status} open (default: pending or seen) | all (newest 200). Listing open ones marks them seen. */
    @RequirePermission(module = PermissionModule.PEOPLE, action = PermissionAction.VIEW)
    public List<DeviceViews.Request> requests(String status) {
        boolean open = status == null || status.isBlank() || "open".equals(status);
        if (!open && !"all".equals(status)) {
            throw validation("status", "OneOf");
        }
        Map<UUID, PersonCard> visible = people.visibleActive().stream().collect(Collectors.toMap(PersonCard::id, c -> c));
        List<AuthDeviceStore.ChangeRequest> rows = store.requests(school(), open, 200).stream()
                .filter(r -> visible.containsKey(r.personId())).toList();
        store.markSeen(rows.stream().filter(r -> r.status().equals("pending")).map(AuthDeviceStore.ChangeRequest::id).toList());
        Map<UUID, String> who = names.names(rows.stream().map(AuthDeviceStore.ChangeRequest::decidedBy).filter(Objects::nonNull).toList());
        return rows.stream().map(r -> request(r, visible.get(r.personId()), who)).toList();
    }

    /** The phone that asked becomes the linked one; the old phone is signed out. */
    @RequirePermission(module = PermissionModule.APPROVALS, action = PermissionAction.APPROVE)
    public DeviceViews.Request approve(UUID requestId) {
        AuthDeviceStore.ChangeRequest r = openRequest(requestId);
        UUID me = CurrentUser.require().userId();
        if (!store.decide(r.id(), "approved", me, null)) {
            throw new ApiException(ErrorCode.CONFLICT);
        }
        store.link(r.accountId(), r.newDeviceId(), r.newDeviceName(), r.newPlatform());
        store.event(school(), r.accountId(), "CHANGE_APPROVED", r.oldDeviceName(), r.newDeviceName(), reasonLabel(r.reason()), me);
        logins.signOutEverywhere(r.personId());
        return request(store.request(school(), r.id()).orElseThrow(), null, names.names(Set.of(me)));
    }

    @RequirePermission(module = PermissionModule.APPROVALS, action = PermissionAction.APPROVE)
    public DeviceViews.Request reject(UUID requestId, DeviceViews.Reason body) {
        String reason = reason(body, 300);
        AuthDeviceStore.ChangeRequest r = openRequest(requestId);
        UUID me = CurrentUser.require().userId();
        if (!store.decide(r.id(), "rejected", me, reason)) {
            throw new ApiException(ErrorCode.CONFLICT);
        }
        return request(store.request(school(), r.id()).orElseThrow(), null, names.names(Set.of(me)));
    }

    private AuthDeviceStore.ChangeRequest openRequest(UUID id) {
        AuthDeviceStore.ChangeRequest r = store.request(school(), id).filter(x -> people.visible(x.personId()).isPresent())
                .orElseThrow(() -> new ApiException(ErrorCode.NOT_FOUND));
        if (!r.status().equals("pending") && !r.status().equals("seen")) {
            throw new ApiException(ErrorCode.CONFLICT);   // already decided or cancelled
        }
        return r;
    }

    private DeviceViews.Request request(AuthDeviceStore.ChangeRequest r, PersonCard card, Map<UUID, String> who) {
        DeviceViews.Person person = card != null ? person(card)
                : people.labels(Set.of(r.personId())).values().stream().findFirst()
                        .map(l -> new DeviceViews.Person(l.id(), l.staffId(), l.name(), null, null)).orElse(null);
        return new DeviceViews.Request(r.id(), person, r.status(), r.reason(), r.oldDeviceName(),
                new DeviceViews.NewDevice(r.newDeviceName(), r.newPlatform()), r.createdAt(), r.seenAt(), r.decidedAt(), r.decidedBy(),
                who.get(r.decidedBy()), r.rejectionReason());
    }

    // ------------------------------------------------------------------ reset / block / unblock

    /** Unlinks the phone; the next phone the person logs in on is linked. Signs them out everywhere. */
    @RequirePermission(module = PermissionModule.PEOPLE, action = PermissionAction.EDIT)
    public DeviceViews.Row reset(UUID personId, DeviceViews.Reason body) {
        String reason = body == null || body.reason() == null || body.reason().isBlank() ? null : reason(body, 100);
        AccountDevice d = accountOf(personId);
        if (d.deviceId() == null && d.openRequestId() == null) {
            throw new ApiException(ErrorCode.CONFLICT);   // no phone to reset
        }
        store.unlink(d.accountId());
        store.event(school(), d.accountId(), "RESET", d.name(), null, reason, CurrentUser.require().userId());
        logins.signOutEverywhere(personId);
        return rowOf(personId);
    }

    /** The linked phone can no longer be used by any account until it is unblocked. Signs the person out everywhere. */
    @RequirePermission(module = PermissionModule.PEOPLE, action = PermissionAction.EDIT)
    public DeviceViews.Row block(UUID personId, DeviceViews.Reason body) {
        String reason = reason(body, 100);
        AccountDevice d = accountOf(personId);
        if (d.deviceId() == null) {
            throw new ApiException(ErrorCode.CONFLICT);   // no phone linked
        }
        if (!d.blocked()) {
            UUID me = CurrentUser.require().userId();
            store.block(school(), d.accountId(), d.deviceId(), d.name(), reason, me);
            store.event(school(), d.accountId(), "BLOCKED", d.name(), null, reason, me);
            logins.signOutEverywhere(personId);
        }
        return rowOf(personId);
    }

    @RequirePermission(module = PermissionModule.PEOPLE, action = PermissionAction.EDIT)
    public DeviceViews.Row unblock(UUID personId) {
        AccountDevice d = accountOf(personId);
        if (d.deviceId() != null && store.unblock(d.deviceId()) > 0) {
            store.event(school(), d.accountId(), "UNBLOCKED", d.name(), null, null, CurrentUser.require().userId());
        }
        return rowOf(personId);
    }

    private AccountDevice accountOf(UUID personId) {
        if (people.visible(personId).filter(PeopleQueries.VisiblePerson::active).isEmpty()) {
            throw new ApiException(ErrorCode.NOT_FOUND);
        }
        return store.ofPeople(school(), Set.of(personId)).values().stream().findFirst()
                .orElseThrow(() -> new ApiException(ErrorCode.NO_LOGIN));
    }

    private DeviceViews.Row rowOf(UUID personId) {
        PersonCard card = people.visibleActive().stream().filter(c -> c.id().equals(personId)).findFirst()
                .orElseThrow(() -> new ApiException(ErrorCode.NOT_FOUND));
        AccountDevice d = store.ofPeople(school(), Set.of(personId)).get(personId);
        return row(card, d, store.policy(school()), d != null && d.blockedBy() != null ? names.names(Set.of(d.blockedBy())) : Map.of(),
                clock.instant());
    }

    // ------------------------------------------------------------------ history

    @Transactional(readOnly = true)
    @RequirePermission(module = PermissionModule.PEOPLE, action = PermissionAction.VIEW)
    public DeviceViews.History history(String kind, int page, int size) {
        if (page < 0 || size < 1 || size > MAX_SIZE) {
            throw validation("size", "Range");
        }
        Set<String> kinds = kind == null || kind.isBlank() ? HISTORY_KINDS
                : "reset".equals(kind) ? RESET_KINDS : Set.of(kind.toUpperCase(Locale.ROOT));
        if (!HISTORY_KINDS.containsAll(kinds)) {
            throw validation("kind", "OneOf");
        }
        Set<UUID> visible = people.visibleActive().stream().map(PersonCard::id).collect(Collectors.toSet());
        // inactive people's history stays readable to those who see everyone; others see their own people's only
        List<AuthDeviceStore.Event> rows = store.events(school(), kinds, page * size, size);
        Set<UUID> personIds = rows.stream().map(AuthDeviceStore.Event::personId).filter(Objects::nonNull).collect(Collectors.toSet());
        Map<UUID, PeopleQueries.PersonLabel> labels = people.labels(personIds);
        Map<UUID, String> who = names.names(rows.stream().map(AuthDeviceStore.Event::by).filter(Objects::nonNull).toList());
        List<DeviceViews.Event> items = new ArrayList<>();
        for (AuthDeviceStore.Event e : rows) {
            if (e.personId() == null || (!visible.contains(e.personId()) && people.visible(e.personId()).isEmpty())) {
                continue;
            }
            PeopleQueries.PersonLabel l = labels.get(e.personId());
            DeviceViews.Person person = l == null ? null : new DeviceViews.Person(l.id(), l.staffId(), l.name(), null, null);
            items.add(new DeviceViews.Event(e.id(), person, e.kind(), e.oldDeviceName(), e.newDeviceName(), e.reason(), e.by(), who.get(e.by()),
                    e.at()));
        }
        return new DeviceViews.History(items, page, size);
    }

    // ------------------------------------------------------------------ settings

    @Transactional(readOnly = true)
    @RequirePermission(module = PermissionModule.PEOPLE, action = PermissionAction.VIEW)
    public DeviceViews.Settings settings() {
        return view(store.policy(school()));
    }

    /** Saves the device settings (send the {@code version} read; a school that never saved them has 0). */
    @RequirePermission(module = PermissionModule.PEOPLE, action = PermissionAction.EDIT)
    public DeviceViews.Settings saveSettings(DeviceViews.Settings s) {
        List<Map<String, String>> errors = new ArrayList<>();
        for (var field : List.of(Map.entry("oneDevice", s.oneDevice() != null), Map.entry("changeRequests", s.changeRequests() != null),
                Map.entry("blockRooted", s.blockRooted() != null), Map.entry("blockMockLocation", s.blockMockLocation() != null),
                Map.entry("version", s.version() != null))) {
            if (!field.getValue()) {
                errors.add(Map.of("field", field.getKey(), "rule", "NotNull"));
            }
        }
        if (s.autoRebindDays() != null && s.autoRebindDays() != 7 && s.autoRebindDays() != 30) {
            errors.add(Map.of("field", "autoRebindDays", "rule", "OneOf"));
        }
        String min = s.minAppVersion() == null || s.minAppVersion().isBlank() ? null : s.minAppVersion().trim();
        if (min != null && !AppVersions.valid(min)) {
            errors.add(Map.of("field", "minAppVersion", "rule", "Pattern"));
        }
        if (!errors.isEmpty()) {
            throw new ApiException(ErrorCode.VALIDATION_ERROR).with("errors", errors);
        }
        AuthDeviceStore.Policy p = new AuthDeviceStore.Policy(s.oneDevice(), s.changeRequests(), s.autoRebindDays(), min, s.blockRooted(),
                s.blockMockLocation(), 0);
        if (!store.savePolicy(school(), p, s.version(), CurrentUser.require().userId())) {
            throw new ApiException(ErrorCode.VERSION_CONFLICT);
        }
        return view(store.policy(school()));
    }

    private static DeviceViews.Settings view(AuthDeviceStore.Policy p) {
        return new DeviceViews.Settings(p.oneDevice(), p.changeRequests(), p.autoRebindDays(), p.minAppVersion(), p.blockRooted(),
                p.blockMockLocation(), p.version());
    }

    // ------------------------------------------------------------------ the app

    /** The signed-in app user's linked phone (Profile › My device); 404 when none is linked. */
    @Transactional(readOnly = true)
    public DeviceViews.MyDevice myDevice() {
        AccountDevice d = store.ofAccount(CurrentUser.require().userId()).filter(x -> x.deviceId() != null)
                .orElseThrow(() -> new ApiException(ErrorCode.NOT_FOUND));
        return new DeviceViews.MyDevice(d.name(), d.platform(), d.os(), d.appVersion(), d.boundAt(), d.lastSeenAt());
    }

    /**
     * The linked phone says what it is now (after an app update, a new OS, its key for signed scans). Only from the
     * linked phone ({@code device_id} of the token), else {@code DEVICE_MISMATCH}; a blocked phone gets
     * {@code DEVICE_BLOCKED}.
     */
    public DeviceViews.MyDevice register(DeviceViews.Register r) {
        CurrentUser user = CurrentUser.require();
        AccountDevice d = store.ofAccount(user.userId()).orElseThrow(() -> new ApiException(ErrorCode.NOT_FOUND));
        if (d.deviceId() == null || !d.deviceId().equals(user.deviceId())) {
            throw new ApiException(ErrorCode.DEVICE_MISMATCH);
        }
        if (d.blocked()) {
            throw new ApiException(ErrorCode.DEVICE_BLOCKED);
        }
        String platform = cut(r.platform(), 10);
        store.describe(user.userId(), cut(r.deviceName(), 100), platform == null ? null : platform.toLowerCase(Locale.ROOT),
                cut(r.osVersion(), 40), cut(r.appVersion(), 20), cut(r.publicKey(), 2000), r.rooted());
        return myDevice();
    }

    // ------------------------------------------------------------------ helpers

    private static DeviceViews.Person person(PersonCard c) {
        return new DeviceViews.Person(c.id(), c.staffId(), c.name(), c.role(), c.departmentId());
    }

    private static String reason(DeviceViews.Reason body, int max) {
        String reason = body == null || body.reason() == null ? "" : body.reason().trim();
        if (reason.isEmpty()) {
            throw validation("reason", "NotBlank");
        }
        if (reason.length() > max) {
            throw validation("reason", "Size");
        }
        return reason;
    }

    static String reasonLabel(String code) {
        return switch (code) {
            case "new_phone" -> "New phone";
            case "lost_phone" -> "Lost phone";
            case "broken" -> "Broken";
            default -> code;
        };
    }

    private static String cut(String s, int max) {
        if (s == null || s.isBlank()) {
            return null;
        }
        String t = s.trim();
        return t.length() <= max ? t : t.substring(0, max);
    }

    private UUID school() {
        return schoolContext.currentSchoolId();
    }

    private static ApiException validation(String field, String rule) {
        return new ApiException(ErrorCode.VALIDATION_ERROR).with("errors", List.of(Map.of("field", field, "rule", rule)));
    }
}
