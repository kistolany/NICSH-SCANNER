package kh.cmt.shared.security;

import java.util.Arrays;
import java.util.Optional;

/** What a role can do in a module. In JSON and in the database: view, create, edit, delete, approve, export. */
public enum PermissionAction {

    VIEW("view"), CREATE("create"), EDIT("edit"), DELETE("delete"), APPROVE("approve"), EXPORT("export");

    private final String key;

    PermissionAction(String key) {
        this.key = key;
    }

    public String key() {
        return key;
    }

    public static Optional<PermissionAction> fromKey(String key) {
        return Arrays.stream(values()).filter(a -> a.key.equals(key)).findFirst();
    }
}
