package kh.cmt.people.application;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import kh.cmt.files.api.FileRef;
import kh.cmt.org.api.OrgLabel;
import kh.cmt.shared.people.PaySummaries;
import kh.cmt.shared.i18n.LocalizedName;
import kh.cmt.shared.security.Sensitive;
import kh.cmt.shared.security.SensitiveField;

/**
 * A person as the Admin Web shows it (profile, Edit person).
 * <ul>
 *   <li>codes: {@code role} staff | teacher | admin; {@code gender} female | male; {@code maritalStatus} single |
 *       married | divorced | widowed; {@code bloodType} A+ … O-; {@code employmentType} full_time | part_time |
 *       contract | volunteer | intern; {@code nationality} ISO country code (KH)</li>
 *   <li>{@code department}, {@code position}: id and name, also when inactive</li>
 *   <li>sensitive: {@code nationalId} (national ID &amp; documents), the phone numbers (phone numbers) — "••••" when
 *       the caller's role may not see them</li>
 *   <li>{@code status} ACTIVE | INACTIVE; for inactive people {@code inactivation} says who, when, why and the last
 *       working day</li>
 *   <li>{@code hasLogin} / {@code login}: the app login account — username, active, must change password, last login</li>
 *   <li>{@code newLogin}: only in the answer to Add person with a login: the username and the generated temporary
 *       password, shown once</li>
 *   <li>{@code photoFileId} / {@code photo}: the photo (open it, or its thumbnail, with {@code GET /api/v1/files/{id}/url})</li>
 *   <li>{@code documents}: the documents (see {@link DocumentView}); {@code expiredDocuments}: how many have expired</li>
 *   <li>{@code pay}: the pay in force today {type, amount, currency} — "••••" without "See salary &amp; bank details";
 *       the history is {@code GET /admin/people/{id}/pay}</li>
 * </ul>
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record PersonView(UUID id, String staffId, LocalizedName name, String gender, LocalDate dateOfBirth,
        String nationality, @Sensitive(SensitiveField.NATIONAL_ID) String nationalId, String maritalStatus,
        String bloodType, @Sensitive(SensitiveField.PHONE) String phone, @Sensitive(SensitiveField.PHONE) String phoneAlt,
        String email, PersonCommands.Address address, Emergency emergencyContact, String role, OrgLabel department,
        OrgLabel position, String employmentType, LocalDate startDate, LocalDate contractEndDate,
        LocalDate probationEndDate, PersonRefView supervisor, String status, Inactivation inactivation, boolean hasLogin, Login login, LoginSent newLogin, UUID photoFileId,
        FileRef photo, List<DocumentView> documents, int expiredDocuments,
        @Sensitive(SensitiveField.SALARY_BANK) PaySummaries.PaySummary pay, long version, Instant createdAt, Instant updatedAt) {

    /** The app login: username, may sign in, must change the password at the next login, last login. */
    public record Login(String username, boolean active, boolean mustChangePassword, Instant lastLoginAt) {
    }

    PersonView withNewLogin(LoginSent sent) {
        return new PersonView(id, staffId, name, gender, dateOfBirth, nationality, nationalId, maritalStatus, bloodType,
                phone, phoneAlt, email, address, emergencyContact, role, department, position, employmentType, startDate,
                contractEndDate, probationEndDate, supervisor, status, inactivation, hasLogin, login,
                sent, photoFileId, photo, documents, expiredDocuments, pay, version, createdAt, updatedAt);
    }

    public static final String ACTIVE = "ACTIVE";
    public static final String INACTIVE = "INACTIVE";

    /** The emergency contact; the phone is a sensitive field. */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record Emergency(String name, String relation, @Sensitive(SensitiveField.PHONE) String phone) {
    }

    /** Another person (the supervisor): id, Staff ID and name. */
    public record PersonRefView(UUID id, String staffId, LocalizedName name, boolean inactive) {
    }

    /** Who moved the person to inactive, when, why (reason code) and their last working day. */
    public record Inactivation(Instant at, UUID by, String reason, LocalDate lastWorkingDay) {
    }
}
