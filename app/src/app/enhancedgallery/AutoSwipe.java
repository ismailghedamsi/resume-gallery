package app.enhancedgallery;

import android.content.Context;
import android.content.SharedPreferences;

/** Auto swipe (slideshow) timing: the single persisted value is the
 *  interval between automatic advances, in SharedPreferences key
 *  "autoswipe_interval_ms" (default 3000). The viewer reads it every
 *  time it schedules a tick, so a change in Settings applies the next
 *  time play is pressed. */
public class AutoSwipe {

    private static final String PREFS = "gallery_enhanced_autoswipe";
    private static final String KEY_INTERVAL_MS = "autoswipe_interval_ms";

    public static final int DEFAULT_MS = 3000;
    public static final int[] CHOICES_MS = {2000, 3000, 5000, 10000};

    public static int getIntervalMs(Context ctx) {
        SharedPreferences p = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        return p.getInt(KEY_INTERVAL_MS, DEFAULT_MS);
    }

    public static void setIntervalMs(Context ctx, int ms) {
        SharedPreferences p = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        p.edit().putInt(KEY_INTERVAL_MS, ms).apply();
    }

    /** Index of the current interval within CHOICES_MS (the default's
     *  index when the stored value is not one of the choices). */
    public static int checkedIndex(Context ctx) {
        int ms = getIntervalMs(ctx);
        for (int i = 0; i < CHOICES_MS.length; i++) {
            if (CHOICES_MS[i] == ms) return i;
        }
        for (int i = 0; i < CHOICES_MS.length; i++) {
            if (CHOICES_MS[i] == DEFAULT_MS) return i;
        }
        return 0;
    }

    /** Dialog label: "3 seconds". */
    public static String choiceLabel(int ms) {
        return (ms / 1000) + " seconds";
    }

    /** Settings subtitle: "Every 3 seconds". */
    public static String subtitle(Context ctx) {
        return "Every " + choiceLabel(getIntervalMs(ctx));
    }
}
