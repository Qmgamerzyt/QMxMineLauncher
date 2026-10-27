package git.artdeell.mojo.modding.modpacks.models;

/**
 * The order results come back in. Modrinth spells each one out as a word while
 * CurseForge counts them, so both live side by side.
 */
public enum SearchSort {
    RELEVANCE("relevance", 1),
    DOWNLOADS("downloads", 6),
    FOLLOWED("follows", 2),
    UPDATED("updated", 3),
    NEWEST("newest", 11);

    /** Value of the Modrinth index parameter. */
    public final String modrinthIndex;
    /** Value of the CurseForge sortField parameter. */
    public final int curseforgeField;

    SearchSort(String modrinthIndex, int curseforgeField) {
        this.modrinthIndex = modrinthIndex;
        this.curseforgeField = curseforgeField;
    }

    /** The order standing for a stored value, falling back to relevance. */
    public static SearchSort fromValue(String value) {
        if(value == null) return RELEVANCE;
        for(SearchSort sort : values()) {
            if(sort.name().equals(value)) return sort;
        }
        return RELEVANCE;
    }
}
