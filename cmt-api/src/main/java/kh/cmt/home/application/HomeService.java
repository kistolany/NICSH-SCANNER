package kh.cmt.home.application;

import java.util.List;
import kh.cmt.attendance.api.TodayCard;
import kh.cmt.leave.api.MyLeaveBalances;
import kh.cmt.notifications.api.MyNotifications;
import kh.cmt.notifications.api.NotificationFeed;
import lombok.AccessLevel;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** The staff app's Home (SPEC §4.2): everything it shows, for the signed-in person. */
@Service
@RequiredArgsConstructor(access = AccessLevel.PACKAGE)
public class HomeService {

    /** "Recent notifications (3 items)". */
    static final int NOTIFICATIONS = 3;

    /** {@code leaveBalances} empty until the leave module (phase 9). */
    public record Home(TodayCard.Today today, TodayCard.Month month, List<MyLeaveBalances.Balance> leaveBalances, Notifications notifications) {
    }

    public record Notifications(List<MyNotifications.Item> items, long unread) {
    }

    private final TodayCard card;
    private final NotificationFeed feed;
    private final ObjectProvider<MyLeaveBalances> balances;

    @Transactional(readOnly = true)
    public Home home(String acceptLanguage) {
        MyNotifications latest = feed.latest(NOTIFICATIONS, acceptLanguage);
        MyLeaveBalances leave = balances.getIfAvailable();
        return new Home(card.today(), card.month(), leave == null ? List.of() : leave.mine(),
                new Notifications(latest.items(), latest.unread()));
    }
}
