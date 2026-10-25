package git.artdeell.mojo.modding.modpacks.models;

import org.jetbrains.annotations.Nullable;

/**
 * Search filters, passed to APIs
 */
public class SearchFilters {
    public ContentKind kind = ContentKind.MOD;
    public String name;
    @Nullable public String mcVersion;

}
