package git.artdeell.mojo.modding.modpacks.api;

import android.util.Log;

import androidx.annotation.NonNull;

import git.artdeell.mojo.modding.modpacks.api.modloader.LoaderInstaller;
import git.artdeell.mojo.modding.modpacks.models.Constants;
import git.artdeell.mojo.modding.modpacks.models.ContentKind;
import git.artdeell.mojo.modding.modpacks.models.ModDetail;
import git.artdeell.mojo.modding.modpacks.models.ModItem;
import git.artdeell.mojo.modding.modpacks.models.SearchCategory;
import git.artdeell.mojo.modding.modpacks.models.SearchFilters;
import git.artdeell.mojo.modding.modpacks.models.SearchResult;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Locale;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * Group all apis under the same umbrella, as another layer of abstraction
 */
public class CommonApi implements ModpackApi {
    /** Runs the per-provider searches, one thread each, so a slow host can't take the shared pool down with it. */
    private static final ExecutorService SEARCH_EXECUTOR = Executors.newFixedThreadPool(2);
    /** How long a search waits for its providers before giving up on them. */
    private static final long SEARCH_WAIT_MS = 40000;

    private final ModpackApi mCurseforgeApi;
    private final ModpackApi mModrinthApi;
    private final ModpackApi[] mModpackApis;

    public static final byte PACK_MODRINTH = 1;
    public static final byte PACK_CURSEFORGE = 2;
    public static final byte PACK_UNDEFINED = 0;

    public CommonApi(String curseforgeApiKey) {
        mModrinthApi = new ModrinthApi();
        if ("DUMMY".equals(curseforgeApiKey)) {
            mCurseforgeApi = null;
            mModpackApis = new ModpackApi[]{mModrinthApi};
        } else {
            mCurseforgeApi = new CurseforgeApi(curseforgeApiKey);
            mModpackApis = new ModpackApi[]{mModrinthApi, mCurseforgeApi};
        }
    }

    @Override
    public SearchResult searchMod(SearchFilters searchFilters, SearchResult previousPageResult) {
        CommonApiSearchResult commonApiSearchResult = (CommonApiSearchResult) previousPageResult;
        // If there are no previous page results, create a new array. Otherwise, use the one from the previous page
        SearchResult[] results = commonApiSearchResult == null ?
                new SearchResult[mModpackApis.length] : commonApiSearchResult.searchResults;

        int totalSize = 0;

        Future<?>[] futures = new Future<?>[mModpackApis.length];
        for(int i = 0; i < mModpackApis.length; i++) {
            // If there is an array and its length is zero, this means that we've exhausted the results for this
            // search query and we don't need to actually do the search
            if(results[i] != null && results[i].results.length == 0) continue;
            // If the previous page result is not null (aka the arrays aren't fresh)
            // and the previous result is null, it means that na error has occured on the previous
            // page. We lost contingency anyway, so don't bother requesting.
            if(previousPageResult != null && results[i] == null) continue;
            futures[i] = SEARCH_EXECUTOR.submit(new ApiDownloadTask(i, searchFilters,
                    results[i]));
        }

        if(Thread.interrupted()) {
            cancelAllFutures(futures);
            return null;
        }
        boolean hasSuccessful = false;
        // Count up all the results
        for(int i = 0; i < mModpackApis.length; i++) {
            Future<?> future = futures[i];
            if(future == null) continue;
            try {
                SearchResult searchResult = results[i] = (SearchResult) future.get(SEARCH_WAIT_MS, TimeUnit.MILLISECONDS);
                if(searchResult != null) hasSuccessful = true;
                else continue;
                totalSize += searchResult.totalResultCount;
            }catch (Exception e) {
                cancelAllFutures(futures);
                e.printStackTrace();
                return null;
            }
        }
        if(!hasSuccessful) {
            return null;
        }
        // Then build an array with all the mods
        ArrayList<ModItem[]> filteredResults = new ArrayList<>(results.length);

        // Sanitize returned values
        for(SearchResult result : results) {
            if(result == null) continue;
            ModItem[] searchResults = result.results;
            // If the length is zero, we don't need to perform needless copies
            if(searchResults.length == 0) continue;
            filteredResults.add(searchResults);
        }
        filteredResults.trimToSize();
        if(Thread.interrupted()) return null;

        ModItem[] concatenatedItems = buildFusedResponse(filteredResults);
        if(Thread.interrupted()) return null;
        // Recycle or create new search result
        if(commonApiSearchResult == null) commonApiSearchResult = new CommonApiSearchResult();
        commonApiSearchResult.searchResults = results;
        commonApiSearchResult.totalResultCount = totalSize;
        commonApiSearchResult.results = concatenatedItems;
        return commonApiSearchResult;
    }

    /** The category list of each kind, worked out once and kept for the next filter. */
    private final HashMap<String, List<SearchCategory>> mCategoryCache = new HashMap<>();

    /**
     * Every host's categories under one list, joined on the name so a category both
     * of them carry needs to be picked only once to narrow the whole search. A host
     * that does not group content of this kind contributes nothing and is left out
     * of the part of the search the categories ask for.
     *
     * @param kind the content the categories belong to
     * @return the categories in alphabetical order, ready for the filter dialog
     */
    @Override
    public List<SearchCategory> listCategories(ContentKind kind) {
        synchronized (mCategoryCache) {
            List<SearchCategory> cached = mCategoryCache.get(kind.name());
            if(cached != null) return cached;
        }
        HashMap<String, SearchCategory> byName = new HashMap<>();
        ArrayList<SearchCategory> merged = new ArrayList<>();
        for(ModpackApi api : mModpackApis) {
            for(SearchCategory category : api.listCategories(kind)) {
                String key = SearchCategory.normalize(category.name);
                if(key.isEmpty()) continue;
                SearchCategory held = byName.get(key);
                if(held == null) {
                    byName.put(key, category);
                    merged.add(category);
                }else {
                    held.merge(category);
                }
            }
        }
        // A single host behind the search leaves nothing to tell apart, so the
        // labels drop the name of it and only the chosen categories are shown
        if(mModpackApis.length < 2) {
            for(SearchCategory category : merged) category.showProvider = false;
        }
        Collections.sort(merged, (left, right) -> left.name.compareToIgnoreCase(right.name));
        List<SearchCategory> result = Collections.unmodifiableList(merged);
        synchronized (mCategoryCache) {
            mCategoryCache.put(kind.name(), result);
        }
        return result;
    }

    @Override
    public Map<String, String> fetchTitles(int apiSource, List<String> projectIds) {
        ModpackApi api = getModpackApi(apiSource);
        if(api == null) return Collections.emptyMap();
        return api.fetchTitles(apiSource, projectIds);
    }

    @Override
    public ModDetail getModDetails(ModItem item) {
        Log.i("CommonApi", "Invoking getModDetails on item.apiSource="+item.apiSource +" item.title="+item.title);
        return getModpackApi(item.apiSource).getModDetails(item);
    }

    @Override
    public LoaderInstaller installModpack(ModDetail modDetail, int selectedVersion) throws IOException {
        return getModpackApi(modDetail.apiSource).installModpack(modDetail, selectedVersion);
    }

    public LoaderInstaller installLocalModpack(String modpackName, File modpackFile, String icon) throws IOException {
        short s = checkModpack(modpackFile);
        switch (s) {
            case PACK_MODRINTH:
                return mModrinthApi.installLocalModpack(modpackName, modpackFile, icon);
            case PACK_CURSEFORGE:
                if (mCurseforgeApi == null) return null;
                else return mCurseforgeApi.installLocalModpack(modpackName, modpackFile, icon);
            case PACK_UNDEFINED:
                modpackFile.delete();
                return null;
            default:
                return null;
        }
    }

    private @NonNull ModpackApi getModpackApi(int apiSource) {
        switch (apiSource) {
            case Constants.SOURCE_MODRINTH:
                return mModrinthApi;
            case Constants.SOURCE_CURSEFORGE:
                if (mCurseforgeApi == null) return null;
                else return mCurseforgeApi;
            default:
                throw new UnsupportedOperationException("Unknown API source: " + apiSource);
        }
    }

    public static short checkModpack(File outFile) {
        try (ZipFile zipFile = new ZipFile(outFile)) {
            ZipEntry modrinth = zipFile.getEntry("modrinth.index.json");
            ZipEntry curseforge = zipFile.getEntry("manifest.json");
            if (modrinth != null) {
                return CommonApi.PACK_MODRINTH;
            }
            if (curseforge != null) {
                return CommonApi.PACK_CURSEFORGE;
            }
            return CommonApi.PACK_UNDEFINED; // return this if no modpack was detected
        } catch (Exception e) {
            return -1;
        }
    }

    /** Fuse the arrays in a way that's fair for every endpoint */
    private ModItem[] buildFusedResponse(List<ModItem[]> modMatrix){
        int totalSize = 0;

        // Calculate the total size of the merged array
        for (ModItem[] array : modMatrix) {
            totalSize += array.length;
        }

        ArrayList<ModItem> fusedItems = new ArrayList<>(totalSize);

        // Every title already offered, so the same project arriving from both providers
        // shows up once. The apis are queried in order, which gives the first one in the
        // list the entry whenever both hosts carry the same title.
        HashSet<String> seenTitles = new HashSet<>();
        int maxLength = 0;

        // Find the maximum length of arrays
        for (ModItem[] array : modMatrix) {
            if (array.length > maxLength) {
                maxLength = array.length;
            }
        }

        // Populate the merged array
        for (int i = 0; i < maxLength; i++) {
            for (ModItem[] matrix : modMatrix) {
                if (i < matrix.length) {
                    ModItem item = matrix[i];
                    if(!seenTitles.add(normalizeTitle(item.title))) continue;
                    fusedItems.add(item);
                }
            }
        }

        return fusedItems.toArray(new ModItem[0]);
    }

    /** Titles compare case insensitively and without the punctuation providers add for flair */
    private static String normalizeTitle(String title) {
        if(title == null) return "";
        return title.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]+", " ").trim();
    }

    private void cancelAllFutures(Future<?>[] futures) {
        for(Future<?> future : futures) {
            if(future == null) continue;
            future.cancel(true);
        }
    }

    private class ApiDownloadTask implements Callable<SearchResult> {
        private final int mModApi;
        private final SearchFilters mSearchFilters;
        private final SearchResult mPreviousPageResult;

        private ApiDownloadTask(int modApi, SearchFilters searchFilters, SearchResult previousPageResult) {
            this.mModApi = modApi;
            this.mSearchFilters = searchFilters;
            this.mPreviousPageResult = previousPageResult;
        }

        @Override
        public SearchResult call() {
            return mModpackApis[mModApi].searchMod(mSearchFilters, mPreviousPageResult);
        }
    }

    class CommonApiSearchResult extends SearchResult {
        SearchResult[] searchResults = new SearchResult[mModpackApis.length];
    }
}
