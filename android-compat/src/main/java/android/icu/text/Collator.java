package android.icu.text;

// ZOUKAN -->
// Shadows Suwayomi android.jar's stub of this class, keeping every public member. Members the
// extensions use are implemented; the rest still throw "Stub!" as in android.jar. Generated from
// `javap -public` of android.jar, then edited.
// ZOUKAN <--

public abstract class Collator implements java.util.Comparator<java.lang.Object>, android.icu.util.Freezable<Collator>, java.lang.Cloneable {
    /** The ICU4J collator this one delegates to; null in a subclass that does not use one. */
    com.ibm.icu.text.Collator icu;

    protected Collator() {
    }

    Collator(com.ibm.icu.text.Collator icu) {
        this.icu = icu;
    }

    com.ibm.icu.text.Collator icu() {
        if (icu == null) {
            throw new RuntimeException("Stub!");
        }
        return icu;
    }

    static Collator wrap(com.ibm.icu.text.Collator collator) {
        // ICU4J's Collator.getInstance always returns a RuleBasedCollator
        return new RuleBasedCollator((com.ibm.icu.text.RuleBasedCollator) collator);
    }
    public static final int CANONICAL_DECOMPOSITION = 17;
    public static final int FULL_DECOMPOSITION = 15;
    public static final int IDENTICAL = 15;
    public static final int NO_DECOMPOSITION = 16;
    public static final int PRIMARY = 0;
    public static final int QUATERNARY = 3;
    public static final int SECONDARY = 1;
    public static final int TERTIARY = 2;
    public boolean equals(java.lang.Object p0) {
        return p0 instanceof Collator && icu() .equals(((Collator) p0).icu);
    }
    public int hashCode() {
        return icu().hashCode();
    }
    public void setStrength(int p0) {
        icu().setStrength(p0);
    }
    public void setDecomposition(int p0) {
        icu().setDecomposition(p0);
    }
    public void setReorderCodes(int... p0) {
        icu().setReorderCodes(p0);
    }
    public static final android.icu.text.Collator getInstance() {
        return wrap(com.ibm.icu.text.Collator.getInstance());
    }
    public java.lang.Object clone() throws java.lang.CloneNotSupportedException {
        return wrap((com.ibm.icu.text.Collator) icu().clone());
    }
    public static final android.icu.text.Collator getInstance(android.icu.util.ULocale p0) {
        throw new RuntimeException("Stub!");
    }
    public static final android.icu.text.Collator getInstance(java.util.Locale p0) {
        return wrap(com.ibm.icu.text.Collator.getInstance(p0));
    }
    public static java.util.Locale[] getAvailableLocales() {
        return com.ibm.icu.text.Collator.getAvailableLocales();
    }
    public static final android.icu.util.ULocale[] getAvailableULocales() {
        throw new RuntimeException("Stub!");
    }
    public static final java.lang.String[] getKeywords() {
        return com.ibm.icu.text.Collator.getKeywords();
    }
    public static final java.lang.String[] getKeywordValues(java.lang.String p0) {
        return com.ibm.icu.text.Collator.getKeywordValues(p0);
    }
    public static final java.lang.String[] getKeywordValuesForLocale(java.lang.String p0, android.icu.util.ULocale p1, boolean p2) {
        throw new RuntimeException("Stub!");
    }
    public static final android.icu.util.ULocale getFunctionalEquivalent(java.lang.String p0, android.icu.util.ULocale p1, boolean[] p2) {
        throw new RuntimeException("Stub!");
    }
    public static final android.icu.util.ULocale getFunctionalEquivalent(java.lang.String p0, android.icu.util.ULocale p1) {
        throw new RuntimeException("Stub!");
    }
    public static java.lang.String getDisplayName(java.util.Locale p0, java.util.Locale p1) {
        return com.ibm.icu.text.Collator.getDisplayName(p0, p1);
    }
    public static java.lang.String getDisplayName(android.icu.util.ULocale p0, android.icu.util.ULocale p1) {
        throw new RuntimeException("Stub!");
    }
    public static java.lang.String getDisplayName(java.util.Locale p0) {
        return com.ibm.icu.text.Collator.getDisplayName(p0);
    }
    public static java.lang.String getDisplayName(android.icu.util.ULocale p0) {
        throw new RuntimeException("Stub!");
    }
    public int getStrength() {
        return icu().getStrength();
    }
    public int getDecomposition() {
        return icu().getDecomposition();
    }
    public boolean equals(java.lang.String p0, java.lang.String p1) {
        return compare(p0, p1) == 0;
    }
    public android.icu.text.UnicodeSet getTailoredSet() {
        throw new RuntimeException("Stub!");
    }
    public abstract int compare(java.lang.String p0, java.lang.String p1);
    public int compare(java.lang.Object p0, java.lang.Object p1) {
        return compare(p0 == null ? null : p0.toString(), p1 == null ? null : p1.toString());
    }
    public abstract android.icu.text.CollationKey getCollationKey(java.lang.String p0);
    public android.icu.text.Collator setMaxVariable(int p0) {
        icu().setMaxVariable(p0);
        return this;
    }
    public int getMaxVariable() {
        return icu().getMaxVariable();
    }
    public abstract int getVariableTop();
    public abstract android.icu.util.VersionInfo getVersion();
    public abstract android.icu.util.VersionInfo getUCAVersion();
    public int[] getReorderCodes() {
        return icu().getReorderCodes();
    }
    public static int[] getEquivalentReorderCodes(int p0) {
        return com.ibm.icu.text.Collator.getEquivalentReorderCodes(p0);
    }
    public boolean isFrozen() {
        return icu != null && icu.isFrozen();
    }
    public android.icu.text.Collator freeze() {
        icu().freeze();
        return this;
    }
    public android.icu.text.Collator cloneAsThawed() {
        return wrap(icu().cloneAsThawed());
    }

    public interface ReorderCodes {
        public static final int CURRENCY = 4099;
        public static final int DEFAULT = -1;
        public static final int DIGIT = 4100;
        public static final int FIRST = 4096;
        public static final int NONE = 103;
        public static final int OTHERS = 103;
        public static final int PUNCTUATION = 4097;
        public static final int SPACE = 4096;
        public static final int SYMBOL = 4098;
    }
}
