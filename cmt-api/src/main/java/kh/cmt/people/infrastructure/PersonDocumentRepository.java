package kh.cmt.people.infrastructure;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import kh.cmt.people.domain.PersonDocument;
import org.springframework.data.jpa.repository.JpaRepository;

/** Documents of people; limited to the caller's record scope like the people themselves. */
public interface PersonDocumentRepository extends JpaRepository<PersonDocument, UUID> {

    List<PersonDocument> findByPersonIdOrderByTypeAscCreatedAtDesc(UUID personId);

    Optional<PersonDocument> findByFileId(UUID fileId);
}
