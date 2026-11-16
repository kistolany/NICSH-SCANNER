package kh.cmt.files.api;

import java.util.UUID;
import kh.cmt.shared.security.CurrentUser;

/**
 * Who may download a file: only the module whose record uses it knows (a person's documents follow the People
 * permission, the record scope and the sensitive fields; the school logo is for everybody of the school). Each such
 * module provides a policy; the files module asks them all. When none knows the file, only its uploader may read it.
 */
public interface FileReadPolicy {

    enum Decision { ALLOW, DENY, ABSTAIN }

    /** ABSTAIN when the file is not one of this module's. */
    Decision decide(UUID fileId, CurrentUser caller);
}
