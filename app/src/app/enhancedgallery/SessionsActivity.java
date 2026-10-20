package app.enhancedgallery;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.DialogInterface;
import android.content.Intent;
import android.graphics.Color;
import android.os.Bundle;
import android.view.Gravity;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import android.view.ViewGroup;
import android.widget.AdapterView;
import android.widget.BaseAdapter;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.List;

/** Sessions manager (from Settings → Sessions), on the platform
 *  ActionBar. Lists every session with its folder count; the active one
 *  is tagged. Tap = switch to it and go back. Long-press = Rename /
 *  Manage folders / Delete session. "+" creates a session (it does NOT
 *  switch to it, unless it is the first one). Standard patterns only. */
public class SessionsActivity extends Activity {

    private static final int MENU_ADD = 1;

    private ListView listView;
    private SessionAdapter adapter;

    /** One row: the session plus its added-root count. */
    private static class Row {
        SessionStore.Session session;
        int folderCount;
        boolean active;
    }

    private final List<Row> rows = new ArrayList<>();

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setTitle("Sessions");
        if (getActionBar() != null) {
            getActionBar().setDisplayHomeAsUpEnabled(true);
        }

        listView = new ListView(this);
        listView.setDivider(null);
        listView.setBackgroundColor(Color.BLACK);
        setContentView(listView);
        adapter = new SessionAdapter();
        listView.setAdapter(adapter);

        listView.setOnItemClickListener(new AdapterView.OnItemClickListener() {
            @Override public void onItemClick(AdapterView<?> parent, View view,
                                              int position, long id) {
                Row r = adapter.getItem(position);
                if (!r.active) {
                    SessionStore.setActive(SessionsActivity.this, r.session.id);
                }
                finish(); // the folder screen reloads for the new session
            }
        });
        listView.setOnItemLongClickListener(new AdapterView.OnItemLongClickListener() {
            @Override public boolean onItemLongClick(AdapterView<?> parent, View view,
                                                     int position, long id) {
                showRowActions(adapter.getItem(position));
                return true;
            }
        });
        load();
    }

    @Override
    protected void onResume() {
        super.onResume();
        load(); // folder counts may have changed in the manager
    }

    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        MenuItem add = menu.add(Menu.NONE, MENU_ADD, Menu.NONE, "New session");
        add.setIcon(android.R.drawable.ic_input_add);
        add.setShowAsAction(MenuItem.SHOW_AS_ACTION_ALWAYS);
        return true;
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        if (item.getItemId() == MENU_ADD) {
            // Creates WITHOUT switching (SessionStore activates only
            // when it is the first session) — the list just reloads.
            SessionSwitcher.promptCreate(this,
                    "Session " + (rows.size() + 1), false,
                    new Runnable() {
                        @Override public void run() { load(); }
                    }, null);
            return true;
        }
        if (item.getItemId() == android.R.id.home) {
            finish();
            return true;
        }
        return super.onOptionsItemSelected(item);
    }

    private void load() {
        rows.clear();
        String activeId = SessionStore.getActiveId(this);
        for (SessionStore.Session s : SessionStore.getSessions(this)) {
            Row r = new Row();
            r.session = s;
            r.folderCount = FolderSelection.getAddedFor(this, s.id).size();
            r.active = s.id.equals(activeId);
            rows.add(r);
        }
        adapter.notifyDataSetChanged();
    }

    private void showRowActions(final Row r) {
        List<String> actions = new ArrayList<>();
        actions.add("Rename");
        actions.add("Manage folders");
        final boolean canDelete = rows.size() > 1;
        if (canDelete) actions.add("Delete session");
        new AlertDialog.Builder(this)
                .setTitle(r.session.name)
                .setItems(actions.toArray(new String[0]),
                        new DialogInterface.OnClickListener() {
                            @Override public void onClick(DialogInterface d, int which) {
                                String action = actions.get(which);
                                if ("Rename".equals(action)) {
                                    promptRename(r);
                                } else if ("Manage folders".equals(action)) {
                                    SessionStore.setActive(
                                            SessionsActivity.this, r.session.id);
                                    startActivity(new Intent(SessionsActivity.this,
                                            ManageFoldersActivity.class));
                                } else if ("Delete session".equals(action)) {
                                    confirmDelete(r);
                                }
                            }
                        })
                .show();
    }

    private void promptRename(final Row r) {
        final EditText input = new EditText(this);
        input.setSingleLine(true);
        input.setText(r.session.name);
        input.setSelectAllOnFocus(true);
        new AlertDialog.Builder(this)
                .setTitle("Rename session")
                .setView(input)
                .setPositiveButton("Rename", new DialogInterface.OnClickListener() {
                    @Override public void onClick(DialogInterface d, int which) {
                        SessionStore.renameSession(SessionsActivity.this,
                                r.session.id, input.getText().toString());
                        load();
                    }
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    private void confirmDelete(final Row r) {
        new AlertDialog.Builder(this)
                .setTitle("Delete session")
                .setMessage("Delete '" + r.session.name + "'?\n\n"
                        + "Its folder list and remembered place will be "
                        + "removed. The folders and images stay on your phone.")
                .setPositiveButton("Delete", new DialogInterface.OnClickListener() {
                    @Override public void onClick(DialogInterface d, int which) {
                        SessionStore.deleteSession(
                                SessionsActivity.this, r.session.id);
                        load();
                    }
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    private int dp(int v) {
        return (int) (v * getResources().getDisplayMetrics().density);
    }

    private class SessionAdapter extends BaseAdapter {
        @Override public int getCount() { return rows.size(); }
        @Override public Row getItem(int position) { return rows.get(position); }
        @Override public long getItemId(int position) { return position; }

        @Override
        public View getView(int position, View convertView, ViewGroup parent) {
            LinearLayout row;
            TextView name, info;
            if (convertView == null) {
                row = new LinearLayout(SessionsActivity.this);
                row.setOrientation(LinearLayout.HORIZONTAL);
                row.setGravity(Gravity.CENTER_VERTICAL);
                row.setPadding(dp(16), dp(12), dp(16), dp(12));
                name = new TextView(SessionsActivity.this);
                name.setTextColor(Color.WHITE);
                name.setTextSize(17);
                info = new TextView(SessionsActivity.this);
                info.setTextColor(Color.GRAY);
                info.setTextSize(13);
                LinearLayout texts = new LinearLayout(SessionsActivity.this);
                texts.setOrientation(LinearLayout.VERTICAL);
                texts.addView(name);
                texts.addView(info);
                row.addView(texts, new LinearLayout.LayoutParams(
                        0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
                row.setTag(new Object[]{name, info});
            } else {
                row = (LinearLayout) convertView;
                Object[] t = (Object[]) row.getTag();
                name = (TextView) t[0];
                info = (TextView) t[1];
            }
            Row r = rows.get(position);
            name.setText(r.session.name);
            String sub = r.folderCount + (r.folderCount == 1
                    ? " folder" : " folders");
            if (r.active) sub += "  ·  Active";
            info.setText(sub);
            return row;
        }
    }
}
