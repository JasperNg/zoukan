// ZOUKAN -->
package android.text.util;

import android.text.Spannable;
import android.text.style.URLSpan;
import android.widget.TextView;
import java.util.function.Function;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Linkify for the desktop, in place of android.jar's stub. On Android it makes links in text
 * clickable; the desktop draws extension text (setting summaries) itself and has no clickable
 * spans, so every addLinks leaves the text as it is and reports that it added no links. Extensions
 * call it while building their settings screen (en.mehgazone), which a stub broke.
 */
public class Linkify {
    public static final int WEB_URLS = 0x01;
    public static final int EMAIL_ADDRESSES = 0x02;
    public static final int PHONE_NUMBERS = 0x04;
    public static final int MAP_ADDRESSES = 0x08;
    public static final int ALL = WEB_URLS | EMAIL_ADDRESSES | PHONE_NUMBERS | MAP_ADDRESSES;

    private static final int PHONE_NUMBER_MINIMUM_DIGITS = 5;

    /** As on Android: a URL preceded by '@' is part of an email address. */
    public static final MatchFilter sUrlMatchFilter = (s, start, end) -> start == 0 || s.charAt(start - 1) != '@';

    public static final MatchFilter sPhoneNumberMatchFilter = (s, start, end) -> {
        int digits = 0;
        for (int i = start; i < end; i++) {
            if (Character.isDigit(s.charAt(i))) digits++;
        }
        return digits >= PHONE_NUMBER_MINIMUM_DIGITS;
    };

    public static final TransformFilter sPhoneNumberTransformFilter = (match, url) -> {
        StringBuilder digits = new StringBuilder();
        String s = match.group();
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '+' || Character.isDigit(c)) digits.append(c);
        }
        return digits.toString();
    };

    public interface MatchFilter {
        boolean acceptMatch(CharSequence s, int start, int end);
    }

    public interface TransformFilter {
        String transformUrl(Matcher match, String url);
    }

    public Linkify() {
    }

    public static final boolean addLinks(Spannable text, int mask) {
        return false;
    }

    public static final boolean addLinks(Spannable text, int mask, Function<String, URLSpan> urlSpanFactory) {
        return false;
    }

    public static final boolean addLinks(TextView text, int mask) {
        return false;
    }

    public static final void addLinks(TextView text, Pattern pattern, String scheme) {
    }

    public static final void addLinks(TextView text, Pattern pattern, String scheme, MatchFilter matchFilter,
            TransformFilter transformFilter) {
    }

    public static final void addLinks(TextView text, Pattern pattern, String defaultScheme, String[] schemes,
            MatchFilter matchFilter, TransformFilter transformFilter) {
    }

    public static final boolean addLinks(Spannable text, Pattern pattern, String scheme) {
        return false;
    }

    public static final boolean addLinks(Spannable spannable, Pattern pattern, String scheme,
            MatchFilter matchFilter, TransformFilter transformFilter) {
        return false;
    }

    public static final boolean addLinks(Spannable spannable, Pattern pattern, String defaultScheme,
            String[] schemes, MatchFilter matchFilter, TransformFilter transformFilter) {
        return false;
    }

    public static final boolean addLinks(Spannable spannable, Pattern pattern, String defaultScheme,
            String[] schemes, MatchFilter matchFilter, TransformFilter transformFilter,
            Function<String, URLSpan> urlSpanFactory) {
        return false;
    }
}
// ZOUKAN <--
