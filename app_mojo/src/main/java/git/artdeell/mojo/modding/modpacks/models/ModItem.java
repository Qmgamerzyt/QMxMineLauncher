package git.artdeell.mojo.modding.modpacks.models;

import androidx.annotation.NonNull;

public class ModItem extends ModSource {

    public String id;
    public String title;
    public String description;
    public String imageUrl;
    /** The page of this project on its own site, as the source gave it. */
    public String webUrl;

    public ModItem(int apiSource, ContentKind kind, String id, String title, String description, String imageUrl) {
        this.apiSource = apiSource;
        this.kind = kind;
        this.id = id;
        this.title = title;
        this.description = description;
        this.imageUrl = imageUrl;
    }

    @NonNull
    @Override
    public String toString() {
        return "ModItem{" +
                "id='" + id + '\'' +
                ", title='" + title + '\'' +
                ", description='" + description + '\'' +
                ", imageUrl='" + imageUrl + '\'' +
                ", apiSource=" + apiSource +
                ", kind=" + kind +
                '}';
    }

    /** The page to open in the in-app browser, guessed from the id when the source kept it back. */
    public String webPageUrl() {
        if(webUrl != null && !webUrl.isEmpty()) return webUrl;
        if(id == null || id.isEmpty()) return null;
        if(apiSource == Constants.SOURCE_MODRINTH) return "https://modrinth.com/project/"+id;
        if(apiSource == Constants.SOURCE_CURSEFORGE) return "https://www.curseforge.com/projects/"+id;
        return null;
    }

    public String getIconCacheTag() {
        return apiSource+"_"+id;
    }
}
