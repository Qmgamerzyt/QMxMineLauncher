package git.artdeell.mojo.modding.modpacks.models;

/**
 * One project a version of content leans on. The host that published the version
 * names the project by an id of its own, and says whether the content runs without
 * it or not.
 */
public class ContentDependency {
    /** The provider the project is hosted by. */
    public final int apiSource;
    /** The id the provider gives the project this leans on. */
    public final String projectId;
    /** The version of that project this version was built against, null when none is named. */
    public final String versionId;
    /** Whether the content will not run without this. */
    public final boolean required;

    public ContentDependency(int apiSource, String projectId, String versionId, boolean required) {
        this.apiSource = apiSource;
        this.projectId = projectId;
        this.versionId = versionId;
        this.required = required;
    }
}
