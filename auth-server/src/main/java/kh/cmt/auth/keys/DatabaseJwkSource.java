package kh.cmt.auth.keys;

import com.nimbusds.jose.KeySourceException;
import com.nimbusds.jose.jwk.JWK;
import com.nimbusds.jose.jwk.JWKSelector;
import com.nimbusds.jose.jwk.source.JWKSource;
import com.nimbusds.jose.proc.SecurityContext;
import java.util.List;

/** Feeds Spring Authorization Server (signing + the /oauth2/jwks endpoint) from {@link SigningKeyService}. */
public class DatabaseJwkSource implements JWKSource<SecurityContext> {

    private final SigningKeyService keys;

    public DatabaseJwkSource(SigningKeyService keys) {
        this.keys = keys;
    }

    @Override
    public List<JWK> get(JWKSelector selector, SecurityContext context) throws KeySourceException {
        return selector.select(new com.nimbusds.jose.jwk.JWKSet(keys.keys()));
    }
}
