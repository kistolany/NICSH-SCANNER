package kh.cmt.shared.security;

import java.util.UUID;
import java.util.function.Supplier;

/**
 * Runs work for a given school when there is no signed-in caller to take it from, e.g. the outbox relay handling an
 * event of school X. {@link SchoolContext} answers this school inside the call; outside nothing changes. Never used in
 * a request: a caller's school always comes from their token.
 */
public final class SchoolOverride {

    private static final ThreadLocal<UUID> SCHOOL = new ThreadLocal<>();

    private SchoolOverride() {
    }

    public static <T> T callAs(UUID school, Supplier<T> work) {
        UUID before = SCHOOL.get();
        SCHOOL.set(school);
        try {
            return work.get();
        } finally {
            if (before == null) {
                SCHOOL.remove();
            } else {
                SCHOOL.set(before);
            }
        }
    }

    public static void runAs(UUID school, Runnable work) {
        callAs(school, () -> {
            work.run();
            return null;
        });
    }

    static UUID current() {
        return SCHOOL.get();
    }
}
