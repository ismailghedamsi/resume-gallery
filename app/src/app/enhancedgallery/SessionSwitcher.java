package app.enhancedgallery;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.DialogInterface;
import android.graphics.Color;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;

import java.util.List;

/** The ☰ session switcher (v2.0), shared by the folder list, the grid
 *  and the viewer so session switching looks and behaves the same
 *  everywhere: a "Sessions" dialog listing the sessions in creation
 *  order (the active one marked ✓) plus a "New session" button.
 *
 *  Picking a different session activates it and reports onSwitched —
 *  what that means is each screen's business (the folder list reloads,
 *  the grid goes back, the viewer jumps to the new session's place).
 *
 *  Creating a session (v2.0.1 flow): "New session" dismisses the list
 *  dialog FIRST, then the name prompt runs. When the prompt creates
 *  the session, the switcher is shown AGAIN, rebuilt from the store,
 *  so the new session is right there in the list with its ✓ — the
 *  user sees the creation landed and which session is now selected.
 *  Only when that re-shown dialog closes (Done, a tap, Back) with the
 *  new session active and still folderless is onNewSession reported;
 *  the CALLER then launches the system folder picker with its own
 *  request code to add the new session's first folder, because only
 *  the caller can handle that result. Cancelling the name prompt
 *  re-shows the list dialog unchanged.
 *
 *  The list dialog is ALWAYS built from SessionStore's current state
 *  at the moment it is shown, and at most one switcher dialog exists
 *  at a time (a static reference is kept and dismissed before any new
 *  one is shown) — no cached snapshots, no stacked or orphaned
 *  dialogs. */
public final class SessionSwitcher {

    private SessionSwitcher() {
    }

    public interface Callback {
        /** A different existing session was picked and is now active. */
        void onSwitched();

        /** A session was created via the switcher and the switcher
         *  has closed with that session active and still folderless.
         *  The caller should launch the system folder picker now. */
        void onNewSession();
    }

    /** The switcher dialog currently on screen, if any. */
    private static AlertDialog sDialog;

    /** Id of a session created during THIS switcher interaction whose
     *  picker follow-up is still pending. Set when a create succeeds
     *  inside show(); consumed (one-shot) when the re-shown dialog
     *  closes — and cleared whenever a plain switch happens or a new
     *  interaction starts, so the picker is never offered for a
     *  session the user did not just create. */
    private static String sPendingCreatedId;

    /** True while a "New session" tap is moving from the list dialog
     *  into the name prompt: the list dialog's own dismiss (caused by
     *  that tap) must not run the close-out logic. */
    private static boolean sHeadingToCreate;

    /** The ☰ button used in ActionBars (as an action view) and in the
     *  viewer's top bar (as a plain child view). */
    public static Button barButton(Activity act, View.OnClickListener click) {
        Button b = new Button(act);
        b.setText("☰");
        b.setTextSize(22);
        b.setTextColor(Color.WHITE);
        b.setBackgroundColor(Color.TRANSPARENT);
        int h = (int) (10 * act.getResources().getDisplayMetrics().density);
        int v = (int) (4 * act.getResources().getDisplayMetrics().density);
        b.setPadding(h, v, h, v);
        b.setContentDescription("Sessions");
        b.setOnClickListener(click);
        return b;
    }

    /** Show the switcher dialog, built fresh from the store. Any
     *  switcher dialog already up is dismissed first. */
    public static void show(final Activity act, final Callback cb) {
        // A fresh interaction starts clean: no pending create from an
        // earlier one may leak its picker offer into this one.
        sPendingCreatedId = null;
        sHeadingToCreate = false;
        showList(act, cb, false);
    }

    /** The list dialog itself. {@code afterCreate} marks the re-show
     *  right after a successful creation: it carries a "Done" button
     *  instead of "Cancel", and closing it completes the pending
     *  create (see completePendingCreate). */
    private static void showList(final Activity act, final Callback cb,
                                 final boolean afterCreate) {
        dismissCurrent();
        final List<SessionStore.Session> sessions = SessionStore.getSessions(act);
        final String activeId = SessionStore.getActiveId(act);
        String[] labels = new String[sessions.size()];
        for (int i = 0; i < sessions.size(); i++) {
            SessionStore.Session s = sessions.get(i);
            labels[i] = s.name + (s.id.equals(activeId) ? "  ✓" : "");
        }
        AlertDialog.Builder builder = new AlertDialog.Builder(act)
                .setTitle("Sessions")
                .setItems(labels, new DialogInterface.OnClickListener() {
                    @Override public void onClick(DialogInterface d, int which) {
                        SessionStore.Session picked = sessions.get(which);
                        if (picked.id.equals(activeId)) {
                            // Already here: the tap just closes the
                            // dialog; if this is the post-create
                            // re-show, the close-out in onDismiss
                            // offers the picker for the new session.
                            return;
                        }
                        // A plain switch supersedes any pending
                        // create follow-up.
                        sPendingCreatedId = null;
                        SessionStore.setActive(act, picked.id);
                        if (cb != null) cb.onSwitched();
                    }
                })
                .setNeutralButton("New session", new DialogInterface.OnClickListener() {
                    @Override public void onClick(DialogInterface d, int which) {
                        // Dismiss the list dialog BEFORE the name
                        // prompt opens — never stack the prompt (and
                        // later the picker) on top of this dialog.
                        sHeadingToCreate = true;
                        d.dismiss();
                        promptCreate(act,
                                "Session " + (sessions.size() + 1), true,
                                new Runnable() {
                                    @Override public void run() {
                                        // Created + activated by
                                        // promptCreate. Re-show the
                                        // switcher, rebuilt fresh, so
                                        // the new session is visible
                                        // in the list with its ✓.
                                        sPendingCreatedId =
                                                SessionStore.getActiveId(act);
                                        showList(act, cb, true);
                                    }
                                },
                                new Runnable() {
                                    @Override public void run() {
                                        // Cancelled: land back on the
                                        // list, unchanged — unless an
                                        // earlier create in this same
                                        // interaction is still pending,
                                        // in which case its re-show
                                        // mode (Done + picker offer)
                                        // is preserved.
                                        boolean stillPending =
                                                sPendingCreatedId != null
                                                        && sPendingCreatedId.equals(
                                                                SessionStore.getActiveId(act));
                                        showList(act, cb, stillPending);
                                    }
                                });
                    }
                });
        if (afterCreate) {
            // Dismiss-only; the close-out runs in onDismiss.
            builder.setPositiveButton("Done", null);
        } else {
            builder.setNegativeButton("Cancel", null);
        }
        final AlertDialog dialog = builder.create();
        dialog.setOnDismissListener(new DialogInterface.OnDismissListener() {
            @Override public void onDismiss(DialogInterface d) {
                if (sDialog == dialog) sDialog = null;
                if (sHeadingToCreate) {
                    // This dismiss belongs to a "New session" tap; the
                    // create flow owns what happens next.
                    sHeadingToCreate = false;
                    return;
                }
                if (afterCreate) completePendingCreate(act, cb);
            }
        });
        sDialog = dialog;
        dialog.show();
    }

    /** Close-out for the post-create re-show: the dialog has closed.
     *  When the just-created session is still the active one and has
     *  no folders yet, tell the host (once) so it opens the system
     *  folder picker for it. Switching to a different session in the
     *  re-shown dialog clears the pending id first, so no picker is
     *  offered after a plain switch. */
    private static void completePendingCreate(Activity act, Callback cb) {
        final String id = sPendingCreatedId;
        sPendingCreatedId = null; // one-shot, whatever happens next
        if (id == null || cb == null) return;
        if (!id.equals(SessionStore.getActiveId(act))) return;
        if (FolderSelection.hasAny(act)) return;
        cb.onNewSession();
    }

    /** Dismiss the switcher dialog on screen, if any — a new one is
     *  never stacked on top of a stale one. */
    private static void dismissCurrent() {
        if (sDialog != null) {
            AlertDialog d = sDialog;
            sDialog = null;
            try {
                d.dismiss();
            } catch (Exception ignored) {
                // Host already gone; nothing to clean up.
            }
        }
    }

    /** Name dialog for a new session: an EditText prefilled with the
     *  suggested name. "Create" creates the session (activating it when
     *  {@code activate} is set, or when it ends up the only session) and
     *  runs onCreated. Any other way out — Cancel, Back, tapping
     *  outside — runs onCancelled exactly once, if given. */
    public static void promptCreate(final Activity act, String prefill,
                                    final boolean activate,
                                    final Runnable onCreated,
                                    final Runnable onCancelled) {
        promptCreate(act, "New session", prefill, activate, onCreated, onCancelled);
    }

    /** As above, with a custom dialog title (the first-run flow asks
     *  for the FIRST session by name). */
    public static void promptCreate(final Activity act, String title,
                                    String prefill, final boolean activate,
                                    final Runnable onCreated,
                                    final Runnable onCancelled) {
        final EditText input = new EditText(act);
        input.setSingleLine(true);
        input.setText(prefill);
        input.setSelectAllOnFocus(true);
        final boolean[] created = new boolean[1];
        AlertDialog dialog = new AlertDialog.Builder(act)
                .setTitle(title)
                .setView(input)
                .setPositiveButton("Create", new DialogInterface.OnClickListener() {
                    @Override public void onClick(DialogInterface d, int which) {
                        String name = input.getText().toString();
                        String id = SessionStore.createSession(act, name);
                        if (activate) SessionStore.setActive(act, id);
                        created[0] = true;
                        if (onCreated != null) onCreated.run();
                    }
                })
                .setNegativeButton("Cancel", null)
                .create();
        dialog.setOnDismissListener(new DialogInterface.OnDismissListener() {
            @Override public void onDismiss(DialogInterface d) {
                if (!created[0] && onCancelled != null) onCancelled.run();
            }
        });
        dialog.show();
    }
}
