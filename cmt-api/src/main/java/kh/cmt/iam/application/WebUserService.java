package kh.cmt.iam.application;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonUnwrapped;
import jakarta.persistence.EntityManager;
import java.time.Clock;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;
import kh.cmt.iam.domain.PermissionMatrix.Problem;
import kh.cmt.iam.domain.Role;
import kh.cmt.iam.domain.SystemRole;
import kh.cmt.iam.domain.WebUser;
import kh.cmt.iam.infrastructure.AuthAccountStore;
import kh.cmt.iam.infrastructure.AuthAccountStore.LoginInfo;
import kh.cmt.iam.infrastructure.RoleAccessStore;
import kh.cmt.iam.infrastructure.RoleRepository;
import kh.cmt.iam.infrastructure.WebUserRepository;
import kh.cmt.people.api.PeopleQueries;
import kh.cmt.shared.domain.SoftDeleteFilter;
import kh.cmt.shared.i18n.LocalizedName;
import kh.cmt.shared.security.CurrentUser;
import kh.cmt.shared.security.FieldMask;
import kh.cmt.shared.security.PermissionAction;
import kh.cmt.shared.security.PermissionModule;
import kh.cmt.shared.security.RequirePermission;
import kh.cmt.shared.security.SchoolContext;
import kh.cmt.shared.security.ScopeType;
import kh.cmt.shared.security.SensitiveField;
import kh.cmt.shared.security.SignOuts;
import kh.cmt.shared.web.ApiException;
import kh.cmt.shared.web.ErrorCode;
import kh.cmt.shared.web.PageResponse;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Web users (Web users &amp; roles &gt; Web users) and their logins.
 * <p>
 * Every web user has an account on the auth server, written here in the same transaction. The admin gives the
 * password (typed, or generated and shown once — nothing is sent by email or SMS); it must be changed at the first
 * sign-in unless the admin said otherwise ({@link AdminPasswords}). Deactivating signs the user out everywhere at once: refresh tokens
 * revoked, the auth server's login session ended, access tokens refused by this API ({@link SignOuts}). A change of
 * role applies at the user's next token refresh (at most 15 minutes).
 * <p>
 * Rules: nobody changes their own role or deactivates themselves ({@code OWN_ACCOUNT}); at least one active Super
 * Admin web user must remain ({@code LAST_SUPER_ADMIN}); a username is used by one login only ({@code USERNAME_TAKEN});
 * a person is linked to one active web user ({@code PERSON_TAKEN}); access may end on a date (last day included),
 * never on a day already past.
 * <p>
 * Permissions in the Users module: view to read, create to add, edit to change (also reset password, sign out, move
 * role), delete to deactivate.
 */
@Service
@Transactional
public class WebUserService {

    static final int MAX_PAGE_SIZE = 200;
    private static final String SUPER_ADMIN = SystemRole.SUPER_ADMIN.code();
    private static final Pattern USERNAME = Pattern.compile("[A-Za-z0-9._@+-]{3,100}");
    private static final Pattern PHONE = Pattern.compile("[+0-9 ()-]{6,30}");

    private final WebUserRepository users;
    private final RoleRepository roles;
    private final RoleAccessStore roleAccess;
    private final RoleSeeder seeder;
    private final AuthAccountStore accounts;
    private final SignOuts signOuts;
    private final FieldMask fieldMask;
    private final SchoolContext schoolContext;
    private final PeopleQueries peopleQueries;
    private final EntityManager em;
    private final Clock clock;
    private final AdminPasswords passwords;

    WebUserService(WebUserRepository users, RoleRepository roles, RoleAccessStore roleAccess, RoleSeeder seeder,
            AuthAccountStore accounts, AdminPasswords passwords, SignOuts signOuts, FieldMask fieldMask,
            SchoolContext schoolContext, PeopleQueries peopleQueries, EntityManager em, Clock clock) {
        this.peopleQueries = peopleQueries;
        this.users = users;
        this.roles = roles;
        this.roleAccess = roleAccess;
        this.seeder = seeder;
        this.accounts = accounts;
        this.passwords = passwords;
        this.signOuts = signOuts;
        this.fieldMask = fieldMask;
        this.schoolContext = schoolContext;
        this.em = em;
        this.clock = clock;
    }

    // ------------------------------------------------------------------ reading

    /**
     * {@code status}: active (default; expired users are in it, with status EXPIRED) or inactive (deactivated).
     * {@code q} searches name and username; {@code role} is a role code. Sorted by name.
     */
    @Transactional(readOnly = true)
    @RequirePermission(module = PermissionModule.USERS, action = PermissionAction.VIEW)
    public PageResponse<WebUserView> list(String status, String q, String role, int page, int size) {
        if (page < 0 || size < 1 || size > MAX_PAGE_SIZE) {
            fail(new Problem("page/size", "Range"));
        }
        boolean inactive;
        if (status == null || status.isBlank() || status.equals("active")) {
            inactive = false;
        } else if (status.equals("inactive")) {
            inactive = true;
            SoftDeleteFilter.includeInactive(em);   // the Inactive tab
        } else {
            throw fail(new Problem("status", "OneOf"));
        }
        UUID school = schoolContext.currentSchoolId();
        Specification<WebUser> spec = (root, query, cb) -> cb.and(cb.equal(root.get("schoolId"), school),
                cb.equal(root.get("inactive"), inactive));
        if (role != null && !role.isBlank()) {
            spec = spec.and((root, query, cb) -> cb.equal(root.get("roleCode"), role.trim()));
        }
        if (q != null && !q.isBlank()) {
            String pattern = "%" + q.trim().toLowerCase().replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_") + "%";
            spec = spec.and((root, query, cb) -> cb.or(cb.like(cb.lower(root.get("name")), pattern, '\\'),
                    cb.like(cb.lower(root.get("username")), pattern, '\\')));
        }
        var found = users.findAll(spec, PageRequest.of(page, size, Sort.by("name").and(Sort.by("id"))));
        Views views = new Views(found.getContent());
        return PageResponse.of(found, views::of);
    }

    @Transactional(readOnly = true)
    @RequirePermission(module = PermissionModule.USERS, action = PermissionAction.VIEW)
    public WebUserView get(UUID id) {
        return view(load(id));
    }

    /** The caller's own name and username (header of the Admin Web); empty for a caller without a web user. */
    @Transactional(readOnly = true)
    public Optional<WebUser> own(UUID webUserId) {
        return webUserId == null ? Optional.empty() : users.findById(webUserId)
                .filter(u -> u.getSchoolId().equals(schoolContext.currentSchoolId()));
    }

    /** Roles page, "Users of this role": the active web users of a role, by name. */
    @Transactional(readOnly = true)
    @RequirePermission(module = PermissionModule.USERS, action = PermissionAction.VIEW)
    public List<WebUserView> usersOfRole(UUID roleId) {
        Role role = activeRole(roleId);
        List<WebUser> found = users.findBySchoolIdAndRoleCodeOrderByName(role.getSchoolId(), role.getCode());
        Views views = new Views(found);
        return found.stream().map(views::of).toList();
    }

    // ------------------------------------------------------------------ changing

    /** A new web user, and the password when it was generated (shown once). */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record Created(@JsonUnwrapped WebUserView user, String temporaryPassword) {
    }

    /** "Reset password": the username, the generated password (shown once; null when typed), must it be changed. */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record PasswordGiven(String username, String temporaryPassword, boolean mustChangePassword) {
    }

    @RequirePermission(module = PermissionModule.USERS, action = PermissionAction.CREATE)
    public Created create(WebUserCommands.Create command) {
        UUID school = schoolContext.currentSchoolId();
        ensureSeeded(school);
        List<Problem> problems = new ArrayList<>();
        String name = name(command.name(), problems);
        String username = username(command.username(), problems);
        String phone = phone(command.phone(), problems);
        Optional<Role> role = command.role() == null || command.role().isBlank() ? defaultRole(school)
                : roleByCode(school, command.role().trim());
        if (role.isEmpty()) {
            problems.add(new Problem("role", "UnknownRole"));
        }
        LocalDate lastDay = command.accessExpiresOn();
        checkLastDay(lastDay, null, problems);
        String reason = reason(command.accessExpiresReason(), problems);
        checkPersonExists(command.linkedPersonId(), problems);
        failIfAny(problems);
        if (accounts.usernameTaken(username, null)) {
            throw new ApiException(ErrorCode.USERNAME_TAKEN);
        }
        checkPersonFree(school, command.linkedPersonId(), null);

        WebUser user = new WebUser();
        user.setAccountId(UUID.randomUUID());
        user.setName(name);
        user.setUsername(username);
        user.setPhone(phone);
        user.setRoleCode(role.get().getCode());
        user.setLinkedPersonId(command.linkedPersonId());
        user.setTwoFactorRequired(Boolean.TRUE.equals(command.twoFactorRequired()));
        user.setAccessExpires(lastDay, reason);
        AdminPasswords.Chosen password = passwords.choose(school, username, command.password());
        user = save(user);
        accounts.create(user.getAccountId(), account(user), password.hash(),
                password.wasGenerated() || !Boolean.FALSE.equals(command.mustChangePassword()));
        return new Created(view(user), password.generated());
    }

    @RequirePermission(module = PermissionModule.USERS, action = PermissionAction.EDIT)
    public WebUserView update(UUID id, WebUserCommands.Update command) {
        WebUser user = loadActive(id);
        checkVersion(command.version(), user.getVersion());
        UUID school = user.getSchoolId();
        List<Problem> problems = new ArrayList<>();
        String name = name(command.name(), problems);
        String username = username(command.username(), problems);
        // a caller who may not see phone numbers got "••••": what they send back never overwrites the number
        String phone = fieldMask.keepIfHidden(SensitiveField.PHONE, phone(command.phone(), problems), user.getPhone());
        String roleCode = command.role() == null || command.role().isBlank() ? user.getRoleCode() : command.role().trim();
        if (!roleCode.equals(user.getRoleCode()) && roleByCode(school, roleCode).isEmpty()) {
            problems.add(new Problem("role", "UnknownRole"));
        }
        LocalDate lastDay = command.accessExpiresOn();
        checkLastDay(lastDay, user.getAccessExpiresOn(), problems);
        String reason = reason(command.accessExpiresReason(), problems);
        if (!Objects.equals(command.linkedPersonId(), user.getLinkedPersonId())) {
            checkPersonExists(command.linkedPersonId(), problems);
        }
        failIfAny(problems);

        if (!roleCode.equals(user.getRoleCode())) {
            refuseOwnAccount(user);
            if (SUPER_ADMIN.equals(user.getRoleCode())) {
                keepOneSuperAdmin(school, Set.of(user.getId()));
            }
        }
        if (!username.equalsIgnoreCase(user.getUsername()) && accounts.usernameTaken(username, user.getAccountId())) {
            throw new ApiException(ErrorCode.USERNAME_TAKEN);
        }
        if (!Objects.equals(command.linkedPersonId(), user.getLinkedPersonId())) {
            checkPersonFree(school, command.linkedPersonId(), user.getId());
        }
        user.setName(name);
        user.setUsername(username);
        user.setPhone(phone);
        user.setRoleCode(roleCode);
        user.setLinkedPersonId(command.linkedPersonId());
        user.setTwoFactorRequired(Boolean.TRUE.equals(command.twoFactorRequired()));
        user.setAccessExpires(lastDay, reason);
        user = save(user);
        accounts.update(user.getAccountId(), account(user));
        return view(user);
    }

    /**
     * Deactivates a web user (moves it to the Inactive tab, with an optional reason): signed out everywhere at once and
     * no more sign-in. Not yourself, and not the last active Super Admin.
     */
    @RequirePermission(module = PermissionModule.USERS, action = PermissionAction.DELETE)
    public void deactivate(UUID id, String reason) {
        WebUser user = load(id);
        if (user.isInactive()) {
            return;
        }
        refuseOwnAccount(user);
        if (SUPER_ADMIN.equals(user.getRoleCode())) {
            keepOneSuperAdmin(user.getSchoolId(), Set.of(user.getId()));
        }
        String why = reason == null || reason.isBlank() ? null : reason.trim();
        if (why != null && why.length() > 500) {
            fail(new Problem("reason", "Size"));
        }
        user.inactivate(CurrentUser.require().userId(), clock.instant(), why);
        user = save(user);
        accounts.update(user.getAccountId(), account(user));
        signOutEverywhere(user);
    }

    /**
     * Makes a deactivated web user active again. If the role was deleted in the meantime, the user gets the default
     * role for new web users.
     */
    @RequirePermission(module = PermissionModule.USERS, action = PermissionAction.EDIT)
    public WebUserView restore(UUID id) {
        WebUser user = load(id);
        if (!user.isInactive()) {
            return view(user);
        }
        checkPersonFree(user.getSchoolId(), user.getLinkedPersonId(), user.getId());
        if (roleByCode(user.getSchoolId(), user.getRoleCode()).isEmpty()) {
            user.setRoleCode(defaultRole(user.getSchoolId()).orElseThrow().getCode());
        }
        user.restore();
        user = save(user);
        accounts.update(user.getAccountId(), account(user));
        return view(user);
    }

    /**
     * "Reset password": the admin types a new password or leaves it empty for a generated one (shown once); it must be
     * changed at the next sign-in unless the admin typed it and said otherwise. The user is signed out everywhere and no
     * longer locked after wrong passwords.
     */
    @RequirePermission(module = PermissionModule.USERS, action = PermissionAction.EDIT)
    public PasswordGiven resetPassword(UUID id, WebUserCommands.SetPassword command) {
        WebUser user = loadActive(id);
        AdminPasswords.Chosen password = passwords.choose(user.getSchoolId(), user.getUsername(),
                command == null ? null : command.password());
        boolean mustChange = password.wasGenerated() || command == null || !Boolean.FALSE.equals(command.mustChangePassword());
        accounts.setPassword(user.getAccountId(), password.hash(), mustChange);
        signOutEverywhere(user);
        return new PasswordGiven(user.getUsername(), password.generated(), mustChange);
    }

    /** "Sign out": ends every login of the user at once. Returns the number of login sessions that were open. */
    @RequirePermission(module = PermissionModule.USERS, action = PermissionAction.EDIT)
    public int signOut(UUID id) {
        return signOutEverywhere(loadActive(id));
    }

    /** "Reset two-step verification" (lost phone): the authenticator is removed; the user sets it up again at login. */
    @RequirePermission(module = PermissionModule.USERS, action = PermissionAction.EDIT)
    public WebUserView resetTwoFactor(UUID id) {
        WebUser user = loadActive(id);
        accounts.resetTwoFactor(user.getAccountId());
        return view(user);
    }

    /**
     * "Move to role": these web users get another role (applies at their next token refresh). Not yourself; at least
     * one active Super Admin must remain. Returns the number of users moved.
     */
    @RequirePermission(module = PermissionModule.USERS, action = PermissionAction.EDIT)
    public int moveRole(WebUserCommands.MoveRole command) {
        UUID school = schoolContext.currentSchoolId();
        List<Problem> problems = new ArrayList<>();
        Set<UUID> ids = new LinkedHashSet<>(command.userIds() == null ? List.of() : command.userIds());
        ids.remove(null);
        if (ids.isEmpty()) {
            problems.add(new Problem("userIds", "NotEmpty"));
        } else if (ids.size() > 500) {
            problems.add(new Problem("userIds", "Size"));
        }
        Optional<Role> target = command.role() == null || command.role().isBlank() ? Optional.empty()
                : roleByCode(school, command.role().trim());
        if (target.isEmpty()) {
            problems.add(new Problem("role", "UnknownRole"));
        }
        failIfAny(problems);

        List<WebUser> moving = ids.stream().map(this::loadActive).toList();
        moving.forEach(WebUserService::refuseOwnAccount);
        String to = target.get().getCode();
        if (!SUPER_ADMIN.equals(to) && moving.stream().anyMatch(u -> SUPER_ADMIN.equals(u.getRoleCode()))) {
            keepOneSuperAdmin(school, ids);
        }
        int moved = 0;
        for (WebUser user : moving) {
            if (!user.getRoleCode().equals(to)) {
                user.setRoleCode(to);
                user = save(user);
                accounts.update(user.getAccountId(), account(user));
                moved++;
            }
        }
        return moved;
    }

    /**
     * Roles page, "Force logout everywhere": every active user of the role is signed out at once, except the caller.
     * Returns the number of users signed out.
     */
    @RequirePermission(module = PermissionModule.USERS, action = PermissionAction.EDIT)
    public int signOutRole(UUID roleId) {
        Role role = activeRole(roleId);
        UUID me = CurrentUser.require().userId();
        int count = 0;
        for (WebUser user : users.findBySchoolIdAndRoleCodeOrderByName(role.getSchoolId(), role.getCode())) {
            if (!user.getAccountId().equals(me)) {
                signOutEverywhere(user);
                count++;
            }
        }
        return count;
    }

    // ------------------------------------------------------------------ rules

    private int signOutEverywhere(WebUser user) {
        int sessions = accounts.signOutEverywhere(user.getAccountId());
        signOuts.signOut(user.getAccountId());
        return sessions;
    }

    private static void refuseOwnAccount(WebUser user) {
        if (user.getAccountId().equals(CurrentUser.require().userId())) {
            throw new ApiException(ErrorCode.OWN_ACCOUNT);
        }
    }

    /** At least one active Super Admin who can still sign in must remain when {@code leaving} stop being one. */
    private void keepOneSuperAdmin(UUID school, Set<UUID> leaving) {
        LocalDate today = LocalDate.now(clock);
        boolean another = users.findBySchoolIdAndRoleCodeOrderByName(school, SUPER_ADMIN).stream()
                .anyMatch(u -> !leaving.contains(u.getId()) && u.canSignIn(today));
        if (!another) {
            throw new ApiException(ErrorCode.LAST_SUPER_ADMIN);
        }
    }

    /** A linked person must be an active person of the school (checked when the link is made or changed). */
    private void checkPersonExists(UUID personId, List<Problem> problems) {
        if (personId != null && !peopleQueries.isActive(personId)) {
            problems.add(new Problem("linkedPersonId", "UnknownPerson"));
        }
    }

    private void checkPersonFree(UUID school, UUID personId, UUID exceptId) {
        if (personId != null && users.existsBySchoolIdAndLinkedPersonIdAndIdNot(school, personId,
                exceptId == null ? new UUID(0, 0) : exceptId)) {
            throw new ApiException(ErrorCode.PERSON_TAKEN);
        }
    }

    /** The last day may not be in the past, unless it is the one already stored (no change). */
    private void checkLastDay(LocalDate lastDay, LocalDate stored, List<Problem> problems) {
        if (lastDay != null && !lastDay.equals(stored) && lastDay.isBefore(LocalDate.now(clock))) {
            problems.add(new Problem("accessExpiresOn", "Past"));
        }
    }

    private static String name(String value, List<Problem> problems) {
        String cleaned = value == null ? "" : value.trim().replaceAll("\\s+", " ");
        if (cleaned.isEmpty()) {
            problems.add(new Problem("name", "NotBlank"));
        } else if (cleaned.length() > 150) {
            problems.add(new Problem("name", "Size"));
        }
        return cleaned;
    }

    private static String username(String value, List<Problem> problems) {
        String cleaned = value == null ? "" : value.trim();
        if (cleaned.isEmpty()) {
            problems.add(new Problem("username", "NotBlank"));
        } else if (!USERNAME.matcher(cleaned).matches()) {
            problems.add(new Problem("username", "Pattern"));
        }
        return cleaned;
    }

    private static String phone(String value, List<Problem> problems) {
        if (value == null || value.isBlank() || FieldMask.MASK.equals(value)) {
            return value == null || value.isBlank() ? null : value;
        }
        String cleaned = value.trim();
        if (!PHONE.matcher(cleaned).matches()) {
            problems.add(new Problem("phone", "Pattern"));
        }
        return cleaned;
    }

    private static String reason(String value, List<Problem> problems) {
        String cleaned = value == null || value.isBlank() ? null : value.trim();
        if (cleaned != null && cleaned.length() > 200) {
            problems.add(new Problem("accessExpiresReason", "Size"));
        }
        return cleaned;
    }

    // ------------------------------------------------------------------ helpers

    /** The account as the auth server needs it, from the web user. */
    private static AuthAccountStore.Account account(WebUser user) {
        return new AuthAccountStore.Account(user.getSchoolId(), user.getId(), user.getUsername(), user.getRoleCode(),
                user.getLinkedPersonId(), user.getPhone(), user.isTwoFactorRequired(), user.getAccessExpiresOn(),
                !user.isInactive());
    }

    /** A web user of the caller's school, active or not. */
    private WebUser load(UUID id) {
        UUID school = schoolContext.currentSchoolId();
        return users.findById(id).filter(u -> u.getSchoolId().equals(school))
                .orElseThrow(() -> new ApiException(ErrorCode.NOT_FOUND));
    }

    /** Changes other than restore need an active user (deactivated ones are only restored). */
    private WebUser loadActive(UUID id) {
        WebUser user = load(id);
        if (user.isInactive()) {
            throw new ApiException(ErrorCode.CONFLICT);
        }
        return user;
    }

    private Role activeRole(UUID roleId) {
        UUID school = schoolContext.currentSchoolId();
        return roles.findById(roleId).filter(r -> r.getSchoolId().equals(school) && !r.isInactive())
                .orElseThrow(() -> new ApiException(ErrorCode.NOT_FOUND));
    }

    private Optional<Role> roleByCode(UUID school, String code) {
        ensureSeeded(school);
        return roles.findBySchoolIdAndCode(school, code).filter(r -> !r.isInactive());
    }

    private Optional<Role> defaultRole(UUID school) {
        ensureSeeded(school);
        return roles.findFirstBySchoolIdAndDefaultRoleTrue(school).filter(r -> !r.isInactive())
                .or(() -> roleByCode(school, SystemRole.VIEWER.code()));
    }

    private void ensureSeeded(UUID school) {
        try {
            seeder.seed(school);
        } catch (DataIntegrityViolationException seededInParallel) {
            // created by a parallel request: fine
        }
    }

    private WebUser save(WebUser user) {
        try {
            return users.saveAndFlush(user);
        } catch (DataIntegrityViolationException e) {
            // lost a race with a parallel request using the same username or person
            String message = String.valueOf(e.getMostSpecificCause().getMessage());
            throw new ApiException(message.contains("web_user_person_idx") ? ErrorCode.PERSON_TAKEN : ErrorCode.USERNAME_TAKEN);
        }
    }

    private static void checkVersion(Long sent, long current) {
        if (sent == null) {
            fail(new Problem("version", "NotNull"));
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

    private static ApiException fail(Problem problem) {
        throw new ApiException(ErrorCode.VALIDATION_ERROR)
                .with("errors", List.of(Map.of("field", problem.field(), "rule", problem.rule())));
    }

    private WebUserView view(WebUser user) {
        return new Views(List.of(user)).of(user);
    }

    /** Builds views for a page of users, reading their roles and logins once. */
    private final class Views {

        private record RoleInfo(LocalizedName name, RoleView.ScopeView scope, boolean twoFactor) {
        }

        private final Map<String, Optional<RoleInfo>> roleInfo = new HashMap<>();
        private final Map<UUID, LoginInfo> logins;
        private final LocalDate today = LocalDate.now(clock);

        Views(List<WebUser> page) {
            Set<UUID> accountIds = new HashSet<>();
            page.forEach(u -> accountIds.add(u.getAccountId()));
            this.logins = accounts.loginInfo(accountIds);
        }

        WebUserView of(WebUser u) {
            Optional<RoleInfo> role = roleInfo.computeIfAbsent(u.getRoleCode(), code -> roles.findBySchoolIdAndCode(u.getSchoolId(), code)
                    .map(r -> new RoleInfo(new LocalizedName(r.getName().getKm(), r.getName().getEn(), r.getName().getAr()),
                            new RoleView.ScopeView(r.getScopeType(), r.getScopeType() == ScopeType.SELECTED_DEPARTMENTS
                                    ? roleAccess.departments(r.getId()).stream().sorted().toList() : List.of()),
                            !r.isInactive() && roleAccess.loginRules(r.getId()).twoFactorRequired())));
            LoginInfo login = logins.get(u.getAccountId());
            String status = u.isInactive() ? WebUserView.INACTIVE : u.canSignIn(today) ? WebUserView.ACTIVE : WebUserView.EXPIRED;
            Integer daysLeft = u.getAccessExpiresOn() == null || u.getAccessExpiresOn().isBefore(today) ? null
                    : (int) ChronoUnit.DAYS.between(today, u.getAccessExpiresOn());
            return new WebUserView(u.getId(), u.getName(), u.getUsername(), u.getPhone(),
                    new WebUserView.RoleRef(u.getRoleCode(), role.map(RoleInfo::name).orElse(null)),
                    role.map(RoleInfo::scope).orElse(null), u.getLinkedPersonId(),
                    new WebUserView.TwoFactor(u.isTwoFactorRequired(), role.map(RoleInfo::twoFactor).orElse(false),
                            login != null && login.twoFactorEnrolled()),
                    status, u.getAccessExpiresOn(), u.getAccessExpiresReason(), daysLeft,
                    login == null ? null : login.lastLoginAt(),
                    u.isInactive() ? new WebUserView.Deactivation(u.getDeletedAt(), u.getDeletedBy(), u.getDeleteReason()) : null,
                    u.getVersion(), u.getCreatedAt(), u.getUpdatedAt());
        }
    }
}
