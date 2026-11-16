package android.os;

// ZOUKAN -->
// Shadows Suwayomi android.jar's stub of LocaleList, keeping every public member. The default list
// is the JVM's default locale (java.util.Locale.getDefault()). setDefault keeps its override
// inside this class and never calls Locale.setDefault.

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;

public final class LocaleList implements Parcelable {
    public static final Parcelable.Creator<LocaleList> CREATOR = null;

    private static final LocaleList EMPTY = new LocaleList();
    private static volatile LocaleList sDefaultOverride;
    private static String sLoggedOverrideTags;

    private final Locale[] mList;

    public LocaleList(Locale... list) {
        if (list == null || list.length == 0) {
            mList = new Locale[0];
            return;
        }
        LinkedHashSet<Locale> seen = new LinkedHashSet<>();
        for (Locale locale : list) {
            if (locale == null) {
                throw new NullPointerException("list[" + seen.size() + "] is null");
            }
            if (!seen.add(locale)) {
                throw new IllegalArgumentException("list[" + seen.size() + "] is a repetition");
            }
        }
        mList = seen.toArray(new Locale[0]);
    }

    public Locale get(int index) {
        return (0 <= index && index < mList.length) ? mList[index] : null;
    }

    public boolean isEmpty() {
        return mList.length == 0;
    }

    public int size() {
        return mList.length;
    }

    public int indexOf(Locale locale) {
        for (int i = 0; i < mList.length; i++) {
            if (mList[i].equals(locale)) {
                return i;
            }
        }
        return -1;
    }

    @Override
    public boolean equals(Object other) {
        return other instanceof LocaleList && Arrays.equals(mList, ((LocaleList) other).mList);
    }

    @Override
    public int hashCode() {
        return Arrays.hashCode(mList);
    }

    @Override
    public String toString() {
        StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i < mList.length; i++) {
            if (i > 0) {
                sb.append(',');
            }
            sb.append(mList[i]);
        }
        return sb.append(']').toString();
    }

    @Override
    public int describeContents() {
        return 0;
    }

    @Override
    public void writeToParcel(Parcel dest, int parcelableFlags) {
        throw new RuntimeException("Stub!");
    }

    public String toLanguageTags() {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < mList.length; i++) {
            if (i > 0) {
                sb.append(',');
            }
            sb.append(mList[i].toLanguageTag());
        }
        return sb.toString();
    }

    public static LocaleList getEmptyLocaleList() {
        return EMPTY;
    }

    public static LocaleList forLanguageTags(String list) {
        if (list == null || list.isEmpty()) {
            return EMPTY;
        }
        List<Locale> locales = new ArrayList<>();
        for (String tag : list.split(",")) {
            Locale locale = Locale.forLanguageTag(tag.trim());
            if (!locales.contains(locale)) {
                locales.add(locale);
            }
        }
        return new LocaleList(locales.toArray(new Locale[0]));
    }

    public static boolean isPseudoLocale(android.icu.util.ULocale locale) {
        if (locale == null) {
            return false;
        }
        String language = locale.getLanguage();
        String country = locale.getCountry();
        return ("en".equals(language) && "XA".equals(country))
                || ("ar".equals(language) && "XB".equals(country));
    }

    public Locale getFirstMatch(String[] supportedLocales) {
        if (mList.length == 0 || supportedLocales == null || supportedLocales.length == 0) {
            return mList.length > 0 ? mList[0] : null;
        }
        for (Locale locale : mList) {
            for (String tag : supportedLocales) {
                Locale supported = Locale.forLanguageTag(tag);
                if (supported.equals(locale)) {
                    return supported;
                }
            }
            for (String tag : supportedLocales) {
                Locale supported = Locale.forLanguageTag(tag);
                if (supported.getLanguage().equals(locale.getLanguage())) {
                    return supported;
                }
            }
        }
        return Locale.forLanguageTag(supportedLocales[0]);
    }

    public static LocaleList getDefault() {
        LocaleList override = sDefaultOverride;
        return override != null ? override : new LocaleList(Locale.getDefault());
    }

    public static LocaleList getAdjustedDefault() {
        return getDefault();
    }

    public static void setDefault(LocaleList locales) {
        if (locales == null) {
            throw new NullPointerException("locales is null");
        }
        if (locales.isEmpty()) {
            throw new IllegalArgumentException("locales is empty");
        }
        // Every extension and the app share one JVM, so Locale.setDefault here would change date,
        // number and string formatting for the whole app. The override stays inside LocaleList.
        String tags = locales.toLanguageTags();
        boolean changed;
        synchronized (LocaleList.class) {
            changed = !tags.equals(sLoggedOverrideTags);
            sLoggedOverrideTags = tags;
            sDefaultOverride = locales;
        }
        if (changed) {
            android.util.Log.w("LocaleList", "An extension asked to change the default locale to " + tags
                    + "; only LocaleList.getDefault() reflects it, the app's locale is unchanged");
        }
    }
}
// ZOUKAN <--
