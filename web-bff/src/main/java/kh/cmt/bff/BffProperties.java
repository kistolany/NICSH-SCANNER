package kh.cmt.bff;

import org.springframework.boot.context.properties.ConfigurationProperties;

/** Everything under {@code bff.*} in application.yml. */
@ConfigurationProperties("bff")
public record BffProperties(String apiUri, AuthServer authServer, String postLogoutRedirectUri, Cookie cookie) {

    public record AuthServer(String publicUrl, String internalUrl) {
    }

    public record Cookie(String name, boolean secure) {
    }
}
