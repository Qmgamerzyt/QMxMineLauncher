package git.artdeell.mojo.utils.profiles;

import androidx.annotation.NonNull;

import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import git.artdeell.mojo.JVersionList;
import git.artdeell.mojo.Tools;
import git.artdeell.mojo.modding.modpacks.models.SearchLoader;

/**
 * What an instance runs, worked out from its version. A profile written by a mod
 * loader names the Minecraft version it was built on top of, while the content
 * providers are asked for that version and for that loader rather than for the
 * profile itself, so a search started from an instance lands on things that fit it.
 */
public class InstanceVersions {
    /** How far the chain of profiles naming another profile is followed. */
    private static final int MAX_DEPTH = 8;
    /** Last version shaped number of a profile name, used when there is nothing to read. */
    private static final Pattern TRAILING_VERSION = Pattern.compile("(\\d+\\.\\d+(?:\\.\\d+)?)$");

    private InstanceVersions() {}

    /**
     * The Minecraft version an instance runs, following the profile a mod loader
     * created down to the game it sits on.
     *
     * @param versionId the version of the instance, as its profile names it
     * @return the Minecraft version, the version itself when it already is one, or
     *         null when the instance has no version to work with
     */
    public static String resolveGameVersion(String versionId) {
        if(!Tools.isValidString(versionId)) return null;
        String candidate = versionId;
        for(int depth = 0; depth < MAX_DEPTH; depth++) {
            String parent = parentOf(candidate);
            if(parent == null || parent.equals(candidate)) break;
            candidate = parent;
        }
        if(!looksLikeGameVersion(candidate)) {
            // The profile is there but its JSON is not readable, so the version it
            // was named after is taken straight from the name instead
            String fromName = trailingVersion(versionId);
            if(fromName != null) return fromName;
        }
        return candidate;
    }

    /** Whether the version id looks like one of the game's own versions. */
    private static boolean looksLikeGameVersion(String versionId) {
        Matcher matcher = TRAILING_VERSION.matcher(versionId);
        return matcher.find() && versionId.equals(matcher.group(1));
    }

    /** The version a profile sits on top of, or null when there is nothing to read. */
    private static String parentOf(String versionId) {
        JVersionList.Version version;
        try {
            version = Tools.getVersionInfo(versionId, true);
        }catch (RuntimeException e) {
            return null;
        }
        if(version == null || !Tools.isValidString(version.inheritsFrom)) return null;
        return version.inheritsFrom;
    }

    /** The last version shaped number of a profile name, or null when it has none. */
    private static String trailingVersion(String versionId) {
        Matcher matcher = TRAILING_VERSION.matcher(versionId);
        return matcher.find() ? matcher.group(1) : null;
    }

    /**
     * The loader an instance runs on, read from the name its profile was given.
     *
     * @param versionId the version of the instance
     * @return the loader, or ANY when the profile is the game on its own
     */
    @NonNull
    public static SearchLoader resolveLoader(String versionId) {
        if(!Tools.isValidString(versionId)) return SearchLoader.ANY;
        String id = versionId.toLowerCase(Locale.ROOT);
        // NeoForge is looked for first, its name holds the one of Forge
        if(id.contains("neoforge")) return SearchLoader.NEOFORGE;
        if(id.contains("fabric")) return SearchLoader.FABRIC;
        if(id.contains("quilt")) return SearchLoader.QUILT;
        if(id.contains("forge")) return SearchLoader.FORGE;
        return SearchLoader.ANY;
    }
}
