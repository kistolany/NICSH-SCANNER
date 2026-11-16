package kh.cmt.iam.application;

import java.util.Collection;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import kh.cmt.iam.api.AccountNames;
import kh.cmt.iam.infrastructure.WebUserRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Names of web users by their login account, for "created by" / "changed by" columns of other modules. */
@Service
class AccountNameService implements AccountNames {

    private final WebUserRepository webUsers;

    AccountNameService(WebUserRepository webUsers) {
        this.webUsers = webUsers;
    }

    @Override
    @Transactional(readOnly = true)
    public Map<UUID, String> names(Collection<UUID> accountIds) {
        Map<UUID, String> names = new HashMap<>();
        var ids = accountIds.stream().filter(Objects::nonNull).distinct().toList();
        if (!ids.isEmpty()) {
            webUsers.namesByAccount(ids).forEach(row -> names.put((UUID) row[0], (String) row[1]));
        }
        return names;
    }
}
