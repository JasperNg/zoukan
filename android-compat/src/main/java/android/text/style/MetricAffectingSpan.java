package android.text.style;

// ZOUKAN -->
// Shadows Suwayomi android.jar's stub of this class (whose constructor throws "Stub!"), keeping every
// public member. Follows AOSP.

import android.text.TextPaint;

public abstract class MetricAffectingSpan extends CharacterStyle implements UpdateLayout {
    public MetricAffectingSpan() {
    }

    public abstract void updateMeasureState(TextPaint p);

    @Override
    public MetricAffectingSpan getUnderlying() {
        return this;
    }

    static class Passthrough extends MetricAffectingSpan {
        private final MetricAffectingSpan mStyle;

        Passthrough(MetricAffectingSpan cs) {
            mStyle = cs;
        }

        @Override
        public void updateDrawState(TextPaint tp) {
            mStyle.updateDrawState(tp);
        }

        @Override
        public void updateMeasureState(TextPaint tp) {
            mStyle.updateMeasureState(tp);
        }

        @Override
        public MetricAffectingSpan getUnderlying() {
            return mStyle.getUnderlying();
        }
    }
}
// ZOUKAN <--
