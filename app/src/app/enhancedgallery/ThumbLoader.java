package app.enhancedgallery;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.net.Uri;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.util.LruCache;
import android.util.Size;
import android.widget.ImageView;

import java.io.InputStream;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Async downsampled image decoding. Never decodes full-size images for the
 *  grid, and never holds all bitmaps in memory.
 *
 *  Performance notes (v1.1):
 *  - Thumbnails use ContentResolver.loadThumbnail (system thumbnail cache).
 *  - Decoding reads from the file path when possible (no slow provider streams).
 *  - sampleFor() picks the sample whose result is CLOSEST to the target, so
 *    decoded bitmaps are small; the viewer has its own single-thread executor
 *    so prefetches never starve the current image. */
public class ThumbLoader {

    /** Grid/folder thumbnails. */
    public static final ExecutorService BG = Executors.newFixedThreadPool(3);
    /** Fullscreen viewer decodes: one thread, current image first, then
     *  neighbours queued behind it — never competing with the current image. */
    public static final ExecutorService VIEWER = Executors.newSingleThreadExecutor();

    private static final Handler UI = new Handler(Looper.getMainLooper());

    private static final int MAX_MEM_KB = (int) (Runtime.getRuntime().maxMemory() / 1024);
    private static final LruCache<Long, Bitmap> thumbCache =
            new LruCache<Long, Bitmap>(MAX_MEM_KB / 8) {
                @Override protected int sizeOf(Long k, Bitmap b) {
                    return b.getByteCount() / 1024;
                }
            };
    private static final LruCache<Long, Bitmap> viewerCache =
            new LruCache<Long, Bitmap>(MAX_MEM_KB / 4) {
                @Override protected int sizeOf(Long k, Bitmap b) {
                    return b.getByteCount() / 1024;
                }
            };

    public static Bitmap getThumb(long id) { return thumbCache.get(id); }
    public static Bitmap getViewer(long id) { return viewerCache.get(id); }
    public static void putViewer(long id, Bitmap b) { viewerCache.put(id, b); }

    /** Power-of-two sample whose decoded long edge is CLOSEST to the target
     *  (undershoot allowed): the result lands within [target/2, target]
     *  instead of up to 2x the target. */
    public static int sampleFor(long targetLongEdge, BitmapFactory.Options bounds) {
        int longEdge = Math.max(bounds.outWidth, bounds.outHeight);
        int target = (int) targetLongEdge;
        int s = 1;
        int bestDiff = Math.abs(longEdge - target);
        while (true) {
            int next = s * 2;
            int r = longEdge / next;
            if (r <= 0) break;
            int d = Math.abs(r - target);
            if (d < bestDiff) {
                bestDiff = d;
                s = next;
            } else {
                break;
            }
        }
        return s;
    }

    /** Decode the image identified by id, downsampled so the long edge is
     *  close to maxLongEdge. Stream-based (fallback path). */
    public static Bitmap decode(Context ctx, long id, long maxLongEdge) {
        return decodeUri(ctx, id, MediaRepo.uriFor(id), maxLongEdge);
    }

    /** Decode downsampled from an arbitrary content Uri (MediaStore or
     *  Storage Access Framework document). */
    public static Bitmap decodeUri(Context ctx, long id, Uri uri, long maxLongEdge) {
        if (uri == null) return null;
        BitmapFactory.Options o = new BitmapFactory.Options();
        o.inJustDecodeBounds = true;
        InputStream in = null;
        try {
            in = ctx.getContentResolver().openInputStream(uri);
            BitmapFactory.decodeStream(in, null, o);
        } catch (Exception ignored) {
        } finally {
            try { if (in != null) in.close(); } catch (Exception ignored) {}
        }
        if (o.outWidth <= 0 || o.outHeight <= 0) return null;
        o.inJustDecodeBounds = false;
        o.inSampleSize = sampleFor(maxLongEdge, o);
        Bitmap b = null;
        try {
            in = ctx.getContentResolver().openInputStream(uri);
            b = BitmapFactory.decodeStream(in, null, o);
        } catch (Exception ignored) {
        } finally {
            try { if (in != null) in.close(); } catch (Exception ignored) {}
        }
        return b;
    }

    /** Decode downsampled directly from the file path (fast, no provider
     *  stream); falls back to the stream decoder for the given Uri
     *  (MediaStore id Uri, or an SAF document Uri) if the file can't be read. */
    public static Bitmap decodePath(Context ctx, long id, Uri uri, String path, long maxLongEdge) {
        if (path != null && !path.startsWith("content://")) {
            try {
                BitmapFactory.Options o = new BitmapFactory.Options();
                o.inJustDecodeBounds = true;
                BitmapFactory.decodeFile(path, o);
                if (o.outWidth > 0 && o.outHeight > 0) {
                    o.inJustDecodeBounds = false;
                    o.inSampleSize = sampleFor(maxLongEdge, o);
                    Bitmap b = BitmapFactory.decodeFile(path, o);
                    if (b != null) return b;
                }
            } catch (Exception ignored) {
            }
        }
        return decodeUri(ctx, id, uri, maxLongEdge);
    }

    public interface OnLoaded { void onLoaded(ImageView view, long id, Bitmap bitmap); }

    /** Load a grid/folder thumbnail into view, async, race-safe.
     *  Uses the system thumbnail cache (fast); falls back to a downsampled
     *  file decode. */
    public static void loadThumb(final Context ctx, final ImageView view, final long id,
                                final String path, final OnLoaded done) {
        loadThumb(ctx, view, id, id > 0 ? MediaRepo.uriFor(id) : null, path, done);
    }

    /** As above, but the thumbnail source is an explicit content Uri —
     *  required for SAF-enumerated images, whose ids are not MediaStore ids. */
    public static void loadThumb(final Context ctx, final ImageView view, final long id,
                                final Uri uri, final String path, final OnLoaded done) {
        view.setTag(id);
        Bitmap cached = thumbCache.get(id);
        if (cached != null) {
            done.onLoaded(view, id, cached);
            return;
        }
        BG.execute(new Runnable() {
            @Override public void run() {
                final Context appCtx = ctx.getApplicationContext();
                Bitmap b = null;
                if (Build.VERSION.SDK_INT >= 29 && uri != null) {
                    try {
                        b = appCtx.getContentResolver().loadThumbnail(
                                uri, new Size(256, 256), null);
                    } catch (Exception ignored) {
                    }
                }
                if (b == null) b = decodePath(appCtx, id, uri, path, 256);
                if (b != null) thumbCache.put(id, b);
                final Bitmap fb = b;
                UI.post(new Runnable() {
                    @Override public void run() {
                        Object tag = view.getTag();
                        if (tag instanceof Long && (Long) tag == id && fb != null) {
                            done.onLoaded(view, id, fb);
                        }
                    }
                });
            }
        });
    }
}
