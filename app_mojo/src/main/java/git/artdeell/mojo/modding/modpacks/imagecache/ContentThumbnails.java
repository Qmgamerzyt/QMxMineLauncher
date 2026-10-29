package git.artdeell.mojo.modding.modpacks.imagecache;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.util.Log;
import android.view.ViewGroup;
import android.widget.ImageView;

import git.artdeell.mojo.R;
import git.artdeell.mojo.Tools;
import git.artdeell.mojo.modding.modpacks.models.ContentIndex;
import git.artdeell.mojo.modding.modpacks.models.ContentKind;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.util.Map;
import java.util.WeakHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * Puts a picture in front of a content row. Rows that came from a provider reuse the
 * icon the content screens already cached, rows that came from the device read the
 * picture out of the file itself, which is cheap enough for the few rows a list holds.
 */
public class ContentThumbnails {
    /** Largest thumbnail the rows ever show, kept small so a list holds few pixels. */
    private static final int TARGET_SIZE = 128;
    /** One shared cache of provider icons, since a fresh one would bring threads of its own. */
    private static final ModIconCache ICONS = new ModIconCache();
    /** Two threads read local files, a list never asks for more than a handful at once. */
    private static final ExecutorService POOL = Executors.newFixedThreadPool(2);
    /** Receivers still waiting on a picture, so a row that goes away stops waiting. */
    private static final Map<ImageView, ImageReceiver> WAITING = new WeakHashMap<>();

    /**
     * Show the picture of a piece of content in a row. The row keeps the picture it
     * already holds until its own arrives, so a list being rebuilt stays easy on the eye.
     *
     * @param view          the picture view of the row
     * @param gameDirectory the instance the content sits in
     * @param entry         the row being shown
     * @param fallbackKind  the kind of the list, used when the entry does not say
     */
    public static void bind(ImageView view, File gameDirectory, ContentIndex.Entry entry,
                            ContentKind fallbackKind) {
        cancel(view);
        ContentKind kind = kindOf(entry, fallbackKind);
        if(kind == null) return;
        setImage(view, placeholderOf(kind));
        if(entry.iconUrl != null && !entry.iconUrl.isEmpty() && entry.projectId != null) {
            String tag = entry.apiSource + "_" + entry.projectId;
            ImageReceiver receiver = bitmap -> {
                if(bitmap != null && tag.equals(view.getTag(R.id.content_thumb_tag)))
                    view.setImageBitmap(bitmap);
            };
            synchronized (WAITING) {
                WAITING.put(view, receiver);
            }
            view.setTag(R.id.content_thumb_tag, tag);
            ICONS.getImage(receiver, tag, entry.iconUrl);
            return;
        }
        String token = "local_" + System.nanoTime() + "_" + view.hashCode();
        view.setTag(R.id.content_thumb_tag, token);
        File picture = pictureFile(gameDirectory, kind, entry);
        if(picture == null) return;
        POOL.execute(() -> {
            Bitmap bitmap = readPicture(picture);
            Tools.runOnUiThread(() -> {
                if(!token.equals(view.getTag(R.id.content_thumb_tag))) return;
                if(bitmap != null) view.setImageBitmap(bitmap);
                else setImage(view, placeholderOf(kind));
            });
        });
    }

    /** Stop a row that is going away from waiting on a picture. */
    public static void cancel(ImageView view) {
        ImageReceiver receiver;
        synchronized (WAITING) {
            receiver = WAITING.remove(view);
        }
        if(receiver != null) ICONS.cancelImage(receiver);
        view.setTag(R.id.content_thumb_tag, null);
    }

    /** Put the picture that stands for a kind in a row that holds no content of its own. */
    public static void setPlaceholder(ImageView view, ContentKind kind) {
        cancel(view);
        setImage(view, placeholderOf(kind));
    }

    /** Stop every row of a list from waiting on a picture. */
    public static void cancelAll(ViewGroup container) {
        for(int i = 0; i < container.getChildCount(); i++) {
            ImageView view = container.getChildAt(i).findViewById(R.id.content_thumb);
            if(view != null) cancel(view);
        }
    }

    private static void setImage(ImageView view, int drawable) {
        view.setImageResource(drawable);
    }

    private static ContentKind kindOf(ContentIndex.Entry entry, ContentKind fallbackKind) {
        if(entry.kind != null) {
            try {
                return ContentKind.valueOf(entry.kind);
            }catch (IllegalArgumentException ignored) {
            }
        }
        return fallbackKind;
    }

    /** The picture that stands in for a kind until the real one shows up. */
    private static int placeholderOf(ContentKind kind) {
        switch (kind) {
            case MOD: return R.drawable.ic_px_experiment;
            case MODPACK: return R.drawable.ic_px_data_import;
            case RESOURCE_PACK: return R.drawable.ic_px_image;
            case SHADER: return R.drawable.ic_px_image_renderer;
            case WORLD: return R.drawable.ic_px_home;
            default: return R.drawable.ic_px_file;
        }
    }

    /**
     * Where the picture of a locally placed file lives: the icon a world folder carries,
     * or a pack.png sitting inside a resource pack archive.
     */
    private static File pictureFile(File gameDirectory, ContentKind kind, ContentIndex.Entry entry) {
        if(kind.folder == null || entry.fileName == null || entry.fileName.isEmpty()) return null;
        File root = new File(gameDirectory, kind.folder);
        if(kind == ContentKind.WORLD) {
            File world = new File(root, entry.fileName);
            File icon = new File(world, "icon.png");
            if(icon.isFile()) return icon;
            File old = new File(world, "worldicon.png");
            return old.isFile() ? old : null;
        }
        File archive = new File(root, entry.fileName);
        return archive.isFile() && archive.length() > 0 ? archive : null;
    }

    /** Read a picture for a row, either straight off the disk or out of an archive. */
    private static Bitmap readPicture(File picture) {
        try {
            if(picture.getName().toLowerCase().endsWith(".png")) return decode(picture);
            try(ZipFile zip = new ZipFile(picture)) {
                ZipEntry entry = findPackIcon(zip);
                if(entry == null) return null;
                try(InputStream input = zip.getInputStream(entry)) {
                    byte[] bytes = readAll(input);
                    if(bytes == null) return null;
                    return decode(bytes);
                }
            }
        }catch (IOException | RuntimeException e) {
            Log.w("ContentThumbnails", "Could not read a picture out of " + picture, e);
            return null;
        }
    }

    /** The pack picture of an archive, the one sitting at its root first. */
    private static ZipEntry findPackIcon(ZipFile zip) {
        ZipEntry root = zip.getEntry("pack.png");
        if(root != null) return root;
        String[] wanted = {"assets/pack.png", "pack.png"};
        for(String name : wanted) {
            ZipEntry entry = zip.getEntry(name);
            if(entry != null) return entry;
        }
        return null;
    }

    private static byte[] readAll(InputStream input) throws IOException {
        java.io.ByteArrayOutputStream output = new java.io.ByteArrayOutputStream();
        byte[] buffer = new byte[8192];
        int read;
        while((read = input.read(buffer)) > 0) output.write(buffer, 0, read);
        return output.toByteArray();
    }

    private static Bitmap decode(File file) {
        BitmapFactory.Options bounds = new BitmapFactory.Options();
        bounds.inJustDecodeBounds = true;
        BitmapFactory.decodeFile(file.getAbsolutePath(), bounds);
        return decodeWith(file.getAbsolutePath(), bounds);
    }

    private static Bitmap decode(byte[] bytes) {
        BitmapFactory.Options bounds = new BitmapFactory.Options();
        bounds.inJustDecodeBounds = true;
        BitmapFactory.decodeByteArray(bytes, 0, bytes.length, bounds);
        BitmapFactory.Options options = new BitmapFactory.Options();
        options.inSampleSize = sampleSize(bounds);
        return BitmapFactory.decodeByteArray(bytes, 0, bytes.length, options);
    }

    private static Bitmap decodeWith(String path, BitmapFactory.Options bounds) {
        BitmapFactory.Options options = new BitmapFactory.Options();
        options.inSampleSize = sampleSize(bounds);
        return BitmapFactory.decodeFile(path, options);
    }

    /** How far the picture is scaled down, so a row never decodes a full sized image. */
    private static int sampleSize(BitmapFactory.Options bounds) {
        int largest = Math.max(bounds.outWidth, bounds.outHeight);
        if(largest <= TARGET_SIZE) return 1;
        int sample = 1;
        while(largest / (sample * 2) >= TARGET_SIZE) sample *= 2;
        return sample;
    }
}
