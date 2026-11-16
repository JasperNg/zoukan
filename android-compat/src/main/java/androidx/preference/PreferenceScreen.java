package androidx.preference;

/*
 * Copyright (C) Contributors to the Suwayomi project
 *
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */

import android.content.Context;

// ZOUKAN: whole file -->
// A PreferenceGroup, as on Android; addPreference and getPreferences (the direct children)
// are PreferenceGroup's
public class PreferenceScreen extends PreferenceGroup {
    public PreferenceScreen(Context context) {
        super(context);
    }
}
// ZOUKAN: whole file <--
