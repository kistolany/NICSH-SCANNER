package kh.cmt.iam.api;

import java.util.Collection;
import java.util.Map;
import java.util.UUID;

/**
 * Who did something, by name: the name of the office user (web user, deactivated ones too) behind each login account
 * id ({@code created_by}, {@code changed_by}…). Accounts without a web user are left out.
 */
public interface AccountNames {

    Map<UUID, String> names(Collection<UUID> accountIds);
}
