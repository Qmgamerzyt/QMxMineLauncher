package git.artdeell.mojo.modding.modpacks.models;

import androidx.annotation.NonNull;

import java.util.Locale;

/**
 * One category as both providers spell it. The two hosts group content in their own
 * way, so a name can belong to one side only, or to both with a different key each.
 * The name is what the reader of a filter picks, the keys stay behind it.
 */
public class SearchCategory {
    /** Name shown in the filter list, also used to match the two providers together. */
    public String name;
    /** Key in the Modrinth categories facet, null when Modrinth has no such category. */
    public String modrinthSlug;
    /** Id in the CurseForge categoryIds parameter, 0 when CurseForge has no such category. */
    public int curseforgeId;
    /** Whether a label tells which host a category belongs to, turned off when there is only one. */
    public boolean showProvider = true;

    public SearchCategory(String name, String modrinthSlug, int curseforgeId) {
        this.name = name;
        this.modrinthSlug = modrinthSlug;
        this.curseforgeId = curseforgeId;
    }

    public boolean knownByModrinth() {
        return modrinthSlug != null && !modrinthSlug.isEmpty();
    }

    public boolean knownByCurseforge() {
        return curseforgeId != 0;
    }

    /**
     * Take over whatever the other host carries for the same name, so one entry in
     * the filter list drives both searches.
     */
    public void merge(SearchCategory other) {
        if(other == null) return;
        if(!knownByModrinth() && other.knownByModrinth()) modrinthSlug = other.modrinthSlug;
        if(!knownByCurseforge() && other.knownByCurseforge()) curseforgeId = other.curseforgeId;
    }

    /**
     * Label for the filter list. A category carried by one provider only says which
     * one, because picking it narrows the search down to that host.
     */
    @NonNull
    public String label() {
        if(!showProvider) return name;
        if(knownByModrinth() && knownByCurseforge()) return name;
        if(knownByModrinth()) return name + " (Modrinth)";
        if(knownByCurseforge()) return name + " (CurseForge)";
        return name;
    }

    /** Names match across providers without their case or punctuation getting in the way. */
    public static String normalize(String name) {
        if(name == null) return "";
        return name.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]+", " ").trim();
    }
}
