package androidx.preference;

import android.content.Context;

// ZOUKAN: whole file -->

/** A minimal androidx.preference.PreferenceCategory: a titled section of a screen. */
public class PreferenceCategory extends PreferenceGroup {
    public PreferenceCategory(Context context) {
        super(context);
    }

    /** As Android: the row of a category itself cannot be selected. */
    @Override
    public boolean isSelectable() {
        return false;
    }

    /** As Android: a category disables what depends on it only when it is disabled itself. */
    @Override
    public boolean shouldDisableDependents() {
        return !super.isEnabled();
    }
}
// ZOUKAN: whole file <--
