package android.text;


import android.graphics.drawable.Drawable;
import java.lang.reflect.Array;
import org.jetbrains.annotations.NotNull;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Element;
import org.jsoup.nodes.Node;
import org.jsoup.nodes.TextNode;
import org.jsoup.select.NodeTraversor;
import org.jsoup.select.NodeVisitor;
import org.xml.sax.XMLReader;

/**
 * Project: TachiServer
 * Author: nulldev
 * Creation Date: 16/08/16
 *
 * Android compat class for processing HTML. Produces plain text the way Android's
 * HtmlToSpannedConverter lays it out (no spans are kept).
 */

public class Html {

    public static final int FROM_HTML_SEPARATOR_LINE_BREAK_PARAGRAPH = 0x00000001;
    public static final int FROM_HTML_SEPARATOR_LINE_BREAK_HEADING = 0x00000002;
    public static final int FROM_HTML_SEPARATOR_LINE_BREAK_LIST_ITEM = 0x00000004;
    public static final int FROM_HTML_SEPARATOR_LINE_BREAK_LIST = 0x00000008;
    public static final int FROM_HTML_SEPARATOR_LINE_BREAK_DIV = 0x00000010;
    public static final int FROM_HTML_SEPARATOR_LINE_BREAK_BLOCKQUOTE = 0x00000020;
    public static final int FROM_HTML_OPTION_USE_CSS_COLORS = 0x00000100;
    public static final int FROM_HTML_MODE_LEGACY = 0x00000000;
    public static final int FROM_HTML_MODE_COMPACT = 0x0000003f;

    public static final int TO_HTML_PARAGRAPH_LINES_CONSECUTIVE = 0x00000000;
    public static final int TO_HTML_PARAGRAPH_LINES_INDIVIDUAL = 0x00000001;

    public static Spanned fromHtml(String source) {
        return fromHtml(source, FROM_HTML_MODE_LEGACY);
    }

    public static Spanned fromHtml(String source, int flags) {
        return new FakeSpanned(toPlainText(source, flags));
    }

    public static Spanned fromHtml(String source, Html.ImageGetter imageGetter, Html.TagHandler tagHandler) {
        return fromHtml(source, FROM_HTML_MODE_LEGACY, imageGetter, tagHandler);
    }

    /** The image getter and tag handler are not called: no spans or drawables are produced. */
    public static Spanned fromHtml(String source, int flags, Html.ImageGetter imageGetter,
            Html.TagHandler tagHandler) {
        return fromHtml(source, flags);
    }

    public static String toHtml(Spanned text) {
        return text.toString();
    }

    public static String toHtml(Spanned text, int option) {
        return toHtml(text);
    }

    /** From: http://stackoverflow.com/a/25228492/5054192 **/
    public static String escapeHtml(CharSequence s) {
        StringBuilder out = new StringBuilder(Math.max(16, s.length()));
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c > 127 || c == '"' || c == '<' || c == '>' || c == '&') {
                out.append("&#");
                out.append((int) c);
                out.append(';');
            } else {
                out.append(c);
            }
        }
        return out.toString();
    }

    /**
     * Mirrors HtmlToSpannedConverter: whitespace runs collapse to one space, br is a newline,
     * block elements are surrounded by a paragraph break (two newlines, or one when the
     * matching FROM_HTML_SEPARATOR flag is set), img is U+FFFC, entities are decoded
     * (&amp;nbsp; stays U+00A0). Like Android, trailing newlines are kept.
     */
    static String toPlainText(String source, int flags) {
        if (source == null || source.isEmpty()) return "";
        Element body = Jsoup.parseBodyFragment(source).body();
        StringBuilder out = new StringBuilder(source.length());
        NodeTraversor.traverse(new NodeVisitor() {
            private int skipDepth = 0;

            @Override
            public void head(@NotNull Node node, int depth) {
                if (skipDepth > 0) {
                    if (node instanceof Element) skipDepth++;
                    return;
                }
                if (node instanceof TextNode) {
                    appendCollapsed(out, ((TextNode) node).getWholeText());
                } else if (node instanceof Element && node != body) {
                    String tag = ((Element) node).normalName();
                    if (tag.equals("script") || tag.equals("style")) {
                        skipDepth = 1;
                    } else if (tag.equals("br")) {
                        out.append('\n');
                    } else if (tag.equals("img")) {
                        out.append('￼');
                    } else {
                        int margin = blockMargin(tag, flags);
                        if (margin > 0) appendNewlines(out, margin);
                    }
                }
            }

            @Override
            public void tail(@NotNull Node node, int depth) {
                if (skipDepth > 0) {
                    if (node instanceof Element) skipDepth--;
                    return;
                }
                if (node instanceof Element && node != body) {
                    int margin = blockMargin(((Element) node).normalName(), flags);
                    if (margin > 0) appendNewlines(out, margin);
                }
            }
        }, body);
        return out.toString();
    }

    /** Newlines around a block element, or 0 for an inline one. */
    private static int blockMargin(String tag, int flags) {
        switch (tag) {
            case "p":
                return margin(flags, FROM_HTML_SEPARATOR_LINE_BREAK_PARAGRAPH);
            case "div":
                return margin(flags, FROM_HTML_SEPARATOR_LINE_BREAK_DIV);
            case "ul":
                return margin(flags, FROM_HTML_SEPARATOR_LINE_BREAK_LIST);
            case "li":
                return margin(flags, FROM_HTML_SEPARATOR_LINE_BREAK_LIST_ITEM);
            case "blockquote":
                return margin(flags, FROM_HTML_SEPARATOR_LINE_BREAK_BLOCKQUOTE);
            case "h1":
            case "h2":
            case "h3":
            case "h4":
            case "h5":
            case "h6":
                return margin(flags, FROM_HTML_SEPARATOR_LINE_BREAK_HEADING);
            default:
                return 0;
        }
    }

    private static int margin(int flags, int flag) {
        return (flags & flag) != 0 ? 1 : 2;
    }

    /** Ensures the text ends with at least {@code minNewlines} newlines, unless it is empty. */
    private static void appendNewlines(StringBuilder out, int minNewlines) {
        int len = out.length();
        if (len == 0) return;
        int existing = 0;
        for (int i = len - 1; i >= 0 && existing < minNewlines && out.charAt(i) == '\n'; i--) {
            existing++;
        }
        for (int i = existing; i < minNewlines; i++) out.append('\n');
    }

    private static void appendCollapsed(StringBuilder out, String text) {
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c == ' ' || c == '\n' || c == '\t' || c == '\r' || c == '\f') {
                char pred = out.length() == 0 ? '\n' : out.charAt(out.length() - 1);
                if (pred != ' ' && pred != '\n') out.append(' ');
            } else {
                out.append(c);
            }
        }
    }

    public interface TagHandler {
        void handleTag(boolean var1, String var2, Editable var3, XMLReader var4);
    }

    public interface ImageGetter {
        Drawable getDrawable(String var1);
    }

    /** A Spanned with no spans, backed by plain text so every CharSequence method agrees. */
    private static final class FakeSpanned implements Spanned {

        private final String string;

        FakeSpanned(String string) {
            this.string = string;
        }

        @SuppressWarnings("unchecked")
        @Override
        public <T> T[] getSpans(int start, int end, Class<T> type) {
            return (T[]) Array.newInstance(type == null ? Object.class : type, 0);
        }

        @Override
        public int getSpanStart(Object tag) {
            return -1;
        }

        @Override
        public int getSpanEnd(Object tag) {
            return -1;
        }

        @Override
        public int getSpanFlags(Object tag) {
            return 0;
        }

        @Override
        public int nextSpanTransition(int start, int limit, Class type) {
            return limit;
        }

        @Override
        public int length() {
            return string.length();
        }

        @Override
        public char charAt(int index) {
            return string.charAt(index);
        }

        @Override
        public CharSequence subSequence(int start, int end) {
            return new FakeSpanned(string.substring(start, end));
        }

        @Override
        public boolean equals(Object other) {
            return other instanceof FakeSpanned && ((FakeSpanned) other).string.equals(string);
        }

        @Override
        public int hashCode() {
            return string.hashCode();
        }

        @NotNull
        @Override
        public String toString() {
            return string;
        }
    }
}
