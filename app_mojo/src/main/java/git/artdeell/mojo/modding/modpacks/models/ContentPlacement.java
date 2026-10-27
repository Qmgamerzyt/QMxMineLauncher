package git.artdeell.mojo.modding.modpacks.models;

import androidx.annotation.Nullable;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.TreeSet;

import git.artdeell.mojo.utils.FileUtils;

/**
 * Where the content of an instance sits while the game may see it, and where it is
 * kept while it is turned off. A file is left in the folder of its kind under a name
 * of its own, which keeps it out of the way of the game, while a world is carried
 * out of the saves folder entirely, because a folder sitting in there is listed as a
 * world whatever it is called.
 */
public class ContentPlacement {
    /** Ending given to a file that is turned off, inside the folder of its kind. */
    public static final String DISABLED_SUFFIX = ".disabled";
    /** Folder holding the worlds of an instance that are turned off. */
    public static final String DISABLED_WORLD_FOLDER = ".disabled_worlds";

    private ContentPlacement() {}

    /** The file or folder a piece of content sits at while the game may see it. */
    @Nullable
    public static File enabled(File gameDirectory, ContentKind kind, String fileName) {
        if(kind == null || kind.folder == null || fileName == null || fileName.isEmpty()) return null;
        return new File(new File(gameDirectory, kind.folder), fileName);
    }

    /** The file or folder a piece of content sits at while it is turned off. */
    @Nullable
    public static File disabled(File gameDirectory, ContentKind kind, String fileName) {
        if(kind == null || kind.folder == null || fileName == null || fileName.isEmpty()) return null;
        if(kind == ContentKind.WORLD)
            return new File(new File(gameDirectory, DISABLED_WORLD_FOLDER), fileName);
        return new File(new File(gameDirectory, kind.folder), fileName + DISABLED_SUFFIX);
    }

    /** Where the content sits at the moment, either of the two places, or null. */
    @Nullable
    public static File present(File gameDirectory, ContentKind kind, String fileName) {
        File enabled = enabled(gameDirectory, kind, fileName);
        if(enabled != null && enabled.exists()) return enabled;
        File disabled = disabled(gameDirectory, kind, fileName);
        if(disabled != null && disabled.exists()) return disabled;
        return null;
    }

    /** Whether the game may see this content at the moment. */
    public static boolean isEnabled(File gameDirectory, ContentKind kind, String fileName) {
        File enabled = enabled(gameDirectory, kind, fileName);
        return enabled != null && enabled.exists();
    }

    /** Every name a kind of content holds in an instance, the turned off ones among them. */
    public static List<String> names(File gameDirectory, ContentKind kind) {
        TreeSet<String> names = new TreeSet<>();
        if(kind == null || kind.folder == null) return new ArrayList<>(names);
        listInto(names, new File(gameDirectory, kind.folder), kind);
        if(kind == ContentKind.WORLD)
            listInto(names, new File(gameDirectory, DISABLED_WORLD_FOLDER), kind);
        return new ArrayList<>(names);
    }

    /** Read the names of one folder, leaving out anything the kind does not sit on. */
    private static void listInto(TreeSet<String> names, File folder, ContentKind kind) {
        File[] listed = folder.listFiles();
        if(listed == null) return;
        for(File file : listed) {
            String name = file.getName();
            if(name.isEmpty() || name.charAt(0) == '.') continue;
            if(kind != ContentKind.WORLD && name.endsWith(DISABLED_SUFFIX)) {
                name = name.substring(0, name.length() - DISABLED_SUFFIX.length());
                if(name.isEmpty()) continue;
            }
            names.add(name);
        }
    }

    /**
     * Move the content between the two places, turning it on or off. Whatever sits
     * in the way is the older of the two, so it goes.
     *
     * @param gameDirectory the instance the content belongs to
     * @param kind          the kind of the content, which decides where it sits
     * @param fileName      the name the content sits under
     * @param enabled       true to let the game see it, false to turn it off
     */
    public static void setEnabled(File gameDirectory, ContentKind kind, String fileName, boolean enabled)
            throws IOException {
        File from = enabled ? disabled(gameDirectory, kind, fileName) : enabled(gameDirectory, kind, fileName);
        File to = enabled ? enabled(gameDirectory, kind, fileName) : disabled(gameDirectory, kind, fileName);
        if(from == null || to == null) throw new IOException("Nothing is known about " + fileName);
        if(!from.exists()) {
            if(!to.exists()) throw new IOException(fileName + " is in neither of its places");
            return;
        }
        if(to.exists()) deleteRecursively(to);
        FileUtils.ensureParentDirectory(to);
        if(!from.renameTo(to))
            throw new IOException("Could not move " + from.getName() + " to " + to.getName());
    }

    /**
     * Drop the content wherever it currently sits, refusing to step outside of the
     * instance it belongs to.
     */
    public static void delete(File gameDirectory, ContentKind kind, String fileName) throws IOException {
        File file = present(gameDirectory, kind, fileName);
        if(file == null) return;
        String root = gameDirectory.getCanonicalPath() + File.separator;
        if(!file.getCanonicalPath().startsWith(root))
            throw new IOException("Refusing to touch a path outside of " + gameDirectory.getName());
        deleteRecursively(file);
    }

    /** Remove a file or a whole folder of content, the two being placed the same way. */
    public static void deleteRecursively(File file) throws IOException {
        if(file.isDirectory()) {
            org.apache.commons.io.FileUtils.deleteDirectory(file);
        }else {
            file.delete();
        }
    }
}
