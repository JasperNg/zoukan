package android.icu.text;

// ZOUKAN -->
// Shadows Suwayomi android.jar's stub of this class, keeping every public member. Members the
// extensions use are implemented; the rest still throw "Stub!" as in android.jar. Generated from
// `javap -public` of android.jar, then edited.
// ZOUKAN <--

public abstract class BreakIterator implements java.lang.Cloneable {
    protected BreakIterator() {
    }

    /** The ICU4J iterator behind an iterator from a get*Instance method, or null. */
    com.ibm.icu.text.BreakIterator icu() {
        return null;
    }
    public static final int DONE = -1;
    public static final int KIND_CHARACTER = 0;
    public static final int KIND_LINE = 2;
    public static final int KIND_SENTENCE = 3;
    public static final int KIND_TITLE = 4;
    public static final int KIND_WORD = 1;
    public static final int WORD_IDEO = 400;
    public static final int WORD_IDEO_LIMIT = 500;
    public static final int WORD_KANA = 300;
    public static final int WORD_KANA_LIMIT = 400;
    public static final int WORD_LETTER = 200;
    public static final int WORD_LETTER_LIMIT = 300;
    public static final int WORD_NONE = 0;
    public static final int WORD_NONE_LIMIT = 100;
    public static final int WORD_NUMBER = 100;
    public static final int WORD_NUMBER_LIMIT = 200;
    public java.lang.Object clone() {
        try {
            return super.clone();
        } catch (CloneNotSupportedException e) {
            throw new AssertionError(e);
        }
    }
    public abstract int first();
    public abstract int last();
    public abstract int next(int p0);
    public abstract int next();
    public abstract int previous();
    public abstract int following(int p0);
    public int preceding(int p0) {
        int pos = following(p0);
        while (pos >= p0 && pos != DONE) {
            pos = previous();
        }
        return pos;
    }
    public boolean isBoundary(int p0) {
        if (p0 == 0) {
            return true;
        }
        return following(p0 - 1) == p0;
    }
    public abstract int current();
    public int getRuleStatus() {
        return 0;
    }
    public int getRuleStatusVec(int[] p0) {
        if (p0 != null && p0.length > 0) {
            p0[0] = 0;
        }
        return 1;
    }
    public abstract java.text.CharacterIterator getText();
    public void setText(java.lang.String p0) {
        setText(new java.text.StringCharacterIterator(p0));
    }
    public void setText(java.lang.CharSequence p0) {
        setText(p0.toString());
    }
    public abstract void setText(java.text.CharacterIterator p0);
    public static android.icu.text.BreakIterator getWordInstance() {
        return new Icu(com.ibm.icu.text.BreakIterator.getWordInstance());
    }
    public static android.icu.text.BreakIterator getWordInstance(java.util.Locale p0) {
        return new Icu(com.ibm.icu.text.BreakIterator.getWordInstance(p0));
    }
    public static android.icu.text.BreakIterator getWordInstance(android.icu.util.ULocale p0) {
        throw new RuntimeException("Stub!");
    }
    public static android.icu.text.BreakIterator getLineInstance() {
        return new Icu(com.ibm.icu.text.BreakIterator.getLineInstance());
    }
    public static android.icu.text.BreakIterator getLineInstance(java.util.Locale p0) {
        return new Icu(com.ibm.icu.text.BreakIterator.getLineInstance(p0));
    }
    public static android.icu.text.BreakIterator getLineInstance(android.icu.util.ULocale p0) {
        throw new RuntimeException("Stub!");
    }
    public static android.icu.text.BreakIterator getCharacterInstance() {
        return new Icu(com.ibm.icu.text.BreakIterator.getCharacterInstance());
    }
    public static android.icu.text.BreakIterator getCharacterInstance(java.util.Locale p0) {
        return new Icu(com.ibm.icu.text.BreakIterator.getCharacterInstance(p0));
    }
    public static android.icu.text.BreakIterator getCharacterInstance(android.icu.util.ULocale p0) {
        throw new RuntimeException("Stub!");
    }
    public static android.icu.text.BreakIterator getSentenceInstance() {
        return new Icu(com.ibm.icu.text.BreakIterator.getSentenceInstance());
    }
    public static android.icu.text.BreakIterator getSentenceInstance(java.util.Locale p0) {
        return new Icu(com.ibm.icu.text.BreakIterator.getSentenceInstance(p0));
    }
    public static android.icu.text.BreakIterator getSentenceInstance(android.icu.util.ULocale p0) {
        throw new RuntimeException("Stub!");
    }
    public static android.icu.text.BreakIterator getTitleInstance() {
        return new Icu(com.ibm.icu.text.BreakIterator.getTitleInstance());
    }
    public static android.icu.text.BreakIterator getTitleInstance(java.util.Locale p0) {
        return new Icu(com.ibm.icu.text.BreakIterator.getTitleInstance(p0));
    }
    public static android.icu.text.BreakIterator getTitleInstance(android.icu.util.ULocale p0) {
        throw new RuntimeException("Stub!");
    }
    public static synchronized java.util.Locale[] getAvailableLocales() {
        return com.ibm.icu.text.BreakIterator.getAvailableLocales();
    }
    /** A BreakIterator from a get*Instance method: delegates to ICU4J's. */
    static final class Icu extends BreakIterator {
        private com.ibm.icu.text.BreakIterator icu;

        Icu(com.ibm.icu.text.BreakIterator icu) {
            this.icu = icu;
        }

        @Override
        com.ibm.icu.text.BreakIterator icu() {
            return icu;
        }

        @Override
        public Object clone() {
            Icu copy = (Icu) super.clone();
            copy.icu = (com.ibm.icu.text.BreakIterator) icu.clone();
            return copy;
        }

        @Override
        public int first() {
            return icu.first();
        }

        @Override
        public int last() {
            return icu.last();
        }

        @Override
        public int next(int n) {
            return icu.next(n);
        }

        @Override
        public int next() {
            return icu.next();
        }

        @Override
        public int previous() {
            return icu.previous();
        }

        @Override
        public int following(int offset) {
            return icu.following(offset);
        }

        @Override
        public int preceding(int offset) {
            return icu.preceding(offset);
        }

        @Override
        public boolean isBoundary(int offset) {
            return icu.isBoundary(offset);
        }

        @Override
        public int current() {
            return icu.current();
        }

        @Override
        public int getRuleStatus() {
            return icu.getRuleStatus();
        }

        @Override
        public int getRuleStatusVec(int[] fillInArray) {
            return icu.getRuleStatusVec(fillInArray);
        }

        @Override
        public java.text.CharacterIterator getText() {
            return icu.getText();
        }

        @Override
        public void setText(String newText) {
            icu.setText(newText);
        }

        @Override
        public void setText(CharSequence newText) {
            icu.setText(newText);
        }

        @Override
        public void setText(java.text.CharacterIterator newText) {
            icu.setText(newText);
        }
    }
}
