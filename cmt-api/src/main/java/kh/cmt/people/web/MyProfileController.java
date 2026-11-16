package kh.cmt.people.web;

import kh.cmt.people.application.MyProfile;
import kh.cmt.people.application.PersonCommands;
import kh.cmt.people.application.PersonService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/**
 * App, Profile screen.
 * <pre>
 * GET  /api/v1/app/me/profile   the caller's own person record (404 if none is linked)
 * POST /api/v1/app/me/photo     {fileId}: an uploaded, confirmed photo becomes theirs (the old one is removed)
 * </pre>
 */
@RestController
class MyProfileController {

    private final PersonService service;

    MyProfileController(PersonService service) {
        this.service = service;
    }

    @GetMapping("/api/v1/app/me/profile")
    MyProfile profile() {
        return service.myProfile();
    }

    @PostMapping("/api/v1/app/me/photo")
    MyProfile photo(@RequestBody PersonCommands.Photo command) {
        return service.changeMyPhoto(command);
    }
}
