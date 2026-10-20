package app.enhancedgallery;

import android.app.Activity;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.TextUtils;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import android.view.ViewGroup;
import android.widget.AdapterView;
import android.widget.BaseAdapter;
import android.widget.FrameLayout;
import android.widget.GridView;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.SearchView;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.List;

/** One folder's photo grid, on the platform ActionBar (title = folder
 *  name, Up = back). A standard SearchView action filters the grid by
 *  file name; while a query is active each result also shows its file
 *  name under the thumbnail. Tapping a photo — filtered or not — opens
 *  the viewer at that photo in the folder's FULL list (the viewer
 *  resolves "startImagePath"), so next/previous walk the whole folder. */
public class GridActivity extends Activity {

    private static final int MENU_SEARCH = 1;
    private static final int MENU_SESSIONS = 2;
    private static final int REQ_ADD = 101;

    private Folder folder;
    private GridView grid;
    private ImageAdapter adapter;
    private final Handler ui = new Handler(Looper.getMainLooper());
    /** The folder's FULL image list (natural order) — search filters a
     *  copy for display only. */
    private List<ImageItem> allImages = new ArrayList<>();
    private final List<ImageItem> displayedImages = new ArrayList<>();
    private String imageQuery = "";
    /** The active session's remembered photo IN THIS FOLDER (the same
     *  object as its entry in allImages), or null. Its cell carries a
     *  "▶ LAST SEEN" chip, and the grid scrolls to it once on load. */
    private ImageItem pointItem = null;
    private boolean scrolledToPoint = false;

    /** Session switcher for the grid: switching leaves this folder —
     *  the folder screen underneath reloads for the new session. */
    private final SessionSwitcher.Callback switchCallback =
            new SessionSwitcher.Callback() {
                @Override public void onSwitched() {
                    finish();
                }

                @Override public void onNewSession() {
                    // Add the new session's first folder, then leave:
                    // the grid belongs to the old session either way.
                    PickerHelper.launch(GridActivity.this, REQ_ADD);
                }
            };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        String folderPath = getIntent().getStringExtra("folderPath");
        String folderTreeUri = getIntent().getStringExtra("folderTreeUri");
        String folderName = getIntent().getStringExtra("folderName");
        folder = new Folder(folderName != null ? folderName : "", folderPath);
        folder.treeUri = folderTreeUri;

        setTitle(folder.name);
        if (getActionBar() != null) {
            getActionBar().setDisplayHomeAsUpEnabled(true);
        }

        grid = new GridView(this);
        grid.setNumColumns(3);
        grid.setStretchMode(GridView.STRETCH_COLUMN_WIDTH);
        grid.setHorizontalSpacing(dp(4));
        grid.setVerticalSpacing(dp(4));
        grid.setPadding(dp(4), dp(4), dp(4), dp(4));
        grid.setBackgroundColor(Color.BLACK);
        setContentView(grid);

        adapter = new ImageAdapter(displayedImages);
        grid.setAdapter(adapter);

        grid.setOnItemClickListener(new AdapterView.OnItemClickListener() {
            @Override public void onItemClick(AdapterView<?> parent, View view, int position, long id) {
                ImageItem item = adapter.getItem(position);
                Intent i = new Intent(GridActivity.this, ViewerActivity.class);
                i.putExtra("folderPath", folder.path);
                i.putExtra("folderTreeUri", folder.treeUri);
                i.putExtra("folderName", folder.name);
                i.putExtra("imagePath", item.path);
                i.putExtra("imageIndex", position);
                // Open AT this photo: the viewer resolves the path in the
                // folder's full list, so navigation from here walks the
                // whole folder — position is only a legacy fallback and
                // would be wrong (filtered index) in search mode.
                i.putExtra("startImagePath", item.path);
                i.putExtra("fromGrid", true);
                startActivity(i);
            }
        });

        load();
    }

    /** ActionBar: a "☰" session switcher action, plus the standard
     *  search action that filters the grid by file name. */
    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        MenuItem sessions = menu.add(Menu.NONE, MENU_SESSIONS, Menu.NONE, "Sessions");
        sessions.setShowAsAction(MenuItem.SHOW_AS_ACTION_ALWAYS);
        sessions.setActionView(SessionSwitcher.barButton(this,
                new View.OnClickListener() {
                    @Override public void onClick(View v) {
                        SessionSwitcher.show(GridActivity.this, switchCallback);
                    }
                }));

        MenuItem search = menu.add(Menu.NONE, MENU_SEARCH, Menu.NONE, "Search in folder");
        search.setIcon(android.R.drawable.ic_menu_search);
        search.setShowAsAction(MenuItem.SHOW_AS_ACTION_IF_ROOM
                | MenuItem.SHOW_AS_ACTION_COLLAPSE_ACTION_VIEW);
        SearchView sv = new SearchView(this);
        sv.setQueryHint("Search in this folder");
        sv.setOnQueryTextListener(new SearchView.OnQueryTextListener() {
            @Override public boolean onQueryTextSubmit(String query) { return false; }
            @Override public boolean onQueryTextChange(String newText) {
                imageQuery = newText != null ? newText : "";
                applyImageFilter();
                return true;
            }
        });
        search.setActionView(sv);
        search.setOnActionExpandListener(new MenuItem.OnActionExpandListener() {
            @Override public boolean onMenuItemActionExpand(MenuItem item) { return true; }
            @Override public boolean onMenuItemActionCollapse(MenuItem item) {
                // Collapsing search restores the full grid, labels hidden.
                imageQuery = "";
                applyImageFilter();
                return true;
            }
        });
        return true;
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        if (item.getItemId() == android.R.id.home) {
            finish();
            return true;
        }
        return super.onOptionsItemSelected(item);
    }

    /** Only the switcher's "new session" picker lands here: the folder
     *  was added to the NEW active session — this grid belongs to the
     *  old one, so leave either way (picked or cancelled). */
    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode != REQ_ADD) return;
        if (resultCode == RESULT_OK && data != null && data.getData() != null) {
            PickerHelper.handleResult(this, data.getData(),
                    new PickerHelper.OnAdded() {
                        @Override public void onAdded(FolderSelection.AddedFolder entry) {
                            finish();
                        }
                    });
        } else {
            finish();
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        load();
    }

    private void load() {
        ThumbLoader.BG.execute(new Runnable() {
            @Override public void run() {
                // Real path -> the fast MediaStore query; tree-Uri-only ->
                // the folder's document tree is enumerated instead.
                final List<ImageItem> images = MediaRepo.getImages(GridActivity.this, folder);
                ui.post(new Runnable() {
                    @Override public void run() {
                        allImages = images;
                        setTitle(folder.name + "  (" + images.size() + ")");
                        resolvePoint(images);
                        applyImageFilter();
                        // Land on the remembered photo once per open.
                        if (pointItem != null && !scrolledToPoint) {
                            scrolledToPoint = true;
                            int di = displayedImages.indexOf(pointItem);
                            if (di >= 0) grid.setSelection(di);
                        }
                    }
                });
            }
        });
    }

    /** This session's remembered place, when it is in THIS folder:
     *  the exact photo by path, else the stored index clamped into
     *  range (the photo itself may have been deleted). A point for a
     *  different folder means no marker here. */
    private void resolvePoint(List<ImageItem> images) {
        pointItem = null;
        SessionStore.Point pt = SessionStore.getActivePoint(this);
        if (pt == null || !folder.path.equals(pt.folderPath) || images.isEmpty()) {
            return;
        }
        int idx = MediaRepo.imageIndexByPath(images, pt.imagePath);
        if (idx < 0) idx = Math.max(0, Math.min(pt.index, images.size() - 1));
        pointItem = images.get(idx);
    }

    /** Rebuild the displayed grid from the full list + the current
     *  query. File-name labels show exactly while a query is active. */
    private void applyImageFilter() {
        displayedImages.clear();
        for (ImageItem item : allImages) {
            if (SearchFilter.imageMatches(item.name, imageQuery)) {
                displayedImages.add(item);
            }
        }
        adapter.setShowNames(!SearchFilter.normalize(imageQuery).isEmpty());
        adapter.notifyDataSetChanged();
    }

    private int dp(int v) {
        return (int) (v * getResources().getDisplayMetrics().density);
    }

    private class ImageAdapter extends BaseAdapter {
        private final List<ImageItem> images;
        private final int cell;
        private final int labelH;
        private boolean showNames = false;

        ImageAdapter(List<ImageItem> images) {
            this.images = images;
            int w = getResources().getDisplayMetrics().widthPixels;
            this.cell = (w - dp(16)) / 3;
            this.labelH = dp(20);
        }

        void setShowNames(boolean show) { this.showNames = show; }

        @Override public int getCount() { return images.size(); }
        @Override public ImageItem getItem(int position) { return images.get(position); }
        @Override public long getItemId(int position) { return images.get(position).id; }

        @Override
        public View getView(int position, View convertView, ViewGroup parent) {
            LinearLayout box;
            ImageView iv;
            TextView label;
            TextView chip;
            if (convertView == null) {
                box = new LinearLayout(GridActivity.this);
                box.setOrientation(LinearLayout.VERTICAL);
                FrameLayout frame = new FrameLayout(GridActivity.this);
                frame.setLayoutParams(new LinearLayout.LayoutParams(cell, cell));
                iv = new ImageView(GridActivity.this);
                iv.setLayoutParams(new FrameLayout.LayoutParams(cell, cell));
                iv.setScaleType(ImageView.ScaleType.CENTER_CROP);
                iv.setBackgroundColor(Color.DKGRAY);
                frame.addView(iv);
                // "▶ LAST SEEN" chip, overlaid on the thumbnail's
                // corner — only visible on this session's remembered
                // photo (decided per bind below).
                chip = new TextView(GridActivity.this);
                chip.setText("▶ LAST SEEN");
                chip.setTextColor(Color.WHITE);
                chip.setTextSize(10);
                chip.setBackgroundColor(0xB3000000);
                chip.setPadding(dp(6), dp(2), dp(6), dp(2));
                FrameLayout.LayoutParams chipLp = new FrameLayout.LayoutParams(
                        ViewGroup.LayoutParams.WRAP_CONTENT,
                        ViewGroup.LayoutParams.WRAP_CONTENT);
                chipLp.gravity = android.view.Gravity.TOP | android.view.Gravity.START;
                chipLp.setMargins(dp(4), dp(4), 0, 0);
                frame.addView(chip, chipLp);
                box.addView(frame);
                label = new TextView(GridActivity.this);
                label.setTextColor(Color.WHITE);
                label.setTextSize(11);
                label.setSingleLine(true);
                label.setEllipsize(TextUtils.TruncateAt.END);
                label.setPadding(dp(2), dp(2), dp(2), dp(2));
                box.addView(label, new LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT, labelH));
                box.setTag(new Object[]{iv, label, chip});
            } else {
                box = (LinearLayout) convertView;
                Object[] t = (Object[]) box.getTag();
                iv = (ImageView) t[0];
                label = (TextView) t[1];
                chip = (TextView) t[2];
            }
            box.setLayoutParams(new GridView.LayoutParams(
                    cell, cell + (showNames ? labelH : 0)));
            label.setVisibility(showNames ? View.VISIBLE : View.GONE);
            ImageItem item = images.get(position);
            chip.setVisibility(item == pointItem ? View.VISIBLE : View.GONE);
            label.setText(item.name);
            iv.setImageBitmap(null);
            iv.setBackgroundColor(Color.DKGRAY);
            Uri uri = item.uri != null ? item.uri
                    : (item.id > 0 ? MediaRepo.uriFor(item.id) : null);
            ThumbLoader.loadThumb(GridActivity.this, iv, item.id, uri, item.path,
                    new ThumbLoader.OnLoaded() {
                        @Override public void onLoaded(ImageView view, long id, Bitmap b) {
                            view.setImageBitmap(b);
                        }
                    });
            return box;
        }
    }
}
