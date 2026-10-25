package git.artdeell.mojo.modding.modpacks.models;

import android.util.Log;

import git.artdeell.mojo.utils.FileUtils;
import git.artdeell.mojo.utils.JSONUtils;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/**
 * Keeps track of the content that has been installed into an instance. It lets a
 * reinstall of the same project drop the file it replaced instead of leaving two
 * copies behind, and it lets the content manager list what is already present.
 */
public class ContentIndex {
    private static final String TAG = "ContentIndex";
    public static final String FILE_NAME = ".qmxmine_content.json";

    public List<Entry> entries = new ArrayList<>();

    public static class Entry {
        public String kind;
        public int apiSource;
        public String projectId;
        public String fileName;
        public String versionName;
    }

    public static File location(File gameDirectory) {
        return new File(gameDirectory, FILE_NAME);
    }

    /**
     * Read the index of an instance, returning an empty one when there is nothing
     * readable yet so that callers never have to handle a missing file.
     */
    public static ContentIndex read(File gameDirectory) {
        File file = location(gameDirectory);
        if(!file.isFile()) return new ContentIndex();
        try {
            ContentIndex index = JSONUtils.readFromFile(file, ContentIndex.class);
            if(index != null && index.entries != null) return index;
        }catch (IOException | RuntimeException e) {
            Log.w(TAG, "Could not read the content index of " + gameDirectory, e);
        }
        return new ContentIndex();
    }

    public void write(File gameDirectory) throws IOException {
        File file = location(gameDirectory);
        FileUtils.ensureParentDirectory(file);
        JSONUtils.writeToFile(file, this);
    }

    /**
     * @return the entry that recorded this project, or null if it was never installed
     */
    public Entry find(ContentKind kind, int apiSource, String projectId) {
        if(projectId == null) return null;
        for(Entry entry : entries) {
            if(entry.kind == null || !entry.kind.equals(kind.name())) continue;
            if(entry.apiSource != apiSource) continue;
            if(projectId.equals(entry.projectId)) return entry;
        }
        return null;
    }

    public void record(ContentKind kind, int apiSource, String projectId, String fileName, String versionName) {
        Entry entry = find(kind, apiSource, projectId);
        if(entry == null) {
            entry = new Entry();
            entries.add(entry);
        }
        entry.kind = kind.name();
        entry.apiSource = apiSource;
        entry.projectId = projectId;
        entry.fileName = fileName;
        entry.versionName = versionName;
    }
}
