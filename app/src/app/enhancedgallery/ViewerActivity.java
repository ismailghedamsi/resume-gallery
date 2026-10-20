package app.enhancedgallery;

import android.app.Activity;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.ImageButton;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import java.util.List;

/** Fullscreen viewer: swipe/tap navigation, double-tap zoom, cross-folder
 *  advance, and the ACTIVE SESSION's resume point saved on every change.
 *  Cross-folder advance walks only the active session's added folders.
 *  The always-visible top bar carries a "☰" session switcher: switching
 *  mid-view jumps to the new session's remembered place (or exits to
 *  its folder list when it has none yet). */
public class ViewerActivity extends Activity implements ZoomableImageView.Listener {

    private static final int REQ_PICKER = 102;

    private Folder currentFolder;
    private boolean fromGrid;
    private List<Folder> folders;
    private List<ImageItem> images;
    private int index = 0;

    private ZoomableImageView photo;
    private LinearLayout infoBar;
    private TextView infoText;
    private ImageButton playPauseBtn;
    private final Handler ui = new Handler(Looper.getMainLooper());

    private int loadToken = 0;

    /** Session switcher outcomes for the viewer. */
    private final SessionSwitcher.Callback switchCallback =
            new SessionSwitcher.Callback() {
                @Override public void onSwitched() {
                    enterActiveSessionPoint();
                }

                @Override public void onNewSession() {
                    // Add the new session's first folder; entering its
                    // place (or leaving) happens when the picker returns.
                    PickerHelper.launch(ViewerActivity.this, REQ_PICKER);
                }
            };

    // ---- auto swipe (slideshow) ----
    private boolean autoSwipeOn = false;
    /** Position an auto advance was launched FROM, used by the next tick
     *  to confirm the advance actually moved (an empty next folder makes
     *  move() stay put; auto swipe must stop there, not spin). */
    private String autoFromPath = null;
    private int autoFromIndex = -1;
    private final Runnable autoTick = new Runnable() {
        @Override public void run() {
            if (!autoSwipeOn) return;
            if (images == null || images.isEmpty()) { scheduleAutoTick(); return; }
            // Zoomed in: navigation is locked, so auto swipe waits —
            // skip this advance and check again after the interval.
            if (photo.getZoom() > 1.001f) { scheduleAutoTick(); return; }
            // Previous auto advance did not move us (e.g. the next
            // folder turned out empty): treat as the end of the line.
            if (autoFromIndex >= 0 && autoFromPath != null
                    && autoFromPath.equals(currentFolder.path)
                    && autoFromIndex == index) {
                endAutoSwipe();
                return;
            }
            if (!canAdvance()) { endAutoSwipe(); return; }
            autoFromPath = currentFolder.path;
            autoFromIndex = index;
            move(1);
            scheduleAutoTick();
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        String folderPath = getIntent().getStringExtra("folderPath");
        String folderTreeUri = getIntent().getStringExtra("folderTreeUri");
        String folderName = getIntent().getStringExtra("folderName");
        currentFolder = new Folder(folderName != null ? folderName : "", folderPath);
        currentFolder.treeUri = folderTreeUri;
        fromGrid = getIntent().getBooleanExtra("fromGrid", true);
        // "startImagePath" (grid taps, incl. in-folder search results):
        // open AT that photo in the folder's FULL list, resolved by
        // path — the sender's index may be a filtered position and is
        // meaningless here. Falls back to the legacy imagePath/imageIndex
        // extras (resume flow) when absent.
        final String pathExtra = getIntent().getStringExtra("startImagePath");
        final boolean byPathOnly = pathExtra != null;
        final String startImagePath = byPathOnly
                ? pathExtra : getIntent().getStringExtra("imagePath");
        final int startIndex = getIntent().getIntExtra("imageIndex", 0);

        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(Color.BLACK);

        photo = new ZoomableImageView(this);
        photo.setListener(this);
        root.addView(photo, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        // The top info bar is ALWAYS visible (user rule, v1.9): created
        // visible, pinned to the top, and never hidden by any code path.
        infoBar = new LinearLayout(this);
        infoBar.setOrientation(LinearLayout.VERTICAL);
        infoBar.setBackgroundColor(0x99000000);
        LinearLayout infoRow = new LinearLayout(this);
        infoRow.setOrientation(LinearLayout.HORIZONTAL);
        infoRow.setGravity(Gravity.CENTER_VERTICAL);
        // "☰" session switcher at the bar's left — present in the
        // viewer like in the folder list and the grid.
        Button sessionsBtn = SessionSwitcher.barButton(this,
                new View.OnClickListener() {
                    @Override public void onClick(View v) {
                        SessionSwitcher.show(ViewerActivity.this, switchCallback);
                    }
                });
        infoRow.addView(sessionsBtn);
        infoText = new TextView(this);
        infoText.setTextColor(Color.WHITE);
        infoText.setTextSize(14);
        infoText.setPadding(dp(16), dp(16), dp(8), dp(12));
        infoRow.addView(infoText, new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        playPauseBtn = new ImageButton(this);
        playPauseBtn.setImageResource(android.R.drawable.ic_media_play);
        playPauseBtn.setBackgroundColor(Color.TRANSPARENT);
        playPauseBtn.setColorFilter(Color.WHITE);
        playPauseBtn.setContentDescription("Auto swipe");
        playPauseBtn.setPadding(dp(12), dp(12), dp(16), dp(12));
        playPauseBtn.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { setAutoSwipe(!autoSwipeOn); }
        });
        infoRow.addView(playPauseBtn);
        infoBar.addView(infoRow);
        FrameLayout.LayoutParams ip = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        ip.gravity = Gravity.TOP;
        root.addView(infoBar, ip);

        ImageButton foldersBtn = new ImageButton(this);
        foldersBtn.setImageResource(android.R.drawable.ic_menu_sort_by_size);
        foldersBtn.setBackgroundColor(0x99000000);
        foldersBtn.setColorFilter(Color.WHITE);
        foldersBtn.setPadding(dp(12), dp(12), dp(12), dp(12));
        foldersBtn.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { goToFolders(); }
        });
        FrameLayout.LayoutParams bp = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        bp.gravity = Gravity.BOTTOM | Gravity.END;
        bp.setMargins(0, 0, dp(16), dp(16));
        root.addView(foldersBtn, bp);

        setContentView(root);

        loadFolder(startImagePath, startIndex, byPathOnly);
    }

    private void loadFolder(final String startImagePath, final int startIndex,
                            final boolean byPathOnly) {
        final int token = ++loadToken;
        final Folder startFolder = currentFolder;
        ThumbLoader.BG.execute(new Runnable() {
            @Override public void run() {
                // The same added-folders list the folder screen shows, so
                // cross-folder advance walks only added folders.
                folders = MediaRepo.getVisibleFolders(ViewerActivity.this);
                images = MediaRepo.getImages(ViewerActivity.this, startFolder);
                int i = 0;
                if (startImagePath != null) i = MediaRepo.imageIndexByPath(images, startImagePath);
                if (i < 0) i = byPathOnly ? 0
                        : Math.max(0, Math.min(startIndex, images.size() - 1));
                final int fi = i;
                ui.post(new Runnable() {
                    @Override public void run() {
                        if (token != loadToken || images.isEmpty()) return;
                        showImage(fi, false);
                    }
                });
            }
        });
    }

    /** Only the switcher's "new session" picker lands here: the folder
     *  was added to the NEW active session; entering that session (its
     *  place, or out to its folder list) happens either way. */
    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode != REQ_PICKER) return;
        if (resultCode == RESULT_OK && data != null && data.getData() != null) {
            PickerHelper.handleResult(this, data.getData(),
                    new PickerHelper.OnAdded() {
                        @Override public void onAdded(FolderSelection.AddedFolder entry) {
                            enterActiveSessionPoint();
                        }
                    });
        } else {
            enterActiveSessionPoint();
        }
    }

    /** After a session switch from the viewer: the photo on screen has
     *  been saving its point under the OLD session all along (every
     *  showImage saves), so nothing is lost. Now enter the NEW active
     *  session: if it has a valid remembered place, re-initialise onto
     *  it; if not, leave — the folder screen shows the new session's
     *  list. Auto swipe never survives a switch. */
    private void enterActiveSessionPoint() {
        if (autoSwipeOn) setAutoSwipe(false);
        final int token = ++loadToken;
        ThumbLoader.BG.execute(new Runnable() {
            @Override public void run() {
                SessionStore.Point pt =
                        SessionStore.getActivePoint(ViewerActivity.this);
                final List<Folder> vis =
                        MediaRepo.getVisibleFolders(ViewerActivity.this);
                Folder target = null;
                List<ImageItem> imgs = null;
                int idx = 0;
                if (pt != null && MediaRepo.contentExists(
                        ViewerActivity.this, pt.imagePath)) {
                    int fi = MediaRepo.folderIndex(vis, pt.folderPath);
                    if (fi >= 0) {
                        Folder cand = vis.get(fi);
                        List<ImageItem> list = MediaRepo.getImages(
                                ViewerActivity.this, cand);
                        if (!list.isEmpty()) {
                            int i = MediaRepo.imageIndexByPath(list, pt.imagePath);
                            idx = i >= 0 ? i : Math.max(0,
                                    Math.min(pt.index, list.size() - 1));
                            target = cand;
                            imgs = list;
                        }
                    }
                }
                final Folder fTarget = target;
                final List<ImageItem> fImgs = imgs;
                final int fIdx = idx;
                ui.post(new Runnable() {
                    @Override public void run() {
                        if (token != loadToken) return;
                        if (fTarget == null) {
                            finish();
                            return;
                        }
                        folders = vis;
                        currentFolder = fTarget;
                        images = fImgs;
                        showImage(fIdx, false);
                    }
                });
            }
        });
    }

    /** Switch to a different folder at the given index (cross-folder advance). */
    private void switchFolder(final Folder f, final int newIndex, String toast) {
        currentFolder = f;
        final int token = ++loadToken;
        ThumbLoader.BG.execute(new Runnable() {
            @Override public void run() {
                images = MediaRepo.getImages(ViewerActivity.this, f);
                ui.post(new Runnable() {
                    @Override public void run() {
                        if (token != loadToken) return;
                        if (images.isEmpty()) {
                            Toast.makeText(ViewerActivity.this, "Folder is empty",
                                    Toast.LENGTH_SHORT).show();
                            return;
                        }
                        showImage(Math.max(0, Math.min(newIndex, images.size() - 1)), false);
                    }
                });
            }
        });
        Toast.makeText(this, toast, Toast.LENGTH_SHORT).show();
    }

    private String folderName() {
        return currentFolder.name != null && !currentFolder.name.isEmpty()
                ? currentFolder.name : currentFolder.path;
    }

    private void showImage(int newIndex, boolean fromUser) {
        if (images == null || images.isEmpty()) return;
        index = newIndex;
        final ImageItem item = images.get(index);
        infoText.setText(folderName() + "   " + (index + 1) + " / " + images.size()
                + "\n" + item.name);
        // the active session's persistent resume point, synchronous,
        // on every change
        SessionStore.savePoint(this, currentFolder.path, item.path, index);
        photo.setImageBitmap(null);
        final int token = loadToken;
        // instant placeholder: the small grid thumbnail, shown until the
        // downsampled decode finishes
        Bitmap thumb = ThumbLoader.getThumb(item.id);
        if (thumb != null) photo.setImageBitmap(thumb);
        Bitmap cached = ThumbLoader.getViewer(item.id);
        if (cached != null) {
            photo.setImageBitmap(cached);
        } else {
            // viewer has its own single thread: the current image decodes
            // first, neighbours only queue behind it
            ThumbLoader.VIEWER.execute(new Runnable() {
                @Override public void run() {
                    final Bitmap b = ThumbLoader.decodePath(
                            ViewerActivity.this.getApplicationContext(),
                            item.id, item.uri, item.path, 2048);
                    if (b != null) ThumbLoader.putViewer(item.id, b);
                    ui.post(new Runnable() {
                        @Override public void run() {
                            if (token != loadToken) return;
                            if (b != null && images != null && index < images.size()
                                    && images.get(index).id == item.id) {
                                photo.setImageBitmap(b);
                            }
                        }
                    });
                    prefetchQueued(index - 1, token);
                    prefetchQueued(index + 1, token);
                }
            });
        }
    }

    /** Neighbour prefetch at a smaller size, queued BEHIND the current image's
     *  decode on the viewer's single thread — it can never starve it. */
    private void prefetchQueued(final int i, final int token) {
        if (images == null || i < 0 || i >= images.size()) return;
        final ImageItem item = images.get(i);
        if (ThumbLoader.getViewer(item.id) != null) return;
        ThumbLoader.VIEWER.execute(new Runnable() {
            @Override public void run() {
                if (token != loadToken) return;
                Bitmap b = ThumbLoader.decodePath(
                        ViewerActivity.this.getApplicationContext(),
                        item.id, item.uri, item.path, 1280);
                if (b != null && token == loadToken) ThumbLoader.putViewer(item.id, b);
            }
        });
    }

    // ---- navigation ----

    @Override public void onNext() { move(1); resetAutoTimer(); }
    @Override public void onPrev() { move(-1); resetAutoTimer(); }
    /** Centre tap: no action since v1.9 — the top bar is always visible
     *  and is never toggled. The callback stays (ZoomableImageView calls
     *  it for centre taps) so the other tap zones and double-tap zoom
     *  are untouched. */
    @Override public void onToggleInfo() {
        // intentionally empty
    }

    // ---- auto swipe engine ----

    /** Turn auto swipe on or off. ON: keep the screen awake and start
     *  the tick timer; OFF: cancel ticks, release the screen flag, and
     *  restore the play affordances. */
    private void setAutoSwipe(boolean on) {
        autoSwipeOn = on;
        autoFromPath = null;
        autoFromIndex = -1;
        ui.removeCallbacks(autoTick);
        if (on) {
            getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
            scheduleAutoTick();
        } else {
            getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        }
        playPauseBtn.setImageResource(on
                ? android.R.drawable.ic_media_pause : android.R.drawable.ic_media_play);
    }

    /** Schedule the next auto advance. The interval is read fresh from
     *  preferences every time, so a Settings change applies on the
     *  next play. */
    private void scheduleAutoTick() {
        ui.removeCallbacks(autoTick);
        ui.postDelayed(autoTick, AutoSwipe.getIntervalMs(this));
    }

    /** A manual swipe/tap while auto swipe is on restarts the wait, so
     *  the next automatic advance comes a full interval after the
     *  user's own jump — and clears the advance-tracking markers. */
    private void resetAutoTimer() {
        if (!autoSwipeOn) return;
        autoFromPath = null;
        autoFromIndex = -1;
        scheduleAutoTick();
    }

    /** True when move(1) would actually go somewhere: another photo in
     *  this folder, or a next visible folder. Mirrors move()'s own
     *  boundary conditions. */
    private boolean canAdvance() {
        if (images == null || images.isEmpty() || folders == null) return false;
        if (index < images.size() - 1) return true;
        int fi = MediaRepo.folderIndex(folders, currentFolder.path);
        return fi >= 0 && fi < folders.size() - 1;
    }

    /** The line has ended: stop the show and say so. */
    private void endAutoSwipe() {
        setAutoSwipe(false);
        Toast.makeText(this, "End of the last folder", Toast.LENGTH_SHORT).show();
    }

    private void move(int dir) {
        if (images == null || images.isEmpty()) return;
        if (folders == null) return;
        if (dir > 0) {
            if (index < images.size() - 1) {
                showImage(index + 1, true);
            } else {
                int fi = MediaRepo.folderIndex(folders, currentFolder.path);
                if (fi >= 0 && fi < folders.size() - 1) {
                    Folder next = folders.get(fi + 1);
                    switchFolder(next, 0, next.name);
                } else {
                    Toast.makeText(this, "Last folder", Toast.LENGTH_SHORT).show();
                }
            }
        } else {
            if (index > 0) {
                showImage(index - 1, true);
            } else {
                int fi = MediaRepo.folderIndex(folders, currentFolder.path);
                if (fi > 0) {
                    final Folder prev = folders.get(fi - 1);
                    final int token = ++loadToken;
                    ThumbLoader.BG.execute(new Runnable() {
                        @Override public void run() {
                            final List<ImageItem> prevImages =
                                    MediaRepo.getImages(ViewerActivity.this, prev);
                            ui.post(new Runnable() {
                                @Override public void run() {
                                    if (token != loadToken || prevImages.isEmpty()) return;
                                    images = prevImages;
                                    currentFolder = prev;
                                    showImage(images.size() - 1, false);
                                }
                            });
                        }
                    });
                    Toast.makeText(this, prev.name, Toast.LENGTH_SHORT).show();
                } else {
                    Toast.makeText(this, "First folder", Toast.LENGTH_SHORT).show();
                }
            }
        }
    }

    private void goToFolders() {
        Intent i = new Intent(this, FolderActivity.class);
        i.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP);
        startActivity(i);
        finish();
    }

    @Override
    protected void onPause() {
        super.onPause();
        // Auto swipe never outlives the visible viewer.
        if (autoSwipeOn) setAutoSwipe(false);
        // extra safety save: disk state survives process kill / accidental close
        if (images != null && !images.isEmpty() && index < images.size()) {
            ImageItem item = images.get(index);
            SessionStore.savePoint(this, currentFolder.path, item.path, index);
        }
    }

    @Override
    public void onBackPressed() {
        if (fromGrid) {
            super.onBackPressed();
        } else {
            // resumed cold-start: back goes to that folder's grid
            Intent i = new Intent(this, GridActivity.class);
            i.putExtra("folderPath", currentFolder.path);
            i.putExtra("folderTreeUri", currentFolder.treeUri);
            i.putExtra("folderName", currentFolder.name);
            startActivity(i);
            finish();
        }
    }

    private int dp(int v) {
        return (int) (v * getResources().getDisplayMetrics().density);
    }
}
