package kh.cmt.org.infrastructure;

import java.util.Optional;
import java.util.UUID;
import kh.cmt.org.domain.SchoolSettings;
import org.springframework.data.jpa.repository.JpaRepository;

public interface SchoolSettingsRepository extends JpaRepository<SchoolSettings, UUID> {

    Optional<SchoolSettings> findBySchoolId(UUID schoolId);
}
