package kh.cmtkernel;

import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

interface KernelEntityRepository extends JpaRepository<KernelEntity, UUID> {
}
