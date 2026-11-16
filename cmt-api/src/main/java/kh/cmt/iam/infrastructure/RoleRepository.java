package kh.cmt.iam.infrastructure;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import kh.cmt.iam.domain.Role;
import org.springframework.data.jpa.repository.JpaRepository;

public interface RoleRepository extends JpaRepository<Role, UUID> {

    /** Active roles of a school (the "active only" filter is on). */
    List<Role> findBySchoolId(UUID schoolId);

    boolean existsBySchoolIdAndSystemTrue(UUID schoolId);

    boolean existsBySchoolIdAndNameEnIgnoreCase(UUID schoolId, String nameEn);

    Optional<Role> findBySchoolIdAndCode(UUID schoolId, String code);

    /** The active default role for new web users. */
    Optional<Role> findFirstBySchoolIdAndDefaultRoleTrue(UUID schoolId);
}
