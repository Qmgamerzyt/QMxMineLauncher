package git.artdeell.mojo.modding.modpacks.api;

import android.content.Context;
import android.widget.Toast;

import androidx.appcompat.app.AlertDialog;

import com.kdt.mcgui.ProgressLayout;

import git.artdeell.mojo.MojoApplication;
import git.artdeell.mojo.R;
import git.artdeell.mojo.Tools;
import git.artdeell.mojo.instances.Instance;
import git.artdeell.mojo.instances.Instances;
import git.artdeell.mojo.modding.modpacks.models.ContentIndex;
import git.artdeell.mojo.modding.modpacks.models.ContentKind;
import git.artdeell.mojo.modding.modpacks.models.ContentPlacement;
import git.artdeell.mojo.modding.modpacks.models.ModDetail;
import git.artdeell.mojo.progresskeeper.DownloaderProgressWrapper;
import git.artdeell.mojo.utils.DownloadUtils;
import git.artdeell.mojo.utils.FileUtils;
import git.artdeell.mojo.utils.ZipUtils;
import git.artdeell.mojo.utils.lifecycle.ContextExecutor;

import java.io.File;
import java.io.IOException;
import java.net.URLDecoder;
import java.util.Enumeration;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * Downloads a single piece of content (a mod, a resource pack, a shader pack or a
 * world) and puts it into the folder of an instance where the game expects it.
 */
public class ContentInstaller {

    /**
     * The work that happens once an instance has been chosen.
     */
    interface InstanceChosen {
        void chosen(Instance instance);
    }

    /**
     * The install itself, run after an instance was picked.
     */
    private interface InstallAction {
        File run(Instance instance) throws IOException;
    }

    /**
     * Ask the user which instance should receive the content, then download it there.
     * The listing and the download happen away from the UI thread.
     *
     * @param context used to report errors
     * @param modDetail the content that was picked
     * @param selectedVersion index of the chosen version in the detail's version list
     */
    public static void handleContentInstallation(Context context, ModDetail modDetail, int selectedVersion) {
        ProgressLayout.setProgress(ProgressLayout.INSTALL_MODPACK, 0, R.string.global_waiting);
        MojoApplication.sExecutorService.execute(() -> {
            try {
                List<Instance> instances = Instances.loadAllInstances();
                Instance selected = Instances.loadSelectedInstance();
                ContextExecutor.executeActivity(activity -> pickInstance(activity, instances, selected,
                        R.string.content_install_select_instance, R.string.generic_install,
                        instance -> MojoApplication.sExecutorService.execute(() -> installInto(context, instance,
                                in -> install(in, modDetail, selectedVersion)))));
            }catch (IOException e) {
                ProgressLayout.clearProgress(ProgressLayout.INSTALL_MODPACK);
                Tools.showErrorRemote(context, R.string.content_install_failed, e);
            }
        });
    }

    /**
     * Put a world archive that the user picked from their own storage into an instance.
     * The archive is owned by this call and is removed once the install is over.
     *
     * @param context used to report errors
     * @param archive the archive that was picked
     * @param archiveName the name the archive was picked under
     */
    public static void handleLocalWorldArchive(Context context, File archive, String archiveName) {
        String worldName = worldNameFor(FileUtils.removeExtension(archiveName));
        ProgressLayout.setProgress(ProgressLayout.INSTALL_MODPACK, 0, R.string.global_waiting);
        MojoApplication.sExecutorService.execute(() -> {
            try {
                List<Instance> instances = Instances.loadAllInstances();
                Instance selected = Instances.loadSelectedInstance();
                ContextExecutor.executeActivity(activity -> pickInstance(activity, instances, selected,
                        R.string.content_install_select_instance, R.string.generic_install,
                        instance -> MojoApplication.sExecutorService.execute(() -> installInto(context, instance,
                                in -> installLocalWorld(in, archive, worldName)))));
            }catch (IOException e) {
                archive.delete();
                ProgressLayout.clearProgress(ProgressLayout.INSTALL_MODPACK);
                Tools.showErrorRemote(context, R.string.content_install_failed, e);
            }
        });
    }

    /**
     * Show the list of instances and hand the one that was picked to the given work.
     *
     * @param titleRes heading of the picker
     * @param buttonRes label of the confirming button
     */
    static void pickInstance(Context context, List<Instance> instances, Instance selected,
                             int titleRes, int buttonRes, InstanceChosen chosen) {
        if(instances.isEmpty()) {
            ProgressLayout.clearProgress(ProgressLayout.INSTALL_MODPACK);
            Tools.showErrorRemote(context, R.string.content_install_no_instances,
                    new IOException("There is no instance to choose"));
            return;
        }
        String[] names = new String[instances.size()];
        int checked = 0;
        for(int i = 0; i < instances.size(); i++) {
            Instance instance = instances.get(i);
            names[i] = instance.name;
            if(selected != null && selected.name != null && selected.name.equals(instance.name)) checked = i;
        }
        new AlertDialog.Builder(context)
                .setTitle(titleRes)
                .setSingleChoiceItems(names, checked, null)
                .setPositiveButton(buttonRes, (dialog, which) -> {
                    int position = ((AlertDialog) dialog).getListView().getCheckedItemPosition();
                    if(position < 0 || position >= instances.size()) {
                        ProgressLayout.clearProgress(ProgressLayout.INSTALL_MODPACK);
                        return;
                    }
                    chosen.chosen(instances.get(position));
                })
                .setNegativeButton(android.R.string.cancel, (dialog, which) ->
                        ProgressLayout.clearProgress(ProgressLayout.INSTALL_MODPACK))
                .setOnCancelListener(dialog -> ProgressLayout.clearProgress(ProgressLayout.INSTALL_MODPACK))
                .show();
    }

    private static void installInto(Context context, Instance instance, InstallAction action) {
        try {
            action.run(instance);
            ContextExecutor.executeActivity(activity ->
                    Toast.makeText(activity, R.string.content_install_success, Toast.LENGTH_SHORT).show());
        }catch (IOException e) {
            Tools.showErrorRemote(context, R.string.content_install_failed, e);
        }finally {
            ProgressLayout.clearProgress(ProgressLayout.INSTALL_MODPACK);
        }
    }

    /**
     * Download one version of a content item into the folder of the given instance.
     * A file this project had installed before is removed, so an update never leaves
     * two copies of the same content behind.
     *
     * @return the file, or the world folder for a world, that the instance ended up with
     */
    public static File install(Instance instance, ModDetail detail, int selectedVersion) throws IOException {
        ContentKind kind = detail.kind;
        if(!kind.installsIntoExistingInstance())
            throw new IOException("Content of kind " + kind + " is not placed inside an instance");
        if(selectedVersion < 0 || selectedVersion >= detail.versionUrls.length)
            throw new IOException("Version " + selectedVersion + " does not exist for " + detail.title);

        String url = detail.versionUrls[selectedVersion];
        if(url == null || url.isEmpty())
            throw new IOException("The selected version of " + detail.title + " has no download link");

        File gameDirectory = instance.getGameDirectory();
        File destination = new File(gameDirectory, kind.folder);
        FileUtils.ensureDirectory(destination);

        ContentIndex index = ContentIndex.read(gameDirectory);
        ContentIndex.Entry previous = index.find(kind, detail.apiSource, detail.id);

        String fileName = fileNameFor(detail, selectedVersion);
        File staged = new File(Tools.DIR_CACHE, "content_" + System.nanoTime() + "_" + fileName);
        FileUtils.ensureParentDirectory(staged);

        String hash = detail.versionHashes[selectedVersion];
        byte[] buffer = new byte[8192];
        try {
            DownloadUtils.ensureSha1(staged, hash, () -> DownloadUtils.downloadFileMonitored(
                    url, staged, buffer,
                    new DownloaderProgressWrapper(R.string.modpack_download_downloading_metadata,
                            ProgressLayout.INSTALL_MODPACK)));
        }catch (IOException e) {
            staged.delete();
            throw e;
        }

        try {
            removeReplaced(gameDirectory, kind, previous);
            File placed;
            if(kind.unpacksArchive()) {
                placed = unpackWorld(staged, destination, worldNameFor(detail.title));
            }else {
                placed = new File(destination, fileName);
                org.apache.commons.io.FileUtils.moveFile(staged, placed);
            }
            index.record(kind, detail.apiSource, detail.id, placed.getName(), detail.title,
                    detail.versionNames[selectedVersion]);
            index.write(gameDirectory);
            return placed;
        }finally {
            staged.delete();
        }
    }

    /**
     * Unpack a world archive the user picked from their own storage into the saves
     * folder of an instance and write it down in that instance's index.
     */
    private static File installLocalWorld(Instance instance, File archive, String worldName) throws IOException {
        try {
            File gameDirectory = instance.getGameDirectory();
            File saves = new File(gameDirectory, ContentKind.WORLD.folder);
            FileUtils.ensureDirectory(saves);
            File placed = unpackWorld(archive, saves, worldName);
            ContentIndex index = ContentIndex.read(gameDirectory);
            index.record(ContentKind.WORLD, ContentIndex.SOURCE_LOCAL, worldName,
                    placed.getName(), worldName, null);
            index.write(gameDirectory);
            return placed;
        }finally {
            archive.delete();
        }
    }

    /**
     * Drop whatever this project had placed into the instance before, be it the file
     * the game reads or a copy that had been turned off.
     */
    private static void removeReplaced(File gameDirectory, ContentKind kind,
                                       ContentIndex.Entry previous) throws IOException {
        if(previous == null || previous.fileName == null) return;
        ContentPlacement.delete(gameDirectory, kind, previous.fileName);
    }

    /**
     * Build the name the file gets inside the instance, preferring the name the
     * provider gave it so that packs keep a name people recognise.
     */
    private static String fileNameFor(ModDetail detail, int selectedVersion) throws IOException {
        String name = null;
        String url = detail.versionUrls[selectedVersion];
        if(url != null) {
            name = FileUtils.getFileName(url);
            if(name != null) {
                int query = name.indexOf('?');
                if(query != -1) name = name.substring(0, query);
                if(name.startsWith("/")) name = name.substring(1);
                try {
                    name = URLDecoder.decode(name, "UTF-8");
                }catch (IOException | IllegalArgumentException ignored) {
                    // A malformed escape leaves the name as it came
                }
            }
        }
        if(name == null || name.isEmpty()) {
            name = detail.title + "-" + detail.versionNames[selectedVersion];
        }
        name = FileUtils.escapeFileName(name);
        if(name.length() > 200) name = name.substring(0, 200);
        if(name.isEmpty() || name.equals(".") || name.equals("..")) name = "content";
        return name;
    }

    /**
     * Turn any title into a folder name the saves list will show.
     */
    private static String worldNameFor(String title) {
        String raw = title == null || title.isEmpty() ? "world" : title;
        String name = FileUtils.escapeFileName(raw);
        if(name.isEmpty()) name = "world";
        if(name.length() > 60) name = name.substring(0, 60);
        return name;
    }

    /**
     * Unpack a world archive into the saves folder. World archives either hold the
     * world at their root or wrap it in a folder, so the root is looked up before
     * anything is moved.
     */
    private static File unpackWorld(File archive, File savesDirectory, String worldName) throws IOException {
        File temporary = new File(Tools.DIR_CACHE, "world_" + System.nanoTime());
        FileUtils.ensureDirectory(temporary);
        try {
            try(ZipFile zipFile = new ZipFile(archive)) {
                rejectEscapingEntries(zipFile, temporary);
                ZipUtils.zipExtract(zipFile, "", temporary);
            }
            File worldRoot = findWorldRoot(temporary);
            if(worldRoot == null)
                throw new IOException("The archive does not hold a single world");
            File target = uniqueDirectory(savesDirectory, worldName);
            if(worldRoot.equals(temporary)) {
                FileUtils.ensureDirectory(target);
                File[] children = temporary.listFiles();
                if(children != null) {
                    for(File child : children) {
                        File moveTarget = new File(target, child.getName());
                        if(child.isDirectory()) {
                            org.apache.commons.io.FileUtils.moveDirectory(child, moveTarget);
                        }else {
                            org.apache.commons.io.FileUtils.moveFile(child, moveTarget);
                        }
                    }
                }
            }else {
                org.apache.commons.io.FileUtils.moveDirectory(worldRoot, target);
            }
            return target;
        }finally {
            org.apache.commons.io.FileUtils.deleteQuietly(temporary);
        }
    }

    /**
     * @return the folder holding level.dat, or null when the archive holds no world
     *         or holds several of them
     */
    private static File findWorldRoot(File unpacked) {
        if(new File(unpacked, "level.dat").isFile()) return unpacked;
        File[] children = unpacked.listFiles();
        if(children == null) return null;
        File found = null;
        for(File child : children) {
            if(!child.isDirectory() || !new File(child, "level.dat").isFile()) continue;
            if(found != null) return null;
            found = child;
        }
        return found;
    }

    /**
     * Reject entries that would write outside of the folder they are unpacked into.
     */
    private static void rejectEscapingEntries(ZipFile zipFile, File destination) throws IOException {
        String root = destination.getCanonicalPath() + File.separator;
        Enumeration<? extends ZipEntry> entries = zipFile.entries();
        while(entries.hasMoreElements()) {
            String name = entries.nextElement().getName();
            if(!new File(destination, name).getCanonicalPath().startsWith(root))
                throw new IOException("Archive entry leaves the destination folder: " + name);
        }
    }

    private static File uniqueDirectory(File parent, String name) {
        File candidate = new File(parent, name);
        for(int suffix = 1; candidate.exists(); suffix++) {
            candidate = new File(parent, name + "-" + suffix);
        }
        return candidate;
    }
}
