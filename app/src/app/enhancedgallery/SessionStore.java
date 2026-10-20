package app.enhancedgallery;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/** Named browse sessions (v2.0).
 *
 *  A session is an independent browsing context: its own set of added
 *  folders (stored by FolderSelection, keyed by session id) and its own
 *  remembered place — the last photo viewed while the session was
 *  active. Sessions are switched from the ☰ switcher in the folder
 *  list, the grid and the viewer; nothing about one session can disturb
 *  another's place.
 *
 *  Storage (SharedPreferences "gallery_enhanced_sessions", all writes
 *  commit()ed synchronously):
 *    "sessions"   JSONArray [{id, name}] in creation order. The key is
 *                 written (possibly as "[]") the first time the store
 *                 is initialised, which is also when migration runs.
 *    "active_id"  id of the active session.
 *    "point:<id>" the session's resume point:
 *                 {folderPath, imagePath, index, ts}.
 *
 *  Migration (once, in ensureInit): a pre-v2.0 install has one global
 *  folder set (FolderSelection's legacy key) and one global resume
 *  state (ResumeStore). Those become session "Session 1" — its folders
 *  and its place carry over untouched. A truly fresh install gets NO
 *  pre-created session; FolderActivity's first-run flow creates the
 *  first one with the user. */
public class SessionStore {

    private static final String PREFS = "gallery_enhanced_sessions";
    private static final String K_SESSIONS = "sessions";
    private static final String K_ACTIVE = "active_id";
    private static final String K_POINT_PREFIX = "point:";

    private static boolean sInitDone = false;

    /** One session's identity. Its folders live in FolderSelection
     *  (keyed by id); its resume point lives here ("point:<id>"). */
    public static class Session {
        public String id;
        public String name;

        public Session(String id, String name) {
            this.id = id;
            this.name = name;
        }
    }

    /** A session's remembered place: the last photo viewed in it. */
    public static class Point {
        public String folderPath;
        public String imagePath;
        public int index;
        public long ts;
    }

    private static SharedPreferences prefs(Context ctx) {
        return ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    /** Idempotent initialisation + one-time migration. Safe to call
     *  from anywhere, any number of times. */
    public static synchronized void ensureInit(Context ctx) {
        if (sInitDone) return;
        SharedPreferences p = prefs(ctx);
        if (!p.contains(K_SESSIONS)) {
            // First v2.0 run: fold the pre-v2.0 global state (if any)
            // into "Session 1". FolderSelection's raw helpers read and
            // write its own prefs file directly — no recursion back
            // into this store.
            FolderSelection.runLegacyMigrationsRaw(ctx);
            boolean legacyFolders = FolderSelection.hasLegacyAddedRaw(ctx);
            ResumeStore.State legacyPoint = ResumeStore.load(ctx);
            SharedPreferences.Editor e = p.edit();
            if (legacyFolders || legacyPoint != null) {
                String id = newId();
                JSONArray arr = new JSONArray();
                try {
                    JSONObject o = new JSONObject();
                    o.put("id", id);
                    o.put("name", "Session 1");
                    arr.put(o);
                } catch (Exception ignored) {
                }
                e.putString(K_SESSIONS, arr.toString());
                e.putString(K_ACTIVE, id);
                e.commit();
                if (legacyFolders) {
                    FolderSelection.writeLegacyAddedToSessionRaw(ctx, id);
                }
                if (legacyPoint != null) {
                    writePoint(p, id, legacyPoint.folderPath,
                            legacyPoint.imagePath, legacyPoint.index,
                            System.currentTimeMillis());
                }
            } else {
                // Truly fresh: mark initialised with an empty list —
                // no session is pre-created.
                e.putString(K_SESSIONS, "[]");
                e.commit();
            }
        }
        sInitDone = true;
    }

    private static String newId() {
        return UUID.randomUUID().toString();
    }

    /** All sessions in creation order. Creates/migrates on first call. */
    public static List<Session> getSessions(Context ctx) {
        ensureInit(ctx);
        ArrayList<Session> out = new ArrayList<>();
        String json = prefs(ctx).getString(K_SESSIONS, "[]");
        try {
            JSONArray arr = new JSONArray(json);
            for (int i = 0; i < arr.length(); i++) {
                JSONObject o = arr.getJSONObject(i);
                String id = o.optString("id", "");
                if (id.isEmpty()) continue;
                String name = o.optString("name", "");
                out.add(new Session(id, name.isEmpty() ? "Session" : name));
            }
        } catch (Exception ignored) {
        }
        return out;
    }

    public static boolean hasSessions(Context ctx) {
        return !getSessions(ctx).isEmpty();
    }

    /** The active session's id, or null when there are no sessions.
     *  Reads prefs directly (no init side effects) so it is safe to
     *  call in onResume before anything else has run. */
    public static String getActiveId(Context ctx) {
        String id = prefs(ctx).getString(K_ACTIVE, null);
        if (id == null) return null;
        // Defend the invariant: an active id must name a real session.
        String json = prefs(ctx).getString(K_SESSIONS, null);
        if (json == null) return null;
        try {
            JSONArray arr = new JSONArray(json);
            for (int i = 0; i < arr.length(); i++) {
                if (id.equals(arr.getJSONObject(i).optString("id", ""))) return id;
            }
        } catch (Exception ignored) {
        }
        return null;
    }

    /** The active session, or null when there are none. */
    public static Session getActive(Context ctx) {
        String id = getActiveId(ctx);
        if (id == null) return null;
        for (Session s : getSessions(ctx)) {
            if (s.id.equals(id)) return s;
        }
        return null;
    }

    public static void setActive(Context ctx, String id) {
        ensureInit(ctx);
        prefs(ctx).edit().putString(K_ACTIVE, id).commit();
    }

    /** Create a session and return its id. Activates it only when no
     *  valid active session exists (e.g. it is the first) — callers
     *  that want a switch call setActive themselves. */
    public static String createSession(Context ctx, String name) {
        ensureInit(ctx);
        List<Session> list = getSessions(ctx);
        String id = newId();
        list.add(new Session(id, cleanName(name, list.size() + 1)));
        saveSessions(ctx, list);
        if (getActiveId(ctx) == null) {
            prefs(ctx).edit().putString(K_ACTIVE, id).commit();
        }
        return id;
    }

    public static void renameSession(Context ctx, String id, String name) {
        ensureInit(ctx);
        List<Session> list = getSessions(ctx);
        for (int i = 0; i < list.size(); i++) {
            if (list.get(i).id.equals(id)) {
                list.get(i).name = cleanName(name, i + 1);
                saveSessions(ctx, list);
                return;
            }
        }
    }

    /** Delete a session: its entry, its per-session folder set and its
     *  resume point. Refused (returns false) when it is the only
     *  session. When the deleted session was active, the first
     *  remaining session becomes active. */
    public static boolean deleteSession(Context ctx, String id) {
        ensureInit(ctx);
        List<Session> list = getSessions(ctx);
        if (list.size() <= 1) return false;
        boolean found = false;
        for (int i = list.size() - 1; i >= 0; i--) {
            if (list.get(i).id.equals(id)) {
                list.remove(i);
                found = true;
            }
        }
        if (!found) return false;
        saveSessions(ctx, list);
        FolderSelection.clearAddedFor(ctx, id);
        prefs(ctx).edit().remove(K_POINT_PREFIX + id).commit();
        if (id.equals(getActiveId(ctx))) {
            prefs(ctx).edit().putString(K_ACTIVE, list.get(0).id).commit();
        }
        return true;
    }

    // ---- per-session resume points ----

    /** Remember the last-viewed photo under the ACTIVE session. Called
     *  on every photo shown in the viewer; synchronous, like the old
     *  global resume save. No-op when there is no active session. */
    public static void savePoint(Context ctx, String folderPath,
                                 String imagePath, int index) {
        ensureInit(ctx);
        String id = getActiveId(ctx);
        if (id == null) return;
        writePoint(prefs(ctx), id, folderPath, imagePath, index,
                System.currentTimeMillis());
    }

    /** The resume point of one session, or null when it has none. */
    public static Point getPoint(Context ctx, String sessionId) {
        if (sessionId == null) return null;
        String json = prefs(ctx).getString(K_POINT_PREFIX + sessionId, null);
        if (json == null) return null;
        try {
            JSONObject o = new JSONObject(json);
            Point pt = new Point();
            pt.folderPath = o.optString("folderPath", null);
            pt.imagePath = o.optString("imagePath", null);
            pt.index = o.optInt("index", 0);
            pt.ts = o.optLong("ts", 0);
            if (pt.folderPath == null || pt.imagePath == null) return null;
            return pt;
        } catch (Exception e) {
            return null;
        }
    }

    /** The active session's resume point, or null. */
    public static Point getActivePoint(Context ctx) {
        ensureInit(ctx);
        return getPoint(ctx, getActiveId(ctx));
    }

    public static void clearPointForSession(Context ctx, String sessionId) {
        if (sessionId == null) return;
        prefs(ctx).edit().remove(K_POINT_PREFIX + sessionId).commit();
    }

    // ---- internals ----

    private static void writePoint(SharedPreferences p, String sessionId,
                                   String folderPath, String imagePath,
                                   int index, long ts) {
        try {
            JSONObject o = new JSONObject();
            o.put("folderPath", folderPath);
            o.put("imagePath", imagePath);
            o.put("index", index);
            o.put("ts", ts);
            p.edit().putString(K_POINT_PREFIX + sessionId, o.toString()).commit();
        } catch (Exception ignored) {
        }
    }

    private static void saveSessions(Context ctx, List<Session> list) {
        JSONArray arr = new JSONArray();
        try {
            for (Session s : list) {
                JSONObject o = new JSONObject();
                o.put("id", s.id);
                o.put("name", s.name);
                arr.put(o);
            }
        } catch (Exception ignored) {
        }
        prefs(ctx).edit().putString(K_SESSIONS, arr.toString()).commit();
    }

    private static String cleanName(String name, int fallbackNumber) {
        if (name != null) name = name.trim();
        if (name == null || name.isEmpty()) return "Session " + fallbackNumber;
        return name;
    }
}
