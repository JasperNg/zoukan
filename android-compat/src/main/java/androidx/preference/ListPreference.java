package androidx.preference;

/*
 * Copyright (C) Contributors to the Suwayomi project
 *
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */

import android.content.Context;
import android.text.TextUtils;
import com.fasterxml.jackson.annotation.JsonIgnore;

public class ListPreference extends DialogPreference {
    // reference: https://android.googlesource.com/platform/frameworks/support/+/996971f962fcd554339a7cb2859cef9ca89dbcb7/preference/preference/src/main/java/androidx/preference/ListPreference.java
    // Note: remove @JsonIgnore and implement methods if any extension ever uses these methods or the variables behind them

    private CharSequence[] entries;
    private CharSequence[] entryValues;

    public ListPreference(Context context) {
        super(context);
    }

    public CharSequence[] getEntries() {
        return entries;
    }

    public void setEntries(CharSequence[] entries) {
        this.entries = entries;
    }

    public int findIndexOfValue(String value) {
        if (value != null && entryValues != null) {
            for (int i = entryValues.length - 1; i >= 0; i--) {
                if (TextUtils.equals(entryValues[i].toString(), value)) {
                    return i;
                }
            }
        }
        return -1;
    }

    public CharSequence[] getEntryValues() {
        return entryValues;
    }

    public void setEntryValues(CharSequence[] entryValues) {
        this.entryValues = entryValues;
    }

    // ZOUKAN -->
    // As Android: the value is the stored one; setValue persists it (once the preference has its
    // SharedPreferences) without calling the change listener.
    @JsonIgnore
    private String value;

    @JsonIgnore
    public void setValueIndex(int index) {
        if (entryValues != null) {
            setValue(entryValues[index].toString());
        }
    }

    @JsonIgnore
    public String getValue() {
        if (shouldPersist()) {
            Object current = getCurrentValue();
            if (current != null) {
                return current.toString();
            }
        }
        if (value != null) {
            return value;
        }
        Object defaultValue = getDefaultValue();
        return defaultValue instanceof String s ? s : null;
    }

    @JsonIgnore
    public void setValue(String value) {
        this.value = value;
        if (shouldPersist()) {
            saveNewValue(value);
        }
    }

    /** As Android's ListPreference.SimpleSummaryProvider: the chosen entry, or "Not set". */
    public static final class SimpleSummaryProvider implements SummaryProvider<ListPreference> {
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
        public CharSequence provideSummary(ListPreference preference) {
            CharSequence entry = preference.getEntry();
            return entry == null || entry.length() == 0 ? "Not set" : entry;
        }
    }

    @JsonIgnore
    public CharSequence getEntry() {
        int index = findIndexOfValue(getValue());
        return index >= 0 && entries != null && index < entries.length ? entries[index] : null;
    }
    // ZOUKAN <--

    /** Tachidesk specific API */
    @Override
    public String getDefaultValueType() {
        return "String";
    }
}
