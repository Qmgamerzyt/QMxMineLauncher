package git.artdeell.mojo.modding.modpacks.api;

import android.content.Context;

import androidx.appcompat.app.AlertDialog;

import git.artdeell.mojo.MojoApplication;
import git.artdeell.mojo.R;
import git.artdeell.mojo.instances.Instance;
import git.artdeell.mojo.modding.modpacks.models.ContentDependency;
import git.artdeell.mojo.modding.modpacks.models.ContentIndex;
import git.artdeell.mojo.modding.modpacks.models.ContentKind;
import git.artdeell.mojo.modding.modpacks.models.ModDetail;
import git.artdeell.mojo.modding.modpacks.models.ModItem;
import git.artdeell.mojo.utils.lifecycle.ContextExecutor;
import git.artdeell.mojo.utils.profiles.InstanceVersions;

import java.io.IOException;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Walks what a chosen version leans on and brings those along. The required ones
 * come without being asked about, the optional ones are offered as a list of ticks
 * first, and a project the instance already holds is left where it sits. The walk
 * follows the required ones further down, so a dependency of a dependency arrives
 * too, and it stops after a set number of projects so a tangle of relations still
 * ends.
 */
public class DependencyResolver {
    /** How many projects one walk may visit. */
    private static final int MAX_DEPENDENCIES = 50;

    /** What the player decided about the projects a version leans on. */
    public interface Choice {
        /**
         * @param titles           the titles that were found for the listed ids
         * @param approvedOptional keys of the optional projects that should come along
         */
        void chosen(Map<String, String> titles, Set<String> approvedOptional);
    }

    /**
     * Ask which of the optional projects should come along, before anything is
     * installed. Every dependency of the version is listed, the required ones marked
     * as such and kept ticked, since the content does not run without them.
     *
     * @param context         used to build the list
     * @param api             the providers, used to put a name to each id
     * @param detail          the content that was picked
     * @param selectedVersion index of the chosen version in the detail's version list
     * @param choice          what the player decided
     */
    public static void prompt(Context context, ModpackApi api, ModDetail detail,
                              int selectedVersion, Choice choice) {
        ContentDependency[] dependencies = detail.dependenciesOf(selectedVersion);
        if(dependencies.length == 0) {
            choice.chosen(new LinkedHashMap<>(), Collections.emptySet());
            return;
        }
        MojoApplication.sExecutorService.execute(() -> {
            Map<String, String> titles;
            try {
                titles = titlesFor(api, dependencies);
            } catch (RuntimeException e) {
                // A provider that will not name its projects must not hold up the install
                titles = new LinkedHashMap<>();
            }
            Map<String, String> named = titles;
            ContextExecutor.executeActivity(activity ->
                    showChoice(activity, dependencies, named, choice));
        });
    }

    private static void showChoice(Context context, ContentDependency[] dependencies,
                                   Map<String, String> titles, Choice choice) {
        String[] labels = new String[dependencies.length];
        boolean[] ticked = new boolean[dependencies.length];
        for(int i = 0; i < dependencies.length; i++) {
            String name = named(titles, dependencies[i]);
            labels[i] = context.getString(dependencies[i].required
                    ? R.string.content_dependencies_required
                    : R.string.content_dependencies_optional, name);
            ticked[i] = true;
        }
        new AlertDialog.Builder(context)
                .setTitle(R.string.content_dependencies_title)
                .setMultiChoiceItems(labels, ticked, (dialog, which, isChecked) -> {
                    // A project the content will not run without keeps its tick
                    if(dependencies[which].required && !isChecked) {
                        ((AlertDialog) dialog).getListView().setItemChecked(which, true);
                        ticked[which] = true;
                        return;
                    }
                    ticked[which] = isChecked;
                })
                .setPositiveButton(R.string.generic_install, (dialog, which) -> {
                    LinkedHashSet<String> approved = new LinkedHashSet<>();
                    for(int i = 0; i < dependencies.length; i++) {
                        if(ticked[i]) approved.add(key(dependencies[i]));
                    }
                    choice.chosen(titles, approved);
                })
                .setNegativeButton(android.R.string.cancel, (dialog, which) -> { })
                .setOnCancelListener(dialog -> { })
                .show();
    }

    /**
     * Put every approved dependency into the instance, following the required ones
     * they bring along. What the instance already holds is left where it sits, and
     * everything that did not make it is gathered into one report.
     *
     * @param context         used to name the projects in the report
     * @param api             the providers, used to open each dependency
     * @param instance        the instance receiving the content
     * @param detail          the content that was just installed
     * @param selectedVersion index of the version that was installed
     * @param titles          the titles found while the list was offered
     * @param approvedOptional keys of the optional projects the player ticked
     * @return the reports of what did not make it, empty when all of it went in
     */
    public static List<String> installAll(Context context, ModpackApi api, Instance instance,
                                          ModDetail detail, int selectedVersion,
                                          Map<String, String> titles, Set<String> approvedOptional) {
        try {
            return walk(context, api, instance, detail, selectedVersion, titles, approvedOptional);
        } catch (RuntimeException e) {
            // The content itself is in by the time the walk runs, so a walk that falls
            // over still leaves a report rather than going quiet
            String reason = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
            ArrayList<String> reports = new ArrayList<>();
            reports.add(context.getString(R.string.content_dependencies_stopped, reason));
            return reports;
        }
    }

    private static List<String> walk(Context context, ModpackApi api, Instance instance,
                                     ModDetail detail, int selectedVersion,
                                     Map<String, String> titles, Set<String> approvedOptional) {
        ArrayList<String> reports = new ArrayList<>();
        if(!detail.kind.installsIntoExistingInstance()) return reports;
        String mcVersion = InstanceVersions.resolveGameVersion(instance.versionId);
        String loader = InstanceVersions.resolveLoader(instance.versionId).name();
        ContentKind kind = kindFor(detail.kind);
        ContentIndex index = ContentIndex.read(instance.getGameDirectory());
        LinkedHashMap<String, ContentDependency> missed = new LinkedHashMap<>();
        LinkedHashSet<String> visited = new LinkedHashSet<>();
        ArrayDeque<ContentDependency[]> pending = new ArrayDeque<>();
        pending.add(detail.dependenciesOf(selectedVersion));
        int placed = 0;
        while(!pending.isEmpty() && placed < MAX_DEPENDENCIES) {
            for(ContentDependency dependency : pending.removeFirst()) {
                if(placed >= MAX_DEPENDENCIES) break;
                // An optional project is only brought when it was offered and ticked,
                // and one that turns up as required further down comes regardless
                if(!dependency.required && (approvedOptional == null
                        || !approvedOptional.contains(key(dependency)))) continue;
                if(!visited.add(key(dependency))) continue;
                // The instance already holds this one, whatever kind it landed as
                if(index.find(dependency.apiSource, dependency.projectId) != null) continue;
                try {
                    ModDetail dependencyDetail = open(api, dependency, kind, titles,
                            mcVersion, loader);
                    if(dependencyDetail == null) {
                        missed.put(key(dependency), dependency);
                        continue;
                    }
                    int versionIndex = dependencyDetail.bestVersionIndex(mcVersion, loader);
                    ContentInstaller.install(instance, dependencyDetail, versionIndex);
                    placed++;
                    index = ContentIndex.read(instance.getGameDirectory());
                    if(dependency.required) {
                        pending.add(dependencyDetail.dependenciesOf(versionIndex));
                    }
                } catch (IOException | RuntimeException e) {
                    missed.put(key(dependency), dependency);
                }
            }
        }
        if(missed.isEmpty()) return reports;
        // The names of the projects that did not make it are worth one more call,
        // a list of ids says nothing to anyone
        fillMissingTitles(api, missed.values(), titles);
        for(ContentDependency dependency : missed.values()) {
            reports.add(context.getString(R.string.content_dependencies_failed,
                    named(titles, dependency)));
        }
        return reports;
    }

    /** Open the project a dependency names, with the instance in mind for its version. */
    private static ModDetail open(ModpackApi api, ContentDependency dependency, ContentKind kind,
                                  Map<String, String> titles, String mcVersion, String loader) {
        ModItem item = new ModItem(dependency.apiSource, kind, dependency.projectId,
                named(titles, dependency), "", null);
        return api.getModDetails(item);
    }

    /** The titles of every id in the list that is still nameless. */
    private static void fillMissingTitles(ModpackApi api, Iterable<ContentDependency> dependencies,
                                          Map<String, String> titles) {
        LinkedHashMap<Integer, List<String>> bySource = new LinkedHashMap<>();
        for(ContentDependency dependency : dependencies) {
            if(titles.containsKey(key(dependency))) continue;
            List<String> ids = bySource.get(dependency.apiSource);
            if(ids == null) {
                ids = new ArrayList<>();
                bySource.put(dependency.apiSource, ids);
            }
            ids.add(dependency.projectId);
        }
        for(Map.Entry<Integer, List<String>> batch : bySource.entrySet()) {
            try {
                for(Map.Entry<String, String> title
                        : api.fetchTitles(batch.getKey(), batch.getValue()).entrySet()) {
                    titles.put(batch.getKey() + ":" + title.getKey(), title.getValue());
                }
            } catch (RuntimeException ignored) {
                // The ids stay nameless, which the report falls back to on its own
            }
        }
    }

    /** The titles of every project one version leans on, keyed the way the walk keys them. */
    private static Map<String, String> titlesFor(ModpackApi api,
                                                 ContentDependency[] dependencies) {
        LinkedHashMap<Integer, List<String>> bySource = new LinkedHashMap<>();
        for(ContentDependency dependency : dependencies) {
            List<String> ids = bySource.get(dependency.apiSource);
            if(ids == null) {
                ids = new ArrayList<>();
                bySource.put(dependency.apiSource, ids);
            }
            if(!ids.contains(dependency.projectId)) ids.add(dependency.projectId);
        }
        LinkedHashMap<String, String> titles = new LinkedHashMap<>();
        for(Map.Entry<Integer, List<String>> batch : bySource.entrySet()) {
            Map<String, String> found = api.fetchTitles(batch.getKey(), batch.getValue());
            for(Map.Entry<String, String> title : found.entrySet()) {
                titles.put(batch.getKey() + ":" + title.getKey(), title.getValue());
            }
        }
        return titles;
    }

    /** The name the report and the installed row should carry for a dependency. */
    private static String named(Map<String, String> titles, ContentDependency dependency) {
        String title = titles.get(key(dependency));
        return title == null || title.isEmpty() ? dependency.projectId : title;
    }

    private static String key(ContentDependency dependency) {
        return dependency.apiSource + ":" + dependency.projectId;
    }

    /** The kind a dependency of the given content belongs to. */
    private static ContentKind kindFor(ContentKind parent) {
        // A shader leans on mods far more often than on other shaders
        return parent == ContentKind.SHADER ? ContentKind.MOD : parent;
    }
}
