package app.enhancedgallery;

import android.content.Context;
import android.content.SharedPreferences;

/** Last-viewed image state, written synchronously (commit) so it survives
 *  process kill, Android memory refresh, or an accidental app close. */
public class ResumeStore {

    private static final String PREFS = "gallery_enhanced_resume";
    private static final String K_FOLDER = "resume_folder_path";
    private static final String K_IMAGE = "resume_image_path";
    private static final String K_INDEX = "resume_image_index";
    private static final String K_TIME = "resume_timestamp";

    public static void save(Context ctx, String folderPath, String imagePath, int index) {
        SharedPreferences p = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        SharedPreferences.Editor e = p.edit();
        e.putString(K_FOLDER, folderPath);
        e.putString(K_IMAGE, imagePath);
        e.putInt(K_INDEX, index);
        e.putLong(K_TIME, System.currentTimeMillis());
        e.commit(); // synchronous: must be on disk even if we die a millisecond later
    }

    public static class State {
        public String folderPath;
        public String imagePath;
        public int index;
    }

    public static State load(Context ctx) {
        SharedPreferences p = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        State s = new State();
        s.folderPath = p.getString(K_FOLDER, null);
        s.imagePath = p.getString(K_IMAGE, null);
        s.index = p.getInt(K_INDEX, 0);
        if (s.folderPath == null || s.imagePath == null) return null;
        return s;
    }
}
