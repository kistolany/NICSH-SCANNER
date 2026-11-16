package kh.cmt.iam.application;

import java.text.Normalizer;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.regex.Pattern;
import kh.cmt.iam.infrastructure.AuthAccountStore;
import kh.cmt.iam.infrastructure.AuthAccountStore.AppLogin;
import kh.cmt.shared.security.PersonLogins;
import kh.cmt.shared.security.SchoolContext;
import kh.cmt.shared.security.SignOuts;
import kh.cmt.shared.web.ApiException;
import kh.cmt.shared.web.ErrorCode;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The app login of a person, for the people module (Add person › Login, "Give login", "Reset password"; an inactive
 * person cannot log in). The auth server's account is written here in the same transaction as the person.
 * <ul>
 *   <li>username: typed, or made from the English name ("Sokha Lim" → {@code sokha.lim}, then {@code sokha.lim2}…;
 *       from the Staff ID when the name has no Latin letters); one login per username, web and app together</li>
 *   <li>password: given by the admin, never sent ({@link AdminPasswords}): typed, or generated and returned once</li>
 *   <li>"must change password at the next login": on by default, always on for a generated password</li>
 * </ul>
 * Only the person's own <b>app</b> account is touched; a web user linked to the person keeps its Admin Web login.
 */
@Service
@Transactional
class PersonLoginService implements PersonLogins {

    private static final Pattern USERNAME = Pattern.compile("[A-Za-z0-9._@+-]{3,100}");

    private final AuthAccountStore accounts;
    private final SignOuts signOuts;
    private final SchoolContext schoolContext;
    private final AdminPasswords passwords;

    PersonLoginService(AuthAccountStore accounts, SignOuts signOuts, SchoolContext schoolContext, AdminPasswords passwords) {
        this.accounts = accounts;
        this.signOuts = signOuts;
        this.schoolContext = schoolContext;
        this.passwords = passwords;
    }

    @Override
    @Transactional(readOnly = true)
    public boolean exists(UUID personId) {
        return accounts.appAccountOf(personId).isPresent();
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<LoginInfo> info(UUID personId) {
        return accounts.appLoginOf(personId).map(a -> new LoginInfo(a.username(), a.active(), a.mustChangePassword(), a.lastLoginAt()));
    }

    @Override
    public Credentials create(UUID personId, NewLogin login) {
        if (exists(personId)) {
            throw new ApiException(ErrorCode.CONFLICT);
        }
        UUID school = schoolContext.currentSchoolId();
        String username;
        if (login.username() == null || login.username().isBlank()) {
            username = freeUsername(base(login.nameEn(), login.staffId()));
        } else {
            username = login.username().trim();
            if (!USERNAME.matcher(username).matches()) {
                throw new ApiException(ErrorCode.VALIDATION_ERROR)
                        .with("errors", List.of(Map.of("field", "login.username", "rule", "Pattern")));
            }
            if (accounts.usernameTaken(username, null)) {
                throw new ApiException(ErrorCode.USERNAME_TAKEN);
            }
        }
        AdminPasswords.Chosen password = passwords.choose(school, username, login.password());
        accounts.createApp(UUID.randomUUID(), school, personId, username, password.hash(), login.role(), login.phone(),
                password.wasGenerated() || login.mustChangePassword(), login.active());
        return new Credentials(username, password.generated());
    }

    @Override
    public Credentials setPassword(UUID personId, String password, boolean mustChangePassword) {
        AppLogin account = accounts.appLoginOf(personId).orElseThrow(() -> new ApiException(ErrorCode.NO_LOGIN));
        AdminPasswords.Chosen chosen = passwords.choose(schoolContext.currentSchoolId(), account.username(), password);
        accounts.setPassword(account.accountId(), chosen.hash(), chosen.wasGenerated() || mustChangePassword);
        accounts.signOutEverywhere(account.accountId());
        signOuts.signOut(account.accountId());
        return new Credentials(account.username(), chosen.generated());
    }

    @Override
    public void update(UUID personId, String role, String phone) {
        accounts.appAccountOf(personId).ifPresent(account -> accounts.updateApp(account, role, phone));
    }

    @Override
    public void disable(UUID personId) {
        accounts.appAccountOf(personId).ifPresent(account -> {
            accounts.setActive(account, false);
            accounts.signOutEverywhere(account);
            signOuts.signOut(account);
        });
    }

    @Override
    public void enable(UUID personId) {
        accounts.appAccountOf(personId).ifPresent(account -> accounts.setActive(account, true));
    }

    @Override
    public void signOutEverywhere(UUID personId) {
        accounts.appAccountOf(personId).ifPresent(account -> {
            accounts.signOutEverywhere(account);
            signOuts.signOut(account);
        });
    }

    // ------------------------------------------------------------------ helpers

    /** "Sokha Lim" → sokha.lim; accents dropped; no Latin letters at all → the Staff ID (cmt-0151). */
    static String base(String nameEn, String staffId) {
        String plain = nameEn == null ? "" : Normalizer.normalize(nameEn, Normalizer.Form.NFD).replaceAll("\\p{M}", "");
        List<String> words = Arrays.stream(plain.toLowerCase().split("[^a-z0-9]+")).filter(w -> !w.isEmpty()).toList();
        String base = switch (words.size()) {
            case 0 -> staffId == null ? "user" : staffId.toLowerCase().replaceAll("[^a-z0-9.-]", "");
            case 1 -> words.get(0);
            default -> words.get(0) + "." + words.get(words.size() - 1);
        };
        if (base.length() < 3) {
            base = (base + "user").substring(0, Math.max(3, base.length()));
        }
        return base.length() > 30 ? base.substring(0, 30) : base;
    }

    /** {@code base}, or base2, base3... — the first one no login uses yet. */
    private String freeUsername(String base) {
        String candidate = base;
        for (int n = 2; accounts.usernameTaken(candidate, null); n++) {
            candidate = base + n;
        }
        return candidate;
    }
}
