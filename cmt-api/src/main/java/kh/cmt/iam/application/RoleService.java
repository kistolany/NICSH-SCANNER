package kh.cmt.iam.application;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;
import kh.cmt.iam.application.RoleComparison.ModuleDiff;
import kh.cmt.iam.application.RoleComparison.RoleRef;
import kh.cmt.iam.domain.ApprovalLimits;
import kh.cmt.iam.domain.LoginRules;
import kh.cmt.iam.domain.PermissionMatrix;
import kh.cmt.iam.domain.PermissionMatrix.Problem;
import kh.cmt.iam.domain.Role;
import kh.cmt.iam.domain.SystemRole;
import kh.cmt.iam.infrastructure.AuthLoginRuleSync;
import kh.cmt.iam.infrastructure.RoleAccessStore;
import kh.cmt.iam.infrastructure.RoleHistoryStore;
import kh.cmt.iam.infrastructure.RolePermissionStore;
import kh.cmt.iam.infrastructure.RoleRepository;
import kh.cmt.iam.infrastructure.WebUserRepository;
import kh.cmt.org.api.OrgKind;
import kh.cmt.org.api.OrgQueries;
import kh.cmt.shared.i18n.LocalizedName;
import kh.cmt.shared.i18n.LocalizedText;
import kh.cmt.shared.people.PeopleCounts;
import kh.cmt.shared.security.CurrentUser;
import kh.cmt.shared.security.PermissionAction;
import kh.cmt.shared.security.PermissionModule;
import kh.cmt.shared.security.RequirePermission;
import kh.cmt.shared.security.SchoolContext;
import kh.cmt.shared.security.ScopeType;
import kh.cmt.shared.security.SensitiveField;
import kh.cmt.shared.web.ApiException;
import kh.cmt.shared.web.ErrorCode;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.security.web.util.matcher.IpAddressMatcher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Roles and their permission table (Web users and roles page, Roles and permissions tab).
 * <p>
 * Rules: system roles are locked; a custom role can be created, renamed, given permissions and deleted; nobody
 * can change or delete the role they hold themselves; permissions follow the matrix rules (not-applicable cells
 * refused, any action needs view). Every change of permissions or of the record scope raises {@code permVersion}.
 * Every saved change is a new version in the role's change history, and an older version can be restored.
 * <p>
 * Every public call needs a permission in the Users module (see {@link RequirePermission}): view to read, create / edit /
 * delete to change.
 */
@Service
@Transactional
public class RoleService {

    private static final List<String> SYSTEM_ORDER = List.of("super_admin", "hr", "qr_manager", "viewer");

    private final RoleRepository roles;
    private final RolePermissionStore permissions;
    private final RoleAccessStore access;
    private final OrgQueries org;
    private final AuthLoginRuleSync loginSync;
    private final RoleSeeder seeder;
    private final RoleHistory history;
    private final WebUserRepository webUsers;
    private final PeopleCounts peopleCounts;
    private final SchoolContext schoolContext;
    private final java.time.Clock clock;
    private final org.springframework.context.ApplicationEventPublisher events;

    RoleService(RoleRepository roles, RolePermissionStore permissions, RoleAccessStore access, OrgQueries org,
            AuthLoginRuleSync loginSync, RoleSeeder seeder, RoleHistory history, WebUserRepository webUsers,
            PeopleCounts peopleCounts, SchoolContext schoolContext, java.time.Clock clock,
            org.springframework.context.ApplicationEventPublisher events) {
        this.peopleCounts = peopleCounts;
        this.loginSync = loginSync;
        this.access = access;
        this.org = org;
        this.events = events;
        this.roles = roles;
        this.permissions = permissions;
        this.seeder = seeder;
        this.history = history;
        this.webUsers = webUsers;
        this.schoolContext = schoolContext;
        this.clock = clock;
    }

    // ------------------------------------------------------------------ reading

    /** Active roles: system roles first (in their usual order), then custom roles by name. */
    @RequirePermission(module = PermissionModule.USERS, action = PermissionAction.VIEW)
    public List<RoleView> list() {
        UUID school = school();
        Map<UUID, PermissionMatrix> matrices = permissions.loadForSchool(school);
        Map<UUID, Set<UUID>> departments = access.departmentsForSchool(school);
        Map<UUID, Set<SensitiveField>> sensitive = access.sensitiveForSchool(school);
        Map<UUID, ApprovalLimits> limits = access.approvalLimitsForSchool(school);
        Map<UUID, LoginRules> rules = access.loginRulesForSchool(school);
        Map<String, Long> users = new HashMap<>();
        webUsers.countByRole(school).forEach(row -> users.put((String) row[0], (Long) row[1]));
        return roles.findBySchoolId(school).stream()
                .sorted(Comparator.comparing((Role r) -> !r.isSystem())
                        .thenComparing(r -> SYSTEM_ORDER.indexOf(r.getCode()) < 0 ? 99 : SYSTEM_ORDER.indexOf(r.getCode()))
                        .thenComparing(r -> r.getName().getEn().toLowerCase()))
                .map(r -> view(r, matrices.getOrDefault(r.getId(), new PermissionMatrix()), departments.getOrDefault(r.getId(), Set.of()),
                        sensitive.getOrDefault(r.getId(), Set.of()), limits.get(r.getId()), rules.get(r.getId()),
                        users.getOrDefault(r.getCode(), 0L)))
                .toList();
    }

    @RequirePermission(module = PermissionModule.USERS, action = PermissionAction.VIEW)
    public RoleView get(UUID id) {
        return view(load(id));
    }

    @RequirePermission(module = PermissionModule.USERS, action = PermissionAction.VIEW)
    public RoleComparison compare(UUID aId, UUID bId) {
        Role a = load(aId);
        Role b = load(bId);
        PermissionMatrix ma = permissions.load(aId);
        PermissionMatrix mb = permissions.load(bId);
        List<ModuleDiff> modules = new ArrayList<>();
        for (PermissionModule module : PermissionModule.values()) {
            List<String> inA = keys(ma.actionsIn(module));
            List<String> inB = keys(mb.actionsIn(module));
            modules.add(new ModuleDiff(module.key(), inA, inB,
                    inA.stream().filter(x -> !inB.contains(x)).toList(),
                    inB.stream().filter(x -> !inA.contains(x)).toList(), inA.equals(inB)));
        }
        return new RoleComparison(new RoleRef(a.getId(), a.getCode(), name(a), scopeView(a, access.departments(aId))),
                new RoleRef(b.getId(), b.getCode(), name(b), scopeView(b, access.departments(bId))), modules);
    }

    /**
     * "View as this role": what a user of the role may do and see, for the Admin Web to show its pages read-only.
     * Nothing is changed, and the caller's own access stays as it is.
     */
    @RequirePermission(module = PermissionModule.USERS, action = PermissionAction.VIEW)
    public RolePreview viewAs(UUID id) {
        Role role = load(id);
        return new RolePreview(role.getId(), role.getCode(), name(role), permissions.load(id).toApi(),
                scopeView(role, access.departments(id)), RoleView.SensitiveFieldsView.of(access.sensitive(id)), true);
    }

    /** The change history of a role, newest first. */
    @RequirePermission(module = PermissionModule.USERS, action = PermissionAction.VIEW)
    public List<RoleVersionView> history(UUID id) {
        Role role = load(id);
        List<RoleHistoryStore.Row> rows = history.list(id);
        Map<UUID, String> names = new HashMap<>();
        List<UUID> authors = rows.stream().map(RoleHistoryStore.Row::changedBy).filter(java.util.Objects::nonNull).distinct().toList();
        if (!authors.isEmpty()) {
            webUsers.namesByAccount(authors).forEach(row -> names.put((UUID) row[0], (String) row[1]));
        }
        List<RoleVersionView> versions = new ArrayList<>();
        for (int i = 0; i < rows.size(); i++) {
            RoleHistoryStore.Row row = rows.get(i);
            RoleSnapshot snapshot = history.read(row.snapshot());
            RoleSnapshot before = i + 1 < rows.size() ? history.read(rows.get(i + 1).snapshot()) : null;
            versions.add(new RoleVersionView(row.versionNo(), row.change(), row.restoredFrom(), row.changedBy(),
                    row.changedBy() == null ? null : names.get(row.changedBy()), row.changedAt(),
                    snapshot.differencesFrom(before), snapshot, !role.isSystem() && i > 0));
        }
        return versions;
    }

    // ------------------------------------------------------------------ changing

    @RequirePermission(module = PermissionModule.USERS, action = PermissionAction.CREATE)
    public RoleView create(RoleCommands.Create command) {
        ensureSeeded();
        UUID school = school();
        String en = cleanRequired(command.name().en(), "name.en");
        ensureNameFree(en, null);

        List<Problem> problems = new ArrayList<>();
        PermissionMatrix matrix = new PermissionMatrix();
        for (String key : command.viewModules() == null ? List.<String>of() : command.viewModules()) {
            PermissionModule.fromKey(key).ifPresentOrElse(m -> matrix.grant(m, PermissionAction.VIEW),
                    () -> problems.add(new Problem("viewModules", "UnknownModule")));
        }
        Scope scope = command.scope() == null ? new Scope(ScopeType.ALL, Set.of()) : checkScope(command.scope(), problems);
        Set<SensitiveField> sensitive = command.sensitiveFields() == null ? SystemRole.CUSTOM_DEFAULT : checkSensitive(command.sensitiveFields(), problems);
        failIfAny(problems);

        Role role = new Role();
        role.setCode(newCode(en));
        role.setName(new LocalizedText(blankToNull(command.name().km()), en, blankToNull(command.name().ar())));
        role.setDescription(description(command.description()));
        role.setSystem(false);
        role.setScopeType(scope.type());
        role = save(role);
        permissions.replace(school, role.getId(), matrix);
        access.replaceDepartments(school, role.getId(), scope.departments());
        LoginRules rules = LoginRules.open(DEFAULT_SESSION_MINUTES);
        access.createSettings(school, role.getId(), sensitive, ApprovalLimits.standard(), rules);
        loginSync.copy(school, role.getCode(), rules);
        history.record(role, RoleHistory.Change.CREATED, null, snapshot(role));
        return view(role);
    }

    @RequirePermission(module = PermissionModule.USERS, action = PermissionAction.EDIT)
    public RoleView update(UUID id, RoleCommands.Update command) {
        Role role = loadCustom(id);
        checkVersion(command.version(), role.getVersion());
        String en = cleanRequired(command.name().en(), "name.en");
        ensureNameFree(en, id);
        LocalizedText name = new LocalizedText(blankToNull(command.name().km()), en, blankToNull(command.name().ar()));
        LocalizedText description = description(command.description());
        if (same(name, role.getName()) && same(description, role.getDescription())) {
            return view(role);
        }
        history.ensureBaseline(role, snapshot(role));
        role.setName(name);
        role.setDescription(description);
        role = save(role);
        history.record(role, RoleHistory.Change.RENAMED, null, snapshot(role));
        return view(role);
    }

    /** Replaces the permission table of a custom role. Raises permVersion only if something really changed. */
    @RequirePermission(module = PermissionModule.USERS, action = PermissionAction.EDIT)
    public RoleView savePermissions(UUID id, RoleCommands.Permissions command) {
        Role role = loadCustom(id);
        refuseOwnRole(role);   // you cannot change your own access
        checkVersion(command.version(), role.getVersion());

        List<Problem> problems = new ArrayList<>();
        PermissionMatrix wanted = PermissionMatrix.parse(command.permissions(), problems);
        failIfAny(problems);

        PermissionMatrix current = permissions.load(id);
        if (wanted.equals(current)) {
            return view(role);
        }
        history.ensureBaseline(role, snapshot(role));
        permissions.replace(school(), id, wanted);
        return changed(role, RoleHistory.Change.PERMISSIONS);
    }

    /**
     * Replaces the record scope of a custom role (Roles page, "Record scope"). Raises permVersion only if something
     * really changed, so it applies on the next request of every user of the role.
     */
    @RequirePermission(module = PermissionModule.USERS, action = PermissionAction.EDIT)
    public RoleView saveScope(UUID id, RoleCommands.Scope command) {
        Role role = loadCustom(id);
        refuseOwnRole(role);
        checkVersion(command.version(), role.getVersion());
        List<Problem> problems = new ArrayList<>();
        Scope wanted = checkScope(command, problems);
        failIfAny(problems);

        Set<UUID> current = access.departments(id);
        if (wanted.type() == role.getScopeType() && wanted.departments().equals(current)) {
            return view(role);
        }
        history.ensureBaseline(role, snapshot(role));
        role.setScopeType(wanted.type());
        access.replaceDepartments(school(), id, wanted.departments());
        return changed(role, RoleHistory.Change.SCOPE);
    }

    /**
     * Replaces what the users of a custom role see of personal data (Roles page, "Sensitive fields"). Raises
     * permVersion only if something really changed, so it applies on the next request.
     */
    @RequirePermission(module = PermissionModule.USERS, action = PermissionAction.EDIT)
    public RoleView saveSensitiveFields(UUID id, RoleCommands.SensitiveFields command) {
        Role role = loadCustom(id);
        refuseOwnRole(role);
        checkVersion(command.version(), role.getVersion());
        List<Problem> problems = new ArrayList<>();
        Set<SensitiveField> wanted = checkSensitive(command, problems);
        failIfAny(problems);
        if (wanted.equals(access.sensitive(id))) {
            return view(role);
        }
        history.ensureBaseline(role, snapshot(role));
        access.saveSensitive(id, wanted);
        return changed(role, RoleHistory.Change.SENSITIVE_FIELDS);
    }

    /**
     * Moves a custom role to inactive. The role stays in old records; its code is never reused. Refused while web
     * users still have it ({@code IN_USE}: move them to another role first). If it was the default role for new web
     * users, Viewer becomes the default again.
     */
    @RequirePermission(module = PermissionModule.USERS, action = PermissionAction.DELETE)
    public void delete(UUID id) {
        Role role = loadCustom(id);
        refuseOwnRole(role);
        if (webUsers.countBySchoolIdAndRoleCode(role.getSchoolId(), role.getCode()) > 0) {
            throw new ApiException(ErrorCode.IN_USE);
        }
        boolean wasDefault = role.isDefaultRole();
        role.setDefaultRole(false);
        role.inactivate(CurrentUser.require().userId(), clock.instant(), null);
        roles.saveAndFlush(role);
        if (wasDefault) {
            roles.findBySchoolIdAndCode(role.getSchoolId(), SystemRole.VIEWER.code()).ifPresent(viewer -> {
                viewer.setDefaultRole(true);
                roles.save(viewer);
            });
        }
        loginSync.remove(school(), role.getCode());
        events.publishEvent(new RoleChanged(school(), role.getCode()));
    }

    /** Makes this role the one new web users get when none is chosen (Roles page). Any active role, system ones too. */
    @RequirePermission(module = PermissionModule.USERS, action = PermissionAction.EDIT)
    public RoleView makeDefault(UUID id) {
        Role role = load(id);
        if (role.isDefaultRole()) {
            return view(role);
        }
        roles.findFirstBySchoolIdAndDefaultRoleTrue(role.getSchoolId()).ifPresent(previous -> {
            previous.setDefaultRole(false);
            roles.saveAndFlush(previous);   // before the new one: one default per school (unique index)
        });
        role.setDefaultRole(true);
        return view(save(role));
    }

    /**
     * Replaces the approval limits of a custom role (Roles page, "Approval limits"). The escalation role must be
     * another active role, and following the escalations must not come back to this role.
     */
    @RequirePermission(module = PermissionModule.USERS, action = PermissionAction.EDIT)
    public RoleView saveApprovalLimits(UUID id, RoleCommands.Limits command) {
        Role role = loadCustom(id);
        refuseOwnRole(role);
        checkVersion(command.version(), role.getVersion());
        List<Problem> problems = new ArrayList<>();
        if (command.leaveMaxDays() != null && (command.leaveMaxDays() < 1 || command.leaveMaxDays() > 365)) {
            problems.add(new Problem("approvalLimits.leaveMaxDays", "Range"));
        }
        if (command.oldCorrections() == null) {
            problems.add(new Problem("approvalLimits.oldCorrections", "NotNull"));
        }
        if (command.ownRequests() == null) {
            problems.add(new Problem("approvalLimits.ownRequests", "NotNull"));
        }
        String escalateTo = blankToNull(command.escalateTo());
        checkEscalation(role, escalateTo, problems);
        failIfAny(problems);

        ApprovalLimits wanted = new ApprovalLimits(command.leaveMaxDays(), command.oldCorrections(), command.ownRequests(), escalateTo);
        if (wanted.equals(access.approvalLimits(id))) {
            return view(role);
        }
        history.ensureBaseline(role, snapshot(role));
        access.saveApprovalLimits(id, wanted);
        return changed(role, RoleHistory.Change.APPROVAL_LIMITS);
    }

    /**
     * Replaces when and from where the users of a custom role may sign in, and their session timeout (Roles page,
     * "Security"). Copied to the auth server in the same transaction; applies at the next sign-in, token refresh or
     * Admin Web request.
     */
    @RequirePermission(module = PermissionModule.USERS, action = PermissionAction.EDIT)
    public RoleView saveLoginRules(UUID id, RoleCommands.Login command) {
        Role role = loadCustom(id);
        refuseOwnRole(role);
        checkVersion(command.version(), role.getVersion());
        List<Problem> problems = new ArrayList<>();
        Integer minutes = command.sessionTimeoutMinutes();
        if (minutes == null) {
            problems.add(new Problem("loginRules.sessionTimeoutMinutes", "NotNull"));
        } else if (minutes < 15 || minutes > 720) {
            problems.add(new Problem("loginRules.sessionTimeoutMinutes", "Range"));
        }
        if ((command.allowedFrom() == null) != (command.allowedTo() == null)) {
            problems.add(new Problem("loginRules.allowedTo", "BothOrNeither"));
        } else if (command.allowedFrom() != null && command.allowedFrom().equals(command.allowedTo())) {
            problems.add(new Problem("loginRules.allowedTo", "Empty"));
        }
        TreeSet<Integer> days = new TreeSet<>();
        for (Integer day : command.allowedDays() == null ? List.<Integer>of() : command.allowedDays()) {
            if (day == null || day < 1 || day > 7) {
                problems.add(new Problem("loginRules.allowedDays", "Range"));
            } else {
                days.add(day);
            }
        }
        if (days.isEmpty()) {
            problems.add(new Problem("loginRules.allowedDays", "NotEmpty"));
        }
        List<String> ips = new ArrayList<>();
        for (String ip : command.allowedIps() == null ? List.<String>of() : command.allowedIps()) {
            String cleaned = blankToNull(ip);
            if (cleaned == null) {
                continue;
            }
            if (!validIpOrRange(cleaned)) {
                problems.add(new Problem("loginRules.allowedIps", "InvalidAddress"));
            } else if (!ips.contains(cleaned)) {
                ips.add(cleaned);
            }
        }
        if (ips.size() > 50) {
            problems.add(new Problem("loginRules.allowedIps", "TooMany"));
        }
        failIfAny(problems);

        LoginRules current = access.loginRules(id);
        LoginRules wanted = new LoginRules(current.twoFactorRequired(), minutes, command.allowedFrom(), command.allowedTo(),
                List.copyOf(days), ips);
        return saveRules(role, current, wanted, RoleHistory.Change.LOGIN_RULES);
    }

    /**
     * Turns "require two-step verification" on or off for a role (Web users and roles page, Security tab). Works for
     * system roles too; nobody changes it for the role they hold themselves.
     */
    @RequirePermission(module = PermissionModule.USERS, action = PermissionAction.EDIT)
    public RoleView saveTwoFactor(UUID id, RoleCommands.TwoFactor command) {
        Role role = load(id);
        refuseOwnRole(role);
        checkVersion(command.version(), role.getVersion());
        if (command.required() == null) {
            failIfAny(List.of(new Problem("required", "NotNull")));
        }
        LoginRules current = access.loginRules(id);
        return saveRules(role, current, current.withTwoFactor(command.required()), RoleHistory.Change.TWO_FACTOR);
    }

    /**
     * Brings a custom role back to an older version of its change history: name, description, permissions, scope,
     * sensitive fields, approval limits and login rules. This is saved as a new version, so it can be undone the same
     * way. Departments that were retired since are left out of the scope (refused if none is left); the name must
     * still be free and the escalation must not run in a circle.
     */
    @RequirePermission(module = PermissionModule.USERS, action = PermissionAction.EDIT)
    public RoleView restoreVersion(UUID id, int versionNo) {
        Role role = loadCustom(id);
        refuseOwnRole(role);
        RoleSnapshot wanted = history.find(id, versionNo).orElseThrow(() -> new ApiException(ErrorCode.NOT_FOUND));
        RoleSnapshot current = snapshot(role);

        List<Problem> problems = new ArrayList<>();
        String en = cleanRequired(wanted.name().en(), "name.en");
        ensureNameFree(en, id);
        PermissionMatrix matrix = PermissionMatrix.parse(wanted.permissions(), problems);
        Set<UUID> departments = new TreeSet<>();
        if (wanted.scope().type() == ScopeType.SELECTED_DEPARTMENTS) {
            wanted.scope().departmentIds().stream().filter(d -> org.isActive(OrgKind.DEPARTMENT, d)).forEach(departments::add);
            if (departments.isEmpty()) {
                problems.add(new Problem("scope.departmentIds", "UnknownDepartment"));
            }
        }
        ApprovalLimits limits = wanted.approvalLimits();
        checkEscalation(role, limits.escalateTo(), problems);
        failIfAny(problems);

        RoleSnapshot restored = new RoleSnapshot(wanted.name(), wanted.description(), matrix.toApi(),
                new RoleView.ScopeView(wanted.scope().type(), departments.stream().toList()), wanted.sensitiveFields(),
                limits, wanted.loginRules());
        if (restored.equals(current)) {
            return view(role);
        }
        history.ensureBaseline(role, current);
        UUID school = school();
        role.setName(new LocalizedText(blankToNull(wanted.name().km()), en, blankToNull(wanted.name().ar())));
        role.setDescription(description(wanted.description()));
        role.setScopeType(wanted.scope().type());
        permissions.replace(school, id, matrix);
        access.replaceDepartments(school, id, departments);
        RoleView.SensitiveFieldsView fields = wanted.sensitiveFields();
        access.saveSensitive(id, checkSensitive(new RoleCommands.SensitiveFields(fields.salaryBank(), fields.nationalId(),
                fields.phone(), fields.idScans(), null), new ArrayList<>()));
        access.saveApprovalLimits(id, limits);
        access.saveLoginRules(id, wanted.loginRules());
        loginSync.copy(school, role.getCode(), wanted.loginRules());
        role.bumpPermVersion();
        role = save(role);
        events.publishEvent(new RoleChanged(school, role.getCode()));
        history.record(role, RoleHistory.Change.RESTORED, versionNo, snapshot(role));
        return view(role);
    }

    private RoleView saveRules(Role role, LoginRules current, LoginRules wanted, RoleHistory.Change change) {
        if (wanted.equals(current)) {
            return view(role);
        }
        history.ensureBaseline(role, snapshot(role));
        access.saveLoginRules(role.getId(), wanted);
        loginSync.copy(school(), role.getCode(), wanted);
        return changed(role, change);
    }

    /** After a change of what the role grants: new permission version, cache cleared after commit, new history version. */
    private RoleView changed(Role role, RoleHistory.Change change) {
        role.bumpPermVersion();
        role = save(role);
        events.publishEvent(new RoleChanged(school(), role.getCode()));   // the cache is cleared after the commit
        history.record(role, change, null, snapshot(role));
        return view(role);
    }

    // ------------------------------------------------------------------ helpers

    private static final int DEFAULT_SESSION_MINUTES = 480;

    private static void refuseOwnRole(Role role) {
        if (role.getCode().equals(CurrentUser.require().role())) {
            throw new ApiException(ErrorCode.OWN_ROLE);   // you could lock yourself out
        }
    }

    /** The escalation role must be another active role, and the chain from it must not come back to this role. */
    private void checkEscalation(Role role, String escalateTo, List<Problem> problems) {
        if (escalateTo == null) {
            return;
        }
        Map<String, ApprovalLimits> active = access.activeApprovalLimitsByCode(school());
        if (escalateTo.equals(role.getCode())) {
            problems.add(new Problem("approvalLimits.escalateTo", "Self"));
        } else if (!active.containsKey(escalateTo)) {
            problems.add(new Problem("approvalLimits.escalateTo", "UnknownRole"));
        } else if (leadsBackTo(role.getCode(), escalateTo, active)) {
            problems.add(new Problem("approvalLimits.escalateTo", "Loop"));
        }
    }

    /** Does following the escalations from {@code start} come back to {@code role}? */
    private static boolean leadsBackTo(String role, String start, Map<String, ApprovalLimits> active) {
        Set<String> seen = new java.util.HashSet<>();
        for (String next = start; next != null && seen.add(next); ) {
            if (next.equals(role)) {
                return true;
            }
            ApprovalLimits limits = active.get(next);
            next = limits == null ? null : limits.escalateTo();
        }
        return false;
    }

    /** An IPv4 / IPv6 address or a CIDR range, never a host name (that would need a DNS lookup). */
    private static boolean validIpOrRange(String value) {
        if (!value.matches("[0-9a-fA-F:.]+(/[0-9]{1,3})?")) {
            return false;
        }
        try {
            new IpAddressMatcher(value);
            return true;
        } catch (IllegalArgumentException e) {
            return false;
        }
    }

    /** A checked scope: the type and, for SELECTED_DEPARTMENTS, its departments (empty for the other types). */
    private record Scope(ScopeType type, Set<UUID> departments) {
    }

    /** Every group must be given, true or false. */
    private static Set<SensitiveField> checkSensitive(RoleCommands.SensitiveFields in, List<Problem> problems) {
        Set<SensitiveField> visible = EnumSet.noneOf(SensitiveField.class);
        Map<SensitiveField, Boolean> sent = new java.util.EnumMap<>(SensitiveField.class);
        sent.put(SensitiveField.SALARY_BANK, in.salaryBank());
        sent.put(SensitiveField.NATIONAL_ID, in.nationalId());
        sent.put(SensitiveField.PHONE, in.phone());
        sent.put(SensitiveField.ID_SCANS, in.idScans());
        sent.forEach((field, value) -> {
            if (value == null) {
                problems.add(new Problem("sensitiveFields." + field.key(), "NotNull"));
            } else if (value) {
                visible.add(field);
            }
        });
        return visible;
    }

    /** Type required; SELECTED_DEPARTMENTS needs at least one department, each an active department of the school. */
    private Scope checkScope(RoleCommands.Scope in, List<Problem> problems) {
        if (in.type() == null) {
            problems.add(new Problem("scope.type", "NotNull"));
            return new Scope(ScopeType.ALL, Set.of());
        }
        if (in.type() != ScopeType.SELECTED_DEPARTMENTS) {
            return new Scope(in.type(), Set.of());
        }
        Set<UUID> departments = new TreeSet<>();
        for (UUID department : in.departmentIds() == null ? List.<UUID>of() : in.departmentIds()) {
            if (department == null || !org.isActive(OrgKind.DEPARTMENT, department)) {
                problems.add(new Problem("scope.departmentIds", "UnknownDepartment"));
            } else {
                departments.add(department);
            }
        }
        if (departments.isEmpty() && problems.stream().noneMatch(p -> p.field().equals("scope.departmentIds"))) {
            problems.add(new Problem("scope.departmentIds", "NotEmpty"));
        }
        return new Scope(ScopeType.SELECTED_DEPARTMENTS, departments);
    }

    private UUID school() {
        UUID school = schoolContext.currentSchoolId();
        ensureSeeded(school);
        return school;
    }

    private void ensureSeeded() {
        ensureSeeded(schoolContext.currentSchoolId());
    }

    private void ensureSeeded(UUID school) {
        try {
            seeder.seed(school);
        } catch (DataIntegrityViolationException seededInParallel) {
            // another request created the system roles at the same moment: fine
        }
    }

    /** An active role of the caller school. */
    private Role load(UUID id) {
        UUID school = school();
        return roles.findById(id)
                .filter(r -> r.getSchoolId().equals(school) && !r.isInactive())
                .orElseThrow(() -> new ApiException(ErrorCode.NOT_FOUND));
    }

    private Role loadCustom(UUID id) {
        Role role = load(id);
        if (role.isSystem()) {
            throw new ApiException(ErrorCode.ROLE_LOCKED);
        }
        return role;
    }

    private void ensureNameFree(String nameEn, UUID exceptId) {
        boolean taken = roles.findBySchoolId(school()).stream()
                .anyMatch(r -> !r.getId().equals(exceptId) && r.getName().getEn().equalsIgnoreCase(nameEn));
        if (taken) {
            throw new ApiException(ErrorCode.NAME_TAKEN);
        }
    }

    /** code from the English name: "Exam office" -> exam_office, then exam_office_2... if taken (deleted roles count). */
    private String newCode(String nameEn) {
        String base = nameEn.toLowerCase().replaceAll("[^a-z0-9]+", "_").replaceAll("^_+|_+$", "");
        if (base.isEmpty()) {
            base = "role";
        }
        base = base.length() > 40 ? base.substring(0, 40) : base;
        String code = base;
        for (int n = 2; permissions.codeExists(school(), code); n++) {
            code = base + "_" + n;
        }
        return code;
    }

    private Role save(Role role) {
        try {
            return roles.saveAndFlush(role);
        } catch (DataIntegrityViolationException e) {
            throw new ApiException(ErrorCode.NAME_TAKEN);   // lost a race with a parallel request
        }
    }

    private static void checkVersion(Long sent, long current) {
        if (sent == null) {
            throw new ApiException(ErrorCode.VALIDATION_ERROR)
                    .with("errors", List.of(Map.of("field", "version", "rule", "NotNull")));
        }
        if (sent != current) {
            throw new ApiException(ErrorCode.VERSION_CONFLICT);
        }
    }

    private static void failIfAny(List<Problem> problems) {
        if (!problems.isEmpty()) {
            throw new ApiException(ErrorCode.VALIDATION_ERROR).with("errors",
                    problems.stream().map(p -> Map.of("field", p.field(), "rule", p.rule())).toList());
        }
    }

    private static String cleanRequired(String value, String field) {
        String cleaned = blankToNull(value);
        if (cleaned == null) {
            throw new ApiException(ErrorCode.VALIDATION_ERROR)
                    .with("errors", List.of(Map.of("field", field, "rule", "NotBlank")));
        }
        return cleaned.replaceAll("\\s+", " ");
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    private static LocalizedText description(LocalizedName in) {
        return in == null ? new LocalizedText(null, null, null)
                : new LocalizedText(blankToNull(in.km()), blankToNull(in.en()), blankToNull(in.ar()));
    }

    private static boolean same(LocalizedText a, LocalizedText b) {
        LocalizedText x = a == null ? new LocalizedText(null, null, null) : a;
        LocalizedText y = b == null ? new LocalizedText(null, null, null) : b;
        return java.util.Objects.equals(x.getKm(), y.getKm()) && java.util.Objects.equals(x.getEn(), y.getEn())
                && java.util.Objects.equals(x.getAr(), y.getAr());
    }

    private static List<String> keys(java.util.Set<PermissionAction> actions) {
        return java.util.Arrays.stream(PermissionAction.values()).filter(actions::contains).map(PermissionAction::key).toList();
    }

    private static LocalizedName name(Role r) {
        return new LocalizedName(r.getName().getKm(), r.getName().getEn(), r.getName().getAr());
    }

    /** null when there is no description (Hibernate also loads an all-empty description as null). */
    private static LocalizedName descriptionName(Role r) {
        LocalizedText d = r.getDescription();
        return d == null || (d.getKm() == null && d.getEn() == null && d.getAr() == null) ? null
                : new LocalizedName(d.getKm(), d.getEn(), d.getAr());
    }

    /** The role as it is now, as the change history saves it. */
    private RoleSnapshot snapshot(Role r) {
        return new RoleSnapshot(name(r), descriptionName(r), permissions.load(r.getId()).toApi(),
                scopeView(r, access.departments(r.getId())), RoleView.SensitiveFieldsView.of(access.sensitive(r.getId())),
                access.approvalLimits(r.getId()), access.loginRules(r.getId()));
    }

    private RoleView view(Role r) {
        return view(r, permissions.load(r.getId()), access.departments(r.getId()), access.sensitive(r.getId()),
                access.approvalLimits(r.getId()), access.loginRules(r.getId()),
                webUsers.countBySchoolIdAndRoleCode(r.getSchoolId(), r.getCode()));
    }

    private static RoleView.ScopeView scopeView(Role r, Set<UUID> departments) {
        return new RoleView.ScopeView(r.getScopeType(),
                r.getScopeType() == ScopeType.SELECTED_DEPARTMENTS ? departments.stream().sorted().toList() : List.of());
    }

    private RoleView view(Role r, PermissionMatrix matrix, Set<UUID> departments, Set<SensitiveField> sensitive,
            ApprovalLimits limits, LoginRules rules, long users) {
        return new RoleView(r.getId(), r.getCode(), name(r), descriptionName(r), r.isSystem(), r.getPermVersion(), r.getVersion(),
                matrix.toApi(), scopeView(r, departments), RoleView.SensitiveFieldsView.of(sensitive), limits, rules,
                users, sees(r, departments), r.isDefaultRole(), r.getUpdatedAt());
    }

    /** "Sees: N people": all school or the selected departments; null when it depends on the user. */
    private Long sees(Role r, Set<UUID> departments) {
        return switch (r.getScopeType()) {
            case ALL -> peopleCounts.active(null);
            case SELECTED_DEPARTMENTS -> peopleCounts.active(departments);
            case OWN_DEPARTMENT, OWN_TEAM -> null;
        };
    }

    /** Used by SystemRole-aware callers and tests to check a seeded role against its definition. */
    static boolean isSystemCode(String code) {
        return java.util.Arrays.stream(SystemRole.values()).anyMatch(s -> s.code().equals(code));
    }
}
