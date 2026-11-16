package kh.cmt.people.application;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.time.LocalDate;
import java.util.UUID;
import kh.cmt.shared.i18n.LocalizedName;

/**
 * The app's Profile screen: photo, name (all three scripts as available), Staff ID, role, position, department,
 * contact. The photo is opened with {@code GET /api/v1/files/{photoFileId}/url}. The schedule comes with schedules
 * (phase 5), the device with devices (6.3).
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record MyProfile(UUID id, String staffId, LocalizedName name, String role, LocalizedName position, LocalizedName department,
        String departmentColor, String phone, String email, LocalDate startDate, UUID photoFileId) {
}
