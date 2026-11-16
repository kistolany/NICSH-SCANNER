package kh.cmt.auth.twofactor;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.ZoneOffset;
import java.util.HexFormat;
import java.util.Optional;
import java.util.UUID;
import kh.cmt.auth.AuthProperties;
import kh.cmt.auth.account.AccountRepository.AccountClaims;
import kh.cmt.auth.keys.SecretCipher;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;

/**
 * Two-step verification for web users: authenticator app (TOTP) with an SMS code as fallback.
 * <p>
 * A login needs it when the user's role requires it (table {@code role_login_rule}, kept by the iam module), when
 * it is required for the user themselves ({@code account.two_factor_required}, set on the web user), or when the user
 * already enrolled an authenticator. App (mobile) accounts never use it.
 */
@Service
public class TwoFactorService {

    /** Authorities added to the signed-in session once the second step is done. They become {@code amr}. */
    public static final String FACTOR_OTP = "FACTOR_OTP";
    public static final String FACTOR_SMS = "FACTOR_SMS";

    public enum SmsResult { SENT, NO_PHONE, TOO_SOON }

    private static final SecureRandom RANDOM = new SecureRandom();
    private static final String SMS_KEY = "auth:sms-otp:";
    private static final String SMS_COOLDOWN_KEY = "auth:sms-otp-cooldown:";

    private final JdbcClient jdbc;
    private final SecretCipher cipher;
    private final Clock clock;
    private final StringRedisTemplate redis;
    private final OtpSmsSender sms;
    private final AuthProperties props;

    public TwoFactorService(JdbcClient jdbc, SecretCipher cipher, Clock clock, StringRedisTemplate redis,
            OtpSmsSender sms, AuthProperties props) {
        this.jdbc = jdbc;
        this.cipher = cipher;
        this.clock = clock;
        this.redis = redis;
        this.sms = sms;
        this.props = props;
    }

    public boolean requiredFor(AccountClaims account) {
        if (!"web".equals(account.type())) {
            return false;
        }
        boolean roleRequires = jdbc.sql("""
                select two_factor_required from role_login_rule where school_id = :school and role = :role
                """)
                .param("school", account.schoolId()).param("role", account.role())
                .query(Boolean.class).optional().orElse(false);
        return roleRequires || account.twoFactorRequired() || isEnrolled(account.id());
    }

    public boolean isEnrolled(UUID accountId) {
        return jdbc.sql("select totp_enabled_at is not null from account where id = :id")
                .param("id", accountId).query(Boolean.class).optional().orElse(false);
    }

    public boolean hasPhone(UUID accountId) {
        return phone(accountId).isPresent();
    }

    public String issuerName() {
        return props.twoFactor().issuerName();
    }

    /** Finishes enrolment if {@code code} is right for the still-unsaved {@code secret}. */
    public boolean confirmEnrolment(UUID accountId, String secret, String code) {
        long step = Totp.verify(secret, code, nowStep(), -1);
        if (step < 0) {
            return false;
        }
        jdbc.sql("""
                update account set totp_secret_enc = :secret, totp_enabled_at = :now, totp_last_step = :step,
                                   updated_at = :now
                where id = :id
                """)
                .param("secret", cipher.encrypt(secret.getBytes(StandardCharsets.UTF_8)))
                .param("now", clock.instant().atOffset(ZoneOffset.UTC))
                .param("step", step).param("id", accountId).update();
        return true;
    }

    /** True if {@code code} is the current authenticator code. A code is accepted once only. */
    public boolean verifyTotp(UUID accountId, String code) {
        var row = jdbc.sql("select totp_secret_enc, totp_last_step from account where id = :id and totp_enabled_at is not null")
                .param("id", accountId)
                .query((rs, n) -> new Object[] {rs.getString("totp_secret_enc"), rs.getObject("totp_last_step", Long.class)})
                .optional();
        if (row.isEmpty()) {
            return false;
        }
        String secret = new String(cipher.decrypt((String) row.get()[0]), StandardCharsets.UTF_8);
        long last = row.get()[1] == null ? -1 : (Long) row.get()[1];
        long step = Totp.verify(secret, code, nowStep(), last);
        if (step < 0) {
            return false;
        }
        // compare-and-set: two parallel requests with the same code cannot both pass
        return jdbc.sql("""
                update account set totp_last_step = :step
                where id = :id and (totp_last_step is null or totp_last_step < :step)
                """).param("step", step).param("id", accountId).update() == 1;
    }

    public SmsResult sendSms(UUID accountId) {
        Optional<String> phone = phone(accountId);
        if (phone.isEmpty()) {
            return SmsResult.NO_PHONE;
        }
        Boolean first = redis.opsForValue().setIfAbsent(SMS_COOLDOWN_KEY + accountId, "1", props.twoFactor().smsCooldown());
        if (!Boolean.TRUE.equals(first)) {
            return SmsResult.TOO_SOON;
        }
        String code = String.format("%06d", RANDOM.nextInt(1_000_000));
        redis.opsForValue().set(SMS_KEY + accountId, hash(accountId, code), props.twoFactor().smsCodeTtl());
        sms.send(phone.get(), code);
        return SmsResult.SENT;
    }

    /** True if {@code code} matches the SMS sent most recently. A code works once. */
    public boolean verifySms(UUID accountId, String code) {
        String stored = redis.opsForValue().get(SMS_KEY + accountId);
        if (stored == null || code == null) {
            return false;
        }
        boolean match = MessageDigest.isEqual(stored.getBytes(StandardCharsets.UTF_8),
                hash(accountId, code).getBytes(StandardCharsets.UTF_8));
        if (match) {
            redis.delete(SMS_KEY + accountId);
        }
        return match;
    }

    /** Removes the authenticator (lost phone). Used by the admin "reset two-step verification" action (step 3.6). */
    public void reset(UUID accountId) {
        jdbc.sql("update account set totp_secret_enc = null, totp_enabled_at = null, totp_last_step = null where id = :id")
                .param("id", accountId).update();
    }

    private Optional<String> phone(UUID accountId) {
        return jdbc.sql("select phone from account where id = :id and phone is not null and phone <> ''")
                .param("id", accountId).query(String.class).optional();
    }

    private long nowStep() {
        return Totp.stepAt(clock.instant().getEpochSecond());
    }

    private static String hash(UUID accountId, String code) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest((accountId + ":" + code).getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
