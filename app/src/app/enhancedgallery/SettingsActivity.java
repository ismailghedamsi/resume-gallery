package app.enhancedgallery;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.os.Bundle;
import android.view.Gravity;
import android.view.MenuItem;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.TextView;

/** Settings screen, styled on the platform ActionBar ("Settings" title
 *  with Up). Rows: "Sessions" (the active session and the session
 *  manager), "Folders to show" (opens ManageFoldersActivity for the
 *  active session) and "Auto swipe speed" (the viewer's slideshow
 *  interval). Plus an About section. Resume is a core always-on
 *  behaviour and intentionally has no switch here. */
public class SettingsActivity extends Activity {

    private static final int REQ_MANAGER = 43;

    private TextView sessionsSubtitle;
    private TextView foldersSubtitle;
    private TextView speedSubtitle;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setTitle("Settings");
        if (getActionBar() != null) {
            getActionBar().setDisplayHomeAsUpEnabled(true);
        }

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(Color.BLACK);

        // "Folders to show" row: title + current-state subtitle, whole
        // row tappable, opens the folder manager.
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.VERTICAL);
        row.setPadding(dp(16), dp(12), dp(16), dp(12));
        row.setBackgroundColor(0xFF1A1A1A);
        row.setClickable(true);
        TextView rowTitle = new TextView(this);
        rowTitle.setText("Folders to show");
        rowTitle.setTextSize(16);
        rowTitle.setTextColor(Color.WHITE);
        row.addView(rowTitle);
        foldersSubtitle = new TextView(this);
        foldersSubtitle.setTextSize(13);
        foldersSubtitle.setTextColor(Color.GRAY);
        foldersSubtitle.setPadding(0, dp(2), 0, 0);
        row.addView(foldersSubtitle);
        row.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                startActivityForResult(
                        new Intent(SettingsActivity.this, ManageFoldersActivity.class),
                        REQ_MANAGER);
            }
        });
        root.addView(row);

        // "Sessions" row: subtitle names the active session and the
        // session count; tapping opens the sessions manager.
        LinearLayout sessRow = new LinearLayout(this);
        sessRow.setOrientation(LinearLayout.VERTICAL);
        sessRow.setPadding(dp(16), dp(12), dp(16), dp(12));
        sessRow.setBackgroundColor(0xFF1A1A1A);
        sessRow.setClickable(true);
        LinearLayout.LayoutParams sessLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        sessLp.topMargin = dp(2);
        TextView sessTitle = new TextView(this);
        sessTitle.setText("Sessions");
        sessTitle.setTextSize(16);
        sessTitle.setTextColor(Color.WHITE);
        sessRow.addView(sessTitle);
        sessionsSubtitle = new TextView(this);
        sessionsSubtitle.setTextSize(13);
        sessionsSubtitle.setTextColor(Color.GRAY);
        sessionsSubtitle.setPadding(0, dp(2), 0, 0);
        sessRow.addView(sessionsSubtitle);
        sessRow.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                startActivity(new Intent(SettingsActivity.this, SessionsActivity.class));
            }
        });
        root.addView(sessRow, sessLp);

        // "Auto swipe speed" row: subtitle shows the current interval;
        // tapping opens the standard single-choice dialog.
        LinearLayout speedRow = new LinearLayout(this);
        speedRow.setOrientation(LinearLayout.VERTICAL);
        speedRow.setPadding(dp(16), dp(12), dp(16), dp(12));
        speedRow.setBackgroundColor(0xFF1A1A1A);
        speedRow.setClickable(true);
        LinearLayout.LayoutParams speedLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        speedLp.topMargin = dp(2);
        TextView speedTitle = new TextView(this);
        speedTitle.setText("Auto swipe speed");
        speedTitle.setTextSize(16);
        speedTitle.setTextColor(Color.WHITE);
        speedRow.addView(speedTitle);
        speedSubtitle = new TextView(this);
        speedSubtitle.setTextSize(13);
        speedSubtitle.setTextColor(Color.GRAY);
        speedSubtitle.setPadding(0, dp(2), 0, 0);
        speedRow.addView(speedSubtitle);
        speedRow.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { showSpeedDialog(); }
        });
        root.addView(speedRow, speedLp);

        root.addView(new View(this), new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1));

        // About section, pinned to the bottom.
        TextView aboutTitle = new TextView(this);
        aboutTitle.setText("About");
        aboutTitle.setTextSize(13);
        aboutTitle.setTextColor(Color.GRAY);
        aboutTitle.setPadding(dp(16), 0, dp(16), dp(2));
        root.addView(aboutTitle);
        TextView about = new TextView(this);
        about.setText("Gallery Enhanced\nVersion " + versionString());
        about.setTextSize(14);
        about.setTextColor(Color.LTGRAY);
        about.setGravity(Gravity.START);
        about.setPadding(dp(16), 0, dp(16), dp(16));
        root.addView(about);

        setContentView(root);
        refreshSessionsSubtitle();
        refreshSubtitle();
        refreshSpeedSubtitle();
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        if (item.getItemId() == android.R.id.home) {
            finish();
            return true;
        }
        return super.onOptionsItemSelected(item);
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == REQ_MANAGER) {
            refreshSubtitle();
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        refreshSessionsSubtitle();
        refreshSubtitle();
        refreshSpeedSubtitle();
    }

    /** Subtitle under "Sessions": "<active name> · <N> sessions". */
    private void refreshSessionsSubtitle() {
        SessionStore.Session active = SessionStore.getActive(this);
        int n = SessionStore.getSessions(this).size();
        sessionsSubtitle.setText((active != null ? active.name : "None")
                + " · " + n + (n == 1 ? " session" : " sessions"));
    }

    /** Single-choice dialog for the auto swipe interval; the choice is
     *  saved immediately and the subtitle refreshes behind the dialog. */
    private void showSpeedDialog() {
        final int[] choices = AutoSwipe.CHOICES_MS;
        String[] labels = new String[choices.length];
        for (int i = 0; i < choices.length; i++) {
            labels[i] = AutoSwipe.choiceLabel(choices[i]);
        }
        new AlertDialog.Builder(this)
                .setTitle("Auto swipe speed")
                .setSingleChoiceItems(labels, AutoSwipe.checkedIndex(this),
                        new DialogInterface.OnClickListener() {
                            @Override public void onClick(DialogInterface dialog, int which) {
                                AutoSwipe.setIntervalMs(SettingsActivity.this, choices[which]);
                                refreshSpeedSubtitle();
                                dialog.dismiss();
                            }
                        })
                .setNegativeButton("Cancel", null)
                .show();
    }

    /** Subtitle under "Auto swipe speed": "Every N seconds". */
    private void refreshSpeedSubtitle() {
        speedSubtitle.setText(AutoSwipe.subtitle(this));
    }

    /** Subtitle under "Folders to show": how many folders are added,
     *  or that none have been added yet. */
    private void refreshSubtitle() {
        int n = FolderSelection.getAdded(this).size();
        foldersSubtitle.setText(n == 0 ? "None added yet"
                : (n + (n == 1 ? " folder added" : " folders added")));
    }

    private String versionString() {
        try {
            PackageInfo info = getPackageManager().getPackageInfo(getPackageName(), 0);
            return info.versionName == null ? "" : info.versionName;
        } catch (PackageManager.NameNotFoundException e) {
            return "";
        }
    }

    private int dp(int v) {
        return (int) (v * getResources().getDisplayMetrics().density);
    }
}
