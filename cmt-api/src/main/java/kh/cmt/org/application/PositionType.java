package kh.cmt.org.application;

import java.util.Map;
import java.util.UUID;
import kh.cmt.org.api.OrgKind;
import kh.cmt.org.domain.Position;
import kh.cmt.org.infrastructure.PositionRepository;
import org.springframework.stereotype.Component;

@Component
class PositionType extends LocalizedItemType<Position> {

    PositionType(PositionRepository repository, OrgSupport support) {
        super(OrgKind.POSITION, repository, support, false);
    }

    @Override
    protected Position newInstance() {
        return new Position();
    }

    @Override
    protected Map<UUID, Long> peopleCounts() {
        return support.peopleCounts().perPosition();
    }
}
