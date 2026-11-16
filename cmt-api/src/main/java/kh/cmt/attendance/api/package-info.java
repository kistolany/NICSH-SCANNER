/**
 * What other modules may use from the attendance module: the outbox events {@link kh.cmt.attendance.api.AttendanceRecorded}
 * and {@link kh.cmt.attendance.api.MissingCheckOut}, and the app's Today card ({@link kh.cmt.attendance.api.TodayCard}).
 */
@NamedInterface("api")
package kh.cmt.attendance.api;

import org.springframework.modulith.NamedInterface;
