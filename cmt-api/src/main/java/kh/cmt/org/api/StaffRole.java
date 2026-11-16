package kh.cmt.org.api;

/** The kinds of person that get a Staff ID. {@code prefix} is the optional role part of the ID (STF / TCH / ADM). */
public enum StaffRole {

    STAFF("STF"), TEACHER("TCH"), ADMIN("ADM");

    private final String prefix;

    StaffRole(String prefix) {
        this.prefix = prefix;
    }

    public String prefix() {
        return prefix;
    }
}
