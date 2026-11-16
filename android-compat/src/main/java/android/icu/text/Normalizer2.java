package android.icu.text;

// ZOUKAN -->
// Shadows Suwayomi android.jar's stub of this class, keeping every public member. Members the
// extensions use are implemented; the rest still throw "Stub!" as in android.jar. Generated from
// `javap -public` of android.jar, then edited.
// ZOUKAN <--

public abstract class Normalizer2 {
    Normalizer2() {
    }
    public static android.icu.text.Normalizer2 getNFCInstance() {
        return new Icu(com.ibm.icu.text.Normalizer2.getNFCInstance());
    }
    public static android.icu.text.Normalizer2 getNFDInstance() {
        return new Icu(com.ibm.icu.text.Normalizer2.getNFDInstance());
    }
    public static android.icu.text.Normalizer2 getNFKCInstance() {
        return new Icu(com.ibm.icu.text.Normalizer2.getNFKCInstance());
    }
    public static android.icu.text.Normalizer2 getNFKDInstance() {
        return new Icu(com.ibm.icu.text.Normalizer2.getNFKDInstance());
    }
    public static android.icu.text.Normalizer2 getNFKCCasefoldInstance() {
        return new Icu(com.ibm.icu.text.Normalizer2.getNFKCCasefoldInstance());
    }
    public static android.icu.text.Normalizer2 getInstance(java.io.InputStream p0, java.lang.String p1, android.icu.text.Normalizer2.Mode p2) {
        return new Icu(com.ibm.icu.text.Normalizer2.getInstance(p0, p1, com.ibm.icu.text.Normalizer2.Mode.valueOf(p2.name())));
    }
    public java.lang.String normalize(java.lang.CharSequence p0) {
        return normalize(p0, new StringBuilder(p0.length())).toString();
    }
    public abstract java.lang.StringBuilder normalize(java.lang.CharSequence p0, java.lang.StringBuilder p1);
    public abstract java.lang.Appendable normalize(java.lang.CharSequence p0, java.lang.Appendable p1);
    public abstract java.lang.StringBuilder normalizeSecondAndAppend(java.lang.StringBuilder p0, java.lang.CharSequence p1);
    public abstract java.lang.StringBuilder append(java.lang.StringBuilder p0, java.lang.CharSequence p1);
    public abstract java.lang.String getDecomposition(int p0);
    public java.lang.String getRawDecomposition(int p0) {
        return null;
    }
    public int composePair(int p0, int p1) {
        return -1;
    }
    public int getCombiningClass(int p0) {
        return 0;
    }
    public abstract boolean isNormalized(java.lang.CharSequence p0);
    public abstract android.icu.text.Normalizer.QuickCheckResult quickCheck(java.lang.CharSequence p0);
    public abstract int spanQuickCheckYes(java.lang.CharSequence p0);
    public abstract boolean hasBoundaryBefore(int p0);
    public abstract boolean hasBoundaryAfter(int p0);
    public abstract boolean isInert(int p0);

    public enum Mode {
        COMPOSE, DECOMPOSE, FCD, COMPOSE_CONTIGUOUS;
    }
    /** A Normalizer2 from a get*Instance method: delegates to ICU4J's. */
    static final class Icu extends Normalizer2 {
        private final com.ibm.icu.text.Normalizer2 icu;

        Icu(com.ibm.icu.text.Normalizer2 icu) {
            this.icu = icu;
        }

        @Override
        public Normalizer.QuickCheckResult quickCheck(CharSequence s) {
            // android.icu.text.Normalizer is a stub in android.jar
            throw new RuntimeException("Stub!");
        }

        @Override
        public String normalize(CharSequence src) {
            return icu.normalize(src);
        }

        @Override
        public StringBuilder normalize(CharSequence src, StringBuilder dest) {
            return icu.normalize(src, dest);
        }

        @Override
        public Appendable normalize(CharSequence src, Appendable dest) {
            return icu.normalize(src, dest);
        }

        @Override
        public StringBuilder normalizeSecondAndAppend(StringBuilder first, CharSequence second) {
            return icu.normalizeSecondAndAppend(first, second);
        }

        @Override
        public StringBuilder append(StringBuilder first, CharSequence second) {
            return icu.append(first, second);
        }

        @Override
        public String getDecomposition(int c) {
            return icu.getDecomposition(c);
        }

        @Override
        public String getRawDecomposition(int c) {
            return icu.getRawDecomposition(c);
        }

        @Override
        public int composePair(int a, int b) {
            return icu.composePair(a, b);
        }

        @Override
        public int getCombiningClass(int c) {
            return icu.getCombiningClass(c);
        }

        @Override
        public boolean isNormalized(CharSequence s) {
            return icu.isNormalized(s);
        }

        @Override
        public int spanQuickCheckYes(CharSequence s) {
            return icu.spanQuickCheckYes(s);
        }

        @Override
        public boolean hasBoundaryBefore(int c) {
            return icu.hasBoundaryBefore(c);
        }

        @Override
        public boolean hasBoundaryAfter(int c) {
            return icu.hasBoundaryAfter(c);
        }

        @Override
        public boolean isInert(int c) {
            return icu.isInert(c);
        }
    }
}
