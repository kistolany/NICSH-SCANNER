package kh.cmt.auth.app;

import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.http.HttpStatus;

/** A refused app login / refresh with a stable code and optional extra fields (problem JSON). */
class AppLoginException extends RuntimeException {

    static final String INVALID_CREDENTIALS = "INVALID_CREDENTIALS";
    static final String ACCOUNT_LOCKED = "ACCOUNT_LOCKED";
    static final String ACCOUNT_INACTIVE = "ACCOUNT_INACTIVE";
    static final String ACCESS_EXPIRED = "ACCESS_EXPIRED";
    static final String NOT_AN_APP_ACCOUNT = "NOT_AN_APP_ACCOUNT";
    static final String DEVICE_MISMATCH = "DEVICE_MISMATCH";
    static final String DEVICE_BLOCKED = "DEVICE_BLOCKED";
    static final String UPDATE_REQUIRED = "UPDATE_REQUIRED";
    static final String INVALID_TOKEN = "INVALID_TOKEN";
    static final String VALIDATION_ERROR = "VALIDATION_ERROR";

    private final String code;
    private final HttpStatus status;
    private final Map<String, Object> properties = new LinkedHashMap<>();

    AppLoginException(String code, HttpStatus status) {
        super(code);
        this.code = code;
        this.status = status;
    }

    AppLoginException with(String name, Object value) {
        properties.put(name, value);
        return this;
    }

    String code() {
        return code;
    }

    HttpStatus status() {
        return status;
    }

    Map<String, Object> properties() {
        return properties;
    }
}
