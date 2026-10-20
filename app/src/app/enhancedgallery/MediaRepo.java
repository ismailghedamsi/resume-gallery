package app.enhancedgallery;

import android.content.ContentResolver;
import android.content.Context;
import android.content.res.AssetFileDescriptor;
import android.database.Cursor;
import android.net.Uri;
import android.os.Environment;
import android.provider.DocumentsContract;
import android.provider.MediaStore;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.io.FileWriter;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

/** MediaStore access plus the add-folders model. All queries run off the
 *  UI thread (callers). Only folders the user ADDED via the system folder
 *  selector (FolderSelection) are ever surfaced to the UI; the full
 *  MediaStore scan below is used purely as a lookup table (counts and
 *  thumbnails for added folders) and is never displayed directly. */
public class MediaRepo {

    public static final Uri URI = MediaStore.Images.Media.EXTERNAL_CONTENT_URI;

    /** Folder.path marker for an added folder whose real path is unknown:
     *  the path is "tree:" + treeUri, unique per added folder. */
    public static final String TREE_PREFIX = "tree:";

    private static List<Folder> cachedFolders;
    private static final Map<String, List<ImageItem>> imageCache = new HashMap<>();

    /** Process-unique negative ids for SAF-enumerated images (MediaStore
     *  ids are positive, so caches keyed by id never collide). */
    private static final AtomicLong docSeq = new AtomicLong(-1);

    /** Directories discovered under tree-only added roots (keyed by tree
     *  Uri). Walking a document tree is expensive, and the MediaStore pass
     *  it mirrors is itself process-lifetime cached — so is this. */
    private static final Map<String, List<Folder>> treeDiscoveryCache = new HashMap<>();

    private static final String DISK_CACHE = "folders.cache";

    public static void clearCache() {
        cachedFolders = null;
        imageCache.clear();
        treeDiscoveryCache.clear();
    }

    public static Uri uriFor(long id) {
        return Uri.withAppendedPath(URI, String.valueOf(id));
    }

    public static boolean isTreePath(String path) {
        return path != null && path.startsWith(TREE_PREFIX);
    }

    /** Map a folder-selector tree Uri to a filesystem path.
     *  DocumentsContract's tree document id is "volumeId:relative/path":
     *  "primary" is the internal storage root, any other id ("XXXX-XXXX")
     *  is that volume's mount point under /storage. Null on any failure. */
    public static String treeUriToPath(Uri treeUri) {
        try {
            String docId = DocumentsContract.getTreeDocumentId(treeUri);
            if (docId == null) return null;
            int colon = docId.indexOf(':');
            String volumeId = colon >= 0 ? docId.substring(0, colon) : docId;
            String rel = colon >= 0 ? docId.substring(colon + 1) : "";
            String root;
            if (volumeId.isEmpty() || "primary".equals(volumeId)) {
                root = Environment.getExternalStorageDirectory().getAbsolutePath();
            } else {
                root = "/storage/" + volumeId;
            }
            if (rel == null || rel.isEmpty()) return root;
            return root + "/" + rel;
        } catch (Exception e) {
            return null;
        }
    }

    /** Existence check that works for plain file paths and for content://
     *  image paths (SAF folders) alike. */
    public static boolean contentExists(Context ctx, String imagePath) {
        if (imagePath == null) return false;
        if (imagePath.startsWith("content://")) {
            AssetFileDescriptor fd = null;
            try {
                fd = ctx.getContentResolver().openAssetFileDescriptor(
                        Uri.parse(imagePath), "r");
                return fd != null;
            } catch (Exception e) {
                return false;
            } finally {
                try { if (fd != null) fd.close(); } catch (Exception ignored) {}
            }
        }
        return new File(imagePath).exists();
    }

    /** All image folders on the device, numerically sorted — an internal
     *  lookup table only; NEVER shown to the user directly.
     *  Fast pass: no ORDER BY, no File allocations — parent paths come from
     *  string ops, and the (small) folder list is sorted in Java afterwards. */
    public static List<Folder> getFolders(Context ctx) {
        if (cachedFolders != null) return cachedFolders;
        LinkedHashMap<String, Folder> map = new LinkedHashMap<>();
        ContentResolver cr = ctx.getContentResolver();
        String[] proj = {
                MediaStore.Images.Media._ID,
                MediaStore.Images.Media.BUCKET_ID,
                MediaStore.Images.Media.BUCKET_DISPLAY_NAME,
                MediaStore.Images.Media.DATA };
        Cursor c = null;
        try {
            c = cr.query(URI, proj, null, null, null);
            if (c != null) {
                int idCol = c.getColumnIndex(MediaStore.Images.Media._ID);
                int dataCol = c.getColumnIndex(MediaStore.Images.Media.DATA);
                int bucketCol = c.getColumnIndex(MediaStore.Images.Media.BUCKET_DISPLAY_NAME);
                while (c.moveToNext()) {
                    String data = dataCol >= 0 ? c.getString(dataCol) : null;
                    if (data == null) continue;
                    int slash = data.lastIndexOf('/');
                    if (slash <= 0) continue;
                    String parent = data.substring(0, slash);
                    Folder folder = map.get(parent);
                    if (folder == null) {
                        String bname = bucketCol >= 0 ? c.getString(bucketCol) : null;
                        String name = (bname != null && !bname.isEmpty())
                                ? bname : parent.substring(parent.lastIndexOf('/') + 1);
                        folder = new Folder(name, parent);
                        map.put(parent, folder);
                    }
                    folder.count++;
                    if (folder.thumbId == 0 && idCol >= 0) {
                        folder.thumbId = c.getLong(idCol);
                        folder.thumbPath = data;
                    }
                }
            }
        } catch (Exception ignored) {
        } finally {
            if (c != null) c.close();
        }
        ArrayList<Folder> out = new ArrayList<>(map.values());
        Collections.sort(out, new Comparator<Folder>() {
            public int compare(Folder a, Folder b) { return SortUtil.compareFolders(a, b); }
        });
        cachedFolders = out;
        if (!out.isEmpty()) {
            final Context appCtx = ctx.getApplicationContext();
            new Thread(new Runnable() {
                @Override public void run() { writeDiskCache(appCtx, out); }
            }).start();
        }
        return out;
    }

    /** Path → Folder lookup over the full scan (used to borrow counts and
     *  thumbnails for added folders). */
    public static Map<String, Folder> folderMap(Context ctx) {
        HashMap<String, Folder> m = new HashMap<>();
        for (Folder f : getFolders(ctx)) m.put(f.path, f);
        return m;
    }

    /** Marker identifying one directory inside a tree-only added folder
     *  (real path unknown). The root keeps the plain marker (back-compatible
     *  with v1.5 resumes); a subdirectory gets the document id appended
     *  after a newline, which can never appear in a content Uri string. */
    public static String treeMarker(String treeUri, String docId, String rootDocId) {
        if (docId == null || docId.equals(rootDocId)) return TREE_PREFIX + treeUri;
        return TREE_PREFIX + treeUri + "\n" + docId;
    }

    /** Split a tree path marker back into its tree Uri and directory doc
     *  id (null doc id = the tree root itself). Returns String[2]. */
    public static String[] parseTreeMarker(String marker) {
        String[] out = new String[2];
        String rest = marker.substring(TREE_PREFIX.length());
        int nl = rest.indexOf('\n');
        if (nl < 0) {
            out[0] = rest;
            out[1] = null;
        } else {
            out[0] = rest.substring(0, nl);
            out[1] = rest.substring(nl + 1);
        }
        return out;
    }

    /** The folders the user ADDED. An added folder acts as a ROOT: the
     *  visible list contains the root's own folder (when it directly holds
     *  images) plus EVERY descendant directory, at any depth, that directly
     *  holds images — each as its own folder row. Order: added order of
     *  roots; within a root, the root first, then descendants by relative
     *  path in natural order ("1" < "2" < "2/x" < "10"). */
    public static List<Folder> getVisibleFolders(Context ctx) {
        List<Folder> all = getFolders(ctx);
        List<FolderSelection.AddedFolder> added = FolderSelection.getAdded(ctx);
        ArrayList<Folder> out = new ArrayList<>();
        HashSet<String> seen = new HashSet<>();
        for (FolderSelection.AddedFolder a : added) {
            List<Folder> expanded;
            if (a.realPath != null) {
                expanded = expandPathRoot(all, a);
            } else {
                expanded = treeFolders(ctx, a);
                if (expanded.isEmpty()) {
                    // Keep the never-silently-dropped guarantee: an added
                    // folder whose tree can't be walked (e.g. grant gone)
                    // still shows up as its root.
                    expanded = new ArrayList<Folder>();
                    expanded.add(synthRoot(a));
                }
            }
            for (Folder f : expanded) {
                if (seen.add(f.path)) out.add(f);
            }
        }
        return out;
    }

    /** Same mapping over an arbitrary folder list (e.g. the disk cache):
     *  real-path roots expand purely; tree-only roots degrade to their
     *  root entry until the live scan (getVisibleFolders) runs. */
    public static List<Folder> visibleFrom(List<Folder> all,
                                           List<FolderSelection.AddedFolder> added) {
        ArrayList<Folder> out = new ArrayList<>();
        HashSet<String> seen = new HashSet<>();
        for (FolderSelection.AddedFolder a : added) {
            List<Folder> expanded;
            if (a.realPath != null) {
                expanded = expandPathRoot(all, a);
            } else {
                expanded = new ArrayList<Folder>();
                expanded.add(synthRoot(a));
            }
            for (Folder f : expanded) {
                if (seen.add(f.path)) out.add(f);
            }
        }
        return out;
    }

    /** Expand one real-path root into the visible folders: the root itself
     *  (it appears in `all` only when it directly contains images) plus
     *  every MediaStore folder beneath it. No new scanning — `all` already
     *  holds every folder on the device. */
    private static List<Folder> expandPathRoot(List<Folder> all,
                                               FolderSelection.AddedFolder a) {
        ArrayList<Folder> matches = new ArrayList<>();
        String root = a.realPath;
        String prefix = root + "/";
        for (Folder f : all) {
            if (f.path.equals(root) || f.path.startsWith(prefix)) {
                matches.add(f);
            }
        }
        ArrayList<Folder> out = new ArrayList<>(matches.size());
        if (matches.isEmpty()) {
            // Root not indexed by MediaStore at all: keep the folder itself
            // rather than dropping the user's added folder silently.
            Folder f = new Folder(a.name, root);
            f.treeUri = a.treeUri;
            out.add(f);
            return out;
        }
        Collections.sort(matches, new Comparator<Folder>() {
            public int compare(Folder x, Folder y) {
                return SortUtil.compareRelativePath(relPath(root, x.path),
                        relPath(root, y.path));
            }
        });
        for (Folder m : matches) {
            Folder f = new Folder(m.name, m.path);
            f.count = m.count;
            f.thumbId = m.thumbId;
            f.thumbPath = m.thumbPath;
            f.treeUri = a.treeUri;
            out.add(f);
        }
        return out;
    }

    /** Path of `full` relative to root ("" for the root itself). */
    private static String relPath(String root, String full) {
        if (full.equals(root)) return "";
        return full.substring(root.length() + 1);
    }

    /** The root entry of a tree-only added folder, as in v1.5. */
    private static Folder synthRoot(FolderSelection.AddedFolder a) {
        Folder f = new Folder(a.name, a.realPath != null
                ? a.realPath : TREE_PREFIX + a.treeUri);
        f.treeUri = a.treeUri;
        return f;
    }

    /** Directories under a tree-only added root that directly contain
     *  images — the root itself (if it directly contains images) first,
     *  then descendants by relative path, natural order. Walked once per
     *  process per tree (cached); bounded by a cap on examined rows. */
    private static List<Folder> treeFolders(Context ctx,
                                            FolderSelection.AddedFolder a) {
        if (a.treeUri == null || a.treeUri.isEmpty()) return new ArrayList<Folder>();
        List<Folder> cached = treeDiscoveryCache.get(a.treeUri);
        if (cached != null) return cached;
        ArrayList<Folder> out = new ArrayList<>();
        final ArrayList<String> rels = new ArrayList<>(); // parallel to out
        Uri treeUri = Uri.parse(a.treeUri);
        String rootDocId;
        try {
            rootDocId = DocumentsContract.getTreeDocumentId(treeUri);
        } catch (Exception e) {
            return out;
        }
        ContentResolver cr = ctx.getContentResolver();
        String[] proj = {
                DocumentsContract.Document.COLUMN_DOCUMENT_ID,
                DocumentsContract.Document.COLUMN_DISPLAY_NAME,
                DocumentsContract.Document.COLUMN_MIME_TYPE };
        ArrayDeque<DirNode> stack = new ArrayDeque<>();
        stack.push(new DirNode(rootDocId, "", a.name));
        HashSet<String> visited = new HashSet<>();
        long rows = 0;
        while (!stack.isEmpty() && rows < 100000) {
            DirNode node = stack.pop();
            if (!visited.add(node.docId)) continue;
            Uri childrenUri;
            try {
                childrenUri = DocumentsContract.buildChildDocumentsUriUsingTree(
                        treeUri, node.docId);
            } catch (Exception e) {
                continue;
            }
            Cursor c = null;
            int imageCount = 0;
            ArrayList<DirNode> children = new ArrayList<>();
            try {
                c = cr.query(childrenUri, proj, null, null, null);
                if (c != null) {
                    int idCol = c.getColumnIndex(
                            DocumentsContract.Document.COLUMN_DOCUMENT_ID);
                    int nameCol = c.getColumnIndex(
                            DocumentsContract.Document.COLUMN_DISPLAY_NAME);
                    int mimeCol = c.getColumnIndex(
                            DocumentsContract.Document.COLUMN_MIME_TYPE);
                    while (c.moveToNext() && rows < 100000) {
                        rows++;
                        String docId = idCol >= 0 ? c.getString(idCol) : null;
                        if (docId == null) continue;
                        String mime = mimeCol >= 0 ? c.getString(mimeCol) : null;
                        if (mime != null && mime.startsWith("image/")) {
                            imageCount++;
                        } else if (mime == null
                                || DocumentsContract.Document.MIME_TYPE_DIR.equals(mime)) {
                            String name = nameCol >= 0 ? c.getString(nameCol) : null;
                            if (name == null || name.isEmpty()) {
                                int slash = docId.lastIndexOf('/');
                                name = slash >= 0 ? docId.substring(slash + 1) : docId;
                            }
                            String rel = node.relPath.isEmpty()
                                    ? name : node.relPath + "/" + name;
                            children.add(new DirNode(docId, rel, name));
                        }
                    }
                }
            } catch (Exception ignored) {
            } finally {
                if (c != null) c.close();
            }
            if (imageCount > 0) {
                Folder f = new Folder(node.name,
                        treeMarker(a.treeUri, node.docId, rootDocId));
                f.count = imageCount;
                f.treeUri = a.treeUri;
                out.add(f);
                rels.add(node.relPath);
            }
            for (int i = children.size() - 1; i >= 0; i--) stack.push(children.get(i));
        }
        // Root ("" relative path) sorts first, then natural order inside.
        // Sort by the relative paths carried alongside the folders, keeping
        // the two lists in lockstep. The walk-derived paths reflect the
        // actual tree structure even when doc ids are not path-shaped.
        for (int i = 1; i < out.size(); i++) {
            Folder f = out.get(i);
            String r = rels.get(i);
            int j = i - 1;
            while (j >= 0 && SortUtil.compareRelativePath(r, rels.get(j)) < 0) {
                out.set(j + 1, out.get(j));
                rels.set(j + 1, rels.get(j));
                j--;
            }
            out.set(j + 1, f);
            rels.set(j + 1, r);
        }
        treeDiscoveryCache.put(a.treeUri, out);
        return out;
    }

    private static class DirNode {
        final String docId;
        final String relPath;
        final String name;
        DirNode(String docId, String relPath, String name) {
            this.docId = docId;
            this.relPath = relPath;
            this.name = name;
        }
    }

    /** Images in a folder identified by its filesystem path (the fast
     *  MediaStore path). See getImages(Context, Folder) for the SAF case. */
    public static List<ImageItem> getImages(Context ctx, String folderPath) {
        return getImages(ctx, new Folder("", folderPath));
    }

    /** Images in a folder, sorted by display name in NATURAL (numeric-aware)
     *  order: 1.jpg, 2.jpg, 10.jpg — not 1.jpg, 10.jpg, 2.jpg.
     *  When the folder has a real path, selects only that folder's rows via
     *  BUCKET_ID (MediaStore's bucket-id algorithm), falling back to DATA
     *  LIKE. When only a tree Uri is known (rare — e.g. exotic volumes),
     *  enumerates documents through DocumentsContract instead. */
    public static List<ImageItem> getImages(Context ctx, Folder folder) {
        String cacheKey = folderTreeKey(folder);
        List<ImageItem> cached = imageCache.get(cacheKey);
        if (cached != null) return cached;
        ArrayList<ImageItem> out = new ArrayList<>();
        if (folder.path != null && !folder.path.isEmpty() && !isTreePath(folder.path)) {
            fillImagesFromMediaStore(ctx, folder.path, out);
        } else if ((folder.path != null && isTreePath(folder.path))
                || (folder.treeUri != null && !folder.treeUri.isEmpty())) {
            // Tree folder (e.g. a subdirectory of an added root on an
            // exotic volume): list ONLY this directory's direct image
            // children. Recursion is used only for discovering subfolders
            // in the folder list (treeFolders), never here.
            fillImagesFromTreeDir(ctx, folder, out);
        }
        Collections.sort(out, new Comparator<ImageItem>() {
            public int compare(ImageItem a, ImageItem b) {
                return SortUtil.compareNatural(a.name, b.name);
            }
        });
        imageCache.put(cacheKey, out);
        return out;
    }

    private static String folderTreeKey(Folder folder) {
        // The path alone keys real-path folders and tree-marker folders
        // alike: a marker is unique per directory within its tree.
        if (folder.path != null && !folder.path.isEmpty()) {
            return folder.path;
        }
        return folder.treeUri != null ? folder.treeUri : "";
    }

    /** Fast path: one folder's MediaStore rows only. */
    private static void fillImagesFromMediaStore(Context ctx, String folderPath,
                                                 ArrayList<ImageItem> out) {
        ContentResolver cr = ctx.getContentResolver();
        String[] proj = {
                MediaStore.Images.Media._ID,
                MediaStore.Images.Media.DISPLAY_NAME,
                MediaStore.Images.Media.DATA };
        Cursor c = null;
        try {
            String bucketId = String.valueOf(
                    folderPath.toLowerCase(Locale.ROOT).hashCode());
            c = cr.query(URI, proj, MediaStore.Images.Media.BUCKET_ID + " = ?",
                    new String[]{bucketId}, null);
            if (c != null && c.getCount() > 0) {
                fillImages(c, out, null);
                c.close();
                c = null;
            } else {
                if (c != null) { c.close(); c = null; }
                // Fallback: DATA prefix match, then strict parent check per row.
                c = cr.query(URI, proj, MediaStore.Images.Media.DATA + " LIKE ?",
                        new String[]{folderPath + "/%"}, null);
                if (c != null) fillImages(c, out, folderPath);
            }
        } catch (Exception ignored) {
        } finally {
            if (c != null) c.close();
        }
    }

    private static void fillImages(Cursor c, ArrayList<ImageItem> out, String folderPath) {
        int idCol = c.getColumnIndex(MediaStore.Images.Media._ID);
        int nameCol = c.getColumnIndex(MediaStore.Images.Media.DISPLAY_NAME);
        int dataCol = c.getColumnIndex(MediaStore.Images.Media.DATA);
        while (c.moveToNext()) {
            String data = dataCol >= 0 ? c.getString(dataCol) : null;
            if (data == null) continue;
            if (folderPath != null) {
                int slash = data.lastIndexOf('/');
                if (slash <= 0) continue;
                if (!data.substring(0, slash).equals(folderPath)) continue;
            }
            String name = nameCol >= 0 ? c.getString(nameCol) : null;
            if (name == null) {
                int slash = data.lastIndexOf('/');
                name = slash >= 0 ? data.substring(slash + 1) : data;
            }
            long id = c.getLong(idCol);
            out.add(new ImageItem(id, data, name, uriFor(id)));
        }
    }

    /** Fallback for folders inside a tree-only added root: list the image
     *  documents DIRECTLY inside this folder's directory (no recursion —
     *  subfolders are their own rows in the folder list). The directory
     *  is identified by the doc id embedded in the folder's path marker;
     *  a plain marker means the tree root itself. Relies on the
     *  persistable read grant taken when the root was added. */
    private static void fillImagesFromTreeDir(Context ctx, Folder folder,
                                              ArrayList<ImageItem> out) {
        String treeUriStr;
        String docId = null;
        if (folder.path != null && isTreePath(folder.path)) {
            String[] parsed = parseTreeMarker(folder.path);
            treeUriStr = parsed[0];
            docId = parsed[1];
        } else {
            treeUriStr = folder.treeUri;
        }
        if (treeUriStr == null || treeUriStr.isEmpty()) return;
        Uri treeUri = Uri.parse(treeUriStr);
        if (docId == null) {
            try {
                docId = DocumentsContract.getTreeDocumentId(treeUri);
            } catch (Exception e) {
                return;
            }
        }
        Uri childrenUri;
        try {
            childrenUri = DocumentsContract.buildChildDocumentsUriUsingTree(
                    treeUri, docId);
        } catch (Exception e) {
            return;
        }
        String[] proj = {
                DocumentsContract.Document.COLUMN_DOCUMENT_ID,
                DocumentsContract.Document.COLUMN_DISPLAY_NAME,
                DocumentsContract.Document.COLUMN_MIME_TYPE };
        Cursor c = null;
        try {
            c = ctx.getContentResolver().query(childrenUri, proj, null, null, null);
            if (c == null) return;
            int idCol = c.getColumnIndex(DocumentsContract.Document.COLUMN_DOCUMENT_ID);
            int nameCol = c.getColumnIndex(DocumentsContract.Document.COLUMN_DISPLAY_NAME);
            int mimeCol = c.getColumnIndex(DocumentsContract.Document.COLUMN_MIME_TYPE);
            while (c.moveToNext()) {
                String childId = idCol >= 0 ? c.getString(idCol) : null;
                if (childId == null) continue;
                String mime = mimeCol >= 0 ? c.getString(mimeCol) : null;
                if (mime == null || !mime.startsWith("image/")) continue;
                String name = nameCol >= 0 ? c.getString(nameCol) : null;
                if (name == null || name.isEmpty()) {
                    int slash = childId.lastIndexOf('/');
                    name = slash >= 0 ? childId.substring(slash + 1) : childId;
                }
                Uri docUri = DocumentsContract.buildDocumentUriUsingTree(
                        treeUri, childId);
                out.add(new ImageItem(docSeq.getAndDecrement(),
                        docUri.toString(), name, docUri));
            }
        } catch (Exception ignored) {
        } finally {
            if (c != null) c.close();
        }
    }

    // ---- disk cache: folder list for instant cold start ----

    private static String esc(String s) {
        StringBuilder sb = new StringBuilder(s.length());
        for (int i = 0; i < s.length(); i++) {
            char ch = s.charAt(i);
            if (ch == '\\') sb.append("\\\\");
            else if (ch == '\t') sb.append("\\t");
            else if (ch == '\n') sb.append("\\n");
            else sb.append(ch);
        }
        return sb.toString();
    }

    private static String unesc(String s) {
        StringBuilder sb = new StringBuilder(s.length());
        for (int i = 0; i < s.length(); i++) {
            char ch = s.charAt(i);
            if (ch == '\\' && i + 1 < s.length()) {
                char n = s.charAt(++i);
                if (n == 't') sb.append('\t');
                else if (n == 'n') sb.append('\n');
                else sb.append(n); // \\ -> \
            } else {
                sb.append(ch);
            }
        }
        return sb.toString();
    }

    private static void writeDiskCache(Context ctx, List<Folder> folders) {
        File f = new File(ctx.getFilesDir(), DISK_CACHE);
        File tmp = new File(ctx.getFilesDir(), DISK_CACHE + ".tmp");
        FileWriter w = null;
        try {
            w = new FileWriter(tmp);
            for (Folder fl : folders) {
                w.write(esc(fl.name) + "\t" + esc(fl.path) + "\t" + fl.count + "\t"
                        + fl.thumbId + "\t" + esc(fl.thumbPath != null ? fl.thumbPath : "")
                        + "\n");
            }
            w.close();
            w = null;
            tmp.renameTo(f);
        } catch (Exception ignored) {
        } finally {
            try { if (w != null) w.close(); } catch (Exception ignored) {}
        }
    }

    /** Folder list from the last scan, or null if there is none / it is unreadable. */
    public static List<Folder> readDiskCache(Context ctx) {
        File f = new File(ctx.getFilesDir(), DISK_CACHE);
        if (!f.exists()) return null;
        ArrayList<Folder> out = new ArrayList<>();
        BufferedReader r = null;
        try {
            r = new BufferedReader(new FileReader(f));
            String line;
            while ((line = r.readLine()) != null) {
                String[] parts = line.split("\t", -1);
                if (parts.length < 5) continue;
                Folder fl = new Folder(unesc(parts[0]), unesc(parts[1]));
                try {
                    fl.count = Integer.parseInt(parts[2]);
                    fl.thumbId = Long.parseLong(parts[3]);
                } catch (NumberFormatException e) {
                    continue;
                }
                String tp = unesc(parts[4]);
                fl.thumbPath = tp.isEmpty() ? null : tp;
                out.add(fl);
            }
        } catch (Exception ignored) {
            return null;
        } finally {
            try { if (r != null) r.close(); } catch (Exception ignored) {}
        }
        return out.isEmpty() ? null : out;
    }

    public static int folderIndex(List<Folder> folders, String folderPath) {
        if (folderPath == null) return -1;
        for (int i = 0; i < folders.size(); i++) {
            if (folderPath.equals(folders.get(i).path)) return i;
        }
        return -1;
    }

    public static int imageIndexByPath(List<ImageItem> images, String imagePath) {
        for (int i = 0; i < images.size(); i++) {
            if (images.get(i).path.equals(imagePath)) return i;
        }
        return -1;
    }
}
