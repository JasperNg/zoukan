package android.widget;

/*
 * Copyright (C) Contributors to the Suwayomi project
 *
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */

public class Toast {
    public static final int LENGTH_LONG = 1;
    public static final int LENGTH_SHORT = 0;

    private CharSequence text;
    // ZOUKAN -->
    private android.content.Context context;
    private int duration;
    // ZOUKAN <--

    private Toast(CharSequence text) {
        this.text = text;
    }

    // ZOUKAN -->
    public Toast(android.content.Context context) {
        this.context = context;
    }

    // The desktop app shows the text (see xyz.nulldev.androidcompat.ToastSink)
    public void show() {
        xyz.nulldev.androidcompat.ToastSink.show(text);
    }

    public void cancel() {
        // Nothing to take back: the desktop toast hides itself
    }
    // ZOUKAN <--

    public void setView(android.view.View view) {
        throw new RuntimeException("Stub!");
    }

    public android.view.View getView() {
        throw new RuntimeException("Stub!");
    }

    // ZOUKAN -->
    public void setDuration(int duration) {
        this.duration = duration;
    }

    public int getDuration() {
        return duration;
    }
    // ZOUKAN <--

    public void setMargin(float horizontalMargin, float verticalMargin) {
        throw new RuntimeException("Stub!");
    }

    public float getHorizontalMargin() {
        throw new RuntimeException("Stub!");
    }

    public float getVerticalMargin() {
        throw new RuntimeException("Stub!");
    }

    public void setGravity(int gravity, int xOffset, int yOffset) {
        throw new RuntimeException("Stub!");
    }

    public int getGravity() {
        throw new RuntimeException("Stub!");
    }

    public int getXOffset() {
        throw new RuntimeException("Stub!");
    }

    public int getYOffset() {
        throw new RuntimeException("Stub!");
    }

    public static Toast makeText(android.content.Context context, java.lang.CharSequence text, int duration) {
        // ZOUKAN -->
        Toast toast = new Toast(text);
        toast.context = context;
        toast.duration = duration;
        return toast;
        // ZOUKAN <--
    }

    // ZOUKAN -->
    public static android.widget.Toast makeText(android.content.Context context, int resId, int duration) throws android.content.res.Resources.NotFoundException {
        return makeText(context, textOf(context, resId), duration);
    }

    public void setText(int resId) {
        this.text = textOf(context, resId);
    }

    // The desktop has no extension resources to look a string up in: an id that cannot be read
    // leaves the toast without text (only logged) rather than failing the extension's call
    private static CharSequence textOf(android.content.Context context, int resId) {
        try {
            return context.getResources().getText(resId);
        } catch (RuntimeException e) {
            android.util.Log.w("Toast", "No text for string resource " + resId, e);
            return null;
        }
    }

    public void setText(java.lang.CharSequence s) {
        this.text = s;
    }
    // ZOUKAN <--
}