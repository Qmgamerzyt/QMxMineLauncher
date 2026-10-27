package git.artdeell.mojo.modding.modpacks.models;

/**
 * The mod loaders a search can be narrowed to. Modrinth treats a loader as one of
 * its categories, while CurseForge carries a numeric code of its own, so both
 * spellings live here.
 */
public enum SearchLoader {
    ANY("", 0),
    FABRIC("fabric", 4),
    FORGE("forge", 1),
    NEOFORGE("neoforge", 6),
    QUILT("quilt", 5);

    /** Value of the Modrinth categories facet, empty when the loader is not narrowed. */
    public final String facet;
    /** Value of the CurseForge modLoaderType parameter. */
    public final int curseforgeCode;

    SearchLoader(String facet, int curseforgeCode) {
        this.facet = facet;
        this.curseforgeCode = curseforgeCode;
    }

    /** The loader standing for a facet value, or ANY when nothing matches. */
    public static SearchLoader fromFacet(String facet) {
        if(facet == null) return ANY;
        for(SearchLoader loader : values()) {
            if(loader.facet.equals(facet)) return loader;
        }
        return ANY;
    }

    /** The loader a stored name stands for, or ANY when the name is unknown. */
    public static SearchLoader fromName(String name) {
        if(name == null) return ANY;
        for(SearchLoader loader : values()) {
            if(loader.name().equals(name)) return loader;
        }
        return ANY;
    }

    /** The loader a CurseForge code stands for, or ANY when the code is not one of them. */
    public static SearchLoader fromCurseforgeCode(int code) {
        if(code == 0) return ANY;
        for(SearchLoader loader : values()) {
            if(loader != ANY && loader.curseforgeCode == code) return loader;
        }
        return ANY;
    }
}
