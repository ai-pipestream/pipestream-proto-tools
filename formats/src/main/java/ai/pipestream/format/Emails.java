package ai.pipestream.format;

import java.util.regex.Pattern;

/**
 * Email-address validation using the WHATWG "valid e-mail address" production
 * (<a href="https://html.spec.whatwg.org/multipage/input.html#valid-e-mail-address">HTML spec</a>).
 *
 * <p>This is deliberately the pragmatic web-form definition rather than the full RFC 5322 grammar:
 * a local part of the permitted ASCII characters, an {@code @}, and a dotted domain of
 * letter/digit/hyphen labels. It is the same syntactic definition protovalidate uses, so a value
 * that passes here passes there.
 */
public final class Emails {

    // https://html.spec.whatwg.org/multipage/input.html#valid-e-mail-address
    private static final Pattern EMAIL = Pattern.compile(
            "^[a-zA-Z0-9.!#$%&'*+/=?^_`{|}~-]+"
                    + "@[a-zA-Z0-9](?:[a-zA-Z0-9-]{0,61}[a-zA-Z0-9])?"
                    + "(?:\\.[a-zA-Z0-9](?:[a-zA-Z0-9-]{0,61}[a-zA-Z0-9])?)*$");

    private Emails() {
    }

    /** Returns whether {@code value} is a syntactically valid email address. */
    public static boolean isEmail(String value) {
        return EMAIL.matcher(value).matches();
    }
}
