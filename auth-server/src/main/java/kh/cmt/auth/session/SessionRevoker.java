package kh.cmt.auth.session;

import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.oauth2.server.authorization.OAuth2Authorization;
import org.springframework.security.oauth2.server.authorization.OAuth2AuthorizationService;
import org.springframework.stereotype.Service;

/**
 * Ends every login session (refresh-token family) of an account: used by password reset now, and by
 * "force logout everywhere" and device reset in later steps. Access tokens already issued stay valid until
 * they expire (15 minutes); the API additionally compares {@code perm_ver}.
 */
@Service
public class SessionRevoker {

    private final JdbcClient jdbc;
    private final OAuth2AuthorizationService authorizations;

    public SessionRevoker(JdbcClient jdbc, OAuth2AuthorizationService authorizations) {
        this.jdbc = jdbc;
        this.authorizations = authorizations;
    }

    public int revokeAll(UUID accountId) {
        List<UUID> families = jdbc.sql("""
                select distinct family_id from refresh_token where account_id = :account and revoked_at is null
                """).param("account", accountId).query(UUID.class).list();
        for (UUID family : families) {
            OAuth2Authorization session = authorizations.findById(family.toString());
            if (session != null) {
                authorizations.remove(session);   // also marks the family revoked
            }
        }
        jdbc.sql("update refresh_token set revoked_at = now() where account_id = :account and revoked_at is null")
                .param("account", accountId).update();
        return families.size();
    }
}
