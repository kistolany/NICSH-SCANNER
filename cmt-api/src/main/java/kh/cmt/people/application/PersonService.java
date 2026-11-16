package kh.cmt.people.application;

import jakarta.persistence.EntityManager;
import java.time.Clock;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;
import kh.cmt.files.api.FilePurpose;
import kh.cmt.files.api.Files;
import kh.cmt.org.api.OrgKind;
import kh.cmt.org.api.OrgLabel;
import kh.cmt.org.api.OrgQueries;
import kh.cmt.org.api.StaffIdGenerator;
import kh.cmt.org.api.StaffRole;
import kh.cmt.people.domain.Person;
import kh.cmt.people.infrastructure.PersonDirectory;
import kh.cmt.people.infrastructure.PersonDirectory.PersonRef;
import kh.cmt.people.infrastructure.PersonRepository;
import kh.cmt.shared.domain.SoftDeleteFilter;
import kh.cmt.shared.i18n.LocalizedName;
import kh.cmt.shared.i18n.LocalizedText;
import kh.cmt.shared.people.PaySummaries;
import kh.cmt.shared.security.CurrentUser;
import kh.cmt.shared.security.FieldMask;
import kh.cmt.shared.security.PermissionAction;
import kh.cmt.shared.security.PermissionModule;
import kh.cmt.shared.security.PersonLogins;
import kh.cmt.shared.security.RequirePermission;
import kh.cmt.shared.security.SchoolContext;
import kh.cmt.shared.security.SensitiveField;
import kh.cmt.shared.web.ApiException;
import kh.cmt.shared.web.ErrorCode;
import kh.cmt.shared.web.PageResponse;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * People (People page, Add / Edit person, profile, Move to inactive, Restore, Delete forever) and the app's Profile.
 * <p>
 * Every list, search and lookup is limited to the caller's record scope (people outside it are simply not found);
 * whole-school rules (Staff ID and national ID used once, supervisor chain) are checked without it.
 * <p>
 * Rules: the Staff ID is handed out automatically and never used twice; typing or changing one needs the Super Admin
 * role ({@code STAFF_ID_TAKEN} if another person, active or not, has it). A national ID number belongs to one person
 * of the school ({@code NATIONAL_ID_TAKEN}). Department and position must be active when picked
 * (ones already on the record stay valid after being retired). Subjects and classes are for teachers only. The
 * supervisor is another active person and the chain never comes back to the person. Moving a person to inactive needs
 * a reason and ends their app login at once; nobody moves the person they are linked to themselves
 * ({@code OWN_ACCOUNT}). "Delete forever" is for inactive people without history or login, by a Super Admin.
 * <p>
 * Sensitive values (national ID, phone numbers) the caller may not see show as "••••" and are never overwritten
 * by what comes back from the form.
 * <p>
 * Permissions in the People module: view, create, edit (also restore), delete (move to inactive, delete forever).
 */
@Service
@Transactional
public class PersonService {

    static final int MAX_PAGE_SIZE = 200;
    static final String SUPER_ADMIN = "super_admin";

    static final Set<String> ROLES = Set.of("staff", "teacher", "admin");
    static final Set<String> GENDERS = Set.of("female", "male");
    static final Set<String> MARITAL = Set.of("single", "married", "divorced", "widowed");
    static final Set<String> BLOOD = Set.of("A+", "A-", "B+", "B-", "AB+", "AB-", "O+", "O-");
    static final Set<String> EMPLOYMENT = Set.of("full_time", "part_time", "contract", "volunteer", "intern");
    static final Set<String> REASONS = Set.of("RESIGNED", "CONTRACT_ENDED", "TRANSFERRED", "MISTAKE", "OTHER");
    private static final Set<String> COUNTRIES = Set.of(Locale.getISOCountries());

    private static final Pattern STAFF_ID = Pattern.compile("[A-Za-z0-9._/-]{1,30}");
    private static final Pattern NATIONAL_ID = Pattern.compile("[A-Za-z0-9-]{4,30}");
    private static final Pattern PHONE = Pattern.compile("[+0-9 ()-]{6,30}");
    private static final Pattern EMAIL = Pattern.compile("[^@\\s]+@[^@\\s]+\\.[^@\\s]+");

    private final PersonRepository people;
    private final PersonDirectory directory;
    private final OrgQueries org;
    private final StaffIdGenerator staffIds;
    private final PersonLogins logins;
    private final FieldMask fieldMask;
    private final SchoolContext schoolContext;
    private final EntityManager em;
    private final Clock clock;

    private final Files files;
    private final PersonDocumentService documentService;
    private final PaySummaries paySummaries;

    PersonService(PersonRepository people, PersonDirectory directory, OrgQueries org, StaffIdGenerator staffIds,
            PersonLogins logins, FieldMask fieldMask, SchoolContext schoolContext, EntityManager em,
            Clock clock, Files files, PersonDocumentService documentService, PaySummaries paySummaries) {
        this.paySummaries = paySummaries;
        this.files = files;
        this.documentService = documentService;
        this.people = people;
        this.directory = directory;
        this.org = org;
        this.staffIds = staffIds;
        this.logins = logins;
        this.fieldMask = fieldMask;
        this.schoolContext = schoolContext;
        this.em = em;
        this.clock = clock;
    }

    // ------------------------------------------------------------------ reading

    /**
     * The People list. {@code q} searches the names (km / en / ar), Staff ID and email; {@code dept} a department id;
     * {@code role} staff | teacher | admin; {@code status} active (default) | inactive; {@code sort} name (default),
     * staffId, startDate or role, with "-" in front for the other way round.
     */
    @Transactional(readOnly = true)
    @RequirePermission(module = PermissionModule.PEOPLE, action = PermissionAction.VIEW)
    public PageResponse<PersonSummary> list(String q, UUID dept, String role, String status, String sort, int page, int size) {
        List<Map<String, String>> errors = new ArrayList<>();
        if (page < 0 || size < 1 || size > MAX_PAGE_SIZE) {
            errors.add(error("page/size", "Range"));
        }
        boolean inactive = "inactive".equals(status);
        if (status != null && !status.isBlank() && !inactive && !"active".equals(status)) {
            errors.add(error("status", "OneOf"));
        }
        if (role != null && !role.isBlank() && !ROLES.contains(role)) {
            errors.add(error("role", "OneOf"));
        }
        Specification<Person> order = ordered(sort, errors);
        failIfAny(errors);
        if (inactive) {
            SoftDeleteFilter.includeInactive(em);   // the Deactivated tab
        }
        UUID school = schoolContext.currentSchoolId();
        Specification<Person> spec = (root, query, cb) -> cb.and(cb.equal(root.get("schoolId"), school),
                cb.equal(root.get("inactive"), inactive));
        if (dept != null) {
            spec = spec.and((root, query, cb) -> cb.equal(root.get("departmentId"), dept));
        }
        if (role != null && !role.isBlank()) {
            spec = spec.and((root, query, cb) -> cb.equal(root.get("role"), role));
        }
        if (q != null && !q.isBlank()) {
            String pattern = "%" + q.trim().toLowerCase().replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_") + "%";
            spec = spec.and((root, query, cb) -> cb.or(
                    cb.like(cb.lower(root.get("name").get("en")), pattern, '\\'),
                    cb.like(cb.lower(root.get("name").get("km")), pattern, '\\'),
                    cb.like(cb.lower(root.get("name").get("ar")), pattern, '\\'),
                    cb.like(cb.lower(root.get("staffId")), pattern, '\\'),
                    cb.like(cb.lower(root.get("email")), pattern, '\\')));
        }
        var found = people.findAll(spec.and(order), PageRequest.of(page, size));
        Labels labels = new Labels(found.getContent());
        List<UUID> ids = found.getContent().stream().map(Person::getId).toList();
        Map<UUID, Integer> expired = directory.expiredDocuments(ids, LocalDate.now(clock));
        Map<UUID, PaySummaries.PaySummary> pay = paySummaries.current(ids);   // "••••" for roles without the salary flag
        return PageResponse.of(found, p -> new PersonSummary(p.getId(), p.getStaffId(), name(p.getName()), p.getRole(),
                labels.department(p), labels.position(p), p.getPhone(), p.getEmail(), p.getEmploymentType(),
                p.getStartDate(), status(p), p.getPhotoFileId(), expired.getOrDefault(p.getId(), 0),
                pay.containsKey(p.getId()) ? pay.get(p.getId()).type() : null));
    }

    /** One person (also inactive ones). Outside the caller's record scope: not found. */
    @Transactional(readOnly = true)
    @RequirePermission(module = PermissionModule.PEOPLE, action = PermissionAction.VIEW)
    public PersonView get(UUID id) {
        return view(load(id));
    }

    /** The app's Profile screen: the caller's own person record. */
    @Transactional(readOnly = true)
    public MyProfile myProfile() {
        CurrentUser me = CurrentUser.require();
        Person p = me.personId() == null ? null : people.findById(me.personId())
                .filter(x -> x.getSchoolId().equals(me.schoolId())).orElse(null);
        if (p == null) {
            throw new ApiException(ErrorCode.NOT_FOUND);
        }
        Labels labels = new Labels(List.of(p));
        OrgLabel department = labels.department(p);
        OrgLabel position = labels.position(p);
        return new MyProfile(p.getId(), p.getStaffId(), name(p.getName()), p.getRole(), position == null ? null : position.name(),
                department == null ? null : department.name(), department == null ? null : department.color(),
                p.getPhone(), p.getEmail(), p.getStartDate(), p.getPhotoFileId());
    }

    /** The app's "change photo": the caller's own photo becomes this uploaded file (purpose PHOTO). */
    public MyProfile changeMyPhoto(PersonCommands.Photo command) {
        CurrentUser me = CurrentUser.require();
        if (!me.isApp() || me.personId() == null) {
            throw new ApiException(ErrorCode.NOT_FOUND);
        }
        Person p = people.findById(me.personId()).filter(x -> x.getSchoolId().equals(me.schoolId()) && !x.isInactive())
                .orElseThrow(() -> new ApiException(ErrorCode.NOT_FOUND));
        if (command == null || command.fileId() == null) {
            throw new ApiException(ErrorCode.VALIDATION_ERROR).with("errors", List.of(error("fileId", "NotNull")));
        }
        changePhoto(p, command.fileId());
        return myProfile();
    }

    /** Takes the new photo (attached), saves the person, then lets go of the old one (deleted after commit). */
    private void changePhoto(Person p, UUID newPhoto) {
        UUID old = p.getPhotoFileId();
        if (Objects.equals(old, newPhoto)) {
            return;
        }
        if (newPhoto != null) {
            files.attach(newPhoto, FilePurpose.PHOTO);
        }
        p.setPhotoFileId(newPhoto);
        save(p);
        if (old != null) {
            files.discard(old);
        }
    }

    // ------------------------------------------------------------------ changing

    /**
     * Add person. The Staff ID is the next number unless a Super Admin typed one. With {@code login} (wizard step 4)
     * the app login is created in the same transaction; its generated password is in the answer ({@code newLogin}),
     * once.
     */
    @RequirePermission(module = PermissionModule.PEOPLE, action = PermissionAction.CREATE)
    public PersonView create(PersonCommands.Save command) {
        UUID school = schoolContext.currentSchoolId();
        Person p = new Person();
        List<Map<String, String>> errors = new ArrayList<>();
        String typedId = cleanStaffId(command.staffId(), errors);
        apply(p, command, errors, true);
        failIfAny(errors);
        if (typedId != null) {
            requireSuperAdmin();
            if (directory.staffIdTaken(school, typedId, null)) {
                throw new ApiException(ErrorCode.STAFF_ID_TAKEN);
            }
            p.setStaffId(typedId);
        } else {
            p.setStaffId(staffIds.next(StaffRole.valueOf(p.getRole().toUpperCase()),
                    candidate -> directory.staffIdTaken(school, candidate, null)));
        }
        checkUnique(school, p, null);
        if (command.photoFileId() != null) {
            files.attach(command.photoFileId(), FilePurpose.PHOTO);
            p.setPhotoFileId(command.photoFileId());
        }
        p = save(p);
        if (command.login() == null) {
            return view(p);
        }
        PersonCommands.Login login = command.login();
        PersonLogins.Credentials credentials = logins.create(p.getId(), new PersonLogins.NewLogin(login.username(),
                login.password(), !Boolean.FALSE.equals(login.mustChangePassword()), p.getRole(), p.getPhone(),
                p.getName().getEn(), p.getStaffId(), true));
        return view(p).withNewLogin(given(p, credentials));
    }

    /**
     * "Give login": a person without a login gets one (username typed or made from the name), a person with one a new
     * password; typed by the admin or generated (returned once). Must be changed at the next login unless the admin
     * typed it and said otherwise. Nothing is sent by email or SMS: the admin hands it over.
     */
    @RequirePermission(module = PermissionModule.PEOPLE, action = PermissionAction.EDIT)
    public LoginSent sendLogin(UUID id, PersonCommands.SendLogin command) {
        Person p = loadActive(id);
        String password = command == null ? null : command.password();
        boolean mustChange = command == null || !Boolean.FALSE.equals(command.mustChangePassword());
        PersonLogins.Credentials credentials = logins.exists(p.getId()) ? logins.setPassword(p.getId(), password, mustChange)
                : logins.create(p.getId(), new PersonLogins.NewLogin(command == null ? null : command.username(), password, mustChange,
                        p.getRole(), p.getPhone(), p.getName().getEn(), p.getStaffId(), true));
        return given(p, credentials);
    }

    /**
     * "Reset password": the admin types a new password or leaves it empty for a generated one (returned once); it must
     * be changed at the next login unless the admin typed it and said otherwise. The person is signed out everywhere and
     * no longer locked after wrong passwords. {@code NO_LOGIN} without a login.
     */
    @RequirePermission(module = PermissionModule.PEOPLE, action = PermissionAction.EDIT)
    public LoginSent resetPassword(UUID id, PersonCommands.ResetPassword command) {
        Person p = loadActive(id);
        boolean mustChange = command == null || !Boolean.FALSE.equals(command.mustChangePassword());
        return given(p, logins.setPassword(p.getId(), command == null ? null : command.password(), mustChange));
    }

    private LoginSent given(Person p, PersonLogins.Credentials credentials) {
        boolean mustChange = logins.info(p.getId()).map(PersonLogins.LoginInfo::mustChangePassword).orElse(true);
        return new LoginSent(credentials.username(), credentials.temporaryPassword(), mustChange);
    }

    private Person loadActive(UUID id) {
        Person p = load(id);
        if (p.isInactive()) {
            throw new ApiException(ErrorCode.CONFLICT);
        }
        return p;
    }

    /** Edit person: the whole form, with the {@code version} that was read. */
    @RequirePermission(module = PermissionModule.PEOPLE, action = PermissionAction.EDIT)
    public PersonView update(UUID id, PersonCommands.Save command) {
        Person p = load(id);
        if (p.isInactive()) {
            throw new ApiException(ErrorCode.CONFLICT);   // restore first
        }
        if (command.version() == null) {
            failIfAny(List.of(error("version", "NotNull")));
        }
        if (command.version() != p.getVersion()) {
            throw new ApiException(ErrorCode.VERSION_CONFLICT);
        }
        List<Map<String, String>> errors = new ArrayList<>();
        String typedId = cleanStaffId(command.staffId(), errors);
        String roleBefore = p.getRole();
        String phoneBefore = p.getPhone();
        apply(p, command, errors, false);
        failIfAny(errors);
        if (!Objects.equals(roleBefore, p.getRole()) || !Objects.equals(phoneBefore, p.getPhone())) {
            logins.update(p.getId(), p.getRole(), p.getPhone());   // the token's role follows at the next refresh
        }
        if (typedId != null && !typedId.equalsIgnoreCase(p.getStaffId())) {
            requireSuperAdmin();   // "Edit ID": it can break printed cards and old reports
            if (directory.staffIdTaken(p.getSchoolId(), typedId, p.getId())) {
                throw new ApiException(ErrorCode.STAFF_ID_TAKEN);
            }
            p.setStaffId(typedId);
        }
        checkUnique(p.getSchoolId(), p, p.getId());
        p = save(p);
        changePhoto(p, command.photoFileId());
        return view(p);
    }

    /** Move to inactive: hidden from lists, cannot log in, history kept. Repeating it changes nothing. */
    @RequirePermission(module = PermissionModule.PEOPLE, action = PermissionAction.DELETE)
    public void inactivate(UUID id, PersonCommands.Inactivate command) {
        Person p = load(id);
        if (p.isInactive()) {
            return;
        }
        if (p.getId().equals(CurrentUser.require().personId())) {
            throw new ApiException(ErrorCode.OWN_ACCOUNT);
        }
        List<Map<String, String>> errors = new ArrayList<>();
        String reason = command == null || command.reason() == null ? null : command.reason().trim().toUpperCase();
        if (reason == null || reason.isEmpty()) {
            errors.add(error("reason", "NotBlank"));
        } else if (!REASONS.contains(reason)) {
            errors.add(error("reason", "OneOf"));
        }
        LocalDate lastDay = command == null || command.lastWorkingDay() == null ? LocalDate.now(clock) : command.lastWorkingDay();
        if (p.getStartDate() != null && lastDay.isBefore(p.getStartDate())) {
            errors.add(error("lastWorkingDay", "BeforeStart"));
        }
        failIfAny(errors);
        p.inactivate(CurrentUser.require().userId(), clock.instant(), reason, lastDay);
        save(p);
        logins.disable(p.getId());
    }

    /** Back to the active lists; {@code allowLogin} also lets them sign in to the app again. */
    @RequirePermission(module = PermissionModule.PEOPLE, action = PermissionAction.EDIT)
    public PersonView restore(UUID id, PersonCommands.Restore command) {
        Person p = load(id);
        if (p.isInactive()) {
            p.restore();
            p = save(p);
            if (command != null && Boolean.TRUE.equals(command.allowLogin())) {
                logins.enable(p.getId());
            }
        }
        return view(p);
    }

    /**
     * Removes an inactive person for good: Super Admin only, and only without history (records that refer to the
     * person) and without a login account ({@code IN_USE}).
     */
    @RequirePermission(module = PermissionModule.PEOPLE, action = PermissionAction.DELETE)
    public void deleteForever(UUID id) {
        requireSuperAdmin();
        Person p = load(id);
        if (!p.isInactive()) {
            throw new ApiException(ErrorCode.NOT_INACTIVE);
        }
        if (logins.exists(p.getId())) {
            throw new ApiException(ErrorCode.IN_USE);
        }
        try {
            people.delete(p);
            people.flush();
        } catch (DataIntegrityViolationException stillReferenced) {
            throw new ApiException(ErrorCode.IN_USE);   // supervised people, a linked web user, history...
        }
    }

    // ------------------------------------------------------------------ the form

    /** Checks and copies the form into the person; problems are added to {@code errors}. */
    private void apply(Person p, PersonCommands.Save c, List<Map<String, String>> errors, boolean isNew) {
        LocalizedName in = c.name();
        String km = text(in == null ? null : in.km(), 150, "name.km", errors);
        String en = text(in == null ? null : in.en(), 150, "name.en", errors);
        String ar = text(in == null ? null : in.ar(), 150, "name.ar", errors);
        if (km == null) {
            errors.add(error("name.km", "NotBlank"));
        }
        if (en == null) {
            errors.add(error("name.en", "NotBlank"));
        }
        p.setName(new LocalizedText(km, en, ar));
        p.setGender(code(c.gender(), GENDERS, "gender", errors));
        if (c.dateOfBirth() != null && (c.dateOfBirth().isAfter(LocalDate.now(clock)) || c.dateOfBirth().getYear() < 1900)) {
            errors.add(error("dateOfBirth", "Range"));
        }
        p.setDateOfBirth(c.dateOfBirth());
        String nationality = blank(c.nationality()) ? null : c.nationality().trim().toUpperCase();
        if (nationality != null && !COUNTRIES.contains(nationality)) {
            errors.add(error("nationality", "Country"));
        }
        p.setNationality(nationality);
        p.setNationalId(sensitive(SensitiveField.NATIONAL_ID, c.nationalId(), NATIONAL_ID, "nationalId", errors, true, p.getNationalId()));
        p.setMaritalStatus(code(c.maritalStatus(), MARITAL, "maritalStatus", errors));
        String blood = blank(c.bloodType()) ? null : c.bloodType().trim().toUpperCase();
        if (blood != null && !BLOOD.contains(blood)) {
            errors.add(error("bloodType", "OneOf"));
        }
        p.setBloodType(blood);
        p.setPhone(sensitive(SensitiveField.PHONE, c.phone(), PHONE, "phone", errors, false, p.getPhone()));
        p.setPhoneAlt(sensitive(SensitiveField.PHONE, c.phoneAlt(), PHONE, "phoneAlt", errors, false, p.getPhoneAlt()));
        String email = text(c.email(), 150, "email", errors);
        if (email != null && !EMAIL.matcher(email).matches()) {
            errors.add(error("email", "Email"));
        }
        p.setEmail(email == null ? null : email.toLowerCase());
        PersonCommands.Address a = c.address() == null ? new PersonCommands.Address(null, null, null, null, null) : c.address();
        p.setAddress(text(a.province(), 100, "address.province", errors), text(a.district(), 100, "address.district", errors),
                text(a.commune(), 100, "address.commune", errors), text(a.village(), 100, "address.village", errors),
                text(a.street(), 200, "address.street", errors));
        PersonCommands.EmergencyContact e = c.emergencyContact() == null ? new PersonCommands.EmergencyContact(null, null, null)
                : c.emergencyContact();
        p.setEmergencyContact(text(e.name(), 150, "emergencyContact.name", errors),
                text(e.relation(), 50, "emergencyContact.relation", errors),
                sensitive(SensitiveField.PHONE, e.phone(), PHONE, "emergencyContact.phone", errors, false, p.getEmergencyPhone()));

        String role = code(c.role(), ROLES, "role", errors);
        if (role == null && !errors.stream().anyMatch(x -> x.get("field").equals("role"))) {
            errors.add(error("role", "NotNull"));
        }
        p.setRole(role);
        p.setDepartmentId(orgItem(OrgKind.DEPARTMENT, c.departmentId(), isNew ? null : p.getDepartmentId(), "departmentId", errors));
        p.setPositionId(orgItem(OrgKind.POSITION, c.positionId(), isNew ? null : p.getPositionId(), "positionId", errors));
        p.setEmploymentType(code(c.employmentType(), EMPLOYMENT, "employmentType", errors));
        p.setStartDate(c.startDate());
        if (c.startDate() != null && c.contractEndDate() != null && c.contractEndDate().isBefore(c.startDate())) {
            errors.add(error("contractEndDate", "BeforeStart"));
        }
        if (c.startDate() != null && c.probationEndDate() != null && c.probationEndDate().isBefore(c.startDate())) {
            errors.add(error("probationEndDate", "BeforeStart"));
        }
        p.setContractEndDate(c.contractEndDate());
        p.setProbationEndDate(c.probationEndDate());
        p.setSupervisorId(supervisor(p, c.supervisorId(), errors));
    }

    /** The picked item must be active, unless it is the one already on the record. */
    private UUID orgItem(OrgKind kind, UUID picked, UUID current, String field, List<Map<String, String>> errors) {
        if (picked != null && !picked.equals(current) && !org.isActive(kind, picked)) {
            errors.add(error(field, kind == OrgKind.DEPARTMENT ? "UnknownDepartment" : "UnknownPosition"));
        }
        return picked;
    }

    /** Another active person of the school; following the supervisors up never comes back to this person. */
    private UUID supervisor(Person p, UUID picked, List<Map<String, String>> errors) {
        if (picked == null) {
            return null;
        }
        if (picked.equals(p.getId())) {
            errors.add(error("supervisorId", "Self"));
            return picked;
        }
        if (!picked.equals(p.getSupervisorId()) && !directory.isActive(schoolContext.currentSchoolId(), picked)) {
            errors.add(error("supervisorId", "UnknownPerson"));
            return picked;
        }
        if (p.getId() != null) {
            Set<UUID> seen = new HashSet<>();
            for (UUID next = picked; next != null && seen.add(next); next = directory.supervisorOf(next)) {
                if (next.equals(p.getId())) {
                    errors.add(error("supervisorId", "Loop"));
                    break;
                }
            }
        }
        return picked;
    }

    private void checkUnique(UUID school, Person p, UUID exceptId) {
        if (p.getNationalId() != null && directory.nationalIdTaken(school, p.getNationalId(), exceptId)) {
            throw new ApiException(ErrorCode.NATIONAL_ID_TAKEN);
        }
    }

    private static void requireSuperAdmin() {
        if (!SUPER_ADMIN.equals(CurrentUser.require().role())) {
            throw new ApiException(ErrorCode.FORBIDDEN);
        }
    }

    private static String cleanStaffId(String value, List<Map<String, String>> errors) {
        if (blank(value)) {
            return null;
        }
        String cleaned = value.trim().toUpperCase();
        if (!STAFF_ID.matcher(cleaned).matches()) {
            errors.add(error("staffId", "Pattern"));
        }
        return cleaned;
    }

    // ------------------------------------------------------------------ helpers

    private Person load(UUID id) {
        UUID school = schoolContext.currentSchoolId();
        return people.findById(id).filter(p -> p.getSchoolId().equals(school))
                .orElseThrow(() -> new ApiException(ErrorCode.NOT_FOUND));
    }

    private Person save(Person p) {
        try {
            return people.saveAndFlush(p);
        } catch (DataIntegrityViolationException e) {
            // a parallel request took the same Staff ID or national ID first
            String message = String.valueOf(e.getMostSpecificCause().getMessage());
            throw new ApiException(message.contains("person_national_id_idx") ? ErrorCode.NATIONAL_ID_TAKEN : ErrorCode.STAFF_ID_TAKEN);
        }
    }

    /**
     * The order of the list, written into the query (Criteria queries cannot say "nulls last"): people without a value
     * (no start date yet) come last whichever way round, then by name. Not applied to the count query.
     */
    private static Specification<Person> ordered(String sort, List<Map<String, String>> errors) {
        String key = blank(sort) ? "name" : sort.trim();
        boolean desc = key.startsWith("-");
        String field = desc ? key.substring(1) : key;
        if (!Set.of("name", "staffId", "startDate", "role").contains(field)) {
            errors.add(error("sort", "OneOf"));
        }
        return (root, query, cb) -> {
            Class<?> result = query.getResultType();
            if (result != Long.class && result != long.class) {
                jakarta.persistence.criteria.Expression<?> value = switch (field) {
                    case "staffId" -> cb.lower(root.get("staffId"));
                    case "startDate" -> root.get("startDate");
                    case "role" -> root.get("role");
                    default -> cb.lower(root.get("name").get("en"));
                };
                query.orderBy(cb.asc(cb.selectCase().when(cb.isNull(value), 1).otherwise(0)),
                        desc ? cb.desc(value) : cb.asc(value),
                        cb.asc(cb.lower(root.get("name").get("en"))), cb.asc(root.get("id")));
            }
            return null;
        };
    }

    private static String text(String value, int max, String field, List<Map<String, String>> errors) {
        if (blank(value)) {
            return null;
        }
        String cleaned = value.trim().replaceAll("\\s+", " ");
        if (cleaned.length() > max) {
            errors.add(error(field, "Size"));
        }
        return cleaned;
    }

    private static String code(String value, Set<String> allowed, String field, List<Map<String, String>> errors) {
        if (blank(value)) {
            return null;
        }
        String cleaned = value.trim().toLowerCase();
        if (!allowed.contains(cleaned)) {
            errors.add(error(field, "OneOf"));
        }
        return cleaned;
    }

    /**
     * A sensitive value: a caller who may not see it got "••••", so what they send is ignored and the stored value is
     * kept; otherwise it is checked like any value (the mask itself is not a valid value).
     */
    private String sensitive(SensitiveField group, String value, Pattern shape, String field, List<Map<String, String>> errors,
            boolean upper, String stored) {
        return fieldMask.allows(group) ? pattern(value, shape, field, errors, upper) : stored;
    }

    /** A value with a fixed shape. */
    private static String pattern(String value, Pattern shape, String field, List<Map<String, String>> errors, boolean upper) {
        if (blank(value)) {
            return null;
        }
        String cleaned = upper ? value.trim().toUpperCase() : value.trim();
        if (!shape.matcher(cleaned).matches()) {
            errors.add(error(field, "Pattern"));
        }
        return cleaned;
    }

    private static boolean blank(String value) {
        return value == null || value.isBlank();
    }

    private static Map<String, String> error(String field, String rule) {
        return Map.of("field", field, "rule", rule);
    }

    private static void failIfAny(List<Map<String, String>> errors) {
        if (!errors.isEmpty()) {
            throw new ApiException(ErrorCode.VALIDATION_ERROR).with("errors", errors);
        }
    }

    private static LocalizedName name(LocalizedText t) {
        return t == null ? null : new LocalizedName(t.getKm(), t.getEn(), t.getAr());
    }

    private static String status(Person p) {
        return p.isInactive() ? PersonView.INACTIVE : PersonView.ACTIVE;
    }

    private PersonView view(Person p) {
        Labels labels = new Labels(List.of(p));
        PersonView.PersonRefView supervisor = null;
        if (p.getSupervisorId() != null) {
            PersonRef ref = directory.refs(List.of(p.getSupervisorId())).get(p.getSupervisorId());
            if (ref != null) {
                supervisor = new PersonView.PersonRefView(ref.id(), ref.staffId(),
                        new LocalizedName(ref.nameKm(), ref.nameEn(), ref.nameAr()), ref.inactive());
            }
        }
        var login = logins.info(p.getId());
        List<DocumentView> docs = p.getId() == null ? List.of() : documentService.viewsOf(p.getId());
        boolean anyEmergency = p.getEmergencyName() != null || p.getEmergencyRelation() != null || p.getEmergencyPhone() != null;
        boolean anyAddress = Arrays.asList(p.getAddressProvince(), p.getAddressDistrict(), p.getAddressCommune(),
                p.getAddressVillage(), p.getAddressStreet()).stream().anyMatch(Objects::nonNull);
        return new PersonView(p.getId(), p.getStaffId(), name(p.getName()), p.getGender(), p.getDateOfBirth(),
                p.getNationality(), p.getNationalId(), p.getMaritalStatus(), p.getBloodType(), p.getPhone(), p.getPhoneAlt(),
                p.getEmail(), anyAddress ? new PersonCommands.Address(p.getAddressProvince(), p.getAddressDistrict(),
                        p.getAddressCommune(), p.getAddressVillage(), p.getAddressStreet()) : null,
                anyEmergency ? new PersonView.Emergency(p.getEmergencyName(), p.getEmergencyRelation(), p.getEmergencyPhone()) : null,
                p.getRole(), labels.department(p), labels.position(p), p.getEmploymentType(), p.getStartDate(),
                p.getContractEndDate(), p.getProbationEndDate(), supervisor,
                status(p), p.isInactive() ? new PersonView.Inactivation(p.getDeletedAt(), p.getDeletedBy(), p.getDeleteReason(),
                        p.getLastWorkingDay()) : null,
                login.isPresent(), login.map(l -> new PersonView.Login(l.username(), l.active(), l.mustChangePassword(),
                        l.lastLoginAt())).orElse(null), null,
                p.getPhotoFileId(), files.find(p.getPhotoFileId()).orElse(null), docs,
                (int) docs.stream().filter(DocumentView::expired).count(),
                p.getId() == null ? null : paySummaries.current(List.of(p.getId())).get(p.getId()),
                p.getVersion(), p.getCreatedAt(), p.getUpdatedAt());
    }

    /** Department and position names for a page of people, looked up once. */
    private final class Labels {

        private final Map<UUID, OrgLabel> departments;
        private final Map<UUID, OrgLabel> positions;

        Labels(List<Person> page) {
            departments = org.labels(OrgKind.DEPARTMENT, page.stream().map(Person::getDepartmentId).filter(Objects::nonNull).toList());
            positions = org.labels(OrgKind.POSITION, page.stream().map(Person::getPositionId).filter(Objects::nonNull).toList());
        }

        OrgLabel department(Person p) {
            return p.getDepartmentId() == null ? null : departments.get(p.getDepartmentId());
        }

        OrgLabel position(Person p) {
            return p.getPositionId() == null ? null : positions.get(p.getPositionId());
        }
    }
}
