package kh.cmt.iam.domain;

import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * The school's rules for new passwords, the same as the auth server checks them when a password is changed
 * (violation codes MIN_LENGTH, TOO_LONG, UPPERCASE, LOWERCASE, DIGIT, SYMBOL, CONTAINS_USERNAME), and the generator of
 * temporary passwords that always follow them.
 */
public record PasswordRules(int minLength, boolean upper, boolean lower, boolean digit, boolean symbol) {

    public static final int MAX_LENGTH = 128;

    private static final SecureRandom RANDOM = new SecureRandom();
    // no look-alikes (0 O o, 1 l I) so the password can be read out or typed from a slip of paper
    private static final String UPPER = "ABCDEFGHJKLMNPQRSTUVWXYZ";
    private static final String LOWER = "abcdefghijkmnpqrstuvwxyz";
    private static final String DIGITS = "23456789";
    private static final String SYMBOLS = "#@$%&*+=?!";

    /** Empty = acceptable. */
    public List<String> violations(String username, String password) {
        List<String> found = new ArrayList<>();
        if (password.length() < minLength) {
            found.add("MIN_LENGTH");
        }
        if (password.length() > MAX_LENGTH) {
            found.add("TOO_LONG");
        }
        if (upper && password.chars().noneMatch(Character::isUpperCase)) {
            found.add("UPPERCASE");
        }
        if (lower && password.chars().noneMatch(Character::isLowerCase)) {
            found.add("LOWERCASE");
        }
        if (digit && password.chars().noneMatch(Character::isDigit)) {
            found.add("DIGIT");
        }
        if (symbol && password.chars().allMatch(Character::isLetterOrDigit)) {
            found.add("SYMBOL");
        }
        if (username != null && !username.isBlank() && password.toLowerCase().contains(username.toLowerCase())) {
            found.add("CONTAINS_USERNAME");
        }
        return found;
    }

    /**
     * A random temporary password: at least 10 characters (more if the school asks for more), with capitals, small
     * letters, digits and a symbol whatever the rules, so it passes any of them.
     */
    public String generate(String username) {
        int length = Math.max(10, minLength);
        while (true) {
            List<Character> chars = new ArrayList<>();
            chars.add(pick(UPPER));
            chars.add(pick(LOWER));
            chars.add(pick(DIGITS));
            chars.add(pick(SYMBOLS));
            String all = UPPER + LOWER + DIGITS + SYMBOLS;
            while (chars.size() < length) {
                chars.add(pick(all));
            }
            Collections.shuffle(chars, RANDOM);
            StringBuilder out = new StringBuilder();
            chars.forEach(out::append);
            if (violations(username, out.toString()).isEmpty()) {
                return out.toString();
            }
        }
    }

    private static char pick(String from) {
        return from.charAt(RANDOM.nextInt(from.length()));
    }
}
