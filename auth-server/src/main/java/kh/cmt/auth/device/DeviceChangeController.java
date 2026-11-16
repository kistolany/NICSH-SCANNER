package kh.cmt.auth.device;

import java.net.URI;
import java.time.Clock;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * "Request device change", from the phone that got {@code DEVICE_MISMATCH}. It has no login, only the
 * {@code deviceChangeToken} from that answer, sent as header {@value #TOKEN_HEADER}.
 * <pre>
 * GET    /auth/device/change-request    → {request: {id, status, reason, createdAt, rejectionReason} | null}
 * POST   /auth/device/change-request    {reason: new_phone | lost_phone | broken} → 201 the request
 * DELETE /auth/device/change-request    → 204 (cancel the open one)
 * </pre>
 * {@code status}: pending → seen (an admin opened the list) → approved (this phone is now the linked one: log in again)
 * | rejected (with the reason) | cancelled. One open request per account ({@code REQUEST_ALREADY_OPEN}); refused when
 * the school does not take requests ({@code CHANGE_REQUESTS_OFF}). A missing or expired token is {@code INVALID_TOKEN}.
 */
@RestController
@RequestMapping("/auth/device/change-request")
class DeviceChangeController {

    static final String TOKEN_HEADER = "X-Device-Change-Token";
    private static final Set<String> REASONS = Set.of("new_phone", "lost_phone", "broken");

    record Create(String reason) {
    }

    record Request(UUID id, String status, String reason, OffsetDateTime createdAt, String rejectionReason) {
    }

    private final DeviceStore devices;
    private final JdbcClient jdbc;
    private final Clock clock;

    DeviceChangeController(DeviceStore devices, JdbcClient jdbc, Clock clock) {
        this.devices = devices;
        this.jdbc = jdbc;
        this.clock = clock;
    }

    /** The latest request this phone made for the account (open, or decided), or null. */
    @GetMapping
    Map<String, Object> current(@RequestHeader(value = TOKEN_HEADER, required = false) String token) {
        DeviceStore.TokenHolder phone = holder(token);
        Map<String, Object> out = new HashMap<>();
        out.put("request", latest(phone).orElse(null));
        return out;
    }

    @PostMapping
    @Transactional
    ResponseEntity<Request> create(@RequestHeader(value = TOKEN_HEADER, required = false) String token, @RequestBody Create body) {
        DeviceStore.TokenHolder phone = holder(token);
        if (body == null || body.reason() == null || !REASONS.contains(body.reason())) {
            throw new Refused(HttpStatus.BAD_REQUEST, "VALIDATION_ERROR").with("errors", List.of(Map.of("field", "reason", "rule", "OneOf")));
        }
        if (!devices.policy(phone.schoolId()).changeRequests()) {
            throw new Refused(HttpStatus.FORBIDDEN, "CHANGE_REQUESTS_OFF");
        }
        Optional<DeviceStore.Linked> linked = devices.linked(phone.accountId());
        UUID id = UUID.randomUUID();
        try {
            jdbc.sql("""
                    insert into device_change_request (id, school_id, account_id, status, reason, old_device_id, old_device_name,
                                                       new_device_id, new_device_name, new_platform, created_at)
                    values (:id, :s, :a, 'pending', :r, :od, :on, :nd, :nn, :np, :now)""")
                    .param("id", id).param("s", phone.schoolId()).param("a", phone.accountId()).param("r", body.reason())
                    .param("od", linked.map(DeviceStore.Linked::deviceId).orElse(null))
                    .param("on", linked.map(DeviceStore.Linked::name).orElse(null))
                    .param("nd", phone.deviceId()).param("nn", phone.deviceName()).param("np", phone.platform())
                    .param("now", clock.instant().atOffset(ZoneOffset.UTC)).update();
        } catch (DuplicateKeyException open) {
            throw new Refused(HttpStatus.CONFLICT, "REQUEST_ALREADY_OPEN");
        }
        Request created = latest(phone).orElseThrow();
        return ResponseEntity.created(URI.create("/auth/device/change-request")).body(created);
    }

    @DeleteMapping
    @Transactional
    ResponseEntity<Void> cancel(@RequestHeader(value = TOKEN_HEADER, required = false) String token) {
        DeviceStore.TokenHolder phone = holder(token);
        jdbc.sql("""
                update device_change_request set status = 'cancelled', decided_at = :now
                where account_id = :a and new_device_id = :d and status in ('pending', 'seen')""")
                .param("now", clock.instant().atOffset(ZoneOffset.UTC)).param("a", phone.accountId()).param("d", phone.deviceId()).update();
        return ResponseEntity.noContent().build();
    }

    private Optional<Request> latest(DeviceStore.TokenHolder phone) {
        return jdbc.sql("""
                select id, status, reason, created_at, rejection_reason from device_change_request
                where account_id = :a and new_device_id = :d order by created_at desc limit 1""")
                .param("a", phone.accountId()).param("d", phone.deviceId())
                .query((rs, n) -> new Request(rs.getObject("id", UUID.class), rs.getString("status"), rs.getString("reason"),
                        rs.getObject("created_at", OffsetDateTime.class), rs.getString("rejection_reason")))
                .optional();
    }

    private DeviceStore.TokenHolder holder(String token) {
        return devices.tokenHolder(token).orElseThrow(() -> new Refused(HttpStatus.UNAUTHORIZED, "INVALID_TOKEN"));
    }

    // ------------------------------------------------------------------ errors (problem JSON with code)

    static class Refused extends RuntimeException {
        final HttpStatus status;
        final String code;
        final Map<String, Object> properties = new HashMap<>();

        Refused(HttpStatus status, String code) {
            super(code);
            this.status = status;
            this.code = code;
        }

        Refused with(String key, Object value) {
            properties.put(key, value);
            return this;
        }
    }

    @org.springframework.web.bind.annotation.ExceptionHandler(Refused.class)
    ResponseEntity<ProblemDetail> refused(Refused ex) {
        ProblemDetail problem = ProblemDetail.forStatus(ex.status);
        problem.setType(URI.create("urn:cmt:error:" + ex.code));
        problem.setTitle(ex.status.getReasonPhrase());
        problem.setProperty("code", ex.code);
        ex.properties.forEach(problem::setProperty);
        return ResponseEntity.status(ex.status).body(problem);
    }
}
