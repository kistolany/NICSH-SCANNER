package kh.cmt.notifications.infrastructure;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import kh.cmt.notifications.domain.PushToken;
import org.springframework.data.jpa.repository.JpaRepository;

/** Phones to push to. */
public interface PushTokenRepository extends JpaRepository<PushToken, UUID> {

    Optional<PushToken> findByToken(String token);

    List<PushToken> findByPersonIdIn(Collection<UUID> personIds);

    void deleteByToken(String token);
}
