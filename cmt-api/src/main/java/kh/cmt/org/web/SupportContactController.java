package kh.cmt.org.web;

import java.time.Duration;
import kh.cmt.org.api.SchoolInfo;
import kh.cmt.org.application.SettingsService;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * {@code GET /api/v1/public/support} — no sign-in: the school's contact for the app's login screen ("Help", "Contact
 * admin", "Forgot password"). {@code {phone, email}}, either may be null (Settings › School). The school is the
 * configured one (there is no caller yet to tell it).
 */
@RestController
class SupportContactController {

    record SupportContact(String phone, String email) {
    }

    private final SettingsService settings;

    SupportContactController(SettingsService settings) {
        this.settings = settings;
    }

    @GetMapping("/api/v1/public/support")
    ResponseEntity<SupportContact> support() {
        SchoolInfo school = settings.school();
        return ResponseEntity.ok().cacheControl(CacheControl.maxAge(Duration.ofMinutes(10)).cachePublic())
                .body(new SupportContact(school.supportPhone(), school.supportEmail()));
    }
}
