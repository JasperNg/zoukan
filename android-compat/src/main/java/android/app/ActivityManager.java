package android.app;

// ZOUKAN -->
// Shadows Suwayomi android.jar's stub of this class, keeping every public member. Members the
// extensions use are implemented; the rest still throw "Stub!" as in android.jar. Generated from
// `javap -public` of android.jar, then edited.
// ZOUKAN <--

public class ActivityManager {
    /** The desktop instance, returned by Context.getSystemService(ACTIVITY_SERVICE). */
    public static final ActivityManager INSTANCE = new ActivityManager();

    private ActivityManager() {
    }
    public static final java.lang.String ACTION_REPORT_HEAP_LIMIT = "android.app.action.REPORT_HEAP_LIMIT";
    public static final int LOCK_TASK_MODE_LOCKED = 1;
    public static final int LOCK_TASK_MODE_NONE = 0;
    public static final int LOCK_TASK_MODE_PINNED = 2;
    public static final java.lang.String META_HOME_ALTERNATE = "android.app.home.alternate";
    public static final int MOVE_TASK_NO_USER_ACTION = 2;
    public static final int MOVE_TASK_WITH_HOME = 1;
    public static final int RECENT_IGNORE_UNAVAILABLE = 2;
    public static final int RECENT_WITH_EXCLUDED = 1;
    public int getMemoryClass() {
        return (int) Math.min(Integer.MAX_VALUE, Runtime.getRuntime().maxMemory() / (1024 * 1024));
    }
    public int getLargeMemoryClass() {
        return getMemoryClass();
    }
    public boolean isLowRamDevice() {
        return false;
    }
    public java.util.List<android.app.ActivityManager.RecentTaskInfo> getRecentTasks(int p0, int p1) throws java.lang.SecurityException {
        throw new RuntimeException("Stub!");
    }
    public java.util.List<android.app.ActivityManager.AppTask> getAppTasks() {
        throw new RuntimeException("Stub!");
    }
    public android.util.Size getAppTaskThumbnailSize() {
        throw new RuntimeException("Stub!");
    }
    public int addAppTask(android.app.Activity p0, android.content.Intent p1, android.app.ActivityManager.TaskDescription p2, android.graphics.Bitmap p3) {
        throw new RuntimeException("Stub!");
    }
    public java.util.List<android.app.ActivityManager.RunningTaskInfo> getRunningTasks(int p0) throws java.lang.SecurityException {
        throw new RuntimeException("Stub!");
    }
    public void moveTaskToFront(int p0, int p1) {
        throw new RuntimeException("Stub!");
    }
    public void moveTaskToFront(int p0, int p1, android.os.Bundle p2) {
        throw new RuntimeException("Stub!");
    }
    public boolean isActivityStartAllowedOnDisplay(android.content.Context p0, int p1, android.content.Intent p2) {
        throw new RuntimeException("Stub!");
    }
    public java.util.List<android.app.ActivityManager.RunningServiceInfo> getRunningServices(int p0) throws java.lang.SecurityException {
        throw new RuntimeException("Stub!");
    }
    public android.app.PendingIntent getRunningServiceControlPanel(android.content.ComponentName p0) throws java.lang.SecurityException {
        throw new RuntimeException("Stub!");
    }
    public void getMemoryInfo(android.app.ActivityManager.MemoryInfo p0) {
        // The machine's physical memory, from the JVM's OperatingSystemMXBean
        long total = 0;
        long free = 0;
        if (java.lang.management.ManagementFactory.getOperatingSystemMXBean() instanceof com.sun.management.OperatingSystemMXBean os) {
            total = os.getTotalMemorySize();
            free = os.getFreeMemorySize();
        }
        if (total <= 0) {
            Runtime runtime = Runtime.getRuntime();
            total = runtime.maxMemory();
            free = runtime.maxMemory() - runtime.totalMemory() + runtime.freeMemory();
        }
        p0.totalMem = total;
        p0.availMem = free;
        p0.threshold = total / 16;
        p0.lowMemory = free < p0.threshold;
    }
    public boolean clearApplicationUserData() {
        throw new RuntimeException("Stub!");
    }
    public java.util.List<android.app.ActivityManager.ProcessErrorStateInfo> getProcessesInErrorState() {
        throw new RuntimeException("Stub!");
    }
    public boolean isBackgroundRestricted() {
        return false;
    }
    public java.util.List<android.app.ActivityManager.RunningAppProcessInfo> getRunningAppProcesses() {
        throw new RuntimeException("Stub!");
    }
    public java.util.List<android.app.ApplicationExitInfo> getHistoricalProcessExitReasons(java.lang.String p0, int p1, int p2) {
        throw new RuntimeException("Stub!");
    }
    public void setProcessStateSummary(byte[] p0) {
        throw new RuntimeException("Stub!");
    }
    public static boolean isLowMemoryKillReportSupported() {
        throw new RuntimeException("Stub!");
    }
    public static void getMyMemoryState(android.app.ActivityManager.RunningAppProcessInfo p0) {
        throw new RuntimeException("Stub!");
    }
    public android.os.Debug.MemoryInfo[] getProcessMemoryInfo(int[] p0) {
        throw new RuntimeException("Stub!");
    }
    public void restartPackage(java.lang.String p0) {
        throw new RuntimeException("Stub!");
    }
    public void killBackgroundProcesses(java.lang.String p0) {
        throw new RuntimeException("Stub!");
    }
    public android.content.pm.ConfigurationInfo getDeviceConfigurationInfo() {
        throw new RuntimeException("Stub!");
    }
    public int getLauncherLargeIconDensity() {
        throw new RuntimeException("Stub!");
    }
    public int getLauncherLargeIconSize() {
        throw new RuntimeException("Stub!");
    }
    public static boolean isUserAMonkey() {
        return false;
    }
    public static boolean isRunningInTestHarness() {
        return false;
    }
    public static boolean isRunningInUserTestHarness() {
        return false;
    }
    public void dumpPackageState(java.io.FileDescriptor p0, java.lang.String p1) {
        throw new RuntimeException("Stub!");
    }
    public void setWatchHeapLimit(long p0) {
        throw new RuntimeException("Stub!");
    }
    public void clearWatchHeapLimit() {
        throw new RuntimeException("Stub!");
    }
    public boolean isInLockTaskMode() {
        return false;
    }
    public int getLockTaskModeState() {
        return LOCK_TASK_MODE_NONE;
    }
    public static void setVrThread(int p0) {
        throw new RuntimeException("Stub!");
    }
    public void appNotResponding(java.lang.String p0) {
        throw new RuntimeException("Stub!");
    }

    public static class AppTask {
        public void finishAndRemoveTask() {
            throw new RuntimeException("Stub!");
        }
        public android.app.ActivityManager.RecentTaskInfo getTaskInfo() {
            throw new RuntimeException("Stub!");
        }
        public void moveToFront() {
            throw new RuntimeException("Stub!");
        }
        public void startActivity(android.content.Context p0, android.content.Intent p1, android.os.Bundle p2) {
            throw new RuntimeException("Stub!");
        }
        public void setExcludeFromRecents(boolean p0) {
            throw new RuntimeException("Stub!");
        }
    }

    public static class MemoryInfo implements android.os.Parcelable {
        public static final android.os.Parcelable.Creator<android.app.ActivityManager.MemoryInfo> CREATOR = null;
        public long availMem;
        public boolean lowMemory;
        public long threshold;
        public long totalMem;
        public MemoryInfo() {

        }
        public int describeContents() {
            return 0;
        }
        public void writeToParcel(android.os.Parcel p0, int p1) {
            throw new RuntimeException("Stub!");
        }
        public void readFromParcel(android.os.Parcel p0) {
            throw new RuntimeException("Stub!");
        }
    }

    public static class ProcessErrorStateInfo implements android.os.Parcelable {
        public static final int CRASHED = 1;
        public static final android.os.Parcelable.Creator<android.app.ActivityManager.ProcessErrorStateInfo> CREATOR = null;
        public static final int NOT_RESPONDING = 2;
        public static final int NO_ERROR = 0;
        public int condition;
        public byte[] crashData;
        public java.lang.String longMsg;
        public int pid;
        public java.lang.String processName;
        public java.lang.String shortMsg;
        public java.lang.String stackTrace;
        public java.lang.String tag;
        public int uid;
        public ProcessErrorStateInfo() {
            throw new RuntimeException("Stub!");
        }
        public int describeContents() {
            return 0;
        }
        public void writeToParcel(android.os.Parcel p0, int p1) {
            throw new RuntimeException("Stub!");
        }
        public void readFromParcel(android.os.Parcel p0) {
            throw new RuntimeException("Stub!");
        }
    }

    public static class RecentTaskInfo extends android.app.TaskInfo implements android.os.Parcelable {
        public static final android.os.Parcelable.Creator<android.app.ActivityManager.RecentTaskInfo> CREATOR = null;
        public int affiliatedTaskId;
        public java.lang.CharSequence description;
        public int id;
        public int persistentId;
        public RecentTaskInfo() {
            throw new RuntimeException("Stub!");
        }
        public int describeContents() {
            return 0;
        }
        public void readFromParcel(android.os.Parcel p0) {
            throw new RuntimeException("Stub!");
        }
        public void writeToParcel(android.os.Parcel p0, int p1) {
            throw new RuntimeException("Stub!");
        }
    }

    public static class RunningAppProcessInfo implements android.os.Parcelable {
        public static final android.os.Parcelable.Creator<android.app.ActivityManager.RunningAppProcessInfo> CREATOR = null;
        public static final int IMPORTANCE_BACKGROUND = 400;
        public static final int IMPORTANCE_CACHED = 400;
        public static final int IMPORTANCE_CANT_SAVE_STATE = 350;
        public static final int IMPORTANCE_EMPTY = 500;
        public static final int IMPORTANCE_FOREGROUND = 100;
        public static final int IMPORTANCE_FOREGROUND_SERVICE = 125;
        public static final int IMPORTANCE_GONE = 1000;
        public static final int IMPORTANCE_PERCEPTIBLE = 230;
        public static final int IMPORTANCE_PERCEPTIBLE_PRE_26 = 130;
        public static final int IMPORTANCE_SERVICE = 300;
        public static final int IMPORTANCE_TOP_SLEEPING = 325;
        public static final int IMPORTANCE_TOP_SLEEPING_PRE_28 = 150;
        public static final int IMPORTANCE_VISIBLE = 200;
        public static final int REASON_PROVIDER_IN_USE = 1;
        public static final int REASON_SERVICE_IN_USE = 2;
        public static final int REASON_UNKNOWN = 0;
        public int importance;
        public int importanceReasonCode;
        public android.content.ComponentName importanceReasonComponent;
        public int importanceReasonPid;
        public int lastTrimLevel;
        public int lru;
        public int pid;
        public java.lang.String[] pkgList;
        public java.lang.String processName;
        public int uid;
        public RunningAppProcessInfo() {
            throw new RuntimeException("Stub!");
        }
        public RunningAppProcessInfo(java.lang.String p0, int p1, java.lang.String[] p2) {
            throw new RuntimeException("Stub!");
        }
        public int describeContents() {
            return 0;
        }
        public void writeToParcel(android.os.Parcel p0, int p1) {
            throw new RuntimeException("Stub!");
        }
        public void readFromParcel(android.os.Parcel p0) {
            throw new RuntimeException("Stub!");
        }
    }

    public static class RunningServiceInfo implements android.os.Parcelable {
        public static final android.os.Parcelable.Creator<android.app.ActivityManager.RunningServiceInfo> CREATOR = null;
        public static final int FLAG_FOREGROUND = 2;
        public static final int FLAG_PERSISTENT_PROCESS = 8;
        public static final int FLAG_STARTED = 1;
        public static final int FLAG_SYSTEM_PROCESS = 4;
        public long activeSince;
        public int clientCount;
        public int clientLabel;
        public java.lang.String clientPackage;
        public int crashCount;
        public int flags;
        public boolean foreground;
        public long lastActivityTime;
        public int pid;
        public java.lang.String process;
        public long restarting;
        public android.content.ComponentName service;
        public boolean started;
        public int uid;
        public RunningServiceInfo() {
            throw new RuntimeException("Stub!");
        }
        public int describeContents() {
            return 0;
        }
        public void writeToParcel(android.os.Parcel p0, int p1) {
            throw new RuntimeException("Stub!");
        }
        public void readFromParcel(android.os.Parcel p0) {
            throw new RuntimeException("Stub!");
        }
    }

    public static class RunningTaskInfo extends android.app.TaskInfo implements android.os.Parcelable {
        public static final android.os.Parcelable.Creator<android.app.ActivityManager.RunningTaskInfo> CREATOR = null;
        public java.lang.CharSequence description;
        public int id;
        public int numRunning;
        public android.graphics.Bitmap thumbnail;
        public RunningTaskInfo() {
            throw new RuntimeException("Stub!");
        }
        public int describeContents() {
            return 0;
        }
        public void readFromParcel(android.os.Parcel p0) {
            throw new RuntimeException("Stub!");
        }
        public void writeToParcel(android.os.Parcel p0, int p1) {
            throw new RuntimeException("Stub!");
        }
    }

    public static class TaskDescription implements android.os.Parcelable {
        public static final android.os.Parcelable.Creator<android.app.ActivityManager.TaskDescription> CREATOR = null;
        public TaskDescription(java.lang.String p0, int p1, int p2) {
            throw new RuntimeException("Stub!");
        }
        public TaskDescription(java.lang.String p0, int p1) {
            throw new RuntimeException("Stub!");
        }
        public TaskDescription(java.lang.String p0) {
            throw new RuntimeException("Stub!");
        }
        public TaskDescription() {
            throw new RuntimeException("Stub!");
        }
        public TaskDescription(java.lang.String p0, android.graphics.Bitmap p1, int p2) {
            throw new RuntimeException("Stub!");
        }
        public TaskDescription(java.lang.String p0, android.graphics.Bitmap p1) {
            throw new RuntimeException("Stub!");
        }
        public TaskDescription(android.app.ActivityManager.TaskDescription p0) {
            throw new RuntimeException("Stub!");
        }
        public java.lang.String getLabel() {
            throw new RuntimeException("Stub!");
        }
        public android.graphics.Bitmap getIcon() {
            throw new RuntimeException("Stub!");
        }
        public int getPrimaryColor() {
            throw new RuntimeException("Stub!");
        }
        public int describeContents() {
            return 0;
        }
        public void writeToParcel(android.os.Parcel p0, int p1) {
            throw new RuntimeException("Stub!");
        }
        public void readFromParcel(android.os.Parcel p0) {
            throw new RuntimeException("Stub!");
        }
        public java.lang.String toString() {
            throw new RuntimeException("Stub!");
        }
        public boolean equals(java.lang.Object p0) {
            throw new RuntimeException("Stub!");
        }
    }
}
