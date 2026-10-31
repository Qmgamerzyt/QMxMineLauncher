package git.artdeell.mojo.modding.modpacks.models;

/**
 * The mod loaders a search can be narrowed to. Modrinth treats a loader as one of
 * its categories, while CurseForge carries a numeric code of its own, so both
 * spellings live here.
 */
public enum SearchLoader {
    ANY("", 0, ""),
    FABRIC("fabric", 4, "Fabric"),
    FORGE("forge", 1, "Forge"),
    NEOFORGE("neoforge", 6, "NeoForge"),
    QUILT("quilt", 5, "Quilt"),
    // Shaders do not run on a mod loader, these two are what they run on instead
    IRIS("iris", 0, "Iris"),
    OPTIFINE("optifine", 0, "OptiFine");

    /** Value of the Modrinth categories facet, empty when the loader is not narrowed. */
    public final String facet;
    /** Value of the CurseForge modLoaderType parameter, zero when CurseForge has no code for it. */
    public final int curseforgeCode;
    /** The name players know the loader by, empty for the un narrowed one. */
    public final String display;

    SearchLoader(String facet, int curseforgeCode, String display) {
        this.facet = facet;
        this.curseforgeCode = curseforgeCode;
        this.display = display;
    }

    /**
     * The loaders a search of this kind can be narrowed by, null when the kind has none:
     * resource packs and worlds are not built on a loader, shaders run on iris or optifine.
     */
    public static SearchLoader[] forKind(ContentKind kind) {
        switch (kind) {
            case SHADER: return new SearchLoader[]{ANY, IRIS, OPTIFINE};
            case RESOURCE_PACK:
            case WORLD: return null;
            default: return new SearchLoader[]{ANY, FABRIC, FORGE, NEOFORGE, QUILT};
        }
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
