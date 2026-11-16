package kh.cmt.people.application;

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * The result of creating a login, "Give login" or "Reset password": the username and, when the password was
 * generated, the temporary password — shown <b>once</b> so the admin can hand it over (nothing is sent by email or SMS);
 * it is never stored in clear and cannot be read again.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record LoginSent(String username, String temporaryPassword, boolean mustChangePassword) {
}
