package kh.cmt.leave.api;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;
import kh.cmt.shared.i18n.LocalizedName;

/** The signed-in person's leave balances this calendar year, for the app's Home (step 7.9) and Leave page (9.1). */
public interface MyLeaveBalances {

    /**
     * One active leave type: {@code type} its code (annual, sick…, the app labels by it), its name and colour, whether it
     * may be taken by half days; days in all (entitled + adjusted), used (approved), left (total − used) and pending
     * (requests still waiting).
     */
    record Balance(UUID typeId, String type, LocalizedName name, String color, boolean halfDays, BigDecimal total, BigDecimal used,
            BigDecimal left, BigDecimal pending) {
    }

    List<Balance> mine();
}
