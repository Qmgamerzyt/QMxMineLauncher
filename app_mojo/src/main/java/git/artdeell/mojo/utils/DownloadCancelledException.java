package git.artdeell.mojo.utils;

import java.io.IOException;

/** Thrown when the stop button ends a download. It carries no fault on its own. */
public class DownloadCancelledException extends IOException {
    public DownloadCancelledException() {
        super("Download stopped");
    }
}
