package kh.cmt.devices.application;

import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import kh.cmt.approvals.api.ApprovalSource;
import kh.cmt.approvals.api.Approvals;
import kh.cmt.devices.infrastructure.AuthDeviceStore;
import kh.cmt.people.api.PeopleQueries;
import kh.cmt.shared.security.SchoolContext;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * Device change requests (the auth server stores them) in the one approvals inbox (step 10.1): listed with the other
 * requests, decided here the way the Devices page does it ({@link DeviceService#approve}: the new phone is linked and
 * the old one signed out; reject with the admin's reason). Only active people the caller may see.
 */
@Component
@RequiredArgsConstructor
class DeviceChangeApprovals implements ApprovalSource {

    private static final Set<String> OPEN = Set.of("pending", "seen");

    private final AuthDeviceStore store;
    private final DeviceService devices;
    private final PeopleQueries people;
    private final SchoolContext schoolContext;

    @Override
    public Approvals.Type type() {
        return Approvals.Type.DEVICE_CHANGE;
    }

    @Override
    public List<Request> list(boolean open, int limit) {
        Set<UUID> visible = people.visibleActive().stream().map(PeopleQueries.PersonCard::id).collect(Collectors.toSet());
        List<AuthDeviceStore.ChangeRequest> rows = store.requests(schoolContext.currentSchoolId(), open, open ? limit : limit * 2).stream()
                .filter(r -> visible.contains(r.personId()))
                .filter(r -> open || r.status().equals("approved") || r.status().equals("rejected"))
                .toList();
        return rows.stream().map(DeviceChangeApprovals::request)
                .sorted(open ? Comparator.comparing(Request::requestedAt) : Comparator.comparing(Request::decidedAt, Comparator.reverseOrder()))
                .limit(limit).toList();
    }

    @Override
    public Optional<Request> find(UUID id) {
        return store.request(schoolContext.currentSchoolId(), id).filter(r -> people.visible(r.personId()).isPresent())
                .map(DeviceChangeApprovals::request);
    }

    @Override
    public void approve(UUID id, String comment) {
        devices.approve(id);
    }

    @Override
    public void reject(UUID id, String comment) {
        devices.reject(id, new DeviceViews.Reason(comment));
    }

    private static Request request(AuthDeviceStore.ChangeRequest r) {
        Map<String, Object> details = new LinkedHashMap<>();
        details.put("reasonCode", r.reason());
        if (r.oldDeviceName() != null) {
            details.put("oldDevice", r.oldDeviceName());
        }
        details.put("newDevice", r.newDeviceName());
        details.put("platform", r.newPlatform());
        if (r.seenAt() != null) {
            details.put("seenAt", r.seenAt().toString());
        }
        Approvals.Status status = OPEN.contains(r.status()) ? Approvals.Status.PENDING
                : r.status().equals("approved") ? Approvals.Status.APPROVED
                : r.status().equals("rejected") ? Approvals.Status.REJECTED : Approvals.Status.CANCELLED;
        return new Request(r.id(), r.personId(), status, DeviceService.reasonLabel(r.reason()), details, r.createdAt(), r.decidedAt(),
                r.decidedBy(), r.rejectionReason());
    }
}
