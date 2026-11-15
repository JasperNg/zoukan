package android.text;

// ZOUKAN -->
// Shadows Suwayomi android.jar's stub of SpannableString, keeping every public member. A plain
// implementation of AOSP's SpannableString/SpannableStringInternal: immutable text with a list of
// spans (object, start, end, flags), queried as AOSP does.

import java.lang.reflect.Array;
import java.util.ArrayList;
import java.util.List;

public class SpannableString implements CharSequence, GetChars, Spannable {
    private final String mText;
    private final List<SpanEntry> mSpans = new ArrayList<>();

    private static final class SpanEntry {
        final Object span;
        int start;
        int end;
        int flags;

        SpanEntry(Object span, int start, int end, int flags) {
            this.span = span;
            this.start = start;
            this.end = end;
            this.flags = flags;
        }
    }

    public SpannableString(CharSequence source) {
        this(source, 0, source.length());
    }

    private SpannableString(CharSequence source, int start, int end) {
        mText = source.toString().substring(start, end);
        if (source instanceof Spanned) {
            Spanned spanned = (Spanned) source;
            for (Object span : spanned.getSpans(start, end, Object.class)) {
                int st = spanned.getSpanStart(span);
                int en = spanned.getSpanEnd(span);
                int fl = spanned.getSpanFlags(span);
                if (st < start) {
                    st = start;
                }
                if (en > end) {
                    en = end;
                }
                setSpan(span, st - start, en - start, fl);
            }
        }
    }

    public static SpannableString valueOf(CharSequence source) {
        if (source instanceof SpannableString) {
            return (SpannableString) source;
        } else {
            return new SpannableString(source);
        }
    }

    @Override
    public void setSpan(Object what, int start, int end, int flags) {
        if (start < 0 || end > mText.length() || start > end) {
            throw new IndexOutOfBoundsException(
                    "setSpan (" + start + " ... " + end + ") is invalid for length " + mText.length());
        }
        for (SpanEntry entry : mSpans) {
            if (entry.span == what) {
                entry.start = start;
                entry.end = end;
                entry.flags = flags;
                return;
            }
        }
        mSpans.add(new SpanEntry(what, start, end, flags));
    }

    @Override
    public void removeSpan(Object what) {
        mSpans.removeIf(entry -> entry.span == what);
    }

    @Override
    public final CharSequence subSequence(int start, int end) {
        return new SpannableString(this, start, end);
    }

    @Override
    public final void getChars(int start, int end, char[] dest, int off) {
        mText.getChars(start, end, dest, off);
    }

    @Override
    public final int length() {
        return mText.length();
    }

    private SpanEntry find(Object span) {
        for (SpanEntry entry : mSpans) {
            if (entry.span == span) {
                return entry;
            }
        }
        return null;
    }

    @Override
    public int getSpanStart(Object what) {
        SpanEntry entry = find(what);
        return entry == null ? -1 : entry.start;
    }

    @Override
    public int getSpanEnd(Object what) {
        SpanEntry entry = find(what);
        return entry == null ? -1 : entry.end;
    }

    @Override
    public int getSpanFlags(Object what) {
        SpanEntry entry = find(what);
        return entry == null ? 0 : entry.flags;
    }

    @Override
    public int nextSpanTransition(int start, int limit, Class kind) {
        if (kind == null) {
            kind = Object.class;
        }
        for (SpanEntry entry : mSpans) {
            if (entry.start > start && entry.start < limit && kind.isInstance(entry.span)) {
                limit = entry.start;
            }
            if (entry.end > start && entry.end < limit && kind.isInstance(entry.span)) {
                limit = entry.end;
            }
        }
        return limit;
    }

    @Override
    @SuppressWarnings("unchecked")
    public <T> T[] getSpans(int queryStart, int queryEnd, Class<T> kind) {
        Class<?> type = kind == null ? Object.class : kind;
        List<SpanEntry> found = new ArrayList<>();
        for (SpanEntry entry : mSpans) {
            if (entry.start > queryEnd || entry.end < queryStart) {
                continue;
            }
            if (entry.start != entry.end && queryStart != queryEnd) {
                if (entry.start == queryEnd || entry.end == queryStart) {
                    continue;
                }
            }
            if (!type.isInstance(entry.span)) {
                continue;
            }
            found.add(entry);
        }
        // Higher priority first; insertion order otherwise (List.sort is stable)
        found.sort((a, b) -> Integer.compare(b.flags & Spanned.SPAN_PRIORITY, a.flags & Spanned.SPAN_PRIORITY));
        T[] result = (T[]) Array.newInstance(type, found.size());
        for (int i = 0; i < found.size(); i++) {
            result[i] = (T) found.get(i).span;
        }
        return result;
    }

    @Override
    public int hashCode() {
        int hash = mText.hashCode();
        hash = hash * 31 + mSpans.size();
        for (SpanEntry entry : mSpans) {
            if (entry.span != this) {
                hash = hash * 31 + entry.span.hashCode();
            }
            hash = hash * 31 + entry.start;
            hash = hash * 31 + entry.end;
            hash = hash * 31 + entry.flags;
        }
        return hash;
    }

    @Override
    public boolean equals(Object o) {
        if (!(o instanceof Spanned) || !toString().equals(o.toString())) {
            return false;
        }
        Spanned other = (Spanned) o;
        Object[] otherSpans = other.getSpans(0, other.length(), Object.class);
        Object[] spans = getSpans(0, length(), Object.class);
        if (spans.length != otherSpans.length) {
            return false;
        }
        for (int i = 0; i < spans.length; i++) {
            Object thisSpan = spans[i];
            Object otherSpan = otherSpans[i];
            if (thisSpan == this) {
                if (other != otherSpan) {
                    return false;
                }
            } else if (!thisSpan.equals(otherSpan)) {
                return false;
            }
            if (getSpanStart(thisSpan) != other.getSpanStart(otherSpan)
                    || getSpanEnd(thisSpan) != other.getSpanEnd(otherSpan)
                    || getSpanFlags(thisSpan) != other.getSpanFlags(otherSpan)) {
                return false;
            }
        }
        return true;
    }

    @Override
    public final String toString() {
        return mText;
    }

    @Override
    public final char charAt(int i) {
        return mText.charAt(i);
    }
}
// ZOUKAN <--
