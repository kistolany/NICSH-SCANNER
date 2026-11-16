package kh.cmt.files.infrastructure;

import java.util.UUID;
import kh.cmt.files.domain.StoredFile;
import org.springframework.data.jpa.repository.JpaRepository;

public interface StoredFileRepository extends JpaRepository<StoredFile, UUID> {
}
