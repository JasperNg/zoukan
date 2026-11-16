package androidx.preference;

import android.content.Context;
import android.content.SharedPreferences;
import com.fasterxml.jackson.annotation.JsonIgnore;

import java.util.LinkedList;
import java.util.List;

// ZOUKAN: whole file

/**
 * A minimal androidx.preference.PreferenceGroup: a preference that holds others (a screen or a
 * category). Children get the group's SharedPreferences, also when the group gets them after the
 * children were added.
 */
public abstract class PreferenceGroup extends Preference {
    @JsonIgnore
    private final List<Preference> preferences = new LinkedList<>();
    private boolean orderingAsAdded = true;
    private int currentPreferenceOrder = 0;
    private int initialExpandedChildrenCount = Integer.MAX_VALUE;

    public PreferenceGroup(Context context) {
        super(context);
    }

    public boolean addPreference(Preference preference) {
        if (preferences.contains(preference)) {
            return true;
        }
        // A preference has one parent: adding it to a second group moves it there
        PreferenceGroup previousParent = preference.getParent();
        if (previousParent != null && previousParent != this) {
            previousParent.removePreference(preference);
        }
        if (orderingAsAdded) {
            if (preference.getOrder() == Preference.DEFAULT_ORDER) {
                preference.setOrder(currentPreferenceOrder++);
            }
            if (preference instanceof PreferenceGroup group) {
                group.setOrderingAsAdded(orderingAsAdded);
            }
        }
        // propagate own shared preferences
        preference.setSharedPreferences(getSharedPreferences());
        preference.assignParent(this);
        // Sorted by order, stable: after the last preference whose order is not greater
        int index = preferences.size();
        for (int i = 0; i < preferences.size(); i++) {
            if (compareOrder(preferences.get(i), preference) > 0) {
                index = i;
                break;
            }
        }
        preferences.add(index, preference);
        return true;
    }

    private int compareOrder(Preference existing, Preference added) {
        if (existing.getOrder() != added.getOrder()) {
            return Integer.compare(existing.getOrder(), added.getOrder());
        }
        if (orderingAsAdded) {
            return 0;
        }
        return String.valueOf(existing.getTitle()).compareToIgnoreCase(String.valueOf(added.getTitle()));
    }

    public boolean removePreference(Preference preference) {
        boolean removed = preferences.remove(preference);
        if (removed) {
            preference.assignParent(null);
        }
        return removed;
    }

    public boolean removePreferenceRecursively(CharSequence key) {
        Preference preference = findPreference(key);
        if (preference == null || preference.getParent() == null) {
            return false;
        }
        return preference.getParent().removePreference(preference);
    }

    public void removeAll() {
        for (Preference preference : preferences) {
            preference.assignParent(null);
        }
        preferences.clear();
    }

    public int getPreferenceCount() {
        return preferences.size();
    }

    public Preference getPreference(int index) {
        return preferences.get(index);
    }

    /** The preference with this key in this group or a group in it, or null. */
    @SuppressWarnings("unchecked")
    public <T extends Preference> T findPreference(CharSequence key) {
        if (key == null) {
            throw new IllegalArgumentException("Key cannot be null");
        }
        if (key.toString().equals(getKey())) {
            return (T) this;
        }
        for (Preference preference : preferences) {
            if (key.toString().equals(preference.getKey())) {
                return (T) preference;
            }
            if (preference instanceof PreferenceGroup group) {
                Preference found = group.findPreference(key);
                if (found != null) {
                    return (T) found;
                }
            }
        }
        return null;
    }

    public void setOrderingAsAdded(boolean orderingAsAdded) {
        this.orderingAsAdded = orderingAsAdded;
    }

    public boolean isOrderingAsAdded() {
        return orderingAsAdded;
    }

    public void setInitialExpandedChildrenCount(int expandedCount) {
        this.initialExpandedChildrenCount = expandedCount;
    }

    public int getInitialExpandedChildrenCount() {
        return initialExpandedChildrenCount;
    }

    /** Tachidesk specific API: the direct children, in the order they were added. */
    @JsonIgnore
    public List<Preference> getPreferences() {
        return preferences;
    }

    /** Tachidesk specific API: the children get the same SharedPreferences. */
    @Override
    public void setSharedPreferences(SharedPreferences sharedPreferences) {
        super.setSharedPreferences(sharedPreferences);
        for (Preference preference : preferences) {
            preference.setSharedPreferences(sharedPreferences);
        }
    }

    /** A group stores no value of its own. */
    @Override
    public String getDefaultValueType() {
        return "None";
    }
}
