package androidx.preference;

/*
 * Copyright (C) Contributors to the Suwayomi project
 *
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */

import android.content.Context;
import android.content.SharedPreferences;
import com.fasterxml.jackson.annotation.JsonIgnore;

import java.util.HashSet;
import java.util.Objects;
import java.util.Set;

/**
 * A minimal implementation of androidx.preference.Preference
 */
public class Preference {
    // reference: https://android.googlesource.com/platform/frameworks/support/+/996971f962fcd554339a7cb2859cef9ca89dbcb7/preference/preference/src/main/java/androidx/preference/Preference.java
    // Note: `Preference` doesn't actually hold or persist the value, `OnPreferenceChangeListener` is called and it's up to the extension to persist it.

    @JsonIgnore
    protected Context context;

    private boolean isVisible = true;
    private boolean isEnabled = true;
    private String key;
    private CharSequence title;
    private CharSequence summary;
    private Object defaultValue;

    /** Tachidesk specific API */
    @JsonIgnore
    private SharedPreferences sharedPreferences;

    @JsonIgnore
    public OnPreferenceChangeListener onChangeListener;

    // ZOUKAN -->
    @JsonIgnore
    private OnPreferenceClickListener onClickListener;
    // ZOUKAN <--

    public Preference(Context context) {
        this.context = context;
    }

    public Context getContext() {
        return context;
    }

    public void setOnPreferenceChangeListener(OnPreferenceChangeListener onPreferenceChangeListener) {
        this.onChangeListener = onPreferenceChangeListener;
    }

    // ZOUKAN -->
    public void setOnPreferenceClickListener(OnPreferenceClickListener onPreferenceClickListener) {
        this.onClickListener = onPreferenceClickListener;
    }

    @JsonIgnore
    public OnPreferenceClickListener getOnPreferenceClickListener() {
        return onClickListener;
    }

    @JsonIgnore
    public OnPreferenceChangeListener getOnPreferenceChangeListener() {
        return onChangeListener;
    }

    /** Zoukan specific API: a click on the preference's row, as Android's Preference.performClick(). */
    public boolean performClick() {
        if (!isEnabled) {
            return false;
        }
        return onClickListener != null && onClickListener.onPreferenceClick(this);
    }
    // ZOUKAN <--

    public CharSequence getTitle() {
        return title;
    }

    public void setTitle(CharSequence title) {
        this.title = title;
    }

    public CharSequence getSummary() {
        return summary;
    }

    public void setSummary(CharSequence summary) {
        this.summary = summary;
    }

    public void setEnabled(boolean enabled) {
        isEnabled = enabled;
    }

    public boolean isEnabled() {
        return isEnabled;
    }

    public String getKey() {
        return key;
    }

    public void setKey(String key) {
        this.key = key;
    }

    public void setDefaultValue(Object defaultValue) {
        this.defaultValue = defaultValue;
    }

    public boolean callChangeListener(Object newValue) {
        return onChangeListener == null || onChangeListener.onPreferenceChange(this, newValue);
    }

    public Object getDefaultValue() {
        return defaultValue;
    }

    /** Tachidesk specific API */
    public String getDefaultValueType() {
        return defaultValue.getClass().getSimpleName();
    }

    /** Tachidesk specific API */
    public SharedPreferences getSharedPreferences() {
        return sharedPreferences;
    }

    public void setVisible(boolean visible) {
        isVisible = visible;
    }

    public boolean getVisible() {
        return isVisible;
    }

    // ZOUKAN -->
    /** As Android's Preference.isVisible(). */
    public boolean isVisible() {
        return isVisible;
    }

    /**
     * As Android: whether a value set on this preference goes to its SharedPreferences. Here that
     * needs a key and the SharedPreferences the screen gives it.
     */
    protected boolean shouldPersist() {
        return key != null && sharedPreferences != null;
    }
    // ZOUKAN <--

    /** Tachidesk specific API */
    public void setSharedPreferences(SharedPreferences sharedPreferences) {
        this.sharedPreferences = sharedPreferences;
    }

    public interface OnPreferenceChangeListener {
        boolean onPreferenceChange(Preference preference, Object newValue);
    }

    public interface OnPreferenceClickListener {
        boolean onPreferenceClick(Preference preference);
    }

    /** Tachidesk specific API */
    @SuppressWarnings("unchecked")
    public Object getCurrentValue() {
        // ZOUKAN: also without SharedPreferences (a preference not added to a screen yet), and a
        // default value of the wrong type is ignored instead of throwing ClassCastException
        if (!shouldPersist()) {
            return Objects.requireNonNullElseGet(defaultValue, () -> switch (getDefaultValueType()) {
                case "String" -> "";
                case "Boolean" -> false;
                case "Set<String>" -> new HashSet<>();
                default -> throw new RuntimeException("Unsupported type");
            });
        }
        return switch (getDefaultValueType()) {
            case "String" -> sharedPreferences.getString(key, defaultValue instanceof String s ? s : null);
            case "Boolean" -> sharedPreferences.getBoolean(key, defaultValue instanceof Boolean b && b);
            case "Set<String>" -> sharedPreferences.getStringSet(key, defaultValue instanceof Set<?> set ? (Set<String>) set : null);
            default -> throw new RuntimeException("Unsupported type");
        };
    }

    /** Tachidesk specific API */
    @SuppressWarnings("unchecked")
    public void saveNewValue(Object value) {
        if (!shouldPersist()) {
            return;
        }
        switch (getDefaultValueType()) {
            case "String" -> sharedPreferences.edit().putString(key, (String) value).apply();
            case "Boolean" -> sharedPreferences.edit().putBoolean(key, (Boolean) value).apply();
            case "Set<String>" ->
                    sharedPreferences.edit().putStringSet(key, (Set<String>) value).apply();
            default -> throw new RuntimeException("Unsupported type");
        }
    }
}
