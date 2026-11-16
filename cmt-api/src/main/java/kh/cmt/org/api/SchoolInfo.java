package kh.cmt.org.api;

import com.fasterxml.jackson.annotation.JsonInclude;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.util.List;
import java.util.UUID;
import kh.cmt.shared.i18n.LocalizedName;

/**
 * Settings, School section. {@code workingDays} are ISO weekdays (1 = Monday ... 7 = Sunday). {@code timezone} is
 * an IANA name such as Asia/Phnom_Penh. {@code logoFileId} points at an uploaded file (upload comes with step 4.3).
 * {@code supportPhone} / {@code supportEmail}: who staff contact for help (the app's login screen shows them).
 * {@code version} is the revision of this section: send back the one you read when saving.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record SchoolInfo(@NotNull @Valid LocalizedName name, @Size(max = 300) String address,
        @NotNull List<Integer> workingDays, @NotNull String timezone, UUID logoFileId, @Size(max = 30) String supportPhone,
        @Size(max = 150) String supportEmail, Long version) {
}
