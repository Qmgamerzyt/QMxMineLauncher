package git.artdeell.mojo.modding.modpacks.models;


import androidx.annotation.NonNull;

import java.util.Arrays;

public class ModDetail extends ModItem {
    /* A cheap way to map from the front facing name to the underlying id */
    public String[] versionNames;
    public String [] mcVersionNames;
    /* Loaders a version was published for, several of them joined by a comma, empty when the host does not say */
    public String[] loaderNames;
    public String[] versionUrls;
    /* SHA 1 hashes, null if a hash is unavailable */
    public String[] versionHashes;
    /* The projects each version leans on, null when the host says nothing about them */
    public ContentDependency[][] versionDependencies;
    public ModDetail(ModItem item, String[] versionNames, String[] mcVersionNames,
                     String[] loaderNames, String[] versionUrls, String[] hashes,
                     ContentDependency[][] versionDependencies) {
        super(item.apiSource, item.kind, item.id, item.title, item.description, item.imageUrl);
        this.versionNames = versionNames;
        this.mcVersionNames = mcVersionNames;
        this.loaderNames = loaderNames;
        this.versionUrls = versionUrls;
        this.versionHashes = hashes;
        this.versionDependencies = versionDependencies;

        // Add the mc version to the version model
        for (int i=0; i<versionNames.length; i++){
            if (!versionNames[i].contains(mcVersionNames[i]))
                versionNames[i] += " - " + mcVersionNames[i];
        }
    }

    /**
     * The version an instance should be offered first, so the one that suits it is
     * already sitting in the spinner. A version carrying both the Minecraft version
     * and the loader of the instance wins, then one carrying the Minecraft version,
     * and the newest of the list stands in when nothing lines up.
     *
     * @param mcVersion  the Minecraft version the instance runs, or null when unknown
     * @param loaderName the loader the instance runs on, or null when unknown
     * @return the index into versionNames to start the spinner at
     */
    public int bestVersionIndex(String mcVersion, String loaderName) {
        boolean wantMcVersion = mcVersion != null && !mcVersion.isEmpty();
        // A search for any loader has no version to favour on that account
        boolean wantLoader = loaderName != null && !loaderName.isEmpty()
                && !"ANY".equalsIgnoreCase(loaderName);
        int bestIndex = 0;
        int bestScore = -1;
        for(int i = 0; i < versionNames.length; i++) {
            int score = 0;
            if(wantMcVersion && i < mcVersionNames.length
                    && mcVersion.equals(mcVersionNames[i])) score += 2;
            if(wantLoader && holdsLoader(i, loaderName)) score += 1;
            if(score > bestScore) {
                bestScore = score;
                bestIndex = i;
            }
        }
        return bestIndex;
    }

    /**
     * The projects the version at the given index leans on, empty when the host
     * says nothing about them or the index is out of reach.
     */
    public ContentDependency[] dependenciesOf(int index) {
        if(versionDependencies == null || index < 0 || index >= versionDependencies.length
                || versionDependencies[index] == null) return new ContentDependency[0];
        return versionDependencies[index];
    }

    /** Whether one version was published for the given loader, matched without case. */
    public boolean holdsLoader(int index, String loaderName) {
        if(loaderNames == null || index >= loaderNames.length) return false;
        String declared = loaderNames[index];
        if(declared == null || declared.isEmpty()) return false;
        for(String loader : declared.split(",")) {
            String trimmed = loader.trim();
            // A file published for any loader goes with whichever one the instance runs
            if("ANY".equalsIgnoreCase(trimmed) || loaderName.equalsIgnoreCase(trimmed)) return true;
        }
        return false;
    }

    @NonNull
    @Override
    public String toString() {
        return "ModDetail{" +
                "versionNames=" + Arrays.toString(versionNames) +
                ", mcVersionNames=" + Arrays.toString(mcVersionNames) +
                ", versionIds=" + Arrays.toString(versionUrls) +
                ", id='" + id + '\'' +
                ", title='" + title + '\'' +
                ", description='" + description + '\'' +
                ", imageUrl='" + imageUrl + '\'' +
                ", apiSource=" + apiSource +
                ", kind=" + kind +
                '}';
    }
}
