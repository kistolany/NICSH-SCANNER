package kh.cmt.qrlocation.application;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import kh.cmt.files.api.Files;
import kh.cmt.iam.api.AccountNames;
import kh.cmt.org.api.SchoolInfo;
import kh.cmt.org.api.SchoolSettingsQueries;
import kh.cmt.qrlocation.api.QrCodes;
import kh.cmt.qrlocation.domain.Gate;
import kh.cmt.qrlocation.domain.GateQr;
import kh.cmt.qrlocation.domain.SchoolArea;
import kh.cmt.qrlocation.infrastructure.GateQrRepository;
import kh.cmt.qrlocation.infrastructure.GateRepository;
import kh.cmt.qrlocation.infrastructure.QrSheet;
import kh.cmt.qrlocation.infrastructure.QrSigner;
import kh.cmt.qrlocation.infrastructure.SchoolAreaRepository;
import kh.cmt.shared.security.CurrentUser;
import kh.cmt.shared.security.PermissionAction;
import kh.cmt.shared.security.PermissionModule;
import kh.cmt.shared.security.RequirePermission;
import kh.cmt.shared.security.SchoolContext;
import kh.cmt.shared.web.ApiException;
import kh.cmt.shared.web.ErrorCode;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Gate QR codes (Locations &amp; QR › Create QR, QR history). Each "Create QR" makes a new version of the gate's QR; the
 * version it replaces is still accepted for {@link #GRACE} (5 minutes), long enough to post the new sheet. A version
 * can be expired at once ("Expire now"), and moving a gate to inactive expires its QR at once.
 * <p>
 * Permissions (QR module): history — view; create, print and download — create (a printed QR lets people check in, so
 * seeing the page is not enough); expire now — edit.
 */
@Service
@Transactional
public class QrService implements QrCodes {

    public static final Duration GRACE = Duration.ofMinutes(5);
    static final int MAX_PAGE = 200;
    private static final DateTimeFormatter DAY = DateTimeFormatter.ofPattern("d MMM yyyy", Locale.ENGLISH);

    private final GateQrRepository qrs;
    private final GateRepository gates;
    private final SchoolAreaRepository areas;
    private final QrSigner signer;
    private final QrSheet sheets;
    private final SchoolSettingsQueries settings;
    private final Files files;
    private final AccountNames names;
    private final SchoolContext schoolContext;
    private final Clock clock;

    QrService(GateQrRepository qrs, GateRepository gates, SchoolAreaRepository areas, QrSigner signer, QrSheet sheets,
            SchoolSettingsQueries settings, Files files, AccountNames names, SchoolContext schoolContext, Clock clock) {
        this.qrs = qrs;
        this.gates = gates;
        this.areas = areas;
        this.signer = signer;
        this.sheets = sheets;
        this.settings = settings;
        this.files = files;
        this.names = names;
        this.schoolContext = schoolContext;
        this.clock = clock;
    }

    // ------------------------------------------------------------------ the page

    /** A new QR version for an active gate; the open one expires {@link #GRACE} from now. */
    @RequirePermission(module = PermissionModule.QR, action = PermissionAction.CREATE)
    public QrViews.Created create(UUID gateId) {
        Gate gate = loadGate(gateId);
        if (gate.isInactive()) {
            throw new ApiException(ErrorCode.CONFLICT);   // restore the gate first
        }
        Instant now = clock.instant();
        UUID me = CurrentUser.require().userId();
        GateQr replaced = null;
        for (GateQr open : qrs.findUsable(gateId, now)) {   // locks them: two "Create QR" at once make one after the other
            if (open.getExpiresAt() == null) {
                open.expire(now.plus(GRACE), GateQr.ExpiredReason.REPLACED, me);
                replaced = open;
            }
        }
        qrs.flush();
        int version = qrs.lastVersion(gateId) + 1;
        String salt = QrSigner.newSalt();
        QrSigner.Signed signed = signer.sign(gate.getSchoolId(), gateId, version, salt);
        GateQr qr;
        try {
            qr = qrs.saveAndFlush(new GateQr(gateId, version, salt, signed.sigHash()));
        } catch (DataIntegrityViolationException parallel) {
            throw new ApiException(ErrorCode.CONFLICT);
        }
        Map<UUID, String> who = names.names(Set.of(me));
        return new QrViews.Created(view(qr, gate, who, now), replaced == null ? null : view(replaced, gate, who, now));
    }

    /** History, newest first; one gate's with {@code gateId}. */
    @Transactional(readOnly = true)
    @RequirePermission(module = PermissionModule.QR, action = PermissionAction.VIEW)
    public QrViews.History history(UUID gateId, int page, int size) {
        if (page < 0 || size < 1 || size > MAX_PAGE) {
            throw new ApiException(ErrorCode.VALIDATION_ERROR).with("errors", List.of(Map.of("field", "size", "rule", "Range")));
        }
        UUID school = school();
        Page<GateQr> found = gateId == null
                ? qrs.findBySchoolIdOrderByCreatedAtDescQrVersionDesc(school, PageRequest.of(page, size))
                : qrs.findBySchoolIdAndGateIdOrderByCreatedAtDescQrVersionDesc(school, gateId, PageRequest.of(page, size));
        Map<UUID, Gate> byGate = new HashMap<>();
        gates.findAllById(found.getContent().stream().map(GateQr::getGateId).collect(Collectors.toSet())).forEach(g -> byGate.put(g.getId(), g));
        Map<UUID, String> who = names.names(found.getContent().stream().map(GateQr::getCreatedBy).collect(Collectors.toSet()));
        Instant now = clock.instant();
        List<QrViews.Qr> items = new ArrayList<>();
        for (GateQr q : found.getContent()) {
            items.add(view(q, byGate.get(q.getGateId()), who, now));
        }
        return new QrViews.History(items, page, size, found.getTotalElements());
    }

    /** "Expire now": the version stops being accepted at once (repeating it changes nothing). */
    @RequirePermission(module = PermissionModule.QR, action = PermissionAction.EDIT)
    public QrViews.Qr expire(UUID qrId) {
        GateQr qr = qrs.findById(qrId).filter(q -> q.getSchoolId().equals(school())).orElseThrow(() -> new ApiException(ErrorCode.NOT_FOUND));
        Instant now = clock.instant();
        qr.expire(now, GateQr.ExpiredReason.MANUAL, CurrentUser.require().userId());
        qr = qrs.saveAndFlush(qr);
        Gate gate = gates.findById(qr.getGateId()).orElse(null);
        return view(qr, gate, names.names(Set.of(qr.getCreatedBy() == null ? new UUID(0, 0) : qr.getCreatedBy())), now);
    }

    /**
     * The sheet to post at the gate, A4: {@code pdf} or {@code png}. {@code version}: the open one when left out; a
     * version that no longer works cannot be printed ({@code QR_EXPIRED}).
     */
    @Transactional(readOnly = true)
    @RequirePermission(module = PermissionModule.QR, action = PermissionAction.CREATE)
    public QrViews.Sheet sheet(UUID gateId, Integer version, String format) {
        Gate gate = loadGate(gateId);
        GateQr qr = (version == null ? qrs.findByGateIdAndExpiresAtIsNull(gateId) : qrs.findByGateIdAndQrVersion(gateId, version))
                .orElseThrow(() -> new ApiException(ErrorCode.NOT_FOUND));
        if (gate.isInactive() || !qr.acceptedAt(clock.instant())) {
            throw new ApiException(ErrorCode.QR_EXPIRED);
        }
        SchoolInfo school = settings.school();
        String schoolName = Optional.ofNullable(school.name().en()).filter(s -> !s.isBlank()).orElse(school.name().km());
        String area = areas.findById(gate.getAreaId()).map(SchoolArea::getName).orElse(null);
        byte[] logo = files.read(school.logoFileId()).orElse(null);
        String payload = signer.sign(gate.getSchoolId(), gateId, qr.getQrVersion(), qr.getSalt()).payload();
        String footer = gate.getName() + " · QR v" + qr.getQrVersion() + " · "
                + DAY.format(LocalDate.ofInstant(qr.getCreatedAt(), clock.getZone()));
        QrSheet.Content content = new QrSheet.Content(payload, schoolName, logo, gate.getName(), area, footer);
        String base = "QR_" + gate.getName().replaceAll("[^A-Za-z0-9]+", "-").replaceAll("^-|-$", "") + "_v" + qr.getQrVersion();
        return "png".equals(format)
                ? new QrViews.Sheet(sheets.png(content), "image/png", base + ".png")
                : new QrViews.Sheet(sheets.pdf(content), "application/pdf", base + ".pdf");
    }

    // ------------------------------------------------------------------ for the gates (same module)

    /** The open version of each gate (for the gate cards). */
    @Transactional(readOnly = true)
    public Map<UUID, GateQr> openOf(java.util.Collection<UUID> gateIds) {
        Map<UUID, GateQr> out = new HashMap<>();
        if (!gateIds.isEmpty()) {
            qrs.findByGateIdInAndExpiresAtIsNull(gateIds).forEach(q -> out.put(q.getGateId(), q));
        }
        return out;
    }

    /** The gate was moved to inactive: its QR codes stop working now (a restored gate needs a new QR). */
    void gateRetired(UUID gateId) {
        Instant now = clock.instant();
        UUID me = CurrentUser.current().map(CurrentUser::userId).orElse(null);
        qrs.findUsable(gateId, now).forEach(q -> q.expire(now, GateQr.ExpiredReason.GATE_INACTIVE, me));
    }

    // ------------------------------------------------------------------ for the scan

    @Override
    @Transactional(readOnly = true)
    public Check verify(String payload, Instant at) {
        Optional<QrSigner.Parsed> parsed = QrSigner.parse(payload);
        if (parsed.isEmpty()) {
            return new Check(Result.QR_INVALID, null, null, null, null);
        }
        QrSigner.Parsed p = parsed.get();
        UUID school = school();
        Optional<Gate> gate = gates.findById(p.gateId()).filter(g -> g.getSchoolId().equals(school));
        Optional<GateQr> qr = gate.flatMap(g -> qrs.findByGateIdAndQrVersion(g.getId(), p.version()));
        if (qr.isEmpty() || !signer.matches(school, p.gateId(), p.version(), qr.get().getSalt(), qr.get().getSigHash(), p.sig())) {
            return new Check(Result.QR_INVALID, null, null, null, null);
        }
        Gate g = gate.get();
        GateQr q = qr.get();
        if (!q.acceptedAt(at)) {
            return new Check(Result.QR_EXPIRED, g.getId(), g.getAreaId(), q.getQrVersion(), q.getExpiresAt());
        }
        if (g.isInactive()) {
            return new Check(Result.QR_INVALID, g.getId(), g.getAreaId(), q.getQrVersion(), q.getExpiresAt());
        }
        return new Check(Result.VALID, g.getId(), g.getAreaId(), q.getQrVersion(), q.getExpiresAt());
    }

    // ------------------------------------------------------------------ helpers

    private QrViews.Qr view(GateQr q, Gate gate, Map<UUID, String> who, Instant now) {
        GateQr.Status status = q.status(now);
        Long left = status == GateQr.Status.EXPIRING ? Math.max(0, Duration.between(now, q.getExpiresAt()).toSeconds()) : null;
        QrViews.GateRef ref = gate == null ? null : new QrViews.GateRef(gate.getId(), gate.getName(), gate.isInactive());
        return new QrViews.Qr(q.getId(), ref, q.getQrVersion(), status.name(), q.getExpiresAt(), left,
                q.getExpiredReason() == null ? null : q.getExpiredReason().name(), q.getCreatedAt(), q.getCreatedBy(), who.get(q.getCreatedBy()));
    }

    private Gate loadGate(UUID id) {
        return gates.findById(id).filter(g -> g.getSchoolId().equals(school())).orElseThrow(() -> new ApiException(ErrorCode.NOT_FOUND));
    }

    private UUID school() {
        return schoolContext.currentSchoolId();
    }
}
