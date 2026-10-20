package app.enhancedgallery;

import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.database.Cursor;
import android.net.Uri;
import android.provider.DocumentsContract;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/** Folders the user ADDED, one at a time, through Android's system folder
 *  selector (Intent.ACTION_OPEN_DOCUMENT_TREE). The app starts knowing NO
 *  folders; folders that were never added are never displayed anywhere.
 *
 *  Since v2.0 the added set is PER-SESSION: SessionStore owns the session
 *  list, and this class stores one folder set per session id (key
 *  "added_v3:<sessionId>" in the prefs file below, JSON array, written
 *  with commit() so a change is on disk immediately). Every public
 *  method here operates on the ACTIVE session's set — the session is
 *  resolved internally via SessionStore — except the explicit
 *  getAddedFor/clearAddedFor used by the sessions manager. Each entry
 *  keeps the tree Uri (the app holds a persistable read grant for it),
 *  the derived filesystem path when one could be computed, and the
 *  display name. */
public class FolderSelection {

    private static final String PREFS = "gallery_enhanced_selection";
    /** v2.0 per-session storage: one key per session id. */
    private static final String K_ADDED_PREFIX = "added_v3:";
    /** Pre-v2.0 global added set — read once by SessionStore's migration
     *  and copied into the first session's key; never written again. */
    private static final String K_ADDED = "added_folders_v2";
    // v1.3 (checkbox picker) keys — read once to migrate a saved selection.
    private static final String K_CHOSEN_OLD = "selection_chosen";
    private static final String K_PATHS_OLD = "selected_folder_paths";

    /** One added folder. {@code treeUri} is the identity ("" only for
     *  entries migrated from the v1.3 path-set); {@code realPath} may be
     *  null when the tree Uri could not be mapped to a filesystem path. */
    public static class AddedFolder {
        public String treeUri;
        public String realPath;
        public String name;

        public AddedFolder(String treeUri, String realPath, String name) {
            this.treeUri = treeUri;
            this.realPath = realPath;
            this.name = name;
        }
    }

    /** All folders added to the ACTIVE session, in the order they were
     *  added. Never null. */
    public static List<AddedFolder> getAdded(Context ctx) {
        SessionStore.ensureInit(ctx);
        return getAddedFor(ctx, SessionStore.getActiveId(ctx));
    }

    /** All folders added to one specific session (no session switching
     *  involved). Never null; empty when the session id is null/unknown. */
    public static List<AddedFolder> getAddedFor(Context ctx, String sessionId) {
        if (sessionId == null) return new ArrayList<>();
        SharedPreferences p = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        return parseAdded(p.getString(K_ADDED_PREFIX + sessionId, null));
    }

    /** Forget one session's whole folder set (session deletion). The
     *  persistable Uri grants are deliberately NOT released here: the
     *  same folder may belong to another session too, and a leftover
     *  grant is harmless. */
    public static void clearAddedFor(Context ctx, String sessionId) {
        if (sessionId == null) return;
        SharedPreferences p = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        p.edit().remove(K_ADDED_PREFIX + sessionId).commit();
    }

    private static ArrayList<AddedFolder> parseAdded(String json) {
        ArrayList<AddedFolder> out = new ArrayList<>();
        if (json == null) return out;
        try {
            JSONArray arr = new JSONArray(json);
            for (int i = 0; i < arr.length(); i++) {
                JSONObject o = arr.getJSONObject(i);
                String name = o.optString("n", "");
                if (name.isEmpty()) name = "Folder";
                String rp = o.isNull("p") ? null : o.optString("p", null);
                out.add(new AddedFolder(o.optString("u", ""), rp, name));
            }
        } catch (Exception ignored) {
        }
        return out;
    }

    /** True when at least one folder has been added. */
    public static boolean hasAny(Context ctx) {
        return !getAdded(ctx).isEmpty();
    }

    /** Find the stored entry a displayed folder belongs to, or null when
     *  it is not under any added root. Tree Uri equality wins first (every
     *  folder inside a tree-only root carries the root's tree Uri, and
     *  real-path folders carry their root's too). Otherwise the real path
     *  resolves by exact match, then by LONGEST root prefix — a visible
     *  subfolder (e.g. /Instagram/2) maps to the root that contains it
     *  (/Instagram, or /Instagram/2 itself when both were added). */
    public static AddedFolder findAdded(Context ctx, String realPath, String treeUri) {
        List<AddedFolder> added = getAdded(ctx);
        if (treeUri != null && !treeUri.isEmpty()) {
            for (AddedFolder a : added) {
                if (treeUri.equals(a.treeUri)) return a;
            }
        }
        if (realPath == null || MediaRepo.isTreePath(realPath)) return null;
        AddedFolder best = null;
        for (AddedFolder a : added) {
            if (a.realPath == null) continue;
            if (realPath.equals(a.realPath)) return a;
            if (realPath.startsWith(a.realPath + "/")
                    && (best == null || a.realPath.length() > best.realPath.length())) {
                best = a;
            }
        }
        return best;
    }

    /** Add a folder (from the system picker). Duplicates — same tree Uri,
     *  or the same resolved filesystem path — are ignored. */
    public static void addFolder(Context ctx, AddedFolder entry) {
        List<AddedFolder> list = getAdded(ctx);
        for (AddedFolder a : list) {
            if (entry.treeUri != null && !entry.treeUri.isEmpty()
                    && entry.treeUri.equals(a.treeUri)) return;
            if (entry.realPath != null && entry.realPath.equals(a.realPath)) return;
        }
        list.add(entry);
        save(ctx, list);
    }

    /** Remove an added folder and release its persistable Uri grant. */
    public static void removeFolder(Context ctx, AddedFolder entry) {
        List<AddedFolder> list = getAdded(ctx);
        boolean removed = false;
        for (int i = list.size() - 1; i >= 0; i--) {
            AddedFolder a = list.get(i);
            boolean uriMatch = entry.treeUri != null && !entry.treeUri.isEmpty()
                    && entry.treeUri.equals(a.treeUri);
            boolean pathMatch = entry.realPath != null && entry.realPath.equals(a.realPath);
            if (uriMatch || pathMatch) {
                list.remove(i);
                removed = true;
            }
        }
        if (!removed) return;
        save(ctx, list);
        if (entry.treeUri != null && !entry.treeUri.isEmpty()) {
            try {
                ctx.getContentResolver().releasePersistableUriPermission(
                        Uri.parse(entry.treeUri), Intent.FLAG_GRANT_READ_URI_PERMISSION);
            } catch (Exception ignored) {
                // Grant may already be gone; the removal stands either way.
            }
        }
    }

    /** Build the stored entry for a tree Uri returned by the system
     *  folder selector: derive the filesystem path and the display name. */
    public static AddedFolder buildEntry(Context ctx, Uri treeUri) {
        String realPath = MediaRepo.treeUriToPath(treeUri);
        String name = null;
        Cursor c = null;
        try {
            Uri docUri = DocumentsContract.buildDocumentUriUsingTree(
                    treeUri, DocumentsContract.getTreeDocumentId(treeUri));
            c = ctx.getContentResolver().query(docUri,
                    new String[]{DocumentsContract.Document.COLUMN_DISPLAY_NAME},
                    null, null, null);
            if (c != null && c.moveToFirst()) {
                int col = c.getColumnIndex(DocumentsContract.Document.COLUMN_DISPLAY_NAME);
                if (col >= 0) name = c.getString(col);
            }
        } catch (Exception ignored) {
        } finally {
            if (c != null) c.close();
        }
        if (name == null || name.isEmpty()) {
            if (realPath != null) {
                int s = realPath.lastIndexOf('/');
                name = s >= 0 ? realPath.substring(s + 1) : realPath;
            }
            if (name == null || name.isEmpty()) name = "Folder";
        }
        return new AddedFolder(treeUri.toString(), realPath, name);
    }

    /** Persist a folder list as the ACTIVE session's set. */
    private static void save(Context ctx, List<AddedFolder> list) {
        SessionStore.ensureInit(ctx);
        String sessionId = SessionStore.getActiveId(ctx);
        if (sessionId == null) return;
        SharedPreferences p = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        p.edit().putString(K_ADDED_PREFIX + sessionId, toJson(list)).commit();
    }

    private static String toJson(List<AddedFolder> list) {
        JSONArray arr = new JSONArray();
        try {
            for (AddedFolder a : list) {
                JSONObject o = new JSONObject();
                o.put("u", a.treeUri == null ? "" : a.treeUri);
                if (a.realPath == null) o.put("p", JSONObject.NULL);
                else o.put("p", a.realPath);
                o.put("n", a.name == null ? "Folder" : a.name);
                arr.put(o);
            }
        } catch (Exception ignored) {
        }
        return arr.toString();
    }

    // ---- raw legacy access for SessionStore's one-time migration ----
    // These touch the prefs file directly and must NOT call
    // SessionStore.ensureInit (which is what calls them).

    /** True when a pre-v2.0 global added set exists. */
    static boolean hasLegacyAddedRaw(Context ctx) {
        SharedPreferences p = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        return p.contains(K_ADDED);
    }

    /** Copy the pre-v2.0 global added set verbatim into a session's key. */
    static void writeLegacyAddedToSessionRaw(Context ctx, String sessionId) {
        SharedPreferences p = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        String json = p.getString(K_ADDED, null);
        if (json == null || sessionId == null) return;
        p.edit().putString(K_ADDED_PREFIX + sessionId, json).commit();
    }

    /** One-time migration from the v1.3 checkbox selection: its paths
     *  become added folders (no tree Uri — they keep working through the
     *  MediaStore fast path on their real path), stored under the
     *  pre-v2.0 global key, from where SessionStore's migration picks
     *  them up into "Session 1". */
    static void runLegacyMigrationsRaw(Context ctx) {
        SharedPreferences p = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        if (p.contains(K_ADDED)) return;
        if (!p.getBoolean(K_CHOSEN_OLD, false)) return;
        Set<String> old = p.getStringSet(K_PATHS_OLD, null);
        if (old == null || old.isEmpty()) return;
        ArrayList<AddedFolder> list = new ArrayList<>();
        for (String path : old) {
            int s = path.lastIndexOf('/');
            String name = s >= 0 ? path.substring(s + 1) : path;
            list.add(new AddedFolder("", path, name.isEmpty() ? "Folder" : name));
        }
        JSONArray arr = new JSONArray();
        try {
            for (AddedFolder a : list) {
                JSONObject o = new JSONObject();
                o.put("u", a.treeUri);
                o.put("p", a.realPath);
                o.put("n", a.name);
                arr.put(o);
            }
        } catch (Exception ignored) {
        }
        p.edit().putString(K_ADDED, arr.toString()).commit();
    }
}
