package kh.cmt.schedule.domain;

import jakarta.persistence.CollectionTable;
import jakarta.persistence.Column;
import jakarta.persistence.ElementCollection;
import jakarta.persistence.Embeddable;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.OrderBy;
import jakarta.persistence.Table;
import java.time.Duration;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.List;
import kh.cmt.shared.domain.SoftDeletable;

/**
 * A working day: 1 or 2 shifts with their times, the minutes of grace before a check-in counts as late, whether
 * checking out early needs a reason, and how long before a shift's start a check-in is taken. Times are school time and
 * a shift ends on the day it starts.
 */
@Entity
@Table(name = "shift_template")
public class ShiftTemplate extends SoftDeletable {

    /** One shift of the day: position 1 or 2, an optional name ("Morning"), start and end. */
    @Embeddable
    public static class Part {

        private int position;

        private String name;

        @Column(name = "start_time")
        private LocalTime start;

        @Column(name = "end_time")
        private LocalTime end;

        protected Part() {
        }

        public Part(int position, String name, LocalTime start, LocalTime end) {
            this.position = position;
            this.name = name;
            this.start = start;
            this.end = end;
        }

        public int getPosition() {
            return position;
        }

        public String getName() {
            return name;
        }

        public LocalTime getStart() {
            return start;
        }

        public LocalTime getEnd() {
            return end;
        }

        public long minutes() {
            return Duration.between(start, end).toMinutes();
        }
    }

    private String name;

    private String color;

    @Column(name = "grace_minutes")
    private int graceMinutes;

    @Column(name = "early_needs_reason")
    private boolean earlyNeedsReason;

    /** Minutes before a shift starts from which a scan checks in; null = no limit. */
    @Column(name = "checkin_opens_minutes")
    private Integer checkInOpensMinutes;

    @ElementCollection(fetch = FetchType.EAGER)
    @CollectionTable(name = "shift_template_part", joinColumns = @JoinColumn(name = "template_id"))
    @OrderBy("position")
    private List<Part> parts = new ArrayList<>();

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public String getColor() {
        return color;
    }

    public void setColor(String color) {
        this.color = color;
    }

    public int getGraceMinutes() {
        return graceMinutes;
    }

    public void setGraceMinutes(int graceMinutes) {
        this.graceMinutes = graceMinutes;
    }

    public boolean isEarlyNeedsReason() {
        return earlyNeedsReason;
    }

    public void setEarlyNeedsReason(boolean earlyNeedsReason) {
        this.earlyNeedsReason = earlyNeedsReason;
    }

    public Integer getCheckInOpensMinutes() {
        return checkInOpensMinutes;
    }

    public void setCheckInOpensMinutes(Integer checkInOpensMinutes) {
        this.checkInOpensMinutes = checkInOpensMinutes;
    }

    public List<Part> getParts() {
        return parts;
    }

    public void setParts(List<Part> parts) {
        this.parts.clear();
        this.parts.addAll(parts);
    }
}
