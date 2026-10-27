package git.artdeell.mojo.instances;

import java.io.File;

public class DisplayInstance {
    protected transient File mInstanceRoot;
    public String name;
    public String versionId;
    public String icon;

    protected void sanitize() {
        sanitizeIcon();
    }

    protected DisplayInstance() {
    }

    protected File getInstanceIconLocation() {
        return new File(mInstanceRoot, "icon.webp");
    }

    /**
     * The label the instance is listed under. An instance can exist without ever being
     * given a name, in which case the folder it lives in stands in for one, so a list of
     * them never has a blank row or a null entry to render.
     *
     * @return the name, or the folder name when there is no name
     */
    public String displayName() {
        if(name != null && !name.trim().isEmpty()) return name;
        if(mInstanceRoot == null) return "";
        return mInstanceRoot.getName();
    }

    private void sanitizeIcon() {
        if(!InstanceIconProvider.hasStaticIcon(icon)) {
            icon = InstanceIconProvider.FALLBACK_ICON_NAME;
        }
    }
}
