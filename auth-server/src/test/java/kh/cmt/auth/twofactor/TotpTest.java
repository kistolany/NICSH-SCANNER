package kh.cmt.auth.twofactor;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

/** RFC 6238 appendix B reference values (SHA-1, secret "12345678901234567890"), last 6 digits. */
class TotpTest {

    static final String SECRET = Totp.base32Encode("12345678901234567890".getBytes(StandardCharsets.US_ASCII));

    @Test
    void matchesTheRfc6238TestVectors() {
        assertThat(SECRET).isEqualTo("GEZDGNBVGY3TQOJQGEZDGNBVGY3TQOJQ");
        assertThat(Totp.code(SECRET, Totp.stepAt(59L))).isEqualTo("287082");
        assertThat(Totp.code(SECRET, Totp.stepAt(1111111109L))).isEqualTo("081804");
        assertThat(Totp.code(SECRET, Totp.stepAt(1111111111L))).isEqualTo("050471");
        assertThat(Totp.code(SECRET, Totp.stepAt(1234567890L))).isEqualTo("005924");
        assertThat(Totp.code(SECRET, Totp.stepAt(2000000000L))).isEqualTo("279037");
        assertThat(Totp.code(SECRET, Totp.stepAt(20000000000L))).isEqualTo("353130");
    }

    @Test
    void base32RoundTrips() {
        String secret = Totp.newSecret();
        assertThat(secret).matches("[A-Z2-7]{32}");
        assertThat(Totp.base32Encode(Totp.base32Decode(secret))).isEqualTo(secret);
    }

    @Test
    void acceptsOneStepOfClockDriftButNotMore() {
        long now = 1000;
        assertThat(Totp.verify(SECRET, Totp.code(SECRET, now), now, -1)).isEqualTo(now);
        assertThat(Totp.verify(SECRET, Totp.code(SECRET, now - 1), now, -1)).isEqualTo(now - 1);
        assertThat(Totp.verify(SECRET, Totp.code(SECRET, now + 1), now, -1)).isEqualTo(now + 1);
        assertThat(Totp.verify(SECRET, Totp.code(SECRET, now - 2), now, -1)).isEqualTo(-1);
        assertThat(Totp.verify(SECRET, Totp.code(SECRET, now + 2), now, -1)).isEqualTo(-1);
    }

    @Test
    void aStepAlreadyUsedIsRefused() {
        long now = 1000;
        assertThat(Totp.verify(SECRET, Totp.code(SECRET, now), now, now)).isEqualTo(-1);
        assertThat(Totp.verify(SECRET, Totp.code(SECRET, now), now, now - 1)).isEqualTo(now);
    }

    @Test
    void rejectsAnythingThatIsNotSixDigits() {
        assertThat(Totp.verify(SECRET, "12345", 1000, -1)).isEqualTo(-1);
        assertThat(Totp.verify(SECRET, "1234567", 1000, -1)).isEqualTo(-1);
        assertThat(Totp.verify(SECRET, "abcdef", 1000, -1)).isEqualTo(-1);
        assertThat(Totp.verify(SECRET, null, 1000, -1)).isEqualTo(-1);
    }

    @Test
    void theAuthenticatorLinkCarriesIssuerAccountAndSecret() {
        String uri = Totp.otpAuthUri("CMT School", "sokha.admin", SECRET);
        assertThat(uri).startsWith("otpauth://totp/CMT%20School:sokha.admin?secret=" + SECRET)
                .contains("issuer=CMT%20School").contains("digits=6").contains("period=30");
    }
}
