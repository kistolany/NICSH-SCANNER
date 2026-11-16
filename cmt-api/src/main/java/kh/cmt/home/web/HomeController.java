package kh.cmt.home.web;

import kh.cmt.home.application.HomeService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpHeaders;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

/**
 * Staff app: Home in one call.
 * <pre>
 * GET /api/v1/app/home → {today {date, serverTime, kind, state, current, changed, label, holiday, leave, shifts [{shiftIdx,
 *     name, start, end, phase, status, checkIn, checkOut, workedMinutes, lateMinutes, earlyMinutes, lateReason, earlyReason,
 *     recordId, version}], gate, outside, missingCheckOut},
 *   month {month, present, late, absent, leave, workingDays, elapsedDays},
 *   leaveBalances [{type, name, total, used, left}],
 *   notifications {items [3 newest, text in the app's language], unread}}
 * </pre>
 */
@RestController
@RequiredArgsConstructor
class HomeController {

    private final HomeService home;

    @GetMapping("/api/v1/app/home")
    HomeService.Home home(@RequestHeader(value = HttpHeaders.ACCEPT_LANGUAGE, required = false) String lang) {
        return home.home(lang);
    }
}
