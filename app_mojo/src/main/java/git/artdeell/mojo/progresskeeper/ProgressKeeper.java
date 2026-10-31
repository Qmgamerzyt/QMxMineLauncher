package git.artdeell.mojo.progresskeeper;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;

public class ProgressKeeper {
    private static final HashMap<String, List<ProgressListener>> sProgressListeners = new HashMap<>();
    private static final HashMap<String, ProgressState> sProgressStates = new HashMap<>();
    private static final List<TaskCountListener> sTaskCountListeners = new ArrayList<>();
    private static final List<KeyedProgressListener> sKeyedListeners = new ArrayList<>();

    public static synchronized void submitProgress(String progressRecord, int progress, int resid, Object... va) {
        ProgressState progressState = sProgressStates.get(progressRecord);
        boolean shouldCallStarted = progressState == null;
        boolean shouldCallEnded = resid == -1 && progress == -1;
        if(shouldCallEnded) {
            shouldCallStarted = false;
            sProgressStates.remove(progressRecord);
        }else if(shouldCallStarted){
            sProgressStates.put(progressRecord, (progressState = new ProgressState()));
        }
        if(shouldCallEnded || shouldCallStarted) updateTaskCount(sProgressStates.size());
        if(progressState != null) {
            progressState.progress = progress;
            progressState.resid = resid;
            progressState.varArg = va;
        }

        List<ProgressListener> progressListeners = sProgressListeners.get(progressRecord);
        if(progressListeners != null)
            for(ProgressListener listener : progressListeners) {
                    if(shouldCallStarted) listener.onProgressStarted();
                    else if(shouldCallEnded) listener.onProgressEnded();
                    else listener.onProgressUpdated(progress, resid, va);
            }
        // A keyed listener is shown every key as it comes and goes, so a list built
        // from them follows work of any key, including one made up on the spot
        for(KeyedProgressListener keyedListener : sKeyedListeners) {
            if(shouldCallStarted) keyedListener.onKeyStarted(progressRecord);
            else if(shouldCallEnded) keyedListener.onKeyEnded(progressRecord);
            else keyedListener.onKeyUpdated(progressRecord, progress, resid, va);
        }
    }

    private static void updateTaskCount(int count) {
        synchronized (sTaskCountListeners) {
            Iterator<TaskCountListener> iterator = sTaskCountListeners.iterator();
            while(iterator.hasNext()) {
                if(iterator.next().onUpdateTaskCount(count)) iterator.remove();
            }
        }
    }

    public static synchronized boolean hasProgressKey(String key) {
        return sProgressStates.get(key) != null;
    }

    public static synchronized void addListener(String progressRecord, ProgressListener listener) {
        ProgressState state = sProgressStates.get(progressRecord);
        if(state != null && (state.resid != -1 || state.progress != -1)) {
            listener.onProgressStarted();
            listener.onProgressUpdated(state.progress, state.resid, state.varArg);
        }else{
            listener.onProgressEnded();
        }
        List<ProgressListener> listenerWeakReferenceList = sProgressListeners.get(progressRecord);
        if(listenerWeakReferenceList == null) sProgressListeners.put(progressRecord, (listenerWeakReferenceList = new ArrayList<>()));
        listenerWeakReferenceList.add(listener);
    }

    public static synchronized void removeListener(String progressRecord, ProgressListener listener) {
        List<ProgressListener> listenerWeakReferenceList = sProgressListeners.get(progressRecord);
        if(listenerWeakReferenceList != null) listenerWeakReferenceList.remove(listener);
    }

    /**
     * Listen to every key at once. Work already running is replayed as the listener
     * is added, so a list built this way shows what started before it existed.
     */
    public static synchronized void addKeyedListener(KeyedProgressListener listener) {
        sKeyedListeners.add(listener);
        for(HashMap.Entry<String, ProgressState> entry : sProgressStates.entrySet()) {
            ProgressState state = entry.getValue();
            listener.onKeyStarted(entry.getKey());
            listener.onKeyUpdated(entry.getKey(), state.progress, state.resid, state.varArg);
        }
    }

    public static synchronized void removeKeyedListener(KeyedProgressListener listener) {
        sKeyedListeners.remove(listener);
    }

    /** One row of progress, wherever it comes from. */
    public interface KeyedProgressListener {
        void onKeyStarted(String key);
        void onKeyUpdated(String key, int progress, int resid, Object... va);
        void onKeyEnded(String key);
    }

    public static void addTaskCountListener(TaskCountListener listener) {
        addTaskCountListener(listener, true);
    }
    public static void addTaskCountListener(TaskCountListener listener, boolean runUpdate) {
        if(runUpdate) synchronized (ProgressKeeper.class) {
            listener.onUpdateTaskCount(sProgressStates.size());
        }
        synchronized (sTaskCountListeners) {
            if(!sTaskCountListeners.contains(listener)) sTaskCountListeners.add(listener);
        }
    }
    public static void removeTaskCountListener(TaskCountListener listener) {
        synchronized (sTaskCountListeners) {
            sTaskCountListeners.remove(listener);
        }
    }

    /**
     * Waits until all tasks are done and runs the runnable, or if there were no pending process remaining
     * The runnable runs from the thread that updated the task count last, and it might be the UI thread,
     * so don't put long running processes in it
     * @param runnable the runnable to run when no tasks are remaining
     */
    public static void waitUntilDone(final Runnable runnable) {
        // If we do it the other way the listener would be removed before it was added, which will cause a listener object leak
        if(getTaskCount() == 0) {
            runnable.run();
            return;
        }
        TaskCountListener listener = taskCount -> {
            if(taskCount == 0) {
                runnable.run();
                return true;
            }
            return false;
        };
        addTaskCountListener(listener);
    }

    public static synchronized int getTaskCount() {
        return sProgressStates.size();
    }

    /**
     * Takes every running task off the screen at once, which is what the stop button does
     * before the work itself notices and lets go.
     */
    public static void clearAll() {
        ArrayList<String> keys;
        synchronized (ProgressKeeper.class) {
            keys = new ArrayList<>(sProgressStates.keySet());
        }
        for(String key : keys) submitProgress(key, -1, -1);
    }

    public static boolean hasOngoingTasks() {
        return getTaskCount() > 0;
    }
}
