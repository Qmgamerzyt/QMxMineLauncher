package git.artdeell.mojo.modding.modpacks.models;

import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;

/**
 * Search filters, passed to APIs
 */
public class SearchFilters {
    public ContentKind kind = ContentKind.MOD;
    public String name;
    @Nullable public String mcVersion;
    /** Loader to narrow by, the name of a SearchLoader entry, ANY for no narrowing. */
    public String loader = SearchLoader.ANY.name();
    /** Field the results are ordered by, the name of a SearchSort entry. */
    public String sort = SearchSort.RELEVANCE.name();
    /** Categories to narrow by, empty when the results are not narrowed. */
    public final List<SearchCategory> categories = new ArrayList<>();

    /**
     * Kinds carry their own set of categories, so a category picked for one kind
     * means nothing to the next. Called whenever the kind changes.
     */
    public void clearCategories() {
        categories.clear();
    }

    /** The Modrinth keys of the picked categories, dropping the ones it does not know. */
    public List<String> modrinthCategories() {
        ArrayList<String> slugs = new ArrayList<>(categories.size());
        for(SearchCategory category : categories) {
            if(category.knownByModrinth()) slugs.add(category.modrinthSlug);
        }
        return slugs;
    }

    /** The CurseForge ids of the picked categories, dropping the ones it does not know. */
    public int[] curseforgeCategories() {
        int[] ids = new int[categories.size()];
        int kept = 0;
        for(SearchCategory category : categories) {
            if(category.knownByCurseforge()) ids[kept++] = category.curseforgeId;
        }
        int[] trimmed = new int[kept];
        System.arraycopy(ids, 0, trimmed, 0, kept);
        return trimmed;
    }

    /** Whether a search is narrowed beyond the kind and the text query. */
    public boolean isFiltered() {
        return (mcVersion != null && !mcVersion.isEmpty())
                || !SearchLoader.ANY.name().equals(loader)
                || !SearchSort.RELEVANCE.name().equals(sort)
                || !categories.isEmpty();
    }
}
