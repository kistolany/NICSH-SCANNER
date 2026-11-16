package kh.cmt.org.api;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;
import java.util.Arrays;

/** The app themes. In JSON and in the database: system, light, dark. */
public enum Theme {

    SYSTEM("system"), LIGHT("light"), DARK("dark");

    private final String code;

    Theme(String code) {
        this.code = code;
    }

    @JsonValue
    public String code() {
        return code;
    }

    @JsonCreator
    public static Theme fromCode(String code) {
        return Arrays.stream(values()).filter(t -> t.code.equals(code)).findFirst()
                .orElseThrow(() -> new IllegalArgumentException("Unknown theme: " + code));
    }
}
