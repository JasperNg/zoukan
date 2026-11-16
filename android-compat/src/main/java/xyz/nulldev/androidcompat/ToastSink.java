package xyz.nulldev.androidcompat;

import java.util.function.Consumer;

/**
 * Zoukan specific: where android.widget.Toast#show() sends its text. The desktop app sets it at
 * startup so extension toasts appear in its window; android-compat itself has no UI. Without a sink
 * the text is only printed.
 */
public final class ToastSink {
    private static volatile Consumer<String> sink;

    private ToastSink() {}

    public static void set(Consumer<String> consumer) {
        sink = consumer;
    }

    public static Consumer<String> get() {
        return sink;
    }

    /** Sends the text to the sink, or prints it when there is none. Never throws. */
    public static void show(CharSequence text) {
        String message = text == null ? "" : text.toString();
        Consumer<String> current = sink;
        if (current == null || message.isBlank()) {
            System.out.printf("made a Toast: \"%s\"%n", message);
            return;
        }
        try {
            current.accept(message);
        } catch (RuntimeException e) {
            System.out.printf("made a Toast: \"%s\" (not shown: %s)%n", message, e);
        }
    }
}
