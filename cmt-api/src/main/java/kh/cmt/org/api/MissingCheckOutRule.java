package kh.cmt.org.api;

/** What happens when someone checked in but never checked out. */
public enum MissingCheckOutRule {

    /** Mark the day "missing check-out" and ask the person to request a correction the next day (default). */
    ASK_CORRECTION_NEXT_DAY,

    /** Close the day automatically at the end of the shift. */
    AUTO_CLOSE_AT_SHIFT_END,

    /** Only flag it for the office; no request is created. */
    FLAG_ONLY
}
