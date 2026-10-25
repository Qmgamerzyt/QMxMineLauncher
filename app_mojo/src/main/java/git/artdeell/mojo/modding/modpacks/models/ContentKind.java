package git.artdeell.mojo.modding.modpacks.models;

/**
 * The kind of game content a search or an install works with. Every kind carries the
 * identifiers that Modrinth and CurseForge use for it, plus the folder inside an
 * instance where a downloaded file belongs.
 *
 * The CurseForge class ids come from
 * https://api.curseforge.com/v1/categories?gameId=432
 */
public enum ContentKind {
    MOD("mod", 6, "mods"),
    RESOURCE_PACK("resourcepack", 12, "resourcepacks"),
    SHADER("shader", 6552, "shaderpacks"),
    WORLD(null, 17, "saves"),
    MODPACK("modpack", 4471, null);

    /** Value of the Modrinth project_type facet, null when Modrinth does not host the kind. */
    public final String modrinthType;
    /** CurseForge class id. */
    public final int curseforgeClassId;
    /** Folder inside the instance game directory, null when the kind does not land in one. */
    public final String folder;

    ContentKind(String modrinthType, int curseforgeClassId, String folder) {
        this.modrinthType = modrinthType;
        this.curseforgeClassId = curseforgeClassId;
        this.folder = folder;
    }

    /** Whether Modrinth is able to answer a search for this kind. */
    public boolean onModrinth() {
        return modrinthType != null;
    }

    /** Whether the downloaded content arrives as an archive to be unpacked into the folder. */
    public boolean unpacksArchive() {
        return this == WORLD;
    }

    /** Whether this kind is placed into an instance that already exists. */
    public boolean installsIntoExistingInstance() {
        return folder != null;
    }
}
