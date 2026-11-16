package android.icu.text;

// ZOUKAN -->
// Shadows Suwayomi android.jar's stub of this class, keeping every public and protected member.
// Delegates to ICU4J's StringSearch. The ULocale constructor stays a stub, as android.jar's
// ULocale is one.

import java.text.CharacterIterator;
import java.text.StringCharacterIterator;
import java.util.Locale;

public final class StringSearch extends SearchIterator {
    private final com.ibm.icu.text.StringSearch icu;
    private RuleBasedCollator collator;

    public StringSearch(String pattern, CharacterIterator target, RuleBasedCollator collator, BreakIterator breakiter) {
        super(target, breakiter);
        this.collator = collator;
        this.icu = new com.ibm.icu.text.StringSearch(
                pattern, target, collator.rbc(), breakiter == null ? null : breakiter.icu());
    }

    public StringSearch(String pattern, CharacterIterator target, RuleBasedCollator collator) {
        this(pattern, target, collator, null);
    }

    public StringSearch(String pattern, CharacterIterator target, Locale locale) {
        this(pattern, target, (RuleBasedCollator) Collator.getInstance(locale), null);
    }

    public StringSearch(String pattern, CharacterIterator target, android.icu.util.ULocale locale) {
        super(target, null);
        throw new RuntimeException("Stub!");
    }

    public StringSearch(String pattern, String target) {
        this(pattern, new StringCharacterIterator(target), (RuleBasedCollator) Collator.getInstance(), null);
    }

    @Override
    com.ibm.icu.text.SearchIterator icu() {
        return icu;
    }

    public RuleBasedCollator getCollator() {
        return collator;
    }

    public void setCollator(RuleBasedCollator collator) {
        this.collator = collator;
        icu.setCollator(collator.rbc());
    }

    public String getPattern() {
        return icu.getPattern();
    }

    public void setPattern(String pattern) {
        icu.setPattern(pattern);
    }

    public boolean isCanonical() {
        return icu.isCanonical();
    }

    public void setCanonical(boolean allowCanonical) {
        icu.setCanonical(allowCanonical);
    }

    @Override
    public void setTarget(CharacterIterator text) {
        super.setTarget(text);
    }

    @Override
    public int getIndex() {
        return icu.getIndex();
    }

    @Override
    public void setIndex(int position) {
        icu.setIndex(position);
    }

    @Override
    public void reset() {
        icu.reset();
    }

    @Override
    protected int handleNext(int position) {
        // Only ICU4J's own next() calls this, and next() is delegated
        throw new RuntimeException("Stub!");
    }

    @Override
    protected int handlePrevious(int position) {
        throw new RuntimeException("Stub!");
    }
}
// ZOUKAN <--
