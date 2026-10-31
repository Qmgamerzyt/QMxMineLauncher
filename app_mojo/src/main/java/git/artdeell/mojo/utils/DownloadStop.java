package git.artdeell.mojo.utils;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Holds the state the stop button works with. Running work reads the generation it
 * started with and gives up as soon as a newer one turns up, which ends what is in
 * flight and leaves what starts after the press alone. Work that hops from one file
 * to the next, a dependency walk for instance, also reads the generation of its own
 * start, so a press landing between two files is not walked past. A row of the
 * download list carries a key of its own, and stopping through that key only ends
 * the work filed under it, the rows beside it carry on.
 */
public final class DownloadStop {
    private static final AtomicLong sGeneration = new AtomicLong();
    private static volatile long sWorkGeneration;
    /** One generation per progress key, bumped when that row's stop is pressed. */
    private static final ConcurrentHashMap<String, AtomicLong> sKeyGenerations = new ConcurrentHashMap<>();

    private DownloadStop() {
    }

    /** The generation running work belongs to. */
    public static long generation() {
        return sGeneration.get();
    }

    /** Ends every download that is running. */
    public static void stopAll() {
        sGeneration.incrementAndGet();
    }

    /** The generation work filed under the given key started with. */
    public static long generationOf(String key) {
        return keyGeneration(key).get();
    }

    /** Ends only the work filed under the given key, leaving every other row alone. */
    public static void stopKey(String key) {
        keyGeneration(key).incrementAndGet();
    }

    /** Give up when the stop belonging to the given key was pressed after the generation was read. */
    public static void checkKey(String key, long generation) throws DownloadCancelledException {
        if (keyGeneration(key).get() != generation) throw new DownloadCancelledException();
    }

    private static AtomicLong keyGeneration(String key) {
        return sKeyGenerations.computeIfAbsent(key, ignored -> new AtomicLong());
    }

    /** Marks where the work that is about to start begins. */
    public static void beginWork() {
        sWorkGeneration = sGeneration.get();
    }

    /** Give up when the stop button was pressed after the current work began. */
    public static void checkWork() throws DownloadCancelledException {
        check(sWorkGeneration);
    }

    /** Give up when the stop button was pressed after the given generation was read. */
    public static void check(long generation) throws DownloadCancelledException {
        if (sGeneration.get() != generation) throw new DownloadCancelledException();
    }

    /** Whether the throwable came out of a stop rather than out of a fault. */
    public static boolean isStopped(Throwable throwable) {
        Throwable current = throwable;
        while (current != null) {
            if (current instanceof DownloadCancelledException) return true;
            Throwable cause = current.getCause();
            if (cause == current) break;
            current = cause;
        }
        return false;
    }
}
