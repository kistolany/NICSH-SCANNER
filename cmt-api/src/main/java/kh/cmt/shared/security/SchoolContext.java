package kh.cmt.shared.security;

import java.util.UUID;

/**
 * Tells the shared kernel which school (tenant) the current work belongs to.
 * Every table has a school_id; entities get it from here when first saved.
 */
public interface SchoolContext {

    UUID currentSchoolId();
}
