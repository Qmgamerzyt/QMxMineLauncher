package git.artdeell.mojo.modding.modpacks.api;


import android.content.Context;

import com.kdt.mcgui.ProgressLayout;

import git.artdeell.mojo.MojoApplication;
import git.artdeell.mojo.R;
import git.artdeell.mojo.Tools;
import git.artdeell.mojo.modding.modpacks.api.modloader.LoaderInstaller;
import git.artdeell.mojo.modding.modpacks.models.ContentKind;
import git.artdeell.mojo.modding.modpacks.models.ModDetail;
import git.artdeell.mojo.modding.modpacks.models.ModItem;
import git.artdeell.mojo.modding.modpacks.models.SearchCategory;
import git.artdeell.mojo.modding.modpacks.models.SearchFilters;
import git.artdeell.mojo.modding.modpacks.models.SearchResult;

import java.io.File;
import java.io.IOException;
import java.util.Collections;
import java.util.List;

/**
 *
 */
public interface ModpackApi {

    /**
     * @param searchFilters Filters
     * @param previousPageResult The result from the previous page
     * @return the list of mod items from specified offset
     */
    SearchResult searchMod(SearchFilters searchFilters, SearchResult previousPageResult);

    /**
     * @param searchFilters Filters
     * @return A list of mod items
     */
    default SearchResult searchMod(SearchFilters searchFilters) {
        return searchMod(searchFilters, null);
    }

    /**
     * The categories a host groups content of this kind under, used to build the list
     * the filter dialog offers. A host with nothing to group it by answers with an
     * empty list, which keeps it out of that part of the search.
     *
     * @param kind the content the categories belong to
     * @return the categories, each carrying the key the host knows it by
     */
    default List<SearchCategory> listCategories(ContentKind kind) {
        return Collections.emptyList();
    }

    /**
     * Fetch the mod details
     * @param item The moditem that was selected
     * @return Detailed data about a mod(pack)
     */
    ModDetail getModDetails(ModItem item);

    /**
     * Download and install the modpack
     * @param modDetail The mod detail data
     * @param selectedVersion The selected version
     */
    default void handleModpackInstallation(Context context, ModDetail modDetail, int selectedVersion) {
        // Doing this here since when starting installation, the progress does not start immediately
        // which may lead to two concurrent installations (very bad)
        ProgressLayout.setProgress(ProgressLayout.INSTALL_MODPACK, 0, R.string.global_waiting);
        MojoApplication.sExecutorService.execute(() -> {
            try {
                installModpack(modDetail, selectedVersion);
            }catch (IOException e) {
                Tools.showErrorRemote(context, R.string.modpack_install_download_failed, e);
            }
        });
    }

    LoaderInstaller installLocalModpack(String modpackName, File modpackFile, String icon) throws IOException;

    /**
     * Install the mod(pack).
     * May require the download of additional files.
     * May requires launching the installation of a modloader
     * @param modDetail The mod detail data
     * @param selectedVersion The selected version
     */
    LoaderInstaller installModpack(ModDetail modDetail, int selectedVersion) throws IOException;
}
