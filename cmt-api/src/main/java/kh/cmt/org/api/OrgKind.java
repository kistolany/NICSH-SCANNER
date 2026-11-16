package kh.cmt.org.api;

import java.util.Arrays;
import java.util.Optional;

/** The lists the org module keeps. {@code path} is the URL segment: {@code /api/v1/admin/<path>}. */
public enum OrgKind {

    DEPARTMENT("departments"),
    POSITION("positions");

    private final String path;

    OrgKind(String path) {
        this.path = path;
    }

    public String path() {
        return path;
    }

    public static Optional<OrgKind> fromPath(String path) {
        return Arrays.stream(values()).filter(k -> k.path.equals(path)).findFirst();
    }
}
