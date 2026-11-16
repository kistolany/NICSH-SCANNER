package kh.cmt.bff;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;

/**
 * Backend-for-frontend of the Admin Web. The browser never sees a token: it logs in through this app,
 * which keeps the tokens in a server-side session (Redis) and gives the browser one HttpOnly cookie.
 * Calls to /api/** are forwarded to cmt-api with the access token attached.
 */
@SpringBootApplication
@EnableConfigurationProperties(BffProperties.class)
public class WebBffApplication {

    public static void main(String[] args) {
        SpringApplication.run(WebBffApplication.class, args);
    }
}
