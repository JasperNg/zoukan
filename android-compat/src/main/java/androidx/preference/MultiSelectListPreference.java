package androidx.preference;

/*
 * Copyright (C) Contributors to the Suwayomi project
 *
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */

import android.content.Context;
import com.fasterxml.jackson.annotation.JsonIgnore;

import android.text.TextUtils;

import java.util.HashSet;
import java.util.Set;

public class MultiSelectListPreference extends DialogPreference {
    // reference: https://android.googlesource.com/platform/frameworks/support/+/996971f962fcd554339a7cb2859cef9ca89dbcb7/preference/preference/src/main/java/androidx/preference/MultiSelectListPreference.java
    // Note: remove @JsonIgnore and implement methods if any extension ever uses these methods or the variables behind them

    private CharSequence[] entries;
    private CharSequence[] entryValues;

    public MultiSelectListPreference(Context context) {
        super(context);
    }

    public void setEntries(CharSequence[] entries) {
        this.entries = entries;
    }

    public CharSequence[] getEntries() {
        return entries;
    }

    public void setEntryValues(CharSequence[] entryValues) {
        this.entryValues = entryValues;
    }

    public CharSequence[] getEntryValues() {
        return entryValues;
    }

    // ZOUKAN -->
    // As Android: setValues stores the values (persisting them once the preference has its
    // SharedPreferences) without calling the change listener.
    @JsonIgnore
    private Set<String> values;

    @JsonIgnore
    public void setValues(Set<String> values) {
        this.values = values == null ? new HashSet<>() : new HashSet<>(values);
        if (shouldPersist()) {
            saveNewValue(this.values);
        }
    }

    @JsonIgnore
    @SuppressWarnings("unchecked")
    public Set<String> getValues() {
        if (shouldPersist()) {
            Object current = getCurrentValue();
            if (current != null) {
                return (Set<String>) current;
            }
        }
        if (values != null) {
            return values;
        }
        Object defaultValue = getDefaultValue();
        return defaultValue instanceof Set ? (Set<String>) defaultValue : new HashSet<>();
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
    // ZOUKAN <--

    /** Tachidesk specific API */
    @Override
    public String getDefaultValueType() {
        return "Set<String>";
    }
}