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
        // interrupt() sets the flag before it reads mLooper: if it read null, the flag is set now
        if (isInterrupted()) mLooper.quit();
        onLooperPrepared();
        Looper.loop();
    }

    /**
     * Interrupting the thread quits its looper. Android's looper ignores interrupts, so on Android
     * only quit() stops it; here an extension that ends a thread by interrupt would otherwise leak it.
     */
    @Override
    public void interrupt() {
        // The flag first: run() checks it after publishing the looper, so one of the two quits it
        super.interrupt();
        Looper looper;
        synchronized (this) {
            looper = mLooper;
        }
        if (looper != null) looper.quit();
    }

    public Looper getLooper() {
        if (!isAlive()) return null;
        boolean wasInterrupted = false;
        synchronized (this) {
            while (isAlive() && mLooper == null) {
                try {
                    wait();
                } catch (InterruptedException e) {
                    wasInterrupted = true;
                }
            }
        }
        if (wasInterrupted) Thread.currentThread().interrupt();
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
