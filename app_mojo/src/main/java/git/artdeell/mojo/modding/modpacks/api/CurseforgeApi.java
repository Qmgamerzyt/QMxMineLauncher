package git.artdeell.mojo.modding.modpacks.api;

import android.util.Log;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.kdt.mcgui.ProgressLayout;

import git.artdeell.mojo.Tools;
import git.artdeell.mojo.downloader.AcquireableTaskMetadata;
import git.artdeell.mojo.downloader.Downloader;
import git.artdeell.mojo.tasks.mojson.mirrors.DownloadMirror;
import git.artdeell.mojo.modding.fabric.FabriclikeUtils;
import git.artdeell.mojo.modding.forge.ForgelikeUtils;
import git.artdeell.mojo.modding.modpacks.api.modloader.FabriclikeLoaderInstaller;
import git.artdeell.mojo.modding.modpacks.api.modloader.ForgelikeLoaderInstaller;
import git.artdeell.mojo.modding.modpacks.api.modloader.LoaderInstaller;
import git.artdeell.mojo.modding.modpacks.models.Constants;
import git.artdeell.mojo.modding.modpacks.models.ContentKind;
import git.artdeell.mojo.modding.modpacks.models.CurseManifest;
import git.artdeell.mojo.modding.modpacks.models.ModDetail;
import git.artdeell.mojo.modding.modpacks.models.ModItem;
import git.artdeell.mojo.modding.modpacks.models.SearchCategory;
import git.artdeell.mojo.modding.modpacks.models.SearchFilters;
import git.artdeell.mojo.modding.modpacks.models.SearchLoader;
import git.artdeell.mojo.modding.modpacks.models.SearchResult;
import git.artdeell.mojo.modding.modpacks.models.SearchSort;
import git.artdeell.mojo.utils.FileUtils;
import git.artdeell.mojo.utils.GsonJsonUtils;
import git.artdeell.mojo.utils.ZipUtils;

import java.io.File;
import java.io.IOException;
import java.net.URL;
import java.net.URLDecoder;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.regex.Pattern;
import java.util.zip.ZipFile;

public class CurseforgeApi implements ModpackApi{
    private static final Pattern sMcVersionPattern = Pattern.compile("([0-9]+)\\.([0-9]+)\\.?([0-9]+)?");
    private static final int ALGO_SHA_1 = 1;
    // Stolen from
    // https://github.com/AnzhiZhang/CurseForgeModpackDownloader/blob/6cb3f428459f0cc8f444d16e54aea4cd1186fd7b/utils/requester.py#L93
    private static final int CURSEFORGE_MC_GAME_ID = 432;
    private static final int CURSEFORGE_PAGINATION_SIZE = 50;
    private static final int CURSEFORGE_PAGINATION_END_REACHED = -1;
    private static final int CURSEFORGE_PAGINATION_ERROR = -2;

    private final ApiHandler mApiHandler;
    public CurseforgeApi(String apiKey) {
        mApiHandler = new ApiHandler("https://api.curseforge.com/v1", apiKey);
    }

    @Override
    public SearchResult searchMod(SearchFilters searchFilters, SearchResult previousPageResult) {
        CurseforgeSearchResult curseforgeSearchResult = (CurseforgeSearchResult) previousPageResult;

        HashMap<String, Object> params = new HashMap<>();
        params.put("gameId", CURSEFORGE_MC_GAME_ID);
        params.put("classId", searchFilters.kind.curseforgeClassId);
        params.put("searchFilter", searchFilters.name);
        params.put("sortField", SearchSort.fromValue(searchFilters.sort).curseforgeField);
        params.put("sortOrder", "desc");
        if(searchFilters.mcVersion != null && !searchFilters.mcVersion.isEmpty())
            params.put("gameVersion", searchFilters.mcVersion);
        // A loader carries its own code here rather than a category
        SearchLoader loader = SearchLoader.fromName(searchFilters.loader);
        if(loader != SearchLoader.ANY) params.put("modLoaderType", loader.curseforgeCode);
        // Several categories go in as one bracketed list. CurseForge reads the plain
        // spelling as if nothing had been asked for, so a lone one joins in as well
        int[] categoryIds = searchFilters.curseforgeCategories();
        // The picked categories belong to the other host alone, this one has nothing
        // to narrow by and answers with an empty page rather than a full one
        if(categoryIds.length == 0 && !searchFilters.categories.isEmpty())
            return emptyResult(curseforgeSearchResult);
        if(categoryIds.length > 0) {
            StringBuilder bracketed = new StringBuilder("[");
            for(int i = 0; i < categoryIds.length; i++) {
                if(i > 0) bracketed.append(',');
                bracketed.append(categoryIds[i]);
            }
            params.put("categoryIds", bracketed.append(']').toString());
        }
        if(previousPageResult != null)
            params.put("index", curseforgeSearchResult.previousOffset);

        JsonObject response = mApiHandler.get("mods/search", params, JsonObject.class);
        if(response == null) return null;
        JsonArray dataArray = response.getAsJsonArray("data");
        if(dataArray == null) return null;
        JsonObject paginationInfo = response.getAsJsonObject("pagination");
        ArrayList<ModItem> modItemList = new ArrayList<>(dataArray.size());
        for(int i = 0; i < dataArray.size(); i++) {
            JsonObject dataElement = dataArray.get(i).getAsJsonObject();
            JsonElement allowModDistribution = dataElement.get("allowModDistribution");
            // Gson automatically casts null to false, which leans to issues
            // So, only check the distribution flag if it is non-null
            if(!allowModDistribution.isJsonNull() && !allowModDistribution.getAsBoolean()) {
                Log.i("CurseforgeApi", "Skipping modpack "+dataElement.get("name").getAsString() + " because curseforge sucks");
                continue;
            }
            ModItem modItem = new ModItem(Constants.SOURCE_CURSEFORGE,
                    searchFilters.kind,
                    dataElement.get("id").getAsString(),
                    dataElement.get("name").getAsString(),
                    dataElement.get("summary").getAsString(),
                    dataElement.getAsJsonObject("logo").get("thumbnailUrl").getAsString());
            modItemList.add(modItem);
        }
        if(curseforgeSearchResult == null) curseforgeSearchResult = new CurseforgeSearchResult();
        curseforgeSearchResult.results = modItemList.toArray(new ModItem[0]);
        curseforgeSearchResult.totalResultCount = paginationInfo.get("totalCount").getAsInt();
        curseforgeSearchResult.previousOffset += dataArray.size();
        return curseforgeSearchResult;

    }

    /** A page with nothing in it, for a filter this host has no way to answer. */
    private CurseforgeSearchResult emptyResult(CurseforgeSearchResult previous) {
        CurseforgeSearchResult result = new CurseforgeSearchResult();
        result.results = new ModItem[0];
        result.totalResultCount = 0;
        result.previousOffset = previous == null ? 0 : previous.previousOffset;
        return result;
    }

    @Override
    public List<SearchCategory> listCategories(ContentKind kind) {
        HashMap<String, Object> params = new HashMap<>();
        params.put("gameId", CURSEFORGE_MC_GAME_ID);
        params.put("classId", kind.curseforgeClassId);
        JsonObject response = mApiHandler.get("categories", params, JsonObject.class);
        if(response == null) return Collections.emptyList();
        JsonArray data = response.getAsJsonArray("data");
        if(data == null) return Collections.emptyList();
        ArrayList<SearchCategory> categories = new ArrayList<>(data.size());
        for(int i = 0; i < data.size(); i++) {
            JsonObject element = data.get(i).getAsJsonObject();
            if(!element.has("name") || element.get("name").isJsonNull()
                    || !element.has("id") || element.get("id").isJsonNull()) continue;
            String name = element.get("name").getAsString();
            int id = element.get("id").getAsInt();
            if(name.isEmpty() || id == 0) continue;
            categories.add(new SearchCategory(name, null, id));
        }
        return categories;
    }

    @Override
    public ModDetail getModDetails(ModItem item) {
        ArrayList<JsonObject> allModDetails = new ArrayList<>();
        int index = 0;
        while(index != CURSEFORGE_PAGINATION_END_REACHED &&
                index != CURSEFORGE_PAGINATION_ERROR) {
            index = getPaginatedDetails(allModDetails, index, item.id);
        }
        if(index == CURSEFORGE_PAGINATION_ERROR) return null;
        int length = allModDetails.size();
        String[] versionNames = new String[length];
        String[] mcVersionNames = new String[length];
        String[] loaderNames = new String[length];
        String[] versionUrls = new String[length];
        String[] hashes = new String[length];
        for(int i = 0; i < allModDetails.size(); i++) {
            JsonObject modDetail = allModDetails.get(i);
            String displayName = GsonJsonUtils.getStringSafe(modDetail, "displayName");
            versionNames[i] = displayName == null ? "" : displayName;

            // A removed or server only file carries no link at all, the installer
            // reports that when such a version ends up being the one picked
            JsonElement downloadUrl = modDetail.get("downloadUrl");
            versionUrls[i] = downloadUrl == null || downloadUrl.isJsonNull() ? null : downloadUrl.getAsString();

            mcVersionNames[i] = "";
            JsonArray gameVersions = GsonJsonUtils.getJsonArraySafe(modDetail, "gameVersions");
            if(gameVersions != null) {
                for(JsonElement jsonElement : gameVersions) {
                    String gameVersion = jsonElement.getAsString();
                    if(!sMcVersionPattern.matcher(gameVersion).matches()) {
                        continue;
                    }
                    mcVersionNames[i] = gameVersion;
                    break;
                }
            }

            // CurseForge marks the loader a file was built for with a code of its own,
            // zero standing for a file that goes with any of them
            loaderNames[i] = SearchLoader.fromCurseforgeCode(
                    GsonJsonUtils.getIntSafe(modDetail, "modLoader", 0)).name();

            hashes[i] = getSha1FromModData(modDetail);
        }
        return new ModDetail(item, versionNames, mcVersionNames, loaderNames, versionUrls, hashes);
    }

    @Override
    public LoaderInstaller installModpack(ModDetail modDetail, int selectedVersion) throws IOException{
        //TODO considering only modpacks for now
        return ModpackInstaller.downloadModpack(modDetail, selectedVersion, this::installCurseforgeZip);
    }

    public LoaderInstaller installLocalModpack(String modpackName, File modpackFile, String icon) throws IOException {
        return ModpackInstaller.installModpack(modpackName, modpackName, modpackFile, icon, this::installCurseforgeZip);
    }

    private int getPaginatedDetails(ArrayList<JsonObject> objectList, int index, String modId) {
        HashMap<String, Object> params = new HashMap<>();
        params.put("index", index);
        params.put("pageSize", CURSEFORGE_PAGINATION_SIZE);

        JsonObject response = mApiHandler.get("mods/"+modId+"/files", params, JsonObject.class);
        JsonArray data = GsonJsonUtils.getJsonArraySafe(response, "data");
        Log.i("CurseforgeApi", "data...");
        if(data == null) return CURSEFORGE_PAGINATION_ERROR;
        Log.i("CurseforgeApi", "filtering...");
        for(int i = 0; i < data.size(); i++) {
            JsonObject fileInfo = data.get(i).getAsJsonObject();
            if(fileInfo.get("isServerPack").getAsBoolean()) continue;
            objectList.add(fileInfo);
        }
        Log.i("CurseforgeApi", "pag_end");
        if(data.size() < CURSEFORGE_PAGINATION_SIZE) {
            return CURSEFORGE_PAGINATION_END_REACHED; // we read the remainder! yay!
        }
        return index + data.size();
    }

    private LoaderInstaller installCurseforgeZip(File zipFile, File instanceDestination) throws IOException {
        try (ZipFile modpackZipFile = new ZipFile(zipFile)){
            CurseManifest curseManifest = Tools.GLOBAL_GSON.fromJson(
                    Tools.read(ZipUtils.getEntryStream(modpackZipFile, "manifest.json")),
                    CurseManifest.class);
            if(!verifyManifest(curseManifest)) {
                Log.i("CurseforgeApi","manifest verification failed");
                return null;
            }
            try {
                new CurseDownloader().start(curseManifest, instanceDestination);
            }catch (InterruptedException e) {
                throw new IOException("NIY: InterruptedException", e);
            }
            String overridesDir = "overrides";
            if(curseManifest.overrides != null) overridesDir = curseManifest.overrides;
            ZipUtils.zipExtract(modpackZipFile, overridesDir, instanceDestination);
            return createInfo(curseManifest.minecraft);
        }
    }

    private LoaderInstaller createInfo(CurseManifest.CurseMinecraft minecraft) {
        CurseManifest.CurseModLoader primaryModLoader = null;
        for(CurseManifest.CurseModLoader modLoader : minecraft.modLoaders) {
            if(modLoader.primary) {
                primaryModLoader = modLoader;
                break;
            }
        }
        if(primaryModLoader == null) primaryModLoader = minecraft.modLoaders[0];
        String modLoaderId = primaryModLoader.id;
        int dashIndex = modLoaderId.indexOf('-');
        String modLoaderName = modLoaderId.substring(0, dashIndex);
        String modLoaderVersion = modLoaderId.substring(dashIndex+1);
        Log.i("CurseforgeApi", modLoaderId + " " + modLoaderName + " "+modLoaderVersion);
        LoaderInstaller loaderInstaller;
        switch (modLoaderName) {
            case "forge":
                return new ForgelikeLoaderInstaller(ForgelikeUtils.FORGE_UTILS, minecraft.version, modLoaderVersion);
            case "fabric":
                return new FabriclikeLoaderInstaller(FabriclikeUtils.FABRIC_UTILS, minecraft.version, modLoaderVersion);
            case "neoforge":
                return new ForgelikeLoaderInstaller(ForgelikeUtils.NEOFORGE_UTILS, minecraft.version, modLoaderVersion);
            default:
                return null;
            //TODO: Quilt is also Forge? How does that work?
        }
    }

    private String getDownloadUrl(JsonObject fileMetadata) throws IOException {
        if(fileMetadata.get("modId").isJsonNull() || fileMetadata.get("id").isJsonNull()) throw new IOException("Bad metadata schema!");
        long projectID = fileMetadata.get("modId").getAsLong();
        long fileID = fileMetadata.get("id").getAsLong();

        // First try the official api endpoint
        JsonObject response = mApiHandler.get("mods/"+projectID+"/files/"+fileID+"/download-url", JsonObject.class);
        if (response != null && !response.get("data").isJsonNull())
            return response.get("data").getAsString();

        // Otherwise, fallback to building an edge link
        return String.format("https://edge.forgecdn.net/files/%s/%s/%s", fileID/1000, fileID % 1000, fileMetadata.get("fileName").getAsString());
    }

    private void checkRequiredFileFields(JsonObject fileMetadata) throws IOException {
        if(fileMetadata == null || fileMetadata.isJsonNull()) throw new IOException("File metadata is null!");
        boolean hasProjectId = fileMetadata.has("modId");
        boolean hasFileId = fileMetadata.has("id");
        boolean hasLength = fileMetadata.has("fileLength");
        if(!hasProjectId || !hasFileId || !hasLength) {
            StringBuilder builder = new StringBuilder().append("File metadata is mising the following fields:");
            if(!hasProjectId) builder.append(" modId");
            if(!hasFileId) builder.append(" id");
            if(!hasLength) builder.append(" fileLength");
            throw new IOException(builder.toString());
        }
    }

    private @Nullable JsonObject getFile(long projectID, long fileID) {
        JsonObject response = mApiHandler.get("mods/"+projectID+"/files/"+fileID, JsonObject.class);
        return GsonJsonUtils.getJsonObjectSafe(response, "data");
    }

    private String getSha1FromModData(@NonNull JsonObject object) {
        JsonArray hashes = GsonJsonUtils.getJsonArraySafe(object, "hashes");
        if(hashes == null) return null;
        for (JsonElement jsonElement : hashes) {
            // The sha1 = 1; md5 = 2;
            JsonObject jsonObject = GsonJsonUtils.getJsonObjectSafe(jsonElement);
            if(GsonJsonUtils.getIntSafe(
                    jsonObject,
                    "algo",
                    -1) == ALGO_SHA_1) {
                return GsonJsonUtils.getStringSafe(jsonObject, "value");
            }
        }
        return null;
    }

    private boolean verifyManifest(CurseManifest manifest) {
        if(!"minecraftModpack".equals(manifest.manifestType)) return false;
        if(manifest.manifestVersion != 1) return false;
        if(manifest.minecraft == null) return false;
        if(manifest.minecraft.version == null) return false;
        if(manifest.minecraft.modLoaders == null) return false;
        return manifest.minecraft.modLoaders.length >= 1;
    }

    static class CurseforgeSearchResult extends SearchResult {
        int previousOffset;
    }

    class CurseDownloader extends Downloader {

        public CurseDownloader() {
            super(ProgressLayout.INSTALL_MODPACK);
        }

        public void start(CurseManifest curseManifest, File instanceDestination) throws IOException, InterruptedException {
            ArrayList<AcquireableTaskMetadata> taskMetadatas = new ArrayList<>(curseManifest.files.length);
            for(final CurseManifest.CurseFile file : curseManifest.files) {
                taskMetadatas.add(new CurseTaskMetadata(file, instanceDestination));
            }
            runDownloads(taskMetadatas);
        }
    }

    class CurseTaskMetadata extends AcquireableTaskMetadata {
        private final CurseManifest.CurseFile mFile;
        private final File mInstanceDestination;

        public CurseTaskMetadata(CurseManifest.CurseFile mFile, File mInstanceDestination) {
            super(DownloadMirror.DOWNLOAD_CLASS_METADATA);
            this.mFile = mFile;
            this.mInstanceDestination = mInstanceDestination;
        }

        @Override
        public void acquireMetadata() throws IOException {
            JsonObject fileMetadata = getFile(mFile.projectID, mFile.fileID);
            checkRequiredFileFields(fileMetadata);
            String url = getDownloadUrl(fileMetadata);
            this.url = new URL(url);
            this.path = new File(mInstanceDestination, "mods/"+ URLDecoder.decode(FileUtils.getFileName(url),"UTF-8"));
            FileUtils.ensureParentDirectorySilently(this.path);
            this.sha1Hash = getSha1FromModData(fileMetadata);
            this.size = fileMetadata.get("fileLength").getAsLong();
        }
    }
}
