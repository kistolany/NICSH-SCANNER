package kh.cmt.org.web;

import java.time.Duration;
import kh.cmt.org.api.PublicSettings;
import kh.cmt.org.application.SettingsService;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Mobile app: the school settings it needs (grace minutes, whether manual / offline check-in is allowed, whether
 * an early check-out needs a reason, reminder timings, default language and theme).
 */
@RestController
@RequestMapping("/api/v1/app/settings")
class PublicSettingsController {

    private final SettingsService service;

    PublicSettingsController(SettingsService service) {
        this.service = service;
    }

    /** May be kept by the app for a minute, so a change made in the Admin Web reaches phones quickly. */
    @GetMapping("/public")
    ResponseEntity<PublicSettings> publicSettings() {
        return ResponseEntity.ok()
                .cacheControl(CacheControl.maxAge(Duration.ofMinutes(1)).cachePrivate())
                .body(service.publicSettings());
    }
}
