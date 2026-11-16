package kh.cmt.auth.password;

import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.http.HttpStatus;

/** A password request that was refused. {@code code} is a stable code the apps turn into text. */
public class PasswordException extends RuntimeException {

    public static final String WRONG_PASSWORD = "WRONG_PASSWORD";
    public static final String PASSWORD_POLICY = "PASSWORD_POLICY";
    public static final String FORBIDDEN = "FORBIDDEN";

    private final String code;
    private final HttpStatus status;
    private final Map<String, Object> properties = new LinkedHashMap<>();

    public PasswordException(String code, HttpStatus status) {
        super(code);
        this.code = code;
        this.status = status;
    }

    public PasswordException with(String name, Object value) {
        properties.put(name, value);
        return this;
    }

    public String code() {
        return code;
    }

    public HttpStatus status() {
        return status;
    }

    public Map<String, Object> properties() {
        return properties;
    }
}
