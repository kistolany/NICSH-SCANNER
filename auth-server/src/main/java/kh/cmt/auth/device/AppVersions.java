package kh.cmt.auth.device;

/** Compares app versions like "1.4.0" part by part as numbers ("1.10.0" is newer than "1.9.2"); anything else is 0. */
public final class AppVersions {

    private AppVersions() {
    }

    /** True when {@code version} is older than {@code minimum}; an unknown version counts as old. */
    public static boolean older(String version, String minimum) {
        if (minimum == null || minimum.isBlank()) {
            return false;
        }
        if (version == null || version.isBlank()) {
            return true;
        }
        String[] a = version.trim().split("[.+-]");
        String[] b = minimum.trim().split("[.+-]");
        for (int i = 0; i < Math.max(a.length, b.length); i++) {
            int x = part(a, i);
            int y = part(b, i);
            if (x != y) {
                return x < y;
            }
        }
        return false;
    }

    private static int part(String[] parts, int i) {
        if (i >= parts.length) {
            return 0;
        }
        try {
            return Integer.parseInt(parts[i].replaceAll("\\D.*$", ""));
        } catch (NumberFormatException notANumber) {
            return 0;
        }
    }
}
