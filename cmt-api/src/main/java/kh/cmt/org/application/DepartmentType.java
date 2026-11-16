package kh.cmt.org.application;

import java.util.Map;
import java.util.UUID;
import kh.cmt.org.api.OrgKind;
import kh.cmt.org.domain.Department;
import kh.cmt.org.infrastructure.DepartmentRepository;
import org.springframework.stereotype.Component;

@Component
class DepartmentType extends LocalizedItemType<Department> {

    DepartmentType(DepartmentRepository repository, OrgSupport support) {
        super(OrgKind.DEPARTMENT, repository, support, true);
    }

    @Override
    protected Department newInstance() {
        return new Department();
    }

    @Override
    protected Map<UUID, Long> peopleCounts() {
        return support.peopleCounts().perDepartment();
    }
}
