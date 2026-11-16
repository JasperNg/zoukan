package androidx.preference;

/*
 * Copyright (C) Contributors to the Suwayomi project
 *
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.content.Context;
import android.widget.EditText;
import com.fasterxml.jackson.annotation.JsonIgnore;

public class EditTextPreference extends DialogPreference {
    // reference: https://android.googlesource.com/platform/frameworks/support/+/996971f962fcd554339a7cb2859cef9ca89dbcb7/preference/preference/src/main/java/androidx/preference/EditTextPreference.java

    private String text;

    @JsonIgnore
    private OnBindEditTextListener onBindEditTextListener;

    public EditTextPreference(Context context) {
        super(context);
    }

    // ZOUKAN -->
    // As Android: the text is the stored one; setText persists it (once the preference has its
    // SharedPreferences) without calling the change listener.
    public String getText() {
        if (shouldPersist()) {
            Object current = getCurrentValue();
            if (current != null) {
                return current.toString();
            }
        }
        if (text != null) {
            return text;
        }
        Object defaultValue = getDefaultValue();
        return defaultValue instanceof String s ? s : null;
    }

    public void setText(String text) {
        this.text = text;
        if (shouldPersist()) {
            saveNewValue(text);
        }
    }
    // ZOUKAN <--

    // ZOUKAN -->
    /** As Android: an empty text disables the dependents. */
    @Override
    public boolean shouldDisableDependents() {
        String current = getText();
        return current == null || current.isEmpty() || super.shouldDisableDependents();
    }

    /** As Android's EditTextPreference.SimpleSummaryProvider: the text, or "Not set". */
    public static final class SimpleSummaryProvider implements SummaryProvider<EditTextPreference> {
        private static SimpleSummaryProvider instance;

        private SimpleSummaryProvider() {
        }

        public static SimpleSummaryProvider getInstance() {
            if (instance == null) {
                instance = new SimpleSummaryProvider();
            }
            return instance;
        }

        @Override
        public CharSequence provideSummary(EditTextPreference preference) {
            String text = preference.getText();
            return text == null || text.isEmpty() ? "Not set" : text;
        }
    }
    // ZOUKAN <--

    public OnBindEditTextListener getOnBindEditTextListener() {
        return onBindEditTextListener;
    }

    public void setOnBindEditTextListener(@Nullable OnBindEditTextListener onBindEditTextListener) {
        this.onBindEditTextListener = onBindEditTextListener;
    }

    public interface OnBindEditTextListener {
        void onBindEditText(@NonNull EditText editText);
    }

    /** Tachidesk specific API */
    @Override
    public String getDefaultValueType() {
        return "String";
    }
}
