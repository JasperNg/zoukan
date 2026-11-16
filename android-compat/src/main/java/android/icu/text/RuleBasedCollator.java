package android.icu.text;

// ZOUKAN -->
// Shadows Suwayomi android.jar's stub of this class, keeping every public member. Members the
// extensions use are implemented; the rest still throw "Stub!" as in android.jar. Generated from
// `javap -public` of android.jar, then edited.
// ZOUKAN <--

public final class RuleBasedCollator extends android.icu.text.Collator {
    RuleBasedCollator(com.ibm.icu.text.RuleBasedCollator icu) {
        super(icu);
    }

    com.ibm.icu.text.RuleBasedCollator rbc() {
        return (com.ibm.icu.text.RuleBasedCollator) icu;
    }
    public RuleBasedCollator(java.lang.String p0) throws java.lang.Exception {
        super(new com.ibm.icu.text.RuleBasedCollator(p0));
    }
    public java.lang.Object clone() throws java.lang.CloneNotSupportedException {
        return new RuleBasedCollator((com.ibm.icu.text.RuleBasedCollator) rbc().clone());
    }
    public android.icu.text.CollationElementIterator getCollationElementIterator(java.lang.String p0) {
        throw new RuntimeException("Stub!");
    }
    public android.icu.text.CollationElementIterator getCollationElementIterator(java.text.CharacterIterator p0) {
        throw new RuntimeException("Stub!");
    }
    public android.icu.text.CollationElementIterator getCollationElementIterator(android.icu.text.UCharacterIterator p0) {
        throw new RuntimeException("Stub!");
    }
    public boolean isFrozen() {
        return rbc().isFrozen();
    }
    public android.icu.text.Collator freeze() {
        rbc().freeze();
        return this;
    }
    public android.icu.text.RuleBasedCollator cloneAsThawed() {
        return new RuleBasedCollator(rbc().cloneAsThawed());
    }
    public void setUpperCaseFirst(boolean p0) {
        rbc().setUpperCaseFirst(p0);
    }
    public void setLowerCaseFirst(boolean p0) {
        rbc().setLowerCaseFirst(p0);
    }
    public void setCaseFirstDefault() {
        rbc().setCaseFirstDefault();
    }
    public void setAlternateHandlingDefault() {
        rbc().setAlternateHandlingDefault();
    }
    public void setCaseLevelDefault() {
        rbc().setCaseLevelDefault();
    }
    public void setDecompositionDefault() {
        rbc().setDecompositionDefault();
    }
    public void setFrenchCollationDefault() {
        rbc().setFrenchCollationDefault();
    }
    public void setStrengthDefault() {
        rbc().setStrengthDefault();
    }
    public void setNumericCollationDefault() {
        rbc().setNumericCollationDefault();
    }
    public void setFrenchCollation(boolean p0) {
        rbc().setFrenchCollation(p0);
    }
    public void setAlternateHandlingShifted(boolean p0) {
        rbc().setAlternateHandlingShifted(p0);
    }
    public void setCaseLevel(boolean p0) {
        rbc().setCaseLevel(p0);
    }
    public void setDecomposition(int p0) {
        rbc().setDecomposition(p0);
    }
    public void setStrength(int p0) {
        rbc().setStrength(p0);
    }
    public android.icu.text.RuleBasedCollator setMaxVariable(int p0) {
        rbc().setMaxVariable(p0);
        return this;
    }
    public int getMaxVariable() {
        return rbc().getMaxVariable();
    }
    public void setNumericCollation(boolean p0) {
        rbc().setNumericCollation(p0);
    }
    public void setReorderCodes(int... p0) {
        rbc().setReorderCodes(p0);
    }
    public java.lang.String getRules() {
        return rbc().getRules();
    }
    public java.lang.String getRules(boolean p0) {
        return rbc().getRules(p0);
    }
    public android.icu.text.UnicodeSet getTailoredSet() {
        throw new RuntimeException("Stub!");
    }
    public void getContractionsAndExpansions(android.icu.text.UnicodeSet p0, android.icu.text.UnicodeSet p1, boolean p2) throws java.lang.Exception {
        throw new RuntimeException("Stub!");
    }
    public android.icu.text.CollationKey getCollationKey(java.lang.String p0) {
        throw new RuntimeException("Stub!");
    }
    public int getStrength() {
        return rbc().getStrength();
    }
    public int getDecomposition() {
        return rbc().getDecomposition();
    }
    public boolean isUpperCaseFirst() {
        return rbc().isUpperCaseFirst();
    }
    public boolean isLowerCaseFirst() {
        return rbc().isLowerCaseFirst();
    }
    public boolean isAlternateHandlingShifted() {
        return rbc().isAlternateHandlingShifted();
    }
    public boolean isCaseLevel() {
        return rbc().isCaseLevel();
    }
    public boolean isFrenchCollation() {
        return rbc().isFrenchCollation();
    }
    public int getVariableTop() {
        return rbc().getVariableTop();
    }
    public boolean getNumericCollation() {
        return rbc().getNumericCollation();
    }
    public int[] getReorderCodes() {
        return rbc().getReorderCodes();
    }
    public boolean equals(java.lang.Object p0) {
        return p0 instanceof RuleBasedCollator && rbc().equals(((RuleBasedCollator) p0).icu);
    }
    public int hashCode() {
        return rbc().hashCode();
    }
    public int compare(java.lang.String p0, java.lang.String p1) {
        return rbc().compare(p0, p1);
    }
    public android.icu.util.VersionInfo getVersion() {
        throw new RuntimeException("Stub!");
    }
    public android.icu.util.VersionInfo getUCAVersion() {
        throw new RuntimeException("Stub!");
    }
}
