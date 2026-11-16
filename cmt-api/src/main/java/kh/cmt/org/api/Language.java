package kh.cmt.org.api;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;
import java.util.Arrays;

/** The app languages. In JSON and in the database: km, en, ar. */
public enum Language {

    KM("km"), EN("en"), AR("ar");

    private final String code;

    Language(String code) {
        this.code = code;
    }

    @JsonValue
    public String code() {
        return code;
    }

    @JsonCreator
    public static Language fromCode(String code) {
        return Arrays.stream(values()).filter(l -> l.code.equals(code)).findFirst()
                .orElseThrow(() -> new IllegalArgumentException("Unknown language: " + code));
    }
}
