package kh.cmt.people.application;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.time.LocalDate;
import java.util.UUID;
import kh.cmt.org.api.OrgLabel;
import kh.cmt.shared.i18n.LocalizedName;
import kh.cmt.shared.security.Sensitive;
import kh.cmt.shared.security.SensitiveField;

/**
 * One row of the People list. Same codes as {@link PersonView}; the phone is a sensitive field. {@code photoFileId}
 * for the avatar (thumbnail), {@code expiredDocuments} to flag people with expired documents, {@code payType} the pay
 * type in force (monthly | hourly | none; "••••" without "See salary &amp; bank details").
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record PersonSummary(UUID id, String staffId, LocalizedName name, String role, OrgLabel department,
        OrgLabel position, @Sensitive(SensitiveField.PHONE) String phone, String email, String employmentType,
        LocalDate startDate, String status, UUID photoFileId, int expiredDocuments,
        @Sensitive(SensitiveField.SALARY_BANK) String payType) {
}
