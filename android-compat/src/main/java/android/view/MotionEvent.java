package android.view;

// ZOUKAN -->
/**
 * Touch events for extensions that tap on a WebView page (for example Comix). The android.jar
 * stub throws in every method; this keeps the pointer data so KcefWebViewProvider can send it to
 * Chromium as mouse events. It does not extend InputEvent, whose stub constructor throws.
 */
public final class MotionEvent {
    public static final int ACTION_MASK = 0xff;
    public static final int ACTION_DOWN = 0;
    public static final int ACTION_UP = 1;
    public static final int ACTION_MOVE = 2;
    public static final int ACTION_CANCEL = 3;
    public static final int ACTION_OUTSIDE = 4;
    public static final int ACTION_POINTER_DOWN = 5;
    public static final int ACTION_POINTER_UP = 6;
    public static final int ACTION_HOVER_MOVE = 7;
    public static final int ACTION_SCROLL = 8;
    public static final int ACTION_HOVER_ENTER = 9;
    public static final int ACTION_HOVER_EXIT = 10;
    public static final int ACTION_BUTTON_PRESS = 11;
    public static final int ACTION_BUTTON_RELEASE = 12;
    public static final int ACTION_POINTER_INDEX_MASK = 0xff00;
    public static final int ACTION_POINTER_INDEX_SHIFT = 8;

    public static final int AXIS_X = 0;
    public static final int AXIS_Y = 1;
    public static final int AXIS_PRESSURE = 2;
    public static final int AXIS_SIZE = 3;

    public static final int TOOL_TYPE_UNKNOWN = 0;
    public static final int TOOL_TYPE_FINGER = 1;
    public static final int TOOL_TYPE_STYLUS = 2;
    public static final int TOOL_TYPE_MOUSE = 3;
    public static final int TOOL_TYPE_ERASER = 4;

    public static final int BUTTON_PRIMARY = 1;
    public static final int BUTTON_SECONDARY = 2;
    public static final int BUTTON_TERTIARY = 4;

    public static final int EDGE_TOP = 1;
    public static final int EDGE_BOTTOM = 2;
    public static final int EDGE_LEFT = 4;
    public static final int EDGE_RIGHT = 8;

    private long downTime;
    private long eventTime;
    private int action;
    private int metaState;
    private int buttonState;
    private int source;
    private int deviceId;
    private int flags;
    private int edgeFlags;
    private PointerProperties[] properties;
    private PointerCoords[] coords;

    private MotionEvent() {
    }

    public static MotionEvent obtain(long downTime, long eventTime, int action, int pointerCount,
            PointerProperties[] pointerProperties, PointerCoords[] pointerCoords, int metaState,
            int buttonState, float xPrecision, float yPrecision, int deviceId, int edgeFlags,
            int source, int flags) {
        MotionEvent event = new MotionEvent();
        event.downTime = downTime;
        event.eventTime = eventTime;
        event.action = action;
        event.metaState = metaState;
        event.buttonState = buttonState;
        event.deviceId = deviceId;
        event.edgeFlags = edgeFlags;
        event.source = source;
        event.flags = flags;
        event.properties = new PointerProperties[pointerCount];
        event.coords = new PointerCoords[pointerCount];
        for (int i = 0; i < pointerCount; i++) {
            event.properties[i] = new PointerProperties(pointerProperties[i]);
            event.coords[i] = new PointerCoords(pointerCoords[i]);
        }
        return event;
    }

    public static MotionEvent obtain(long downTime, long eventTime, int action, int pointerCount,
            PointerProperties[] pointerProperties, PointerCoords[] pointerCoords, int metaState,
            int buttonState, float xPrecision, float yPrecision, int deviceId, int edgeFlags,
            int source, int displayId, int flags) {
        return obtain(downTime, eventTime, action, pointerCount, pointerProperties, pointerCoords,
            metaState, buttonState, xPrecision, yPrecision, deviceId, edgeFlags, source, flags);
    }

    public static MotionEvent obtain(long downTime, long eventTime, int action, float x, float y,
            float pressure, float size, int metaState, float xPrecision, float yPrecision,
            int deviceId, int edgeFlags) {
        PointerProperties p = new PointerProperties();
        p.toolType = TOOL_TYPE_FINGER;
        PointerCoords c = new PointerCoords();
        c.x = x;
        c.y = y;
        c.pressure = pressure;
        c.size = size;
        return obtain(downTime, eventTime, action, 1, new PointerProperties[] {p},
            new PointerCoords[] {c}, metaState, 0, xPrecision, yPrecision, deviceId, edgeFlags, 0, 0);
    }

    public static MotionEvent obtain(long downTime, long eventTime, int action, float x, float y,
            int metaState) {
        return obtain(downTime, eventTime, action, x, y, 1f, 1f, metaState, 1f, 1f, 0, 0);
    }

    public static MotionEvent obtain(MotionEvent other) {
        return obtain(other.downTime, other.eventTime, other.action, other.properties.length,
            other.properties, other.coords, other.metaState, other.buttonState, 1f, 1f,
            other.deviceId, other.edgeFlags, other.source, other.flags);
    }

    public void recycle() {
    }

    public long getDownTime() {
        return downTime;
    }

    public long getEventTime() {
        return eventTime;
    }

    public int getAction() {
        return action;
    }

    public int getActionMasked() {
        return action & ACTION_MASK;
    }

    public int getActionIndex() {
        return (action & ACTION_POINTER_INDEX_MASK) >> ACTION_POINTER_INDEX_SHIFT;
    }

    public void setAction(int action) {
        this.action = action;
    }

    public int getPointerCount() {
        return coords.length;
    }

    public int getPointerId(int pointerIndex) {
        return properties[pointerIndex].id;
    }

    public int getToolType(int pointerIndex) {
        return properties[pointerIndex].toolType;
    }

    public float getX() {
        return getX(0);
    }

    public float getY() {
        return getY(0);
    }

    public float getX(int pointerIndex) {
        return coords[pointerIndex].x;
    }

    public float getY(int pointerIndex) {
        return coords[pointerIndex].y;
    }

    public float getRawX() {
        return getX();
    }

    public float getRawY() {
        return getY();
    }

    public float getPressure() {
        return coords[0].pressure;
    }

    public float getSize() {
        return coords[0].size;
    }

    public int getMetaState() {
        return metaState;
    }

    public int getButtonState() {
        return buttonState;
    }

    public int getSource() {
        return source;
    }

    public void setSource(int source) {
        this.source = source;
    }

    public int getDeviceId() {
        return deviceId;
    }

    public int getFlags() {
        return flags;
    }

    public int getEdgeFlags() {
        return edgeFlags;
    }

    public void setLocation(float x, float y) {
        offsetLocation(x - getX(), y - getY());
    }

    public void offsetLocation(float deltaX, float deltaY) {
        for (PointerCoords c : coords) {
            c.x += deltaX;
            c.y += deltaY;
        }
    }

    public void getPointerCoords(int pointerIndex, PointerCoords outPointerCoords) {
        outPointerCoords.copyFrom(coords[pointerIndex]);
    }

    public void getPointerProperties(int pointerIndex, PointerProperties outPointerProperties) {
        outPointerProperties.copyFrom(properties[pointerIndex]);
    }

    public static String actionToString(int action) {
        return "ACTION_" + action;
    }

    @Override
    public String toString() {
        return "MotionEvent{action=" + action + ", x=" + getX() + ", y=" + getY() + "}";
    }

    public static final class PointerProperties {
        public int id;
        public int toolType;

        public PointerProperties() {
            clear();
        }

        public PointerProperties(PointerProperties other) {
            copyFrom(other);
        }

        public void clear() {
            id = -1;
            toolType = TOOL_TYPE_UNKNOWN;
        }

        public void copyFrom(PointerProperties other) {
            id = other.id;
            toolType = other.toolType;
        }
    }

    public static final class PointerCoords {
        public float x;
        public float y;
        public float pressure;
        public float size;
        public float touchMajor;
        public float touchMinor;
        public float toolMajor;
        public float toolMinor;
        public float orientation;

        public PointerCoords() {
        }

        public PointerCoords(PointerCoords other) {
            copyFrom(other);
        }

        public void clear() {
            x = 0;
            y = 0;
            pressure = 0;
            size = 0;
            touchMajor = 0;
            touchMinor = 0;
            toolMajor = 0;
            toolMinor = 0;
            orientation = 0;
        }

        public void copyFrom(PointerCoords other) {
            x = other.x;
            y = other.y;
            pressure = other.pressure;
            size = other.size;
            touchMajor = other.touchMajor;
            touchMinor = other.touchMinor;
            toolMajor = other.toolMajor;
            toolMinor = other.toolMinor;
            orientation = other.orientation;
        }

        public float getAxisValue(int axis) {
            switch (axis) {
                case AXIS_X: return x;
                case AXIS_Y: return y;
                case AXIS_PRESSURE: return pressure;
                case AXIS_SIZE: return size;
                default: return 0;
            }
        }

        public void setAxisValue(int axis, float value) {
            switch (axis) {
                case AXIS_X: x = value; break;
                case AXIS_Y: y = value; break;
                case AXIS_PRESSURE: pressure = value; break;
                case AXIS_SIZE: size = value; break;
                default: break;
            }
        }
    }
}
// ZOUKAN <--
