package git.artdeell.mojo.modding.modpacks.api;

import android.content.Context;
import android.net.Uri;
import android.util.Log;
import android.widget.Toast;

import androidx.annotation.Nullable;
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
import git.artdeell.mojo.utils.DownloadStop;
import git.artdeell.mojo.utils.DownloadUtils;
import git.artdeell.mojo.utils.FileUtils;
import git.artdeell.mojo.utils.ZipUtils;
import git.artdeell.mojo.utils.lifecycle.ContextExecutor;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.URLDecoder;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
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
     * Ask the player which instance should receive the content, then download it
     * there along with the projects the chosen version leans on.
     *
     * @param context          used to report errors
     * @param api              the providers, used to open each dependency
     * @param modDetail        the content that was picked
     * @param selectedVersion  index of the chosen version in the detail's version list
     * @param titles           the titles found while the dependency list was offered
     * @param approvedOptional keys of the optional dependencies that should come along
     */
    public static void handleContentInstallation(Context context, ModpackApi api, ModDetail modDetail,
                                                 int selectedVersion, Map<String, String> titles,
                                                 Set<String> approvedOptional,
                                                 Map<String, Integer> chosenVersions) {
        DownloadStop.beginWork();
        String progressKey = ProgressLayout.contentInstallKey();
        ProgressLayout.setProgress(progressKey, 0, R.string.content_install_preparing);
        ArrayList<String> missed = new ArrayList<>();
        MojoApplication.sExecutorService.execute(() -> {
            try {
                List<Instance> instances = Instances.loadAllInstances();
                Instance selected = Instances.loadSelectedInstance();
                ContextExecutor.executeActivity(activity -> pickInstance(activity, instances, selected,
                        R.string.content_install_select_instance, R.string.generic_install,
                        progressKey,
                        instance -> MojoApplication.sExecutorService.execute(() -> installInto(context, instance,
                                in -> {
                                    install(in, modDetail, selectedVersion, progressKey);
                                    missed.addAll(DependencyResolver.installAll(context, api, in,
                                            modDetail, selectedVersion, titles, approvedOptional,
                                            chosenVersions));
                                    return null;
                                }, missed, progressKey))),
                        () -> ProgressLayout.clearProgress(progressKey));
            }catch (IOException e) {
                ProgressLayout.clearProgress(progressKey);
                Tools.showErrorRemote(context, R.string.content_install_failed, e);
            }
        });
    }

    /**
     * Put files the player picked from their own storage into an instance, one instance
     * asked for the whole batch. Every file is copied into the cache first, since the
     * picker hands out content that may live anywhere, then it lands in the folder of
     * its kind or, for a world, is unpacked into the saves folder.
     *
     * @param context used to report errors and to ask which instance takes the files
     * @param kind    the kind the content screen is on
     * @param files   the picked files, each carrying the name it was picked under
     */
    public static void handleLocalFiles(Context context, ContentKind kind, List<PickedFile> files) {
        if(files == null || files.isEmpty()) return;
        if(kind == null || !kind.installsIntoExistingInstance()) return;
        DownloadStop.beginWork();
        String progressKey = ProgressLayout.contentInstallKey();
        ProgressLayout.setProgress(progressKey, 0, R.string.content_install_preparing);
        MojoApplication.sExecutorService.execute(() -> {
            try {
                List<Instance> instances = Instances.loadAllInstances();
                Instance selected = Instances.loadSelectedInstance();
                ContextExecutor.executeActivity(activity -> pickInstance(activity, instances, selected,
                        R.string.content_install_select_instance, R.string.generic_install,
                        progressKey,
                        instance -> MojoApplication.sExecutorService.execute(() ->
                                placePicked(context, instance, kind, files, progressKey))),
                        () -> ProgressLayout.clearProgress(progressKey));
            }catch (IOException e) {
                ProgressLayout.clearProgress(progressKey);
                Tools.showErrorRemote(context, R.string.content_install_failed, e);
            }
        });
    }

    /**
     * The same batch of picked files, for a caller that already knows which instance
     * takes them. The game drawer asks this way, since it runs the instance it is on.
     *
     * @param context  used to report errors
     * @param instance the instance the files are placed into
     * @param kind     the kind the files were picked for
     * @param files    the picked files, each carrying the name it was picked under
     */
    public static void handleLocalFilesInto(Context context, Instance instance, ContentKind kind,
                                            List<PickedFile> files) {
        if(files == null || files.isEmpty() || instance == null) return;
        if(kind == null || !kind.installsIntoExistingInstance()) return;
        DownloadStop.beginWork();
        String progressKey = ProgressLayout.contentInstallKey();
        ProgressLayout.setProgress(progressKey, 0, R.string.content_install_preparing);
        MojoApplication.sExecutorService.execute(() -> placePicked(context, instance, kind, files, progressKey));
    }

    /**
     * Copy every picked file into the instance and write down what landed there. A file
     * that does not belong in the folder of its kind is left out rather than placed, so
     * the game is never handed something it cannot read.
     */
    private static void placePicked(Context context, Instance instance, ContentKind kind,
                                    List<PickedFile> files, String progressKey) {
        int placed = 0;
        int leftOut = 0;
        IOException failure = null;
        try {
            File gameDirectory = instance.getGameDirectory();
            File destination = new File(gameDirectory, kind.folder);
            FileUtils.ensureDirectory(destination);
            ContentIndex index = ContentIndex.read(gameDirectory);
            long keyGeneration = DownloadStop.generationOf(progressKey);
            for(int i = 0; i < files.size(); i++) {
                DownloadStop.checkWork();
                DownloadStop.checkKey(progressKey, keyGeneration);
                PickedFile picked = files.get(i);
                ProgressLayout.setProgress(progressKey,
                        (int)(i * 100L / files.size()), R.string.content_install_copying_n,
                        i + 1, files.size());
                if(!accepts(kind, picked.name)) {
                    leftOut++;
                    continue;
                }
                File staged = null;
                try {
                    staged = stage(context, picked, progressKey, keyGeneration);
                    String title = FileUtils.removeExtension(picked.name);
                    if(kind == ContentKind.WORLD) {
                        File world = unpackWorld(staged, destination, worldNameFor(title));
                        index.record(kind, ContentIndex.SOURCE_LOCAL, world.getName(),
                                world.getName(), title, null, null);
                    }else {
                        String fileName = targetName(kind, picked.name);
                        File placedFile = new File(destination, fileName);
                        if(placedFile.exists() && !placedFile.delete())
                            throw new IOException("Could not replace " + fileName);
                        org.apache.commons.io.FileUtils.moveFile(staged, placedFile);
                        index.record(kind, ContentIndex.SOURCE_LOCAL, fileName, fileName,
                                title, null, null);
                    }
                    placed++;
                }catch (IOException e) {
                    // One file going wrong leaves the rest of the batch to carry on
                    if(DownloadStop.isStopped(e)) throw e;
                    if(failure == null) failure = e;
                    Log.w("ContentInstaller", "Could not import " + picked.name, e);
                }finally {
                    if(staged != null && staged.exists()) staged.delete();
                }
            }
            index.write(gameDirectory);
        }catch (IOException e) {
            ProgressLayout.clearProgress(progressKey);
            Tools.showErrorRemote(context, R.string.content_install_failed, e);
            return;
        }
        ProgressLayout.clearProgress(progressKey);
        reportPicked(context, instance, kind, placed, leftOut, failure);
    }

    /** Copy one picked file into the cache under a name of its own. */
    private static File stage(Context context, PickedFile picked, String progressKey,
                              long keyGeneration) throws IOException {
        String safeName = FileUtils.escapeFileName(picked.name);
        if(safeName.isEmpty() || safeName.equals(".") || safeName.equals("..")) safeName = "content";
        File staged = new File(Tools.DIR_CACHE, "pick_" + System.nanoTime() + "_" + safeName);
        FileUtils.ensureParentDirectory(staged);
        try(InputStream input = context.getContentResolver().openInputStream(picked.uri);
            OutputStream output = new FileOutputStream(staged)) {
            if(input == null) throw new IOException("Could not read " + picked.name);
            DownloadUtils.copyCancellable(input, output,
                    () -> DownloadStop.checkKey(progressKey, keyGeneration));
            output.flush();
        }catch (IOException e) {
            staged.delete();
            throw e;
        }
        return staged;
    }

    /** Whether a file picked under this name may be placed in the folder of the kind. */
    private static boolean accepts(ContentKind kind, String name) {
        String lower = name == null ? "" : name.toLowerCase(Locale.ROOT);
        switch (kind) {
            case MOD: return lower.endsWith(".jar");
            case RESOURCE_PACK: return lower.endsWith(".zip") || lower.endsWith(".mcpack");
            case SHADER: return lower.endsWith(".zip");
            case WORLD: return lower.endsWith(".zip") || lower.endsWith(".mcpack");
            default: return false;
        }
    }

    /** The name the file gets inside the folder of the kind, packs renamed to a zip. */
    private static String targetName(ContentKind kind, String name) {
        String safe = FileUtils.escapeFileName(name);
        if(kind == ContentKind.RESOURCE_PACK && safe.toLowerCase(Locale.ROOT).endsWith(".mcpack"))
            safe = safe.substring(0, safe.length() - ".mcpack".length()) + ".zip";
        if(safe.isEmpty() || safe.equals(".") || safe.equals("..")) safe = "content";
        return safe;
    }

    /** Say how much of the batch made it, and when nothing did, what stood in the way. */
    private static void reportPicked(Context context, Instance instance, ContentKind kind,
                                     int placed, int leftOut, IOException failure) {
        Context appContext = context.getApplicationContext();
        if(placed == 0) {
            StringBuilder message = new StringBuilder(leftOut > 0
                    ? appContext.getString(R.string.content_import_skipped, leftOut,
                        appContext.getString(kind.labelRes))
                    : appContext.getString(R.string.content_import_none));
            if(failure != null) {
                String reason = failure.getMessage();
                if(reason == null || reason.isEmpty()) reason = failure.getClass().getSimpleName();
                message.append('\n').append(reason);
            }
            String text = message.toString();
            Tools.runOnUiThread(() -> new AlertDialog.Builder(appContext)
                    .setTitle(R.string.content_import_local_file)
                    .setMessage(text)
                    .setPositiveButton(android.R.string.ok, null)
                    .show());
            return;
        }
        String done = appContext.getString(R.string.content_import_done, placed,
                instance.displayName());
        Tools.runOnUiThread(() -> Toast.makeText(appContext, done, Toast.LENGTH_LONG).show());
        if(leftOut > 0) {
            String skipped = appContext.getString(R.string.content_import_skipped, leftOut,
                    appContext.getString(kind.labelRes));
            Tools.runOnUiThread(() -> Toast.makeText(appContext, skipped, Toast.LENGTH_LONG).show());
        }
    }

    /**
     * A file the player picked from their own storage, carried with the name the picker
     * showed for it. The launcher reads it through the picker while it makes a copy, so
     * nothing of it is kept beyond that.
     */
    public static final class PickedFile {
        public final Uri uri;
        public final String name;

        public PickedFile(Uri uri, String name) {
            this.uri = uri;
            this.name = name == null || name.isEmpty() ? "content" : name;
        }
    }

    /**
     * Show the list of instances and hand the one that was picked to the given work.
     *
     * @param titleRes heading of the picker
     * @param buttonRes label of the confirming button
     */
    static void pickInstance(Context context, List<Instance> instances, Instance selected,
                             int titleRes, int buttonRes, @Nullable String progressKey,
                             InstanceChosen chosen) {
        if(instances.isEmpty()) {
            clearRow(progressKey);
            Tools.showErrorRemote(context, R.string.content_install_no_instances,
                    new IOException("There is no instance to choose"));
            return;
        }
        String[] names = new String[instances.size()];
        int checked = 0;
        for(int i = 0; i < instances.size(); i++) {
            Instance instance = instances.get(i);
            names[i] = instance.displayName();
            if(selected != null && instance.displayName().equals(selected.displayName())) checked = i;
        }
        new AlertDialog.Builder(context)
                .setTitle(titleRes)
                .setSingleChoiceItems(names, checked, null)
                .setPositiveButton(buttonRes, (dialog, which) -> {
                    int position = ((AlertDialog) dialog).getListView().getCheckedItemPosition();
                    if(position < 0 || position >= instances.size()) {
                        clearRow(progressKey);
                        return;
                    }
                    chosen.chosen(instances.get(position));
                })
                .setNegativeButton(android.R.string.cancel, (dialog, which) ->
                        clearRow(progressKey))
                .setOnCancelListener(dialog -> clearRow(progressKey))
                .show();
    }

    /** Takes one job's row off the screen, callers that hold no row passing nothing. */
    private static void clearRow(@Nullable String progressKey) {
        if(progressKey != null) ProgressLayout.clearProgress(progressKey);
    }

    /**
     * Run the install, then say how it went. An install that brought dependencies
     * along reports what did not make it as one list, standing in for the note that
     * everything went in.
     *
     * @param missed filled by the action with what did not make it, null when the
     *               install does not look for dependencies
     */
    private static void installInto(Context context, Instance instance, InstallAction action,
                                    List<String> missed, String progressKey) {
        try {
            action.run(instance);
            if(missed != null && !missed.isEmpty()) {
                showMissed(context, missed);
            }else {
                ContextExecutor.executeActivity(activity ->
                        Toast.makeText(activity, R.string.content_install_success, Toast.LENGTH_SHORT).show());
            }
        }catch (IOException e) {
            Tools.showErrorRemote(context, R.string.content_install_failed, e);
        }finally {
            ProgressLayout.clearProgress(progressKey);
        }
    }

    /** One report of every dependency that did not make it into the instance. */
    private static void showMissed(Context context, List<String> missed) {
        StringBuilder message = new StringBuilder(
                context.getString(R.string.content_dependencies_report));
        for(String name : missed) {
            message.append("\n- ").append(name);
        }
        ContextExecutor.executeActivity(activity -> new AlertDialog.Builder(activity)
                .setTitle(R.string.content_dependencies_report_title)
                .setMessage(message.toString())
                .setPositiveButton(android.R.string.ok, null)
                .show());
    }

    /**
     * Download one version of a content item into the folder of the given instance.
     * A file this project had installed before is removed, so an update never leaves
     * two copies of the same content behind.
     *
     * @return the file, or the world folder for a world, that the instance ended up with
     */
    public static File install(Instance instance, ModDetail detail, int selectedVersion) throws IOException {
        return install(instance, detail, selectedVersion, ProgressLayout.contentInstallKey());
    }

    /** The same install, filing its progress under the row the given key names. */
    public static File install(Instance instance, ModDetail detail, int selectedVersion,
                               String progressKey) throws IOException {
        ContentKind kind = detail.kind;
        if(!kind.installsIntoExistingInstance())
            throw new IOException("Content of kind " + kind + " is not placed inside an instance");
        DownloadStop.checkWork();
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
        long keyGeneration = DownloadStop.generationOf(progressKey);
        try {
            DownloadUtils.ensureSha1(staged, hash, () -> DownloadUtils.downloadFileMonitored(
                    url, staged, buffer,
                    new DownloaderProgressWrapper(kind.downloadProgressRes, progressKey),
                    () -> DownloadStop.checkKey(progressKey, keyGeneration)));
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
                    detail.versionNames[selectedVersion], detail.imageUrl);
            index.write(gameDirectory);
            return placed;
        }finally {
            staged.delete();
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
