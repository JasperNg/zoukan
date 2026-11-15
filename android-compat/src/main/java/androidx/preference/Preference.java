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
        if (!isEnabled()) {
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

    @SuppressWarnings("unchecked")
    public CharSequence getSummary() {
        // ZOUKAN: as Android, a summary provider replaces the summary set
        if (summaryProvider != null) {
            return summaryProvider.provideSummary(this);
        }
        return summary;
    }

    public void setSummary(CharSequence summary) {
        this.summary = summary;
    }

    public void setEnabled(boolean enabled) {
        isEnabled = enabled;
    }

    public boolean isEnabled() {
        // ZOUKAN: as Android, also off while the preference it depends on disables its dependents,
        // or while its parent group is disabled
        return isEnabled && isDependencyMet() && (parent == null || parent.isEnabled());
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
        return key != null && sharedPreferences != null && persistent;
    }

    @JsonIgnore
    private PreferenceGroup parent;
    private String dependencyKey;
    private boolean persistent = true;
    private boolean iconSpaceReserved = true;
    private boolean singleLineTitle = true;
    private boolean selectable = true;
    private boolean copyingEnabled;
    private boolean shouldDisableView = true;
    private boolean allowDividerAbove = true;
    private boolean allowDividerBelow = true;
    private int order = DEFAULT_ORDER;
    private int layoutResource;
    private int widgetLayoutResource;
    private String fragment;
    @JsonIgnore
    private android.content.Intent intent;
    @JsonIgnore
    private android.graphics.drawable.Drawable icon;
    @JsonIgnore
    @SuppressWarnings("rawtypes")
    private SummaryProvider summaryProvider;
    @JsonIgnore
    private android.os.Bundle extras;

    public static final int DEFAULT_ORDER = Integer.MAX_VALUE;

    /** The group this preference was added to, or null. */
    @JsonIgnore
    public PreferenceGroup getParent() {
        return parent;
    }

    void assignParent(PreferenceGroup parent) {
        this.parent = parent;
    }

    /**
     * As Android: this preference is disabled while the preference with that key (on the same
     * screen) disables its dependents, which a switch does while it is off.
     */
    public void setDependency(String dependencyKey) {
        this.dependencyKey = dependencyKey;
    }

    public String getDependency() {
        return dependencyKey;
    }

    // Guards a dependency cycle (A depends on B, B on A), which would otherwise recurse forever
    private static final ThreadLocal<Set<Preference>> resolving = ThreadLocal.withInitial(HashSet::new);

    /** Whether the preference this one depends on (if any) leaves it enabled. */
    @JsonIgnore
    public boolean isDependencyMet() {
        if (dependencyKey == null) {
            return true;
        }
        PreferenceGroup root = parent;
        while (root != null && root.getParent() != null) {
            root = root.getParent();
        }
        Preference dependency = root == null ? null : root.findPreference(dependencyKey);
        if (dependency == null || dependency == this) {
            return true;
        }
        Set<Preference> visiting = resolving.get();
        if (!visiting.add(this)) {
            return true;
        }
        try {
            return !dependency.shouldDisableDependents();
        } finally {
            visiting.remove(this);
        }
    }

    /** As Android: whether the preferences that depend on this one are disabled. */
    public boolean shouldDisableDependents() {
        return !isEnabled();
    }

    public void setPersistent(boolean persistent) {
        this.persistent = persistent;
    }

    public boolean isPersistent() {
        return persistent;
    }

    public boolean hasKey() {
        return key != null && !key.isEmpty();
    }

    public void setIconSpaceReserved(boolean iconSpaceReserved) {
        this.iconSpaceReserved = iconSpaceReserved;
    }

    public boolean isIconSpaceReserved() {
        return iconSpaceReserved;
    }

    public void setSingleLineTitle(boolean singleLineTitle) {
        this.singleLineTitle = singleLineTitle;
    }

    public boolean isSingleLineTitle() {
        return singleLineTitle;
    }

    public void setSelectable(boolean selectable) {
        this.selectable = selectable;
    }

    public boolean isSelectable() {
        return selectable;
    }

    public void setCopyingEnabled(boolean enabled) {
        this.copyingEnabled = enabled;
    }

    public boolean isCopyingEnabled() {
        return copyingEnabled;
    }

    public void setShouldDisableView(boolean shouldDisableView) {
        this.shouldDisableView = shouldDisableView;
    }

    public boolean getShouldDisableView() {
        return shouldDisableView;
    }

    public void setAllowDividerAbove(boolean allowDividerAbove) {
        this.allowDividerAbove = allowDividerAbove;
    }

    public boolean isAllowDividerAbove() {
        return allowDividerAbove;
    }

    public void setAllowDividerBelow(boolean allowDividerBelow) {
        this.allowDividerBelow = allowDividerBelow;
    }

    public boolean isAllowDividerBelow() {
        return allowDividerBelow;
    }

    public void setOrder(int order) {
        this.order = order;
    }

    public int getOrder() {
        return order;
    }

    public void setLayoutResource(int layoutResId) {
        this.layoutResource = layoutResId;
    }

    public int getLayoutResource() {
        return layoutResource;
    }

    public void setWidgetLayoutResource(int widgetLayoutResId) {
        this.widgetLayoutResource = widgetLayoutResId;
    }

    public int getWidgetLayoutResource() {
        return widgetLayoutResource;
    }

    public void setFragment(String fragment) {
        this.fragment = fragment;
    }

    public String getFragment() {
        return fragment;
    }

    public void setIntent(android.content.Intent intent) {
        this.intent = intent;
    }

    @JsonIgnore
    public android.content.Intent getIntent() {
        return intent;
    }

    /** Icons are not drawn on the desktop; kept for the extension to read back. */
    public void setIcon(android.graphics.drawable.Drawable icon) {
        this.icon = icon;
    }

    public void setIcon(int iconResId) {
        this.icon = null;
    }

    @JsonIgnore
    public android.graphics.drawable.Drawable getIcon() {
        return icon;
    }

    @JsonIgnore
    public android.os.Bundle getExtras() {
        if (extras == null) {
            extras = new android.os.Bundle();
        }
        return extras;
    }

    @JsonIgnore
    public android.os.Bundle peekExtras() {
        return extras;
    }

    public void setTitle(int titleResId) {
        setTitle(context == null ? null : context.getString(titleResId));
    }

    public void setSummary(int summaryResId) {
        setSummary(context == null ? null : context.getString(summaryResId));
    }

    @SuppressWarnings("rawtypes")
    public final void setSummaryProvider(SummaryProvider summaryProvider) {
        this.summaryProvider = summaryProvider;
    }

    @JsonIgnore
    @SuppressWarnings("rawtypes")
    public final SummaryProvider getSummaryProvider() {
        return summaryProvider;
    }

    /** As Android: computes a preference's summary from its state, for example its value. */
    public interface SummaryProvider<T extends Preference> {
        CharSequence provideSummary(T preference);
    }

    /** Android redraws the preference; the desktop screen redraws after every change anyway. */
    protected void notifyChanged() {
    }

    public void notifyDependencyChange(boolean disableDependents) {
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
                case "Integer" -> 0;
                default -> throw new RuntimeException("Unsupported type");
            });
        }
        return switch (getDefaultValueType()) {
            case "String" -> sharedPreferences.getString(key, defaultValue instanceof String s ? s : null);
            case "Boolean" -> sharedPreferences.getBoolean(key, defaultValue instanceof Boolean b && b);
            case "Set<String>" -> sharedPreferences.getStringSet(key, defaultValue instanceof Set<?> set ? (Set<String>) set : null);
            case "Integer" -> sharedPreferences.getInt(key, defaultValue instanceof Integer i ? i : 0);
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
            case "Integer" -> sharedPreferences.edit().putInt(key, ((Number) value).intValue()).apply();
            default -> throw new RuntimeException("Unsupported type");
        }
    }
}
