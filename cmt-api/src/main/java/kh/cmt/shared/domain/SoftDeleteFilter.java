package kh.cmt.shared.domain;

import jakarta.persistence.EntityManager;
import org.hibernate.Session;

/** Switch for the "active only" filter, for screens that must also show inactive rows. */
public final class SoftDeleteFilter {

    private SoftDeleteFilter() {
    }

    public static void includeInactive(EntityManager em) {
        em.unwrap(Session.class).disableFilter(SoftDeletable.ACTIVE_ONLY);
    }

    public static void activeOnly(EntityManager em) {
        em.unwrap(Session.class).enableFilter(SoftDeletable.ACTIVE_ONLY);
    }
}
