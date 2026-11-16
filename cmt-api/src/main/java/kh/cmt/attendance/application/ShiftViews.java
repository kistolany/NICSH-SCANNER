package kh.cmt.attendance.application;

import java.time.LocalDate;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.stream.Stream;
import kh.cmt.approvals.api.Approvals;
import kh.cmt.attendance.domain.AttendanceRecord;
import kh.cmt.iam.api.AccountNames;
import kh.cmt.qrlocation.api.Locations;
import lombok.AccessLevel;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * Turns days of the history ({@link DayHistory}) into shifts as the app and the admin pages show them, with what they
 * need looked up once for all the days: gate names, late / early reason and correction requests, who edited a record.
 */
@Component
@RequiredArgsConstructor(access = AccessLevel.PACKAGE)
class ShiftViews {

    /** A day of one person. */
    record PersonDay(UUID person, DayHistory.Day day) {
    }

    private final Approvals approvals;
    private final Locations locations;
    private final AccountNames accountNames;
    private final HistoryMapper mapper;

    Batch batch(Collection<PersonDay> days) {
        return new Batch(days);
    }

    final class Batch {

        private final Map<UUID, HistoryViews.Gate> gates = new HashMap<>();
        private final Map<UUID, Approvals.View> lateRequests;
        private final Map<UUID, Approvals.View> earlyRequests;
        private final Map<UUID, Approvals.View> corrections;
        private final Map<UUID, String> editors;

        private Batch(Collection<PersonDay> days) {
            List<AttendanceRecord> recorded = days.stream().flatMap(pd -> pd.day().lines().stream()).map(DayHistory.Line::record)
                    .filter(Objects::nonNull).toList();
            this.lateRequests = approvals.latest(Approvals.Type.LATE_REASON,
                    recorded.stream().filter(r -> r.getLateReason() != null).map(AttendanceRecord::getId).toList());
            this.earlyRequests = approvals.latest(Approvals.Type.EARLY_REASON,
                    recorded.stream().filter(r -> r.getEarlyReason() != null).map(AttendanceRecord::getId).toList());
            this.corrections = approvals.latest(Approvals.Type.CORRECTION, days.stream()
                    .flatMap(pd -> pd.day().lines().stream().flatMap(l -> Stream.of(CorrectionService.Field.values())
                            .map(f -> CorrectionService.subject(pd.person(), pd.day().date(), l.shiftIdx(), f))))
                    .toList());
            List<UUID> editedBy = recorded.stream().map(AttendanceRecord::getEditedBy).filter(Objects::nonNull).distinct().toList();
            this.editors = editedBy.isEmpty() ? Map.of() : accountNames.names(editedBy);
        }

        List<HistoryViews.Shift> shifts(PersonDay pd) {
            return pd.day().lines().stream().map(l -> shift(pd.person(), pd.day().date(), l)).toList();
        }

        private HistoryViews.Shift shift(UUID person, LocalDate date, DayHistory.Line l) {
            List<HistoryViews.Correction> asked = Stream.of(CorrectionService.Field.values())
                    .map(f -> corrections.get(CorrectionService.subject(person, date, l.shiftIdx(), f)))
                    .filter(Objects::nonNull).map(mapper::correction).toList();
            List<HistoryViews.Correction> shown = asked.isEmpty() ? null : asked;
            AttendanceRecord r = l.record();
            if (r == null) {
                return mapper.shift(l, null, null, null, null, null, shown);
            }
            HistoryViews.Edited edited = r.getEditedBy() == null ? null
                    : new HistoryViews.Edited(r.getEditedAt(), r.getEditReason(), r.getEditedBy(), editors.get(r.getEditedBy()));
            return mapper.shift(l, gate(r.getInGateId()), gate(r.getOutGateId()), lateReason(r), earlyReason(r), edited, shown);
        }

        private HistoryViews.Gate gate(UUID id) {
            return id == null ? null
                    : gates.computeIfAbsent(id, g -> locations.gate(g).map(x -> new HistoryViews.Gate(x.id(), x.name())).orElse(null));
        }

        private HistoryViews.LateReason lateReason(AttendanceRecord r) {
            if (r.getLateReason() == null) {
                return null;
            }
            Approvals.View request = lateRequests.get(r.getId());
            return request == null ? new HistoryViews.LateReason(r.getLateReason(), null, null, null)
                    : new HistoryViews.LateReason(r.getLateReason(), request.status().name(), request.decidedAt(), request.comment());
        }

        private HistoryViews.EarlyReason earlyReason(AttendanceRecord r) {
            HistoryViews.EarlyReason stored = ShiftViews.earlyReason(r.getEarlyReason());
            Approvals.View request = stored == null ? null : earlyRequests.get(r.getId());
            return request == null ? stored
                    : new HistoryViews.EarlyReason(stored.code(), stored.note(), request.status().name(), request.decidedAt(), request.comment());
        }
    }

    /** Where the day was checked in: the first shift's gate that has one. */
    static HistoryViews.Gate gateOf(List<HistoryViews.Shift> shifts) {
        return shifts.stream().map(HistoryViews.Shift::inGate).filter(Objects::nonNull).findFirst().orElse(null);
    }

    /** "personal: Family" (as the scan stores it) → code "personal", note "Family". */
    static HistoryViews.EarlyReason earlyReason(String stored) {
        if (stored == null || stored.isBlank()) {
            return null;
        }
        int colon = stored.indexOf(": ");
        return colon < 0 ? new HistoryViews.EarlyReason(stored, null)
                : new HistoryViews.EarlyReason(stored.substring(0, colon), stored.substring(colon + 2));
    }
}
