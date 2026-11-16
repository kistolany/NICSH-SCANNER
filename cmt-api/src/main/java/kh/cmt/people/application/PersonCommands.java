package kh.cmt.people.application;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import kh.cmt.shared.i18n.LocalizedName;

/** What the Admin Web sends for people (Add person, Edit person, Move to inactive, Restore). */
public final class PersonCommands {

    private PersonCommands() {
    }

    /**
     * The whole person form (steps 1 "Personal info" and 2 "Work" of Add person; schedule, pay and login have their own
     * steps). {@code staffId}: leave out for the next number; typing one needs the Super Admin role. Codes are lower
     * case (see the view). {@code photoFileId}: an uploaded, confirmed photo (purpose PHOTO); send back the one that was
     * read to keep it, null removes it. {@code version} when editing.
     */
    public record Save(String staffId, LocalizedName name, String gender, LocalDate dateOfBirth, String nationality,
            String nationalId, String maritalStatus, String bloodType, String phone, String phoneAlt, String email,
            Address address, EmergencyContact emergencyContact, String role, UUID departmentId, UUID positionId,
            String employmentType, LocalDate startDate, LocalDate contractEndDate, LocalDate probationEndDate,
            UUID supervisorId, UUID photoFileId, Login login, Long version) {
    }

    /**
     * Add person › Login (only when creating; later through "Give login"): {@code username} (made from the name when
     * left out), {@code password} (generated when left out, then shown once), {@code mustChangePassword} (on when left
     * out). Nothing is sent by email or SMS: the admin hands the login over.
     */
    public record Login(String username, String password, Boolean mustChangePassword) {
    }

    /**
     * "Give login": creates the login if there is none (username optional), or sets a new password; {@code password}
     * empty = generated; {@code mustChangePassword} on unless false.
     */
    public record SendLogin(String username, String password, Boolean mustChangePassword) {
    }

    /** "Reset password": {@code password} typed by the admin, empty = generated; must be changed unless false. */
    public record ResetPassword(String password, Boolean mustChangePassword) {
    }

    public record Address(String province, String district, String commune, String village, String street) {
    }

    public record EmergencyContact(String name, String relation, String phone) {
    }

    /**
     * Move to inactive: {@code reason} RESIGNED | CONTRACT_ENDED | TRANSFERRED | MISTAKE | OTHER (required),
     * {@code lastWorkingDay} (today when left out).
     */
    public record Inactivate(String reason, LocalDate lastWorkingDay) {
    }

    /** A document: {@code type} (see DocumentView), the uploaded file, issue and expiry dates, a note. */
    public record AddDocument(String type, UUID fileId, LocalDate issueDate, LocalDate expiryDate, String note) {
    }

    /** The app's "change photo": an uploaded, confirmed file (purpose PHOTO). */
    public record Photo(UUID fileId) {
    }

    /** Restore: {@code allowLogin} = the person may sign in to the app again (off when left out). */
    public record Restore(Boolean allowLogin) {
    }
}
