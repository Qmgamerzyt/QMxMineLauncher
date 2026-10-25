package git.artdeell.mojo.modding.modpacks.api;

import android.content.Context;
import android.widget.Toast;

import androidx.appcompat.app.AlertDialog;

import git.artdeell.mojo.MojoApplication;
import git.artdeell.mojo.R;
import git.artdeell.mojo.Tools;
import git.artdeell.mojo.instances.Instance;
import git.artdeell.mojo.instances.Instances;
import git.artdeell.mojo.modding.modpacks.models.ContentIndex;
import git.artdeell.mojo.modding.modpacks.models.ContentKind;
import git.artdeell.mojo.utils.lifecycle.ContextExecutor;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/**
 * Shows what an instance already holds and takes it away again. The list comes from
 * the index the installer keeps, so it only ever offers files that the launcher
 * itself placed.
 */
public class InstalledContent {

    /**
     * Ask which instance to look at, then show what that instance holds.
     *
     * @param context used to report errors
     */
    public static void handleManage(Context context) {
        MojoApplication.sExecutorService.execute(() -> {
            try {
                List<Instance> instances = Instances.loadAllInstances();
                Instance selected = Instances.loadSelectedInstance();
                ContextExecutor.executeActivity(activity -> ContentInstaller.pickInstance(
                        activity, instances, selected,
                        R.string.content_manage_pick_instance, android.R.string.ok,
                        instance -> showList(activity, instance)));
            }catch (IOException e) {
                Tools.showErrorRemote(context, R.string.content_install_failed, e);
            }
        });
    }

    private static void showList(Context context, Instance instance) {
        File gameDirectory = instance.getGameDirectory();
        ContentIndex index = ContentIndex.read(gameDirectory);
        List<ContentIndex.Entry> present = new ArrayList<>();
        for(ContentIndex.Entry entry : index.entries) {
            File placed = locationOf(gameDirectory, entry);
            if(placed != null && placed.exists()) present.add(entry);
        }
        if(present.isEmpty()) {
            Tools.showErrorRemote(context, R.string.content_manage_none,
                    new IOException("There is nothing installed in " + instance.name));
            return;
        }
        String[] names = new String[present.size()];
        for(int i = 0; i < present.size(); i++) {
            names[i] = describe(context, present.get(i));
        }
        new AlertDialog.Builder(context)
                .setTitle(R.string.content_manage_title)
                .setItems(names, (dialog, which) ->
                        confirmRemoval(context, instance, index, present.get(which)))
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    private static void confirmRemoval(Context context, Instance instance, ContentIndex index,
                                       ContentIndex.Entry entry) {
        String name = describe(context, entry);
        new AlertDialog.Builder(context)
                .setMessage(context.getString(R.string.content_manage_delete, name))
                .setPositiveButton(R.string.content_manage_remove, (dialog, which) ->
                        MojoApplication.sExecutorService.execute(() ->
                                remove(context, instance, index, entry)))
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    private static void remove(Context context, Instance instance, ContentIndex index,
                               ContentIndex.Entry entry) {
        File gameDirectory = instance.getGameDirectory();
        String name = describe(context, entry);
        try {
            deletePlaced(gameDirectory, entry);
            index.entries.remove(entry);
            index.write(gameDirectory);
        }catch (IOException e) {
            Tools.showErrorRemote(context, R.string.content_install_failed, e);
            return;
        }
        ContextExecutor.executeActivity(activity -> {
            Toast.makeText(activity, activity.getString(R.string.content_manage_removed, name),
                    Toast.LENGTH_SHORT).show();
            showList(activity, instance);
        });
    }

    /**
     * Work out where the entry sits inside the instance. A null result means the entry
     * points at nothing this launcher knows how to reach.
     */
    private static File locationOf(File gameDirectory, ContentIndex.Entry entry) {
        if(entry.fileName == null || entry.fileName.isEmpty()) return null;
        ContentKind kind = kindOf(entry);
        if(kind == null || kind.folder == null) return null;
        return new File(new File(gameDirectory, kind.folder), entry.fileName);
    }

    /**
     * Delete the file or folder the entry describes, refusing to step outside of the
     * folder the kind belongs to.
     */
    private static void deletePlaced(File gameDirectory, ContentIndex.Entry entry) throws IOException {
        ContentKind kind = kindOf(entry);
        if(kind == null || kind.folder == null) return;
        if(entry.fileName == null || entry.fileName.isEmpty()) return;
        File destination = new File(gameDirectory, kind.folder);
        File target = new File(destination, entry.fileName);
        String root = destination.getCanonicalPath() + File.separator;
        if(!target.getCanonicalPath().startsWith(root))
            throw new IOException("Refusing to remove a path outside of " + kind.folder);
        if(target.isFile()) {
            target.delete();
        }else if(target.isDirectory()) {
            org.apache.commons.io.FileUtils.deleteDirectory(target);
        }
    }

    private static ContentKind kindOf(ContentIndex.Entry entry) {
        if(entry.kind == null) return null;
        try {
            return ContentKind.valueOf(entry.kind);
        }catch (IllegalArgumentException e) {
            return null;
        }
    }

    private static String describe(Context context, ContentIndex.Entry entry) {
        String title = entry.title == null || entry.title.isEmpty() ? entry.fileName : entry.title;
        ContentKind kind = kindOf(entry);
        String label = kind == null ? title : context.getString(kind.labelRes) + ": " + title;
        if(entry.versionName == null || entry.versionName.isEmpty()) return label;
        return label + " - " + entry.versionName;
    }
}
