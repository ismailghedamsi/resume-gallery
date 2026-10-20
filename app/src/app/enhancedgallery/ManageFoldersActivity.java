package app.enhancedgallery;

import android.app.Activity;
import android.content.Intent;
import android.graphics.Color;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import android.view.ViewGroup;
import android.widget.AdapterView;
import android.widget.BaseAdapter;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/** "Folders to show" (from Settings): lists ONLY the ACTIVE SESSION's
 *  added folders — FolderSelection is session-scoped, so this screen
 *  manages whichever session is active (its title says which).
 *
 *  Folders are added with the "+" action, which opens Android's system
 *  folder selector (ACTION_OPEN_DOCUMENT_TREE) — see PickerHelper.
 *  Folders are removed the native way: long-press a row.
 *  The folder screen uses the same add and remove paths. */
public class ManageFoldersActivity extends Activity {

    private static final int REQ_ADD = 101;
    private static final int MENU_ADD = 1;

    private ListView listView;
    private TextView emptyMsg;
    private ManageAdapter adapter;
    private final Handler ui = new Handler(Looper.getMainLooper());

    /** One row: the stored entry plus whatever MediaStore knows about it. */
    private static class Row {
        FolderSelection.AddedFolder entry;
        Folder match; // from the MediaStore lookup; null when not indexed
    }

    private final List<Row> rows = new ArrayList<>();

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        SessionStore.Session active = SessionStore.getActive(this);
        setTitle(active != null
                ? "Folders to show — " + active.name : "Folders to show");
        if (getActionBar() != null) {
            getActionBar().setDisplayHomeAsUpEnabled(true);
        }

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(Color.BLACK);

        emptyMsg = new TextView(this);
        emptyMsg.setText("No folders added yet.\n\nUse + to pick a folder with the "
                + "Android folder selector.");
        emptyMsg.setTextColor(Color.GRAY);
        emptyMsg.setTextSize(15);
        emptyMsg.setGravity(Gravity.CENTER);
        emptyMsg.setPadding(dp(24), dp(72), dp(24), dp(24));
        root.addView(emptyMsg, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1));

        listView = new ListView(this);
        listView.setDivider(null);
        root.addView(listView, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1));

        setContentView(root);
        adapter = new ManageAdapter();
        listView.setAdapter(adapter);
        listView.setOnItemLongClickListener(new AdapterView.OnItemLongClickListener() {
            @Override public boolean onItemLongClick(AdapterView<?> parent, View view,
                                                    int position, long id) {
                final Row r = adapter.getItem(position);
                PickerHelper.confirmRemove(ManageFoldersActivity.this, r.entry,
                        new Runnable() {
                            @Override public void run() {
                                loadRows();
                            }
                        });
                return true;
            }
        });
        loadRows();
    }

    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        MenuItem add = menu.add(Menu.NONE, MENU_ADD, Menu.NONE, "Add folder");
        add.setIcon(android.R.drawable.ic_input_add);
        add.setShowAsAction(MenuItem.SHOW_AS_ACTION_ALWAYS);
        return true;
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        if (item.getItemId() == MENU_ADD) {
            PickerHelper.launch(this, REQ_ADD);
            return true;
        }
        if (item.getItemId() == android.R.id.home) {
            finish();
            return true;
        }
        return super.onOptionsItemSelected(item);
    }

    @Override
    protected void onResume() {
        super.onResume();
        loadRows(); // reflect changes made from Settings or elsewhere
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode != REQ_ADD || resultCode != RESULT_OK || data == null
                || data.getData() == null) {
            return;
        }
        PickerHelper.handleResult(this, data.getData(),
                new PickerHelper.OnAdded() {
                    @Override public void onAdded(FolderSelection.AddedFolder entry) {
                        loadRows();
                    }
                });
    }

    /** Build rows off the UI thread: the MediaStore lookup table gives us
     *  counts and thumbnails for added folders that MediaStore has indexed. */
    private void loadRows() {
        ThumbLoader.BG.execute(new Runnable() {
            @Override public void run() {
                List<FolderSelection.AddedFolder> added =
                        FolderSelection.getAdded(ManageFoldersActivity.this);
                Map<String, Folder> byPath =
                        MediaRepo.folderMap(ManageFoldersActivity.this);
                final ArrayList<Row> next = new ArrayList<>(added.size());
                for (FolderSelection.AddedFolder a : added) {
                    Row r = new Row();
                    r.entry = a;
                    r.match = a.realPath != null ? byPath.get(a.realPath) : null;
                    next.add(r);
                }
                ui.post(new Runnable() {
                    @Override public void run() {
                        rows.clear();
                        rows.addAll(next);
                        adapter.notifyDataSetChanged();
                        boolean empty = rows.isEmpty();
                        emptyMsg.setVisibility(empty ? View.VISIBLE : View.GONE);
                        listView.setVisibility(empty ? View.GONE : View.VISIBLE);
                    }
                });
            }
        });
    }

    private int dp(int v) {
        return (int) (v * getResources().getDisplayMetrics().density);
    }

    private class ManageAdapter extends BaseAdapter {
        @Override public int getCount() { return rows.size(); }
        @Override public Row getItem(int position) { return rows.get(position); }
        @Override public long getItemId(int position) { return position; }

        @Override
        public View getView(int position, View convertView, ViewGroup parent) {
            LinearLayout row;
            ImageView thumb;
            TextView name, info, path;
            if (convertView == null) {
                row = new LinearLayout(ManageFoldersActivity.this);
                row.setOrientation(LinearLayout.HORIZONTAL);
                row.setGravity(Gravity.CENTER_VERTICAL);
                row.setPadding(dp(12), dp(8), dp(12), dp(8));
                thumb = new ImageView(ManageFoldersActivity.this);
                int ts = dp(56);
                LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ts, ts);
                lp.rightMargin = dp(12);
                thumb.setLayoutParams(lp);
                thumb.setScaleType(ImageView.ScaleType.CENTER_CROP);
                thumb.setBackgroundColor(Color.DKGRAY);
                row.addView(thumb);
                LinearLayout texts = new LinearLayout(ManageFoldersActivity.this);
                texts.setOrientation(LinearLayout.VERTICAL);
                name = new TextView(ManageFoldersActivity.this);
                name.setTextColor(Color.WHITE);
                name.setTextSize(16);
                info = new TextView(ManageFoldersActivity.this);
                info.setTextColor(Color.GRAY);
                info.setTextSize(13);
                path = new TextView(ManageFoldersActivity.this);
                path.setTextColor(0xFF888888);
                path.setTextSize(11);
                texts.addView(name);
                texts.addView(info);
                texts.addView(path);
                row.addView(texts, new LinearLayout.LayoutParams(
                        0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
                row.setTag(new Object[]{thumb, name, info, path});
            } else {
                row = (LinearLayout) convertView;
                Object[] t = (Object[]) row.getTag();
                thumb = (ImageView) t[0];
                name = (TextView) t[1];
                info = (TextView) t[2];
                path = (TextView) t[3];
            }
            final Row r = rows.get(position);
            name.setText(r.entry.name);
            info.setText(r.match != null && r.match.count > 0
                    ? (r.match.count + (r.match.count == 1 ? " image" : " images"))
                    : "");
            path.setText(r.entry.realPath != null ? r.entry.realPath : r.entry.treeUri);
            thumb.setImageBitmap(null);
            thumb.setBackgroundColor(Color.DKGRAY);
            if (r.match != null && r.match.thumbId != 0) {
                ThumbLoader.loadThumb(ManageFoldersActivity.this, thumb,
                        r.match.thumbId, r.match.thumbPath,
                        new ThumbLoader.OnLoaded() {
                            @Override public void onLoaded(ImageView view, long id,
                                                           android.graphics.Bitmap b) {
                                view.setImageBitmap(b);
                            }
                        });
            }
            return row;
        }
    }
}
