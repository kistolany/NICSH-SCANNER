package kh.cmt.shared.web;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

/**
 * Thrown by use cases for expected failures. Extra numbers (e.g. {@code distanceKm}) travel as
 * problem properties so the app can show them inside its own text.
 */
public class ApiException extends RuntimeException {

    private final ErrorCode code;
    private final Map<String, Object> properties = new LinkedHashMap<>();

    public ApiException(ErrorCode code) {
        super(code.name());
        this.code = code;
    }

    /** Fields of the problem body itself; a property with one of these names would overwrite it. */
    private static final Set<String> RESERVED = Set.of("type", "title", "status", "detail", "instance", "code");

    public ApiException with(String name, Object value) {
        if (RESERVED.contains(name)) {
            throw new IllegalArgumentException("'" + name + "' is a field of the problem body; name the property differently");
        }
        properties.put(name, value);
        return this;
    }

    public ErrorCode code() {
        return code;
    }

    public Map<String, Object> properties() {
        return properties;
    }
}
