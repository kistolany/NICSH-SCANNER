package kh.cmt.notifications.web;

import java.util.Map;
import java.util.UUID;
import kh.cmt.notifications.application.MyNotificationService;
import kh.cmt.notifications.api.MyNotifications;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * Staff app: notifications and the phone's push token.
 * <pre>
 * GET    /api/v1/app/notifications?page=&size=    newest first, text in the app's language; {unread}
 * POST   /api/v1/app/notifications/{id}/read
 * POST   /api/v1/app/notifications/read-all       → {marked}
 * DELETE /api/v1/app/notifications/{id}           → 204 (swiped away; one of the caller's own)
 * DELETE /api/v1/app/notifications                → {removed} ("Clear all")
 * POST   /api/v1/app/push-token                   {token, platform android|ios, locale km|en|ar} → 204
 * DELETE /api/v1/app/push-token                   {token} → 204 (sign out)
 * </pre>
 */
@RestController
@RequestMapping("/api/v1/app")
class MyNotificationsController {

    private final MyNotificationService service;

    MyNotificationsController(MyNotificationService service) {
        this.service = service;
    }

    @GetMapping("/notifications")
    MyNotifications list(@RequestParam(defaultValue = "0") int page, @RequestParam(defaultValue = "30") int size,
            @RequestHeader(value = HttpHeaders.ACCEPT_LANGUAGE, required = false) String lang) {
        return service.list(page, size, lang);
    }

    @PostMapping("/notifications/{id}/read")
    MyNotifications.Item read(@PathVariable UUID id, @RequestHeader(value = HttpHeaders.ACCEPT_LANGUAGE, required = false) String lang) {
        return service.read(id, lang);
    }

    @PostMapping("/notifications/read-all")
    Map<String, Integer> readAll() {
        return Map.of("marked", service.readAll());
    }

    @DeleteMapping("/notifications/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    void remove(@PathVariable UUID id) {
        service.remove(id);
    }

    @DeleteMapping("/notifications")
    Map<String, Integer> removeAll() {
        return Map.of("removed", service.removeAll());
    }

    @PostMapping("/push-token")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    void registerToken(@RequestBody MyNotifications.PushTokenCommand command) {
        service.registerToken(command);
    }

    @DeleteMapping("/push-token")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    void removeToken(@RequestBody MyNotifications.PushTokenCommand command) {
        service.removeToken(command.token());
    }
}
