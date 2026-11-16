package kh.cmt.bff;

import org.springframework.security.web.server.csrf.CsrfToken;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.WebFilter;
import org.springframework.web.server.WebFilterChain;
import reactor.core.publisher.Mono;

/**
 * Spring only creates the CSRF token when something asks for it. The single-page app needs the
 * {@code XSRF-TOKEN} cookie on its very first response, so this filter asks for it on every request.
 */
class CsrfCookieWebFilter implements WebFilter {

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, WebFilterChain chain) {
        Mono<CsrfToken> token = exchange.getAttribute(CsrfToken.class.getName());
        return token == null ? chain.filter(exchange) : token.then(chain.filter(exchange));
    }
}
