package android.icu.text;

// ZOUKAN -->
// Shadows Suwayomi android.jar's stub of this class, keeping every public and protected member.
// StringSearch delegates to ICU4J's StringSearch through icu(); a subclass without one keeps
// android.jar's "Stub!" behaviour.

import java.text.CharacterIterator;

public abstract class SearchIterator {
    public static final int DONE = -1;

    protected BreakIterator breakIterator;
    protected int matchLength;
    protected CharacterIterator targetText;

    protected SearchIterator(CharacterIterator target, BreakIterator breaker) {
        this.targetText = target;
        this.breakIterator = breaker;
    }

    /** The ICU4J search this one delegates to, or null. */
    com.ibm.icu.text.SearchIterator icu() {
        return null;
    }

    private com.ibm.icu.text.SearchIterator requireIcu() {
        com.ibm.icu.text.SearchIterator icu = icu();
        if (icu == null) {
            throw new RuntimeException("Stub!");
        }
        return icu;
    }

    public void setIndex(int position) {
        requireIcu().setIndex(position);
    }

    public void setOverlapping(boolean allowOverlap) {
        requireIcu().setOverlapping(allowOverlap);
    }

    public void setBreakIterator(BreakIterator breakiter) {
        breakIterator = breakiter;
        requireIcu().setBreakIterator(breakiter == null ? null : breakiter.icu());
    }

    public void setTarget(CharacterIterator text) {
        targetText = text;
        requireIcu().setTarget(text);
    }

    public int getMatchStart() {
        return requireIcu().getMatchStart();
    }

    public abstract int getIndex();

    public int getMatchLength() {
        return requireIcu().getMatchLength();
    }

    public BreakIterator getBreakIterator() {
        return breakIterator;
    }

    public CharacterIterator getTarget() {
        return requireIcu().getTarget();
    }

    public String getMatchedText() {
        return requireIcu().getMatchedText();
    }

    public int next() {
        return requireIcu().next();
    }

    public int previous() {
        return requireIcu().previous();
    }

    public boolean isOverlapping() {
        return requireIcu().isOverlapping();
    }

    public void reset() {
        requireIcu().reset();
    }

    public final int first() {
        return requireIcu().first();
    }

    public final int following(int position) {
        return requireIcu().following(position);
    }

    public final int last() {
        return requireIcu().last();
    }

    public final int preceding(int position) {
        return requireIcu().preceding(position);
    }

    public void setElementComparisonType(ElementComparisonType type) {
        requireIcu().setElementComparisonType(
                com.ibm.icu.text.SearchIterator.ElementComparisonType.valueOf(type.name()));
    }

    public ElementComparisonType getElementComparisonType() {
        return ElementComparisonType.valueOf(requireIcu().getElementComparisonType().name());
    }

    protected void setMatchLength(int length) {
        matchLength = length;
    }

    protected abstract int handleNext(int start);

    protected abstract int handlePrevious(int startAt);

    public enum ElementComparisonType {
        STANDARD_ELEMENT_COMPARISON, PATTERN_BASE_WEIGHT_IS_WILDCARD, ANY_BASE_WEIGHT_IS_WILDCARD;
    }
}
// ZOUKAN <--
