// ZOUKAN -->
package android.os;

/** A thread with a Looper, as on Android (Comix drains its offscreen display on one). */
public class HandlerThread extends Thread {
    private final int mPriority;
    private Looper mLooper;

    public HandlerThread(String name) {
        this(name, Process.THREAD_PRIORITY_DEFAULT);
    }

    public HandlerThread(String name, int priority) {
        super(name);
        mPriority = priority;
        setDaemon(true);
    }

    protected void onLooperPrepared() {
    }

    @Override
    public void run() {
        Looper.prepare();
        synchronized (this) {
            mLooper = Looper.myLooper();
            notifyAll();
        }
        onLooperPrepared();
        Looper.loop();
    }

    public Looper getLooper() {
        if (!isAlive()) return null;
        synchronized (this) {
            while (isAlive() && mLooper == null) {
                try {
                    wait();
                } catch (InterruptedException ignored) {
                }
            }
        }
        return mLooper;
    }

    public boolean quit() {
        Looper looper = getLooper();
        if (looper == null) return false;
        looper.quit();
        return true;
    }

    public boolean quitSafely() {
        Looper looper = getLooper();
        if (looper == null) return false;
        looper.quitSafely();
        return true;
    }

    public int getThreadId() {
        return (int) getId();
    }
}
// ZOUKAN <--
