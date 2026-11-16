package android.text.style;

// ZOUKAN -->
// Shadows Suwayomi android.jar's stub of this class (whose constructor throws "Stub!"), keeping every
// public member. Follows AOSP.

import android.text.TextPaint;

public abstract class CharacterStyle {
    public CharacterStyle() {
    }

    public abstract void updateDrawState(TextPaint tp);

    public static CharacterStyle wrap(CharacterStyle cs) {
        if (cs instanceof MetricAffectingSpan) {
            return new MetricAffectingSpan.Passthrough((MetricAffectingSpan) cs);
        } else {
            return new Passthrough(cs);
        }
    }

    public CharacterStyle getUnderlying() {
        return this;
    }

    private static class Passthrough extends CharacterStyle {
        private final CharacterStyle mStyle;

        Passthrough(CharacterStyle cs) {
            mStyle = cs;
        }

        @Override
        public void updateDrawState(TextPaint tp) {
            mStyle.updateDrawState(tp);
        }

        @Override
        public CharacterStyle getUnderlying() {
            return mStyle.getUnderlying();
        }
    }
}
// ZOUKAN <--
