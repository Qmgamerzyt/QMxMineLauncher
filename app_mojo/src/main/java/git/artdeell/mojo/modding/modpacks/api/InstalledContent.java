package git.artdeell.mojo.modding.modpacks.api;

import android.content.Context;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Toast;

import androidx.appcompat.app.AlertDialog;

import git.artdeell.mojo.MojoApplication;
import git.artdeell.mojo.R;
import git.artdeell.mojo.Tools;
import git.artdeell.mojo.instances.Instance;
import git.artdeell.mojo.instances.Instances;
import git.artdeell.mojo.modding.modpacks.models.ContentIndex;
import git.artdeell.mojo.modding.modpacks.models.ContentKind;
import git.artdeell.mojo.modding.modpacks.models.ContentPlacement;
import git.artdeell.mojo.utils.lifecycle.ContextExecutor;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;

/**
 * Shows what an instance already holds, lets each piece of it be turned off and on
 * without losing it, and takes it away when that is asked for. The list brings the
 * rows the installer wrote down together with whatever sits in the folders the game
 * reads, so files placed there by hand are offered as well.
 */
public class InstalledContent {

    /**
     * Show what an instance already holds, without asking which instance to look at.
     * The editor knows the instance it is editing, so the picker is skipped.
     *
     * @param kind only offer content of this kind, or every kind when it is null
     */
    public static void showListFor(Context context, Instance instance, ContentKind kind) {
        MojoApplication.sExecutorService.execute(() -> {
            try {
                ContextExecutor.executeActivity(activity -> showList(activity, instance, kind));
            }catch (RuntimeException e) {
                Tools.showErrorRemote(context, R.string.content_install_failed, e);
            }
        });
    }

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
                        instance -> showList(activity, instance, null)));
            }catch (IOException e) {
                Tools.showErrorRemote(context, R.string.content_install_failed, e);
            }
        });
    }

    private static void showList(Context context, Instance instance, ContentKind kind) {
        File gameDirectory = instance.getGameDirectory();
        ContentIndex index = ContentIndex.read(gameDirectory);
        List<ContentIndex.Entry> entries = collect(gameDirectory, index, kind);
        if(entries.isEmpty()) {
            if(kind == null) {
                Tools.showErrorRemote(context, R.string.content_manage_none,
                        new IOException("There is nothing installed in " + instance.name));
            }else {
                Tools.showErrorRemote(context.getString(R.string.content_manage_kind_empty,
                        context.getString(kind.labelRes)),
                        new IOException("No " + kind + " is installed in " + instance.name));
            }
            return;
        }
        showRows(context, instance, index, entries, kind);
    }

    /**
     * What the instance holds of one kind, or of every kind when none is asked for.
     * The rows the installer wrote down are listed first, then whatever else sits in
     * the folders the game reads, which covers files placed there by hand.
     */
    private static List<ContentIndex.Entry> collect(File gameDirectory, ContentIndex index, ContentKind filter) {
        ArrayList<ContentIndex.Entry> entries = new ArrayList<>();
        HashSet<String> listed = new HashSet<>();
        for(ContentIndex.Entry entry : index.entries) {
            ContentKind kind = kindOf(entry);
            if(kind == null || kind.folder == null) continue;
            if(filter != null && kind != filter) continue;
            if(ContentPlacement.present(gameDirectory, kind, entry.fileName) == null) continue;
            entries.add(entry);
            listed.add(keyOf(kind, entry.fileName));
        }
        for(ContentKind kind : ContentKind.values()) {
            if(kind.folder == null || (filter != null && kind != filter)) continue;
            for(String name : ContentPlacement.names(gameDirectory, kind)) {
                if(!listed.add(keyOf(kind, name))) continue;
                ContentIndex.Entry entry = new ContentIndex.Entry();
                entry.kind = kind.name();
                entry.apiSource = ContentIndex.SOURCE_LOCAL;
                entry.fileName = name;
                entry.title = name;
                entries.add(entry);
            }
        }
        return entries;
    }

    /** One name of a kind standing for the content it belongs to. */
    private static String keyOf(ContentKind kind, String name) {
        return kind.name() + "/" + name;
    }

    /** Rows of content, each carrying whether the game may see it and a way to drop it. */
    private static void showRows(Context context, Instance instance, ContentIndex index,
                                 List<ContentIndex.Entry> entries, ContentKind kind) {
        ScrollView scroll = new ScrollView(context);
        LinearLayout rows = new LinearLayout(context);
        rows.setOrientation(LinearLayout.VERTICAL);
        rows.setPadding(context.getResources().getDimensionPixelSize(R.dimen.padding_heavy),
                0, 0, 0);
        for(ContentIndex.Entry entry : entries) {
            rows.addView(buildRow(context, instance, index, rows, entries, entry, kind));
        }
        scroll.addView(rows);
        new AlertDialog.Builder(context)
                .setTitle(R.string.content_manage_title)
                .setView(scroll)
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    private static View buildRow(Context context, Instance instance, ContentIndex index,
                                 LinearLayout rows, List<ContentIndex.Entry> entries,
                                 ContentIndex.Entry entry, ContentKind kind) {
        File gameDirectory = instance.getGameDirectory();
        ContentKind entryKind = kindOf(entry);
        View row = LayoutInflater.from(context).inflate(R.layout.view_content_toggle_row, rows, false);
        CheckBox check = row.findViewById(R.id.content_toggle_check);
        Button remove = row.findViewById(R.id.content_toggle_remove);
        check.setText(describe(context, entry));
        check.setChecked(ContentPlacement.isEnabled(gameDirectory, entryKind, entry.fileName));
        // The listener is set after the state is put in place, so a row being built
        // never asks for the content to move
        check.setOnCheckedChangeListener((button, isChecked) ->
                MojoApplication.sExecutorService.execute(() ->
                        setEnabled(context, instance, entries, entry, kind, isChecked)));
        remove.setOnClickListener(v ->
                confirmRemoval(context, instance, index, entries, entry, kind));
        return row;
    }

    /** Move one piece of content between the two places, then show the list again. */
    private static void setEnabled(Context context, Instance instance,
                                   List<ContentIndex.Entry> entries, ContentIndex.Entry entry,
                                   ContentKind kind, boolean enabled) {
        String name = describe(context, entry);
        try {
            ContentPlacement.setEnabled(instance.getGameDirectory(), kindOf(entry),
                    entry.fileName, enabled);
        }catch (IOException e) {
            Tools.showErrorRemote(
                    context.getString(R.string.content_manage_failed, name), e);
            return;
        }
        ContextExecutor.executeActivity(activity -> {
            Toast.makeText(activity, activity.getString(enabled
                            ? R.string.content_manage_enabled : R.string.content_manage_disabled, name),
                    Toast.LENGTH_SHORT).show();
            showList(activity, instance, kind);
        });
    }

    private static void confirmRemoval(Context context, Instance instance, ContentIndex index,
                                       List<ContentIndex.Entry> entries, ContentIndex.Entry entry,
                                       ContentKind kind) {
        String name = describe(context, entry);
        new AlertDialog.Builder(context)
                .setMessage(context.getString(R.string.content_manage_delete, name))
                .setPositiveButton(R.string.content_manage_remove, (dialog, which) ->
                        MojoApplication.sExecutorService.execute(() ->
                                remove(context, instance, index, entries, entry, kind)))
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    private static void remove(Context context, Instance instance, ContentIndex index,
                               List<ContentIndex.Entry> entries, ContentIndex.Entry entry,
                               ContentKind kind) {
        String name = describe(context, entry);
        try {
            ContentPlacement.delete(instance.getGameDirectory(), kindOf(entry), entry.fileName);
            // The row goes even when the launcher never wrote it down, since the file
            // it stood for is gone either way
            index.entries.remove(entry);
            entries.remove(entry);
            index.write(instance.getGameDirectory());
        }catch (IOException e) {
            Tools.showErrorRemote(
                    context.getString(R.string.content_manage_failed, name), e);
            return;
        }
        ContextExecutor.executeActivity(activity -> {
            Toast.makeText(activity, activity.getString(R.string.content_manage_removed, name),
                    Toast.LENGTH_SHORT).show();
            showList(activity, instance, kind);
        });
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
