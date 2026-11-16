package kh.cmt.iam.domain;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import kh.cmt.shared.security.PermissionAction;
import kh.cmt.shared.security.PermissionModule;

/**
 * What a role can do: for each module the set of actions. A module that is not in the matrix grants nothing.
 * Pure rules, no Spring:
 * <ul>
 *   <li>an action that does not apply to a module is refused ({@code NotApplicable})</li>
 *   <li>any action other than view needs view in the same module ({@code NeedsView}); removing view therefore
 *       means removing the whole row</li>
 * </ul>
 */
public final class PermissionMatrix {

    /** One problem found while reading a matrix: where, and which rule. */
    public record Problem(String field, String rule) {
    }

    private final Map<PermissionModule, Set<PermissionAction>> grants = new EnumMap<>(PermissionModule.class);

    public PermissionMatrix() {
    }

    public PermissionMatrix(PermissionMatrix copy) {
        copy.grants.forEach((m, a) -> grants.put(m, EnumSet.copyOf(a)));
    }

    /** What the apps send: module key -> action keys. Unknown names and rule breaks are collected in {@code problems}. */
    public static PermissionMatrix parse(Map<String, List<String>> raw, List<Problem> problems) {
        PermissionMatrix matrix = new PermissionMatrix();
        raw.forEach((moduleKey, actionKeys) -> {
            var module = PermissionModule.fromKey(moduleKey);
            if (module.isEmpty()) {
                problems.add(new Problem("permissions." + moduleKey, "UnknownModule"));
                return;
            }
            Set<PermissionAction> actions = EnumSet.noneOf(PermissionAction.class);
            for (String actionKey : actionKeys == null ? List.<String>of() : actionKeys) {
                var action = PermissionAction.fromKey(actionKey);
                if (action.isEmpty()) {
                    problems.add(new Problem("permissions." + moduleKey, "UnknownAction"));
                } else if (!module.get().applies(action.get())) {
                    problems.add(new Problem("permissions." + moduleKey + "." + actionKey, "NotApplicable"));
                } else {
                    actions.add(action.get());
                }
            }
            if (!actions.isEmpty() && !actions.contains(PermissionAction.VIEW)) {
                problems.add(new Problem("permissions." + moduleKey + ".view", "NeedsView"));
            }
            if (!actions.isEmpty()) {
                matrix.grants.put(module.get(), actions);
            }
        });
        return matrix;
    }

    /** Every module and action that applies. */
    public static PermissionMatrix everything() {
        PermissionMatrix matrix = new PermissionMatrix();
        for (PermissionModule module : PermissionModule.values()) {
            matrix.grants.put(module, EnumSet.copyOf(module.applicableActions()));
        }
        return matrix;
    }

    /** All applicable actions in the given modules. */
    public static PermissionMatrix fullAccessTo(PermissionModule... modules) {
        PermissionMatrix matrix = new PermissionMatrix();
        for (PermissionModule module : modules) {
            matrix.grants.put(module, EnumSet.copyOf(module.applicableActions()));
        }
        return matrix;
    }

    /** Only the given actions, in the given modules. */
    public static PermissionMatrix only(Set<PermissionAction> actions, PermissionModule... modules) {
        PermissionMatrix matrix = new PermissionMatrix();
        for (PermissionModule module : modules) {
            Set<PermissionAction> allowed = EnumSet.noneOf(PermissionAction.class);
            actions.stream().filter(module::applies).forEach(allowed::add);
            if (!allowed.isEmpty()) {
                matrix.grants.put(module, allowed);
            }
        }
        return matrix;
    }

    public void grant(PermissionModule module, PermissionAction action) {
        grants.computeIfAbsent(module, m -> EnumSet.noneOf(PermissionAction.class)).add(action);
    }

    public boolean allows(PermissionModule module, PermissionAction action) {
        return grants.getOrDefault(module, Set.of()).contains(action);
    }

    public Set<PermissionAction> actionsIn(PermissionModule module) {
        return Set.copyOf(grants.getOrDefault(module, Set.of()));
    }

    /** As the apps see it: module key -> action keys, modules in table order, actions in the usual order. */
    public Map<String, List<String>> toApi() {
        Map<String, List<String>> out = new LinkedHashMap<>();
        for (PermissionModule module : PermissionModule.values()) {
            Set<PermissionAction> actions = grants.get(module);
            if (actions != null && !actions.isEmpty()) {
                List<String> keys = new ArrayList<>();
                for (PermissionAction action : PermissionAction.values()) {
                    if (actions.contains(action)) {
                        keys.add(action.key());
                    }
                }
                out.put(module.key(), keys);
            }
        }
        return out;
    }

    /** Every granted (module, action) pair; what is stored. */
    public List<Map.Entry<PermissionModule, PermissionAction>> pairs() {
        List<Map.Entry<PermissionModule, PermissionAction>> out = new ArrayList<>();
        grants.forEach((m, actions) -> actions.forEach(a -> out.add(Map.entry(m, a))));
        return out;
    }

    @Override
    public boolean equals(Object o) {
        return o instanceof PermissionMatrix other && grants.equals(other.grants);
    }

    @Override
    public int hashCode() {
        return grants.hashCode();
    }
}
