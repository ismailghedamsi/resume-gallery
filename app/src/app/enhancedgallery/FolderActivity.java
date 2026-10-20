package app.enhancedgallery;

import android.Manifest;
import android.app.Activity;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import android.view.ViewGroup;
import android.widget.AdapterView;
import android.widget.BaseAdapter;
import android.widget.Button;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.SearchView;
import android.widget.TextView;
import android.widget.Toast;

import java.util.ArrayList;
import java.util.List;

/** Home screen: shows ONLY the folders added to the ACTIVE SESSION with
 *  Android's system folder selector. The app starts knowing no folders
 *  and no sessions.
 *
 *  Uses the platform ActionBar: the title is the active session's name,
 *  a "☰" action opens the session switcher, a "+" action opens the
 *  system folder selector (adding to the active session), and
 *  "Settings" sits in the overflow menu. Folders are removed the
 *  native way: long-press a row. */
public class FolderActivity extends Activity {

    private static final int REQ_READ = 42;
    private static final int REQ_ADD = 101;
    private static final int REQ_SETTINGS = 44;
    private static final int MENU_ADD = 1;
    private static final int MENU_SETTINGS = 2;
    private static final int MENU_SEARCH = 3;
    private static final int MENU_SWITCH = 4;

    private ListView listView;
    private TextView emptyMsg;
    private Button emptySelectBtn;
    private FolderAdapter adapter;
    /** The FULL visible (added) folder list — search filters a copy for
     *  display; resume checks and everything else use this list. */
    private List<Folder> shownFolders;
    /** What the adapter actually displays: shownFolders after the search
     *  filter (the same Folder objects, never rebuilt). */
    private final List<Folder> displayedFolders = new ArrayList<>();
    private String folderQuery = "";
    private final Handler ui = new Handler(Looper.getMainLooper());
    private boolean resumedOnce = false;
    /** Folders are scanned once per process, not on every resume. */
    private boolean loaded = false;
    /** The first-launch picker is offered once, not on every rotation. */
    private boolean pickerAutoLaunched = false;
    /** The session id the on-screen list was last rendered for; a
     *  mismatch in onResume means the session was switched elsewhere
     *  (grid/viewer) and the list must be rebuilt for the new session. */
    private String renderedSessionId = null;
    /** The active session's resume point, refreshed on every list load;
     *  the folder row that holds it shows "· at N". */
    private SessionStore.Point sessionPoint = null;
    /** The first-run "create your first session" dialog is offered at
     *  most once per activity instance. */
    private boolean firstRunHandled = false;

    /** Session switcher outcomes for the folder screen. */
    private final SessionSwitcher.Callback switchCallback =
            new SessionSwitcher.Callback() {
                @Override public void onSwitched() {
                    updateTitle();
                    refresh();
                }

                @Override public void onNewSession() {
                    updateTitle();
                    loaded = false;
                    // Add the new session's first folder right away;
                    // the picker result (or its cancel) lands in
                    // onActivityResult, which refreshes either way.
                    pickerAutoLaunched = true;
                    PickerHelper.launch(FolderActivity.this, REQ_ADD);
                }
            };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setTitle("Resume Gallery");
        if (savedInstanceState != null) {
            pickerAutoLaunched = savedInstanceState.getBoolean("pickerAutoLaunched", false);
            firstRunHandled = savedInstanceState.getBoolean("firstRunHandled", false);
        }

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(Color.BLACK);

        emptyMsg = new TextView(this);
        emptyMsg.setText("No folders yet");
        emptyMsg.setTextColor(Color.GRAY);
        emptyMsg.setTextSize(16);
        emptyMsg.setGravity(Gravity.CENTER);
        emptyMsg.setPadding(dp(24), dp(72), dp(24), dp(24));
        emptyMsg.setVisibility(View.GONE);
        root.addView(emptyMsg);

        emptySelectBtn = new Button(this);
        emptySelectBtn.setText("Select a folder");
        emptySelectBtn.setTextSize(16);
        emptySelectBtn.setVisibility(View.GONE);
        emptySelectBtn.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                PickerHelper.launch(FolderActivity.this, REQ_ADD);
            }
        });
        LinearLayout.LayoutParams ebp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        ebp.gravity = Gravity.CENTER_HORIZONTAL;
        ebp.bottomMargin = dp(24);
        root.addView(emptySelectBtn, ebp);

        listView = new ListView(this);
        listView.setDivider(null);
        root.addView(listView, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1));
        setContentView(root);

        adapter = new FolderAdapter(displayedFolders);
        listView.setAdapter(adapter);

        listView.setOnItemClickListener(new AdapterView.OnItemClickListener() {
            @Override public void onItemClick(AdapterView<?> parent, View view, int position, long id) {
                Folder f = adapter.getItem(position);
                Intent i = new Intent(FolderActivity.this, GridActivity.class);
                i.putExtra("folderPath", f.path);
                i.putExtra("folderTreeUri", f.treeUri);
                i.putExtra("folderName", f.name);
                startActivity(i);
            }
        });
        listView.setOnItemLongClickListener(new AdapterView.OnItemLongClickListener() {
            @Override public boolean onItemLongClick(AdapterView<?> parent, View view,
                                                    int position, long id) {
                Folder f = adapter.getItem(position);
                final FolderSelection.AddedFolder entry =
                        FolderSelection.findAdded(FolderActivity.this, f.path, f.treeUri);
                // A long-pressed row can be a SUBFOLDER of the added root
                // (nested-folder expansion): removal always removes the
                // ROOT entry, and the dialog says so.
                boolean partOf = entry != null && !isRootItself(f, entry);
                PickerHelper.confirmRemove(FolderActivity.this, f.name, entry,
                        partOf, new Runnable() {
                    @Override public void run() {
                        refresh();
                    }
                });
                return true;
            }
        });

        if (!hasReadPermission()) {
            requestReadPermission();
        } else {
            startFlow();
        }
    }

    @Override
    protected void onSaveInstanceState(Bundle outState) {
        super.onSaveInstanceState(outState);
        outState.putBoolean("pickerAutoLaunched", pickerAutoLaunched);
        outState.putBoolean("firstRunHandled", firstRunHandled);
    }

    /** Platform ActionBar: "☰" opens the session switcher, "+" opens
     *  the system folder selector, a standard SearchView action filters
     *  the folder list, and "Settings" sits in the overflow menu. */
    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        MenuItem sessions = menu.add(Menu.NONE, MENU_SWITCH, Menu.NONE, "Sessions");
        sessions.setShowAsAction(MenuItem.SHOW_AS_ACTION_ALWAYS);
        sessions.setActionView(SessionSwitcher.barButton(this,
                new View.OnClickListener() {
                    @Override public void onClick(View v) {
                        SessionSwitcher.show(FolderActivity.this, switchCallback);
                    }
                }));

        MenuItem add = menu.add(Menu.NONE, MENU_ADD, Menu.NONE, "Add folder");
        add.setIcon(android.R.drawable.ic_input_add);
        add.setShowAsAction(MenuItem.SHOW_AS_ACTION_ALWAYS);

        MenuItem search = menu.add(Menu.NONE, MENU_SEARCH, Menu.NONE, "Search folders");
        search.setIcon(android.R.drawable.ic_menu_search);
        search.setShowAsAction(MenuItem.SHOW_AS_ACTION_IF_ROOM
                | MenuItem.SHOW_AS_ACTION_COLLAPSE_ACTION_VIEW);
        SearchView sv = new SearchView(this);
        sv.setQueryHint("Search folders");
        sv.setOnQueryTextListener(new SearchView.OnQueryTextListener() {
            @Override public boolean onQueryTextSubmit(String query) { return false; }
            @Override public boolean onQueryTextChange(String newText) {
                folderQuery = newText != null ? newText : "";
                applyFolderFilter();
                return true;
            }
        });
        search.setActionView(sv);
        search.setOnActionExpandListener(new MenuItem.OnActionExpandListener() {
            @Override public boolean onMenuItemActionExpand(MenuItem item) { return true; }
            @Override public boolean onMenuItemActionCollapse(MenuItem item) {
                // Collapsing search restores the full folder list.
                folderQuery = "";
                applyFolderFilter();
                return true;
            }
        });

        menu.add(Menu.NONE, MENU_SETTINGS, Menu.NONE, "Settings");
        return true;
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        if (item.getItemId() == MENU_ADD) {
            PickerHelper.launch(this, REQ_ADD);
            return true;
        }
        if (item.getItemId() == MENU_SETTINGS) {
            startActivityForResult(
                    new Intent(this, SettingsActivity.class), REQ_SETTINGS);
            return true;
        }
        return super.onOptionsItemSelected(item);
    }

    /** True when the visible folder IS the added root itself (not one of
     *  its expanded subfolders). */
    private static boolean isRootItself(Folder f, FolderSelection.AddedFolder entry) {
        if (entry.realPath != null) {
            return f.path.equals(entry.realPath);
        }
        // Tree-only roots: the root keeps the plain marker; subfolders
        // carry an appended document id.
        return MediaRepo.isTreePath(f.path)
                && f.path.equals(MediaRepo.TREE_PREFIX + entry.treeUri);
    }

    /** The ActionBar title is the active session's name, so he always
     *  sees which session he is browsing. */
    private void updateTitle() {
        SessionStore.Session s = SessionStore.getActive(this);
        setTitle(s != null ? s.name : "Resume Gallery");
    }

    /** Entry point after storage permission is available. With no
     *  session at all (fresh install) the first session is created
     *  first; with a session, the flow is the pre-v2.0 one, scoped to
     *  that session: folders exist -> load them; none yet -> the SYSTEM
     *  folder selector opens directly, once. */
    private void startFlow() {
        if (!SessionStore.hasSessions(this)) {
            showFirstRunDialog();
            return;
        }
        proceedWithSession();
    }

    private void proceedWithSession() {
        updateTitle();
        if (FolderSelection.hasAny(this)) {
            load();
        } else if (!pickerAutoLaunched) {
            pickerAutoLaunched = true;
            PickerHelper.launch(this, REQ_ADD);
        } else {
            // Cancelled picker / rotated: the empty state stays, with
            // its own "Select a folder" button. Never relaunch in a loop.
            refresh();
        }
    }

    /** First run: name the first session. Create -> the session exists
     *  and the flow continues into the system folder picker. Cancelling
     *  still creates a default empty "Session 1", so the app is usable
     *  (empty state + "+") — but the picker is NOT thrown at him after
     *  a cancel, and the dialog never loops. */
    private void showFirstRunDialog() {
        if (firstRunHandled) {
            proceedWithSession();
            return;
        }
        firstRunHandled = true;
        SessionSwitcher.promptCreate(this, "Create your first session",
                "Session 1", true,
                new Runnable() {
                    @Override public void run() {
                        proceedWithSession();
                    }
                },
                new Runnable() {
                    @Override public void run() {
                        String id = SessionStore.createSession(
                                FolderActivity.this, "Session 1");
                        SessionStore.setActive(FolderActivity.this, id);
                        pickerAutoLaunched = true; // no picker after a cancel
                        proceedWithSession();
                    }
                });
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == REQ_ADD) {
            if (resultCode == RESULT_OK && data != null && data.getData() != null) {
                PickerHelper.handleResult(this, data.getData(),
                        new PickerHelper.OnAdded() {
                            @Override public void onAdded(
                                    FolderSelection.AddedFolder entry) {
                                refresh();
                            }
                        });
            } else {
                // Cancelled with nothing added: show the empty state. Do NOT
                // relaunch the picker automatically.
                refresh();
            }
            return;
        }
        if (requestCode == REQ_SETTINGS) {
            refresh();
        }
    }

    /** Reload the visible list; only the active session's added
     *  folders are ever shown. */
    private void refresh() {
        updateTitle();
        if (FolderSelection.hasAny(this)) {
            load();
        } else {
            loaded = false;
            setFolders(new ArrayList<Folder>());
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        // The session may have been switched while we were covered
        // (from the grid or the viewer): rebuild for the new session.
        // maybeResume is NOT re-run — that stays a cold-start-only act.
        String aid = SessionStore.getActiveId(this);
        if (aid != null && renderedSessionId != null
                && !aid.equals(renderedSessionId)) {
            updateTitle();
            loaded = false;
            refresh();
            return;
        }
        // Load once per process: no rescan on every resume (the full scan
        // used to repeat every time the user came back here).
        if (!loaded && hasReadPermission()) {
            load();
        }
    }

    private boolean hasReadPermission() {
        if (Build.VERSION.SDK_INT >= 33) {
            return checkSelfPermission(Manifest.permission.READ_MEDIA_IMAGES)
                    == PackageManager.PERMISSION_GRANTED;
        }
        return checkSelfPermission(Manifest.permission.READ_EXTERNAL_STORAGE)
                == PackageManager.PERMISSION_GRANTED;
    }

    private void requestReadPermission() {
        if (Build.VERSION.SDK_INT >= 33) {
            requestPermissions(new String[]{Manifest.permission.READ_MEDIA_IMAGES}, REQ_READ);
        } else {
            requestPermissions(new String[]{Manifest.permission.READ_EXTERNAL_STORAGE}, REQ_READ);
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        if (requestCode == REQ_READ) {
            if (grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                startFlow();
            } else {
                Toast.makeText(this, "Storage permission is required to show your images.",
                        Toast.LENGTH_LONG).show();
            }
        }
    }

    private void load() {
        // Without any added folders there is an empty-state screen (and
        // the "+" action), never the full folder list.
        if (!FolderSelection.hasAny(this)) {
            setFolders(new ArrayList<Folder>());
            return;
        }
        loaded = true;
        // Instant cold start: map the added folders onto the persisted folder
        // list right away, then run the real scan in the background.
        final List<Folder> diskCache = MediaRepo.readDiskCache(this);
        if (diskCache != null && !diskCache.isEmpty()) {
            setFolders(MediaRepo.visibleFrom(diskCache,
                    FolderSelection.getAdded(this)));
        }
        ThumbLoader.BG.execute(new Runnable() {
            @Override public void run() {
                // Added folders only; maybeResume below skips the saved
                // resume image when its folder is no longer added.
                final List<Folder> folders = MediaRepo.getVisibleFolders(FolderActivity.this);
                ui.post(new Runnable() {
                    @Override public void run() {
                        if (!sameFolders(shownFolders, folders)) {
                            setFolders(folders);
                        }
                        maybeResume(folders);
                    }
                });
            }
        });
    }

    private void setFolders(List<Folder> folders) {
        shownFolders = folders;
        renderedSessionId = SessionStore.getActiveId(this);
        sessionPoint = SessionStore.getActivePoint(this);
        applyFolderFilter();
    }

    /** Rebuild the displayed list from the full list + the current search
     *  query. The empty state ("No folders yet") depends on the FULL
     *  list, not on how many rows the filter leaves. */
    private void applyFolderFilter() {
        displayedFolders.clear();
        if (shownFolders != null) {
            String root = Environment.getExternalStorageDirectory().getAbsolutePath();
            for (Folder f : shownFolders) {
                if (SearchFilter.folderMatches(f.name, folderMatchPath(f, root),
                        folderQuery)) {
                    displayedFolders.add(f);
                }
            }
        }
        if (adapter != null) adapter.notifyDataSetChanged();
        boolean none = shownFolders == null || shownFolders.isEmpty();
        emptyMsg.setVisibility(none ? View.VISIBLE : View.GONE);
        emptySelectBtn.setVisibility(none ? View.VISIBLE : View.GONE);
        listView.setVisibility(none ? View.GONE : View.VISIBLE);
    }

    /** The path a folder is searched by: its real path with the storage
     *  root trimmed ("/storage/emulated/0/Pictures/Instagram/2" ->
     *  "Pictures/Instagram/2"), or, for tree-only folders, the picked
     *  directory's document id ("primary:Pictures/Instagram/2"). */
    private static String folderMatchPath(Folder f, String storageRoot) {
        if (MediaRepo.isTreePath(f.path)) {
            return SearchFilter.treePathForMatch(f.path, MediaRepo.TREE_PREFIX);
        }
        return SearchFilter.trimStorageRoot(f.path, storageRoot);
    }

    private static boolean sameFolders(List<Folder> a, List<Folder> b) {
        if (a == null || b == null) return a == b;
        if (a.size() != b.size()) return false;
        for (int i = 0; i < a.size(); i++) {
            Folder x = a.get(i), y = b.get(i);
            if (!x.path.equals(y.path) || !x.name.equals(y.name)
                    || x.count != y.count || x.thumbId != y.thumbId) return false;
        }
        return true;
    }

    /** Cold start: jump straight back to the ACTIVE SESSION's
     *  last-viewed image — each session resumes its own place, and only
     *  its own. Uses the folder list from the scan callback — never
     *  queries on the UI thread. A point whose image is gone, or whose
     *  folder is not in this session, is ignored. */
    private void maybeResume(List<Folder> folders) {
        if (resumedOnce) return;
        resumedOnce = true;
        SessionStore.Point pt = SessionStore.getActivePoint(this);
        if (pt == null) return;
        if (!MediaRepo.contentExists(this, pt.imagePath)) return;
        int fi = MediaRepo.folderIndex(folders, pt.folderPath);
        if (fi < 0) return;
        Folder f = folders.get(fi);
        Intent i = new Intent(this, ViewerActivity.class);
        i.putExtra("folderPath", f.path);
        i.putExtra("folderTreeUri", f.treeUri);
        i.putExtra("folderName", f.name);
        i.putExtra("imagePath", pt.imagePath);
        i.putExtra("imageIndex", pt.index);
        i.putExtra("fromGrid", false);
        startActivity(i);
    }

    private int dp(int v) {
        return (int) (v * getResources().getDisplayMetrics().density);
    }

    private class FolderAdapter extends BaseAdapter {
        private final List<Folder> folders;

        FolderAdapter(List<Folder> folders) { this.folders = folders; }

        @Override public int getCount() { return folders.size(); }
        @Override public Folder getItem(int position) { return folders.get(position); }
        @Override public long getItemId(int position) { return position; }

        @Override
        public View getView(int position, View convertView, ViewGroup parent) {
            LinearLayout row;
            ImageView thumb;
            TextView name, count;
            if (convertView == null) {
                row = new LinearLayout(FolderActivity.this);
                row.setOrientation(LinearLayout.HORIZONTAL);
                row.setGravity(Gravity.CENTER_VERTICAL);
                row.setPadding(dp(12), dp(8), dp(12), dp(8));
                thumb = new ImageView(FolderActivity.this);
                int ts = dp(72);
                LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ts, ts);
                lp.rightMargin = dp(12);
                thumb.setLayoutParams(lp);
                thumb.setScaleType(ImageView.ScaleType.CENTER_CROP);
                thumb.setBackgroundColor(Color.DKGRAY);
                row.addView(thumb);
                LinearLayout texts = new LinearLayout(FolderActivity.this);
                texts.setOrientation(LinearLayout.VERTICAL);
                name = new TextView(FolderActivity.this);
                name.setTextColor(Color.WHITE);
                name.setTextSize(16);
                count = new TextView(FolderActivity.this);
                count.setTextColor(Color.GRAY);
                count.setTextSize(13);
                texts.addView(name);
                texts.addView(count);
                row.addView(texts);
                row.setTag(new Object[]{thumb, name, count});
            } else {
                row = (LinearLayout) convertView;
                Object[] t = (Object[]) row.getTag();
                thumb = (ImageView) t[0];
                name = (TextView) t[1];
                count = (TextView) t[2];
            }
            Folder f = folders.get(position);
            name.setText(f.name);
            String countText = f.count > 0
                    ? (f.count + (f.count == 1 ? " image" : " images")) : "";
            // The folder holding this session's remembered place says
            // where in it he left off: "5,000 images · at 500".
            if (sessionPoint != null && f.path.equals(sessionPoint.folderPath)) {
                String at = "at " + (sessionPoint.index + 1);
                countText = countText.isEmpty() ? at : countText + " · " + at;
            }
            count.setText(countText);
            thumb.setImageBitmap(null);
            thumb.setBackgroundColor(Color.DKGRAY);
            ThumbLoader.loadThumb(FolderActivity.this, thumb, f.thumbId, f.thumbPath,
                    new ThumbLoader.OnLoaded() {
                        @Override public void onLoaded(ImageView view, long id, android.graphics.Bitmap b) {
                            view.setImageBitmap(b);
                        }
                    });
            return row;
        }
    }
}
