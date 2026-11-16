package androidx.preference;

import android.content.Context;
import com.fasterxml.jackson.annotation.JsonIgnore;

// ZOUKAN: whole file -->

/**
 * A minimal androidx.preference.SeekBarPreference: an int between min and max, persisted as an
 * int. As Android, setValue clamps to the range and persists without calling the change listener.
 */
public class SeekBarPreference extends Preference {
    private int min;
    private int max = 100;
    private int seekBarIncrement;
    private boolean showSeekBarValue;
    private boolean adjustable = true;
    private boolean updatesContinuously;
    @JsonIgnore
    private Integer value;

    public SeekBarPreference(Context context) {
        super(context);
        setDefaultValue(0);
    }

    public int getMin() {
        return min;
    }

    public void setMin(int min) {
        this.min = Math.min(min, max);
    }

    public int getMax() {
        return max;
    }

    public void setMax(int max) {
        this.max = Math.max(max, min);
    }

    public int getSeekBarIncrement() {
        return seekBarIncrement;
    }

    public void setSeekBarIncrement(int seekBarIncrement) {
        this.seekBarIncrement = Math.min(max - min, Math.abs(seekBarIncrement));
    }

    public boolean getShowSeekBarValue() {
        return showSeekBarValue;
    }

    public void setShowSeekBarValue(boolean showSeekBarValue) {
        this.showSeekBarValue = showSeekBarValue;
    }

    public boolean isAdjustable() {
        return adjustable;
    }

    public void setAdjustable(boolean adjustable) {
        this.adjustable = adjustable;
    }

    public boolean getUpdatesContinuously() {
        return updatesContinuously;
    }

    public void setUpdatesContinuously(boolean updatesContinuously) {
        this.updatesContinuously = updatesContinuously;
    }

    @JsonIgnore
    public int getValue() {
        if (shouldPersist()) {
            Object current = getCurrentValue();
            if (current instanceof Integer i) {
                return i;
            }
        }
        if (value != null) {
            return value;
        }
        Object defaultValue = getDefaultValue();
        return defaultValue instanceof Integer i ? i : min;
    }

    @JsonIgnore
    public void setValue(int seekBarValue) {
        int clamped = Math.max(min, Math.min(max, seekBarValue));
        this.value = clamped;
        if (shouldPersist()) {
            saveNewValue(clamped);
        }
    }

    /** Tachidesk specific API */
    @Override
    public String getDefaultValueType() {
        return "Integer";
    }
}
// ZOUKAN: whole file <--
