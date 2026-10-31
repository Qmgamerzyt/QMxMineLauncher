package git.artdeell.mojo.modding.modpacks.api;

import android.content.Context;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AlertDialog;

import git.artdeell.mojo.MojoApplication;
import git.artdeell.mojo.R;
import git.artdeell.mojo.Tools;
import git.artdeell.mojo.instances.Instance;
import git.artdeell.mojo.instances.Instances;
import git.artdeell.mojo.modding.modpacks.imagecache.ContentThumbnails;
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
 * reads, so files placed there by hand are offered as well. An instance holding
 * nothing of a kind says so and offers the ways to put something there.
 */
public class InstalledContent {

    /** The screen behind the list opens the file picker when content is asked for. */
    public interface ImportRequest {
        void requestImport(ContentKind kind);
    }

    /** One of the kinds on offer was picked. */
    public interface KindChosen {
        void chosen(ContentKind kind);
    }

    /** The list on screen, so a change to it replaces it instead of stacking up. */
    private static AlertDialog sOpenList;

    /**
     * Show what an instance holds, without asking which instance to look at.
     * The editor knows the instance it is editing, so the picker is skipped.
     *
     * @param context  used to report errors
     * @param instance the instance to look at
     * @param kind     only offer content of this kind, or every kind when it is null
     * @param importer opened for files the player wants to bring in, null when there
     *                 is no screen that can pick them
     */
    public static void showListFor(Context context, Instance instance, ContentKind kind,
                                   ImportRequest importer) {
        MojoApplication.sExecutorService.execute(() -> {
            try {
                ContextExecutor.executeActivity(activity ->
                        showList(activity, instance, kind, importer));
            }catch (RuntimeException e) {
                Tools.showErrorRemote(context, R.string.content_install_failed, e);
            }
        });
    }

    /**
     * Ask which instance to look at, then show what that instance holds.
     *
     * @param context  used to report errors
     * @param importer opened for files the player wants to bring in, null when there
     *                 is no screen that can pick them
     */
    public static void handleManage(Context context, ImportRequest importer) {
        MojoApplication.sExecutorService.execute(() -> {
            try {
                List<Instance> instances = Instances.loadAllInstances();
                Instance selected = Instances.loadSelectedInstance();
                ContextExecutor.executeActivity(activity -> ContentInstaller.pickInstance(
                        activity, instances, selected,
                        R.string.content_manage_pick_instance, android.R.string.ok,
                        null,
                        instance -> showList(activity, instance, null, importer)));
            }catch (IOException e) {
                Tools.showErrorRemote(context, R.string.content_install_failed, e);
            }
        });
    }

    /**
     * Offer a few kinds of content and hand the chosen one over.
     *
     * @param context  used to build the dialog
     * @param titleRes heading of the dialog
     * @param options  the kinds on offer
     * @param chosen   told which kind was picked
     */
    public static void chooseKind(Context context, int titleRes, ContentKind[] options,
                                  KindChosen chosen) {
        if(options == null || options.length == 0) return;
        String[] names = new String[options.length];
        for(int i = 0; i < options.length; i++) names[i] = context.getString(options[i].labelRes);
        new AlertDialog.Builder(context)
                .setTitle(titleRes)
                .setItems(names, (dialog, which) -> {
                    if(which >= 0 && which < options.length) chosen.chosen(options[which]);
                })
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    private static void showList(Context context, Instance instance, ContentKind kind,
                                 ImportRequest importer) {
        File gameDirectory = instance.getGameDirectory();
        ContentIndex index = ContentIndex.read(gameDirectory);
        List<ContentIndex.Entry> entries = collect(gameDirectory, index, kind);
        if(entries.isEmpty()) {
            showEmpty(context, instance, kind, importer);
            return;
        }
        showRows(context, instance, index, entries, kind, importer);
    }

    /**
     * What to offer when there is nothing to list: bring files in from the device, go
     * and fetch some from the content screen, or leave it be.
     */
    private static void showEmpty(Context context, Instance instance, ContentKind kind,
                                  ImportRequest importer) {
        String text = kind == null
                ? context.getString(R.string.content_manage_none)
                : context.getString(R.string.content_manage_kind_empty,
                    context.getString(kind.labelRes));
        text = text + "\n\n" + context.getString(R.string.content_manage_empty_hint);
        AlertDialog.Builder builder = new AlertDialog.Builder(context)
                .setTitle(heading(context, instance, kind))
                .setMessage(text)
                .setNeutralButton(R.string.content_row_browse, (dialog, which) ->
                        browseTo(context, instance, kind))
                .setNegativeButton(R.string.content_browser_close, null);
        if(importer != null) {
            builder.setPositiveButton(R.string.content_manage_import, (dialog, which) -> {
                if(kind == null) chooseKind(context, R.string.content_pick_kind,
                        ContentKind.installedKinds(), importer::requestImport);
                else importer.requestImport(kind);
            });
        }
        builder.show();
    }

    /** Take the player to the content screen, asking which kind when none is at hand. */
    private static void browseTo(Context context, Instance instance, ContentKind kind) {
        if(kind == null) chooseKind(context, R.string.content_pick_kind,
                ContentKind.installedKinds(), picked -> ContentNavigation.open(context, instance, picked));
        else ContentNavigation.open(context, instance, kind);
    }

    /** What the list dialog carries: the instance, and the kind when only one is listed. */
    private static String heading(Context context, Instance instance, ContentKind kind) {
        String who = instance == null
                ? context.getString(R.string.content_manage_title) : instance.displayName();
        String what = kind == null
                ? context.getString(R.string.content_manage_title) : context.getString(kind.labelRes);
        return context.getString(R.string.content_manage_of, who, what);
    }

    /**
     * What the instance holds of one kind, or of every kind when none is asked for.
     * The rows the installer wrote down are listed first, then whatever else sits in
     * the folders the game reads, which covers files placed there by hand.
     */
    private static List<ContentIndex.Entry> collect(File gameDirectory, ContentIndex index,
                                                    ContentKind filter) {
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

    /** Rows of content, each carrying a picture, whether the game may see it, and a way to drop it. */
    private static void showRows(Context context, Instance instance, ContentIndex index,
                                 List<ContentIndex.Entry> entries, ContentKind kind,
                                 ImportRequest importer) {
        ScrollView scroll = new ScrollView(context);
        LinearLayout rows = new LinearLayout(context);
        rows.setOrientation(LinearLayout.VERTICAL);
        rows.setPadding(context.getResources().getDimensionPixelSize(R.dimen.padding_heavy),
                0, context.getResources().getDimensionPixelSize(R.dimen.padding_heavy), 0);
        for(ContentIndex.Entry entry : entries) {
            rows.addView(buildRow(context, instance, index, rows, entries, entry, kind, importer));
        }
        scroll.addView(rows);
        if(sOpenList != null) sOpenList.dismiss();
        sOpenList = new AlertDialog.Builder(context)
                .setTitle(heading(context, instance, kind))
                .setMessage(context.getString(R.string.content_manage_count, entries.size()))
                .setView(scroll)
                .setNegativeButton(android.R.string.cancel, null)
                .setOnDismissListener(dialog -> {
                    if(sOpenList == dialog) sOpenList = null;
                    ContentThumbnails.cancelAll(rows);
                })
                .show();
    }

    private static View buildRow(Context context, Instance instance, ContentIndex index,
                                 LinearLayout rows, List<ContentIndex.Entry> entries,
                                 ContentIndex.Entry entry, ContentKind kind,
                                 ImportRequest importer) {
        File gameDirectory = instance.getGameDirectory();
        ContentKind entryKind = kindOf(entry);
        ContentKind pictureOf = entryKind != null ? entryKind : kind;
        View row = LayoutInflater.from(context).inflate(R.layout.view_content_toggle_row, rows, false);
        ImageView thumb = row.findViewById(R.id.content_thumb);
        CheckBox check = row.findViewById(R.id.content_toggle_check);
        TextView meta = row.findViewById(R.id.content_toggle_meta);
        Button remove = row.findViewById(R.id.content_toggle_remove);
        check.setText(titleOf(entry));
        String metaText = metaOf(context, entry, pictureOf);
        if(metaText == null || metaText.isEmpty()) {
            meta.setVisibility(View.GONE);
        }else {
            meta.setVisibility(View.VISIBLE);
            meta.setText(metaText);
        }
        ContentThumbnails.bind(thumb, gameDirectory, entry, pictureOf);
        check.setChecked(ContentPlacement.isEnabled(gameDirectory, entryKind, entry.fileName));
        // The listener goes on after the state is in place, so a row being built
        // never asks for the content to move
        check.setOnCheckedChangeListener((button, isChecked) ->
                MojoApplication.sExecutorService.execute(() ->
                        setEnabled(context, instance, entries, entry, kind, isChecked, importer)));
        remove.setOnClickListener(v ->
                confirmRemoval(context, instance, index, entries, entry, kind, importer));
        return row;
    }

    /** Move one piece of content between the two places, then show the list again. */
    private static void setEnabled(Context context, Instance instance,
                                   List<ContentIndex.Entry> entries, ContentIndex.Entry entry,
                                   ContentKind kind, boolean enabled, ImportRequest importer) {
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
            showList(activity, instance, kind, importer);
        });
    }

    private static void confirmRemoval(Context context, Instance instance, ContentIndex index,
                                       List<ContentIndex.Entry> entries, ContentIndex.Entry entry,
                                       ContentKind kind, ImportRequest importer) {
        String name = describe(context, entry);
        new AlertDialog.Builder(context)
                .setMessage(context.getString(R.string.content_manage_delete, name))
                .setPositiveButton(R.string.content_manage_remove, (dialog, which) ->
                        MojoApplication.sExecutorService.execute(() ->
                                remove(context, instance, index, entries, entry, kind, importer)))
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    private static void remove(Context context, Instance instance, ContentIndex index,
                               List<ContentIndex.Entry> entries, ContentIndex.Entry entry,
                               ContentKind kind, ImportRequest importer) {
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
            showList(activity, instance, kind, importer);
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

    /** The name the row carries. */
    private static String titleOf(ContentIndex.Entry entry) {
        return entry.title == null || entry.title.isEmpty() ? entry.fileName : entry.title;
    }

    /** The line under the name: the kind it is, and which version of it landed there. */
    private static String metaOf(Context context, ContentIndex.Entry entry, ContentKind kind) {
        String version = entry.versionName == null || entry.versionName.isEmpty()
                ? null : entry.versionName;
        if(kind == null) return version;
        String kindLabel = context.getString(kind.labelRes);
        if(version == null) return kindLabel;
        return context.getString(R.string.content_manage_of, kindLabel, version);
    }

    private static String describe(Context context, ContentIndex.Entry entry) {
        String title = titleOf(entry);
        ContentKind kind = kindOf(entry);
        String label = kind == null ? title : context.getString(kind.labelRes) + ": " + title;
        if(entry.versionName == null || entry.versionName.isEmpty()) return label;
        return label + " - " + entry.versionName;
    }
}
