package kh.cmt.shared.i18n;

import jakarta.persistence.Embeddable;
import java.util.Objects;

/**
 * A name in Khmer, English and Arabic. Each entity maps the three columns with {@code @AttributeOverride}
 * (name_km, name_en, name_ar); see KernelEntity in the tests for the pattern.
 */
@Embeddable
public class LocalizedText {

    public static final String DEFAULT_LANGUAGE = "km";

    private String km;
    private String en;
    private String ar;

    protected LocalizedText() {
    }

    public LocalizedText(String km, String en, String ar) {
        this.km = km;
        this.en = en;
        this.ar = ar;
    }

    public String getKm() {
        return km;
    }

    public String getEn() {
        return en;
    }

    public String getAr() {
        return ar;
    }

    /** Text for a language ("km", "en", "ar"); falls back to Khmer, then English, then Arabic. */
    public String in(String language) {
        String wanted = switch (language == null ? DEFAULT_LANGUAGE : language) {
            case "en" -> en;
            case "ar" -> ar;
            default -> km;
        };
        if (isFilled(wanted)) {
            return wanted;
        }
        for (String candidate : new String[] {km, en, ar}) {
            if (isFilled(candidate)) {
                return candidate;
            }
        }
        return null;
    }

    private static boolean isFilled(String s) {
        return s != null && !s.isBlank();
    }

    @Override
    public boolean equals(Object o) {
        return o instanceof LocalizedText t
                && Objects.equals(km, t.km) && Objects.equals(en, t.en) && Objects.equals(ar, t.ar);
    }

    @Override
    public int hashCode() {
        return Objects.hash(km, en, ar);
    }
}
