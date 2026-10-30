package git.artdeell.mojo.modding.modpacks.api;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.os.Bundle;

import androidx.fragment.app.FragmentActivity;

import git.artdeell.mojo.LauncherActivity;
import git.artdeell.mojo.MojoApplication;
import git.artdeell.mojo.R;
import git.artdeell.mojo.Tools;
import git.artdeell.mojo.fragments.SearchModFragment;
import git.artdeell.mojo.instances.Instance;
import git.artdeell.mojo.modding.modpacks.models.ContentKind;
import git.artdeell.mojo.utils.profiles.InstanceVersions;

/**
 * Takes the player to the content screen for one kind of content, wherever they ask
 * from. A screen that already holds fragments gets the content screen placed on top of
 * its own, anything else, the game drawer for instance, goes through the launcher.
 */
public final class ContentNavigation {
    /** Extra carrying the kind the content screen opens on. */
    public static final String EXTRA_CONTENT_KIND = "qmx_content_kind";

    private ContentNavigation() {
    }

    /**
     * Show the content screen for the given kind, narrowed down by what the instance runs.
     *
     * @param context  the screen the player is on
     * @param instance the instance the content is for, null when there is none to read
     * @param kind     the kind the screen opens on, null to leave it on its default
     */
    public static void open(Context context, Instance instance, ContentKind kind) {
        if(context instanceof FragmentActivity
                && ((FragmentActivity) context).findViewById(R.id.container_fragment) != null) {
            openInActivity((FragmentActivity) context, instance, kind);
        }else {
            openInLauncher(context, kind);
        }
    }

    private static void openInActivity(FragmentActivity activity, Instance instance, ContentKind kind) {
        Bundle bundle = new Bundle(3);
        if(kind != null) bundle.putString(SearchModFragment.ARG_KIND, kind.name());
        String versionId = instance == null ? null : instance.versionId;
        // Reading the profile of the instance belongs off the UI thread, the screen
        // opens once the version it runs has been read
        MojoApplication.sExecutorService.execute(() -> {
            if(versionId != null) {
                String gameVersion = InstanceVersions.resolveGameVersion(versionId);
                String loader = InstanceVersions.resolveLoader(versionId).name();
                if(gameVersion != null && !gameVersion.isEmpty())
                    bundle.putString(SearchModFragment.ARG_MC_VERSION, gameVersion);
                if(loader != null && !loader.isEmpty())
                    bundle.putString(SearchModFragment.ARG_LOADER, loader);
            }
            Tools.runOnUiThread(() -> {
                if(activity.getSupportFragmentManager().isStateSaved()
                        || activity.findViewById(R.id.container_fragment) == null) {
                    openInLauncher(activity, kind);
                    return;
                }
                Tools.swapFragment(activity, SearchModFragment.class, SearchModFragment.TAG, bundle);
            });
        });
    }

    private static void openInLauncher(Context context, ContentKind kind) {
        Intent intent = new Intent(context, LauncherActivity.class);
        if(kind != null) intent.putExtra(EXTRA_CONTENT_KIND, kind.name());
        if(!(context instanceof Activity)) intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        context.startActivity(intent);
    }
}
