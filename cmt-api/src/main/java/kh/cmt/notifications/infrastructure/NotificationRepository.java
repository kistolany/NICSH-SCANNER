package kh.cmt.notifications.infrastructure;

import java.time.Instant;
import java.util.UUID;
import kh.cmt.notifications.domain.Notification;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;

/** Notifications of people. */
public interface NotificationRepository extends JpaRepository<Notification, UUID> {

    Page<Notification> findByPersonIdOrderByCreatedAtDescIdDesc(UUID personId, Pageable page);

    long countByPersonIdAndReadAtIsNull(UUID personId);

    @Modifying
    @Query("update Notification n set n.readAt = :at where n.personId = :person and n.readAt is null")
    int markAllRead(UUID person, Instant at);

    @Modifying
    @Query("delete from Notification n where n.id = :id and n.personId = :person")
    int deleteMine(UUID person, UUID id);

    @Modifying
    @Query("delete from Notification n where n.personId = :person")
    int deleteAllMine(UUID person);
}
