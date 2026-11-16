package kh.cmt.bff;

import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.JWTParser;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.UnknownHostException;
import java.nio.charset.StandardCharsets;
import java.text.ParseException;
import java.time.Clock;
import java.time.DateTimeException;
import java.time.Duration;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.List;
import java.util.Optional;
import java.util.regex.Pattern;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.oauth2.client.OAuth2AuthorizedClient;
import org.springframework.security.oauth2.client.authentication.OAuth2AuthenticationToken;
import org.springframework.security.oauth2.client.web.server.ServerOAuth2AuthorizedClientRepository;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.WebFilter;
import org.springframework.web.server.WebFilterChain;
import org.springframework.web.server.WebSession;
import reactor.core.publisher.Mono;

/**
 * The role's login rules on every request of a signed-in user (Roles page, "Security"): allowed days, hours and
 * addresses, and the session's idle timeout. The auth server puts the rules into the access token
 * ({@code sess_min}, {@code login_from}, {@code login_to}, {@code login_days}, {@code login_ips}, {@code login_tz});
 * they come with every refresh, so a change of the rules applies here within one access token lifetime (15 min).
 * <p>
 * Broken rule: the session ends; {@code /api} and {@code /bff} get 401 with {@code code: LOGIN_NOT_ALLOWED} and the
 * {@code reason} (DAY_NOT_ALLOWED, OUTSIDE_HOURS, IP_NOT_ALLOWED), pages go to the auth server's "not allowed" page.
 * <p>
 * The BFF reads the token it got straight from the auth server's token endpoint; it does not verify the signature
 * again (cmt-api does, on every call). A token that is not a JWT carries no rules.
 */
class LoginRulesWebFilter implements WebFilter {

    private final ServerOAuth2AuthorizedClientRepository authorizedClients;
    private final Clock clock;
    private final String authServerPublicUrl;

    LoginRulesWebFilter(ServerOAuth2AuthorizedClientRepository authorizedClients, Clock clock, String authServerPublicUrl) {
        this.authorizedClients = authorizedClients;
        this.clock = clock;
        this.authServerPublicUrl = authServerPublicUrl;
    }

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, WebFilterChain chain) {
        return exchange.getPrincipal()
                .filter(OAuth2AuthenticationToken.class::isInstance)
                .cast(OAuth2AuthenticationToken.class)
                .flatMap(user -> authorizedClients.<OAuth2AuthorizedClient>loadAuthorizedClient(
                        user.getAuthorizedClientRegistrationId(), user, exchange))
                .flatMap(client -> Mono.justOrEmpty(Rules.of(client.getAccessToken().getTokenValue())))
                .map(Optional::of)
                .defaultIfEmpty(Optional.empty())
                .flatMap(rules -> {
                    if (rules.isEmpty()) {
                        return chain.filter(exchange);
                    }
                    Optional<String> refusal = rules.get().refusal(clock, clientIp(exchange));
                    if (refusal.isPresent()) {
                        return refuse(exchange, refusal.get());
                    }
                    return keepIdleTimeout(exchange, rules.get()).then(chain.filter(exchange));
                });
    }

    /** The session lives as long as the role's idle timeout (the default from the config until the first request). */
    private static Mono<Void> keepIdleTimeout(ServerWebExchange exchange, Rules rules) {
        if (rules.sessionMinutes() == null) {
            return Mono.empty();
        }
        Duration idle = Duration.ofMinutes(rules.sessionMinutes());
        return exchange.getSession().doOnNext(session -> {
            if (!idle.equals(session.getMaxIdleTime())) {
                session.setMaxIdleTime(idle);
            }
        }).then();
    }

    private Mono<Void> refuse(ServerWebExchange exchange, String reason) {
        return exchange.getSession().flatMap(WebSession::invalidate).then(Mono.defer(() -> {
            var response = exchange.getResponse();
            String path = exchange.getRequest().getPath().value();
            if (path.startsWith("/api/") || path.startsWith("/bff/")) {
                response.setStatusCode(HttpStatus.UNAUTHORIZED);
                response.getHeaders().setContentType(MediaType.APPLICATION_PROBLEM_JSON);
                byte[] body = ("{\"type\":\"about:blank\",\"title\":\"Unauthorized\",\"status\":401,"
                        + "\"code\":\"LOGIN_NOT_ALLOWED\",\"reason\":\"" + reason + "\"}").getBytes(StandardCharsets.UTF_8);
                return response.writeWith(Mono.just(response.bufferFactory().wrap(body)));
            }
            response.setStatusCode(HttpStatus.FOUND);
            response.getHeaders().setLocation(URI.create(authServerPublicUrl + "/login/denied?reason=" + reason));
            return response.setComplete();
        }));
    }

    private static String clientIp(ServerWebExchange exchange) {
        InetSocketAddress remote = exchange.getRequest().getRemoteAddress();
        if (remote == null || remote.getAddress() == null) {
            return null;
        }
        String ip = remote.getAddress().getHostAddress();
        int scope = ip.indexOf('%');   // IPv6 zone, e.g. fe80::1%eth0
        return scope < 0 ? ip : ip.substring(0, scope);
    }

    /** The rules from the access token claims; null fields mean no restriction. */
    record Rules(Integer sessionMinutes, LocalTime from, LocalTime to, List<Integer> days, List<String> ips, ZoneId zone) {

        private static final Pattern LITERAL = Pattern.compile("[0-9a-fA-F:.]+");

        static Optional<Rules> of(String accessToken) {
            try {
                JWTClaimsSet claims = JWTParser.parse(accessToken).getJWTClaimsSet();
                List<Object> days = claims.getListClaim("login_days");
                String tz = claims.getStringClaim("login_tz");
                return Optional.of(new Rules(
                        claims.getIntegerClaim("sess_min"),
                        time(claims.getStringClaim("login_from")),
                        time(claims.getStringClaim("login_to")),
                        days == null ? null : days.stream().map(d -> ((Number) d).intValue()).toList(),
                        claims.getStringListClaim("login_ips"),
                        tz == null ? null : ZoneId.of(tz)));
            } catch (ParseException | DateTimeException | ClassCastException notOurToken) {
                return Optional.empty();
            }
        }

        /** The first rule broken now, from {@code ip}; the same rules as the auth server's LoginRuleService. */
        Optional<String> refusal(Clock clock, String ip) {
            ZonedDateTime now = ZonedDateTime.now(zone == null ? clock : clock.withZone(zone));
            if (days != null && !days.isEmpty() && !days.contains(now.getDayOfWeek().getValue())) {
                return Optional.of("DAY_NOT_ALLOWED");
            }
            if (from != null && to != null && !withinHours(now.toLocalTime())) {
                return Optional.of("OUTSIDE_HOURS");
            }
            if (ips != null && !ips.isEmpty() && (ip == null || ips.stream().noneMatch(range -> matches(range, ip)))) {
                return Optional.of("IP_NOT_ALLOWED");
            }
            return Optional.empty();
        }

        private boolean withinHours(LocalTime time) {
            return from.isBefore(to)
                    ? !time.isBefore(from) && time.isBefore(to)
                    : !time.isBefore(from) || time.isBefore(to);   // past midnight, e.g. 22:00 to 06:00
        }

        /** {@code ip} is {@code range} (an address) or inside it (CIDR). Never a DNS lookup: only literal addresses. */
        static boolean matches(String range, String ip) {
            String[] parts = range.split("/", 2);
            if (!LITERAL.matcher(parts[0]).matches() || !LITERAL.matcher(ip).matches()) {
                return false;
            }
            try {
                byte[] network = InetAddress.getByName(parts[0]).getAddress();
                byte[] address = InetAddress.getByName(ip).getAddress();
                int bits = parts.length == 2 ? Integer.parseInt(parts[1]) : network.length * 8;
                if (network.length != address.length || bits < 0 || bits > network.length * 8) {
                    return false;
                }
                for (int i = 0; i < bits; i++) {
                    int mask = 0x80 >> (i % 8);
                    if ((network[i / 8] & mask) != (address[i / 8] & mask)) {
                        return false;
                    }
                }
                return true;
            } catch (UnknownHostException | NumberFormatException notAnAddress) {
                return false;
            }
        }

        private static LocalTime time(String hhmm) {
            return hhmm == null ? null : LocalTime.parse(hhmm);
        }
    }
}
