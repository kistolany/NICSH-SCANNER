package kh.cmt.shared.web;

import org.springframework.http.HttpStatus;

/**
 * Stable error codes. The apps turn a code into text (km / en / ar); the API never sends display text.
 * Never rename a code once the apps use it.
 */
public enum ErrorCode {

    // generic
    VALIDATION_ERROR(HttpStatus.BAD_REQUEST),
    BAD_REQUEST(HttpStatus.BAD_REQUEST),
    UNAUTHORIZED(HttpStatus.UNAUTHORIZED),
    FORBIDDEN(HttpStatus.FORBIDDEN),
    NOT_FOUND(HttpStatus.NOT_FOUND),
    METHOD_NOT_ALLOWED(HttpStatus.METHOD_NOT_ALLOWED),
    CONFLICT(HttpStatus.CONFLICT),
    VERSION_CONFLICT(HttpStatus.CONFLICT),
    NAME_TAKEN(HttpStatus.CONFLICT),          // an active item with this name already exists
    IN_USE(HttpStatus.CONFLICT),              // other records still refer to it
    NOT_INACTIVE(HttpStatus.CONFLICT),        // the action needs the item to be inactive first
    IN_THE_PAST(HttpStatus.CONFLICT),         // it has started or passed: what applied to past days stays as it was
    ROLE_LOCKED(HttpStatus.CONFLICT),         // system roles cannot be changed or deleted
    OWN_ROLE(HttpStatus.FORBIDDEN),           // you cannot change or delete the role you hold yourself
    OWN_ACCOUNT(HttpStatus.FORBIDDEN),        // you cannot change your own role, or deactivate / sign out yourself
    LAST_SUPER_ADMIN(HttpStatus.CONFLICT),    // at least one active Super Admin must remain
    USERNAME_TAKEN(HttpStatus.CONFLICT),      // another login already uses this username
    PERSON_TAKEN(HttpStatus.CONFLICT),        // the person is already linked to another active web user
    STAFF_ID_TAKEN(HttpStatus.CONFLICT),      // another person (active or not) has this Staff ID
    NATIONAL_ID_TAKEN(HttpStatus.CONFLICT),   // another person of the school has this national ID number
    PASSWORD_POLICY(HttpStatus.BAD_REQUEST),  // the password breaks the school's rules (+ violations)
    NO_LOGIN(HttpStatus.CONFLICT),            // the person has no login account yet (send the login first)

    // files
    FILE_TOO_LARGE(HttpStatus.BAD_REQUEST),   // over the size limit of its kind (+ maxBytes)
    FILE_TYPE(HttpStatus.BAD_REQUEST),        // not an allowed type, or the content is not what it claims to be
    FILE_INVALID(HttpStatus.BAD_REQUEST),     // the content cannot be read (broken image)
    FILE_NOT_UPLOADED(HttpStatus.CONFLICT),   // confirmed before the upload was finished
    FILE_NOT_READY(HttpStatus.CONFLICT),      // used before it was confirmed, or for the wrong purpose
    FILE_IN_USE(HttpStatus.CONFLICT),         // already used by another record

    // import
    IMPORT_FILE(HttpStatus.BAD_REQUEST),      // not an Excel (.xlsx) file, unreadable, or too large
    IMPORT_TEMPLATE(HttpStatus.BAD_REQUEST),  // required columns missing (+ missing): use the template
    IMPORT_TOO_MANY_ROWS(HttpStatus.BAD_REQUEST), // more rows than one import takes (+ maxRows)
    RATE_LIMITED(HttpStatus.TOO_MANY_REQUESTS),
    INTERNAL_ERROR(HttpStatus.INTERNAL_SERVER_ERROR),

    // device
    DEVICE_MISMATCH(HttpStatus.FORBIDDEN),
    DEVICE_BLOCKED(HttpStatus.FORBIDDEN),
    UPDATE_REQUIRED(HttpStatus.UPGRADE_REQUIRED),   // older than the school's minimum app version (+ minVersion)
    SIGNATURE_INVALID(HttpStatus.FORBIDDEN),         // the scan is not signed by the linked phone's key

    // scan
    QR_INVALID(HttpStatus.UNPROCESSABLE_ENTITY),
    QR_EXPIRED(HttpStatus.UNPROCESSABLE_ENTITY),
    OUTSIDE_AREA(HttpStatus.UNPROCESSABLE_ENTITY),
    LOCATION_OFF(HttpStatus.UNPROCESSABLE_ENTITY),
    MOCK_LOCATION(HttpStatus.UNPROCESSABLE_ENTITY),   // the phone reports a fake GPS position (school setting)
    ROOTED_DEVICE(HttpStatus.UNPROCESSABLE_ENTITY),   // rooted / jailbroken phone (school setting)
    NO_SHIFT_TODAY(HttpStatus.UNPROCESSABLE_ENTITY),
    TOO_EARLY(HttpStatus.UNPROCESSABLE_ENTITY),       // check-in is not open yet for the next shift (+ shiftIdx, opensAt, shiftStart)
    ALREADY_CHECKED_IN(HttpStatus.CONFLICT),
    ALREADY_CHECKED_OUT(HttpStatus.CONFLICT),
    EARLY_REASON_REQUIRED(HttpStatus.UNPROCESSABLE_ENTITY),
    NOT_LATE(HttpStatus.CONFLICT),                    // a late reason for a check-in that was not late
    NOT_A_WORKING_DAY(HttpStatus.UNPROCESSABLE_ENTITY), // a correction for a shift the person did not have that day (+ kind)

    // approvals
    ALREADY_DECIDED(HttpStatus.CONFLICT),             // the request was already approved / rejected (+ decision)
    APPROVAL_LIMIT(HttpStatus.FORBIDDEN),             // above the approver role's limits (+ reason, escalateTo)

    // leave
    NOT_ENOUGH_BALANCE(HttpStatus.UNPROCESSABLE_ENTITY), // more days than are left, counting waiting requests (+ left, days)
    LEAVE_OVERLAP(HttpStatus.CONFLICT),               // another waiting or approved request covers some of the days (+ requestId)
    NO_WORKING_DAYS(HttpStatus.UNPROCESSABLE_ENTITY); // the dates hold no working day of the person (days off, holidays)

    private final HttpStatus status;

    ErrorCode(HttpStatus status) {
        this.status = status;
    }

    public HttpStatus status() {
        return status;
    }

    /** Problem {@code type} URI, e.g. {@code urn:cmt:error:OUTSIDE_AREA}. */
    public String type() {
        return "urn:cmt:error:" + name();
    }
}
