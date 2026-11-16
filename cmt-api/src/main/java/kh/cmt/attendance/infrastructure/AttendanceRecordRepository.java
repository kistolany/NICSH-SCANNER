package kh.cmt.attendance.infrastructure;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import kh.cmt.attendance.domain.AttendanceRecord;
import org.springframework.data.jpa.repository.JpaRepository;

/** Attendance records: one per person, day and shift. Limited to the caller's record scope. */
public interface AttendanceRecordRepository extends JpaRepository<AttendanceRecord, UUID> {

    Optional<AttendanceRecord> findByPersonIdAndWorkDateAndShiftIdx(UUID personId, LocalDate workDate, int shiftIdx);

    List<AttendanceRecord> findByPersonIdAndWorkDateOrderByShiftIdx(UUID personId, LocalDate workDate);

    List<AttendanceRecord> findByPersonIdAndWorkDateBetweenOrderByWorkDateDescShiftIdxAsc(UUID personId, LocalDate from, LocalDate to);

    /** Everyone's records of these days (the caller's record scope applies), for the admin's day and month views. */
    List<AttendanceRecord> findByWorkDateBetween(LocalDate from, LocalDate to);
}
