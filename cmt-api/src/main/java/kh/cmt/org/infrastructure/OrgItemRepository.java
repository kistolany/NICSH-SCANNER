package kh.cmt.org.infrastructure;

import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.repository.NoRepositoryBean;

@NoRepositoryBean
public interface OrgItemRepository<E> extends JpaRepository<E, UUID>, JpaSpecificationExecutor<E> {
}
