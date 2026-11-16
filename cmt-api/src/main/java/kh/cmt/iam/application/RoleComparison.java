package kh.cmt.iam.application;

import java.util.List;
import java.util.UUID;
import kh.cmt.shared.i18n.LocalizedName;

/** Two roles side by side: for each module what each can do, and where they differ. */
public record RoleComparison(RoleRef a, RoleRef b, List<ModuleDiff> modules) {

    public record RoleRef(UUID id, String code, LocalizedName name, RoleView.ScopeView scope) {
    }

    /** {@code onlyA}: allowed for A but not for B; {@code onlyB} the other way round. */
    public record ModuleDiff(String module, List<String> a, List<String> b, List<String> onlyA, List<String> onlyB,
            boolean same) {
    }
}
