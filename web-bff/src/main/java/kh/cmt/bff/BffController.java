package kh.cmt.bff;

import java.util.LinkedHashMap;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.client.web.server.ServerOAuth2AuthorizedClientRepository;
import org.springframework.security.oauth2.core.oidc.user.OidcUser;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.reactive.function.BodyInserters;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.WebSession;
import org.springframework.web.util.UriComponentsBuilder;
import reactor.core.publisher.Mono;

/** What the single-page app asks the BFF itself (everything else under /api goes on to cmt-api). */
@RestController
class BffController {

    private static final Logger log = LoggerFactory.getLogger(BffController.class);

    /** Claims the app may see. Tokens are never included. */
    private static final String[] VISIBLE_CLAIMS = {"sub", "typ", "role", "perm_ver", "school_id", "locale",
            "person_id", "web_user_id", "amr"};

    private final ServerOAuth2AuthorizedClientRepository authorizedClients;
    private final WebClient webClient;
    private final BffProperties props;
    private final String clientSecret;

    BffController(ServerOAuth2AuthorizedClientRepository authorizedClients, WebClient webClient, BffProperties props,
            @Value("${spring.security.oauth2.client.registration.cmt.client-secret}") String clientSecret) {
        this.authorizedClients = authorizedClients;
        this.webClient = webClient;
        this.props = props;
        this.clientSecret = clientSecret;
    }

    /** Who is signed in. 401 when nobody is, which tells the app to send the browser to the login. */
    @GetMapping("/bff/user")
    Map<String, Object> user(@AuthenticationPrincipal OidcUser user) {
        Map<String, Object> visible = new LinkedHashMap<>();
        for (String claim : VISIBLE_CLAIMS) {
            Object value = user.getClaims().get(claim);
            if (value != null) {
                visible.put(claim, value);
            }
        }
        return visible;
    }

    /**
     * Ends the session here and returns the address the app must navigate to so the auth server ends its own
     * session too (otherwise the next login would sign straight back in without a password).
     */
    @PostMapping("/bff/logout")
    Mono<Map<String, String>> logout(ServerWebExchange exchange, Authentication authentication) {
        OidcUser user = (OidcUser) authentication.getPrincipal();
        String logoutUrl = UriComponentsBuilder.fromUriString(props.authServer().publicUrl() + "/connect/logout")
                .queryParam("id_token_hint", user.getIdToken().getTokenValue())
                .queryParam("post_logout_redirect_uri", props.postLogoutRedirectUri())
                .encode().build().toUriString();

        return authorizedClients.<org.springframework.security.oauth2.client.OAuth2AuthorizedClient>loadAuthorizedClient(
                        SecurityConfig.REGISTRATION, authentication, exchange)
                .flatMap(client -> revokeRefreshToken(client.getRefreshToken() == null ? null
                        : client.getRefreshToken().getTokenValue()))
                .onErrorResume(e -> {
                    log.warn("Could not revoke the refresh token at logout: {}", e.toString());
                    return Mono.empty();
                })
                .then(Mono.defer(() -> exchange.getSession().flatMap(WebSession::invalidate)))
                .thenReturn(Map.of("logoutUrl", logoutUrl));
    }

    private Mono<Void> revokeRefreshToken(String refreshToken) {
        if (refreshToken == null) {
            return Mono.empty();
        }
        MultiValueMap<String, String> form = new LinkedMultiValueMap<>();
        form.add("token", refreshToken);
        form.add("token_type_hint", "refresh_token");
        return webClient.post()
                .uri(props.authServer().internalUrl() + "/oauth2/revoke")
                .headers(h -> h.setBasicAuth("cmt-web-bff", clientSecret))
                .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                .body(BodyInserters.fromFormData(form))
                .retrieve()
                .toBodilessEntity()
                .then();
    }

}
