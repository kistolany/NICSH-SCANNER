package kh.cmt.auth.twofactor;

/**
 * Sends the 6-digit fallback code by SMS. The real gateway adapter is built with the notifications module
 * (step 11.1); until then {@link LoggingOtpSmsSender} is the only implementation.
 */
public interface OtpSmsSender {

    void send(String phone, String code);
}
