package git.artdeell.mojo.modding.modpacks.api;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.kdt.mcgui.ProgressLayout;

import git.artdeell.mojo.R;
import git.artdeell.mojo.Tools;
import git.artdeell.mojo.downloader.Downloader;
import git.artdeell.mojo.downloader.TaskMetadata;
import git.artdeell.mojo.tasks.mojson.mirrors.DownloadMirror;
import git.artdeell.mojo.modding.fabric.FabriclikeUtils;
import git.artdeell.mojo.modding.forge.ForgelikeUtils;
import git.artdeell.mojo.modding.lwjgl3ify.Lwjgl3ifyUtils;
import git.artdeell.mojo.modding.modpacks.api.modloader.FabriclikeLoaderInstaller;
import git.artdeell.mojo.modding.modpacks.api.modloader.ForgelikeLoaderInstaller;
import git.artdeell.mojo.modding.modpacks.api.modloader.LoaderInstaller;
import git.artdeell.mojo.modding.modpacks.api.modloader.Lwjgl3ifyLoaderInstaller;
import git.artdeell.mojo.modding.modpacks.models.Constants;
import git.artdeell.mojo.modding.modpacks.models.ContentKind;
import git.artdeell.mojo.modding.modpacks.models.ModDetail;
import git.artdeell.mojo.modding.modpacks.models.ModItem;
import git.artdeell.mojo.modding.modpacks.models.ModrinthIndex;
import git.artdeell.mojo.modding.modpacks.models.SearchCategory;
import git.artdeell.mojo.modding.modpacks.models.SearchFilters;
import git.artdeell.mojo.modding.modpacks.models.SearchLoader;
import git.artdeell.mojo.modding.modpacks.models.SearchResult;
import git.artdeell.mojo.modding.modpacks.models.SearchSort;
import git.artdeell.mojo.utils.FileUtils;
import git.artdeell.mojo.utils.ZipUtils;

import java.io.File;
import java.io.IOException;
import java.net.URL;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.ZipFile;

public class ModrinthApi implements ModpackApi{
    private final ApiHandler mApiHandler;
    public ModrinthApi(){
        mApiHandler = new ApiHandler("https://api.modrinth.com/v2");
    }

    @Override
    public SearchResult searchMod(SearchFilters searchFilters, SearchResult previousPageResult) {
        ModrinthSearchResult modrinthSearchResult = (ModrinthSearchResult) previousPageResult;

        // Modrinth does not host this kind of content, hand back an empty page for it
        if(!searchFilters.kind.onModrinth()) {
            ModrinthSearchResult emptyResult = new ModrinthSearchResult();
            emptyResult.results = new ModItem[0];
            emptyResult.totalResultCount = 0;
            emptyResult.previousOffset = 0;
            return emptyResult;
        }

        // Fixes an issue where the offset being equal or greater than total_hits is ignored
        if (modrinthSearchResult != null && modrinthSearchResult.previousOffset >= modrinthSearchResult.totalResultCount) {
            ModrinthSearchResult emptyResult = new ModrinthSearchResult();
            emptyResult.results = new ModItem[0];
            emptyResult.totalResultCount = modrinthSearchResult.totalResultCount;
            emptyResult.previousOffset = modrinthSearchResult.previousOffset;
            return emptyResult;
        }


        // Build the facets filters
        // A picked category that Modrinth does not group content under leaves this
        // host with nothing to narrow by, and a search it cannot honour answers
        // with an empty page instead of a full one
        List<String> pickedCategories = searchFilters.modrinthCategories();
        if(pickedCategories.isEmpty() && !searchFilters.categories.isEmpty())
            return emptyResult(modrinthSearchResult);
        HashMap<String, Object> params = new HashMap<>();
        StringBuilder facetString = new StringBuilder();
        facetString.append("[");
        facetString.append(String.format("[\"project_type:%s\"]", searchFilters.kind.modrinthType));
        if(searchFilters.mcVersion != null && !searchFilters.mcVersion.isEmpty())
            facetString.append(String.format(",[\"versions:%s\"]", searchFilters.mcVersion));
        // The loader and every picked category share one group, so a hit carrying any of them is kept
        StringBuilder categoryFacets = new StringBuilder();
        SearchLoader pickedLoader = SearchLoader.fromName(searchFilters.loader);
        if(pickedLoader != SearchLoader.ANY)
            categoryFacets.append(String.format("\"categories:%s\"", pickedLoader.facet));
        for(String category : pickedCategories) {
            if(categoryFacets.length() > 0) categoryFacets.append(',');
            categoryFacets.append(String.format("\"categories:%s\"", category));
        }
        if(categoryFacets.length() > 0) {
            facetString.append(",[").append(categoryFacets).append("]");
        }
        facetString.append("]");
        params.put("facets", facetString.toString());
        params.put("query", searchFilters.name);
        params.put("limit", 50);
        params.put("index", SearchSort.fromValue(searchFilters.sort).modrinthIndex);
        if(modrinthSearchResult != null)
            params.put("offset", modrinthSearchResult.previousOffset);

        JsonObject response = mApiHandler.get("search", params, JsonObject.class);
        if(response == null) return null;
        JsonArray responseHits = response.getAsJsonArray("hits");
        if(responseHits == null) return null;

        ArrayList<ModItem> items = new ArrayList<>(responseHits.size());
        for(int i=0; i<responseHits.size(); ++i){
            JsonObject hit = responseHits.get(i).getAsJsonObject();
            ContentKind hitKind = ContentKind.fromModrinthType(hit.get("project_type").getAsString());
            // A hit from a neighbouring kind belongs in a different folder of the instance
            if(hitKind != searchFilters.kind) continue;
            items.add(new ModItem(
                    Constants.SOURCE_MODRINTH,
                    hitKind,
                    hit.get("project_id").getAsString(),
                    hit.get("title").getAsString(),
                    hit.get("description").getAsString(),
                    hit.get("icon_url").getAsString()
            ));
        }
        if(modrinthSearchResult == null) modrinthSearchResult = new ModrinthSearchResult();
        modrinthSearchResult.previousOffset += responseHits.size();
        modrinthSearchResult.results = items.toArray(new ModItem[0]);
        modrinthSearchResult.totalResultCount = response.get("total_hits").getAsInt();
        return modrinthSearchResult;
    }

    /** A page with nothing in it, for a filter this host has no way to answer. */
    private ModrinthSearchResult emptyResult(ModrinthSearchResult previous) {
        ModrinthSearchResult result = new ModrinthSearchResult();
        result.results = new ModItem[0];
        result.totalResultCount = 0;
        result.previousOffset = previous == null ? 0 : previous.previousOffset;
        return result;
    }

    @Override
    public List<SearchCategory> listCategories(ContentKind kind) {
        if(!kind.onModrinth()) return Collections.emptyList();
        HashMap<String, Object> params = new HashMap<>();
        params.put("project_type", kind.modrinthType);
        JsonArray response = mApiHandler.get("tag/category", params, JsonArray.class);
        if(response == null) return Collections.emptyList();
        ArrayList<SearchCategory> categories = new ArrayList<>(response.size());
        for(int i = 0; i < response.size(); i++) {
            JsonObject element = response.get(i).getAsJsonObject();
            if(!element.has("name") || element.get("name").isJsonNull()) continue;
            // Modrinth calls the key of a category its name at this endpoint
            String name = element.get("name").getAsString();
            String projectType = element.has("project_type") && !element.get("project_type").isJsonNull()
                    ? element.get("project_type").getAsString() : null;
            if(name.isEmpty() || !kind.modrinthType.equals(projectType)) continue;
            categories.add(new SearchCategory(name, name, 0));
        }
        return categories;
    }

    @Override
    public ModDetail getModDetails(ModItem item) {

        JsonArray response = mApiHandler.get(String.format("project/%s/version", item.id), JsonArray.class);
        if(response == null) return null;
        String[] names = new String[response.size()];
        String[] mcNames = new String[response.size()];
        String[] loaders = new String[response.size()];
        String[] urls = new String[response.size()];
        String[] hashes = new String[response.size()];

        for (int i=0; i<response.size(); ++i) {
            JsonObject version = response.get(i).getAsJsonObject();
            names[i] = readString(version, "name");
            // A version may name several Minecraft versions and several loaders, the
            // first of each is the one the row is written for, the rest is kept for
            // matching the instance against the list
            mcNames[i] = readArrayFirst(version, "game_versions");
            loaders[i] = readJoinedArray(version, "loaders");
            JsonArray files = version.getAsJsonArray("files");
            urls[i] = files != null && !files.isEmpty() && files.get(0).isJsonObject()
                    ? readString(files.get(0).getAsJsonObject(), "url") : null;
            // Assume there may not be hashes, in case the API changes
            if(files == null || files.isEmpty() || !files.get(0).isJsonObject()) {
                hashes[i] = null;
                continue;
            }
            JsonObject hashesMap = files.get(0).getAsJsonObject().get("hashes") != null
                    && files.get(0).getAsJsonObject().get("hashes").isJsonObject()
                    ? files.get(0).getAsJsonObject().getAsJsonObject("hashes") : null;
            if(hashesMap == null || hashesMap.get("sha1") == null){
                hashes[i] = null;
                continue;
            }

            hashes[i] = hashesMap.get("sha1").getAsString();
        }

        return new ModDetail(item, names, mcNames, loaders, urls, hashes);
    }

    /** The value of a string field, or an empty string when it is missing or not a string. */
    private static String readString(JsonObject object, String key) {
        if(object == null || !object.has(key) || object.get(key).isJsonNull()) return "";
        try {
            return object.get(key).getAsString();
        }catch (RuntimeException e) {
            return "";
        }
    }

    /** An array field, or null when it is missing or holds something else. */
    private static JsonArray readArray(JsonObject object, String key) {
        if(object == null || !object.has(key) || !object.get(key).isJsonArray()) return null;
        return object.getAsJsonArray(key);
    }

    /** The first entry of a string array field, or an empty string when there is none. */
    private static String readArrayFirst(JsonObject object, String key) {
        JsonArray array = readArray(object, key);
        if(array == null || array.isEmpty() || array.get(0).isJsonNull()) return "";
        try {
            return array.get(0).getAsString();
        }catch (RuntimeException e) {
            return "";
        }
    }

    /** Every entry of a string array field joined by a comma, or an empty string. */
    private static String readJoinedArray(JsonObject object, String key) {
        JsonArray array = readArray(object, key);
        if(array == null || array.isEmpty()) return "";
        StringBuilder joined = new StringBuilder();
        for(JsonElement element : array) {
            if(element.isJsonNull()) continue;
            String value;
            try {
                value = element.getAsString();
            }catch (RuntimeException e) {
                continue;
            }
            if(value.isEmpty()) continue;
            if(joined.length() > 0) joined.append(',');
            joined.append(value);
        }
        return joined.toString();
    }

    @Override
    public LoaderInstaller installModpack(ModDetail modDetail, int selectedVersion) throws IOException{
        //TODO considering only modpacks for now
        return ModpackInstaller.downloadModpack(modDetail, selectedVersion, this::installMrpack);
    }

    public LoaderInstaller installLocalModpack(String modpackName, File modpackFile, String icon) throws IOException {
        return ModpackInstaller.installModpack(modpackName, modpackName, modpackFile, icon, this::installMrpack);
    }

    private static LoaderInstaller createInfo(ModrinthIndex modrinthIndex, File installDestination) throws IOException {
        if(modrinthIndex == null) return null;
        Map<String, String> dependencies = modrinthIndex.dependencies;
        String mcVersion = dependencies.get("minecraft");
        if(mcVersion == null) return null;
        String modLoaderVersion;
        if((modLoaderVersion = dependencies.get("forge")) != null) {
            return new ForgelikeLoaderInstaller(ForgelikeUtils.FORGE_UTILS, mcVersion, modLoaderVersion);
        } else if((modLoaderVersion = dependencies.get("fabric-loader")) != null) {
            return new FabriclikeLoaderInstaller(FabriclikeUtils.FABRIC_UTILS, mcVersion, modLoaderVersion);
        } else if((modLoaderVersion = dependencies.get("quilt-loader")) != null) {
            return new FabriclikeLoaderInstaller(FabriclikeUtils.QUILT_UTILS, mcVersion, modLoaderVersion);
        } else if((modLoaderVersion = dependencies.get("neoforge")) != null) {
            return new ForgelikeLoaderInstaller(ForgelikeUtils.NEOFORGE_UTILS, mcVersion, modLoaderVersion);
        } else if(dependencies.size() == 1) {
            // "Vanilla" pack. Possibly GT:NH, let's try to detect lwjgl3ify
            File lwjgl3ifyJar = Lwjgl3ifyUtils.detectLwjgl3ifyJar(installDestination);
            if(lwjgl3ifyJar != null) return new Lwjgl3ifyLoaderInstaller(lwjgl3ifyJar);
        }

        return null;
    }

    private LoaderInstaller installMrpack(File mrpackFile, File instanceDestination) throws IOException {
        try (ZipFile modpackZipFile = new ZipFile(mrpackFile)){
            ModrinthIndex modrinthIndex = Tools.GLOBAL_GSON.fromJson(
                    Tools.read(ZipUtils.getEntryStream(modpackZipFile, "modrinth.index.json")),
                    ModrinthIndex.class);
            try {
                new ModrinthDownloader().startDownloads(modrinthIndex.files, instanceDestination);
            }catch (InterruptedException e) {
                throw new IOException("NIY: InterruptedException", e);
            }
            ProgressLayout.setProgress(ProgressLayout.INSTALL_MODPACK, 0, R.string.modpack_download_applying_overrides, 1, 2);
            ZipUtils.zipExtract(modpackZipFile, "overrides/", instanceDestination);
            ProgressLayout.setProgress(ProgressLayout.INSTALL_MODPACK, 50, R.string.modpack_download_applying_overrides, 2, 2);
            ZipUtils.zipExtract(modpackZipFile, "client-overrides/", instanceDestination);
            return createInfo(modrinthIndex, instanceDestination);
        }
    }

    class ModrinthSearchResult extends SearchResult {
        int previousOffset;
    }

    static class ModrinthDownloader extends Downloader {
        public ModrinthDownloader() {
            super(ProgressLayout.INSTALL_MODPACK);
        }

        protected void startDownloads(ModrinthIndex.ModrinthIndexFile[] indexFiles, File instanceDestination) throws IOException, InterruptedException {
            String absoluteInstancePath = instanceDestination.getAbsolutePath();
            ArrayList<TaskMetadata> taskMetadatas = new ArrayList<>(indexFiles.length);
            for(ModrinthIndex.ModrinthIndexFile file : indexFiles) {
                File targetPath = new File(instanceDestination, file.path);
                if(!targetPath.getAbsolutePath().startsWith(absoluteInstancePath)) throw new IOException("Bad path!");
                FileUtils.ensureParentDirectory(targetPath);
                taskMetadatas.add(new TaskMetadata(
                        targetPath, new URL(file.downloads[0]), // TODO source selection
                        file.fileSize, file.hashes.sha1,
                        DownloadMirror.DOWNLOAD_CLASS_NONE
                ));
            }
            runDownloads(taskMetadatas);
        }
    }
}
