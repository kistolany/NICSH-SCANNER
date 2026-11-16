package kh.cmt.iam.infrastructure;

import java.util.Collection;
import java.util.List;
import java.util.UUID;
import kh.cmt.iam.domain.WebUser;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Query;

/** Web users. The "active only" filter is on unless the Inactive tab switches it off. */
public interface WebUserRepository extends JpaRepository<WebUser, UUID>, JpaSpecificationExecutor<WebUser> {

    /** Active web users of a role. */
    List<WebUser> findBySchoolIdAndRoleCodeOrderByName(UUID schoolId, String roleCode);

    long countBySchoolIdAndRoleCode(UUID schoolId, String roleCode);

    /** Is the person already linked to another active web user? */
    boolean existsBySchoolIdAndLinkedPersonIdAndIdNot(UUID schoolId, UUID linkedPersonId, UUID exceptId);

    @Query("select u.roleCode, count(u) from WebUser u where u.schoolId = :school group by u.roleCode")
    List<Object[]> countByRole(UUID school);

    /** Names of the web users with these accounts, deactivated ones too (who changed what, in histories). */
    @Query(value = "select account_id, name from web_user where account_id in (:accountIds)", nativeQuery = true)
    List<Object[]> namesByAccount(Collection<UUID> accountIds);
}
