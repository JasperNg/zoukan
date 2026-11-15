package androidx.preference;

/*
 * Copyright (C) Contributors to the Suwayomi project
 *
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */

import android.content.Context;
import com.fasterxml.jackson.annotation.JsonIgnore;

public class TwoStatePreference extends Preference {
    private CharSequence mSummaryOn;
    private CharSequence mSummaryOff;

    public TwoStatePreference(Context context) {
        super(context);
        setDefaultValue(false);
    }

    // ZOUKAN -->
    // As Android: the checked state is the stored one; setChecked persists it (once the preference
    // has its SharedPreferences) without calling the change listener.
    @JsonIgnore
    private Boolean checked;

    @JsonIgnore
    private boolean disableDependentsState;

    @JsonIgnore
    public boolean isChecked() {
        if (shouldPersist()) {
            return Boolean.TRUE.equals(getCurrentValue());
        }
        if (checked != null) {
            return checked;
        }
        return Boolean.TRUE.equals(getDefaultValue());
    }

    @JsonIgnore
    public void setChecked(boolean checked) {
        this.checked = checked;
        if (shouldPersist()) {
            saveNewValue(checked);
        }
    }
    // ZOUKAN <--

    @JsonIgnore
    public CharSequence getSummaryOn() {
        return mSummaryOn;
    }

    @JsonIgnore
    public void setSummaryOn(CharSequence summary) {
        this.mSummaryOn = summary;
    }

    @JsonIgnore
    public CharSequence getSummaryOff() {
        return mSummaryOff;
    }

    @JsonIgnore
    public void setSummaryOff(CharSequence summary) {
        this.mSummaryOff = summary;
    }

    @Override
    public CharSequence getSummary() {
        final CharSequence summary = super.getSummary();
        if (summary != null) {
            return summary;
        }

        final boolean checked = isChecked();
        if (checked && mSummaryOn != null) {
            return mSummaryOn;
        } else if (!checked && mSummaryOff != null) {
            return mSummaryOff;
        }

        return null;
    }

    @JsonIgnore
    public boolean getDisableDependentsState() {
        return disableDependentsState;
    }

    @JsonIgnore
    public void setDisableDependentsState(boolean disableDependentsState) {
        this.disableDependentsState = disableDependentsState;
    }

    /** Tachidesk specific API */
    @Override
    public String getDefaultValueType() {
        return "Boolean";
    }
}