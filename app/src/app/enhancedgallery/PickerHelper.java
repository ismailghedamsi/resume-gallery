package app.enhancedgallery;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.DialogInterface;
import android.content.Intent;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.widget.Toast;

/** Shared interaction pieces for adding and removing folders, used by
 *  both FolderActivity and ManageFoldersActivity so the system-picker
 *  launch, the grant handling and the remove dialog exist in one place. */
public final class PickerHelper {

    private PickerHelper() {
    }

    /** Open Android's system folder selector. */
    public static void launch(Activity a, int requestCode) {
        Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT_TREE);
        i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION
                | Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION);
        a.startActivityForResult(i, requestCode);
    }

    public interface OnAdded {
        void onAdded(FolderSelection.AddedFolder entry);
    }

    /** Take the persistable grant for a picked tree Uri, register the
     *  folder, and report back on the UI thread. Must be called with a
     *  tree Uri returned by the system picker. */
    public static void handleResult(final Activity a, final Uri treeUri,
                                    final OnAdded cb) {
        final Handler ui = new Handler(Looper.getMainLooper());
        ThumbLoader.BG.execute(new Runnable() {
            @Override public void run() {
                try {
                    a.getContentResolver().takePersistableUriPermission(treeUri,
                            Intent.FLAG_GRANT_READ_URI_PERMISSION);
                } catch (Exception ignored) {
                    // Grant may already be held; adding stands either way.
                }
                final FolderSelection.AddedFolder entry =
                        FolderSelection.buildEntry(a, treeUri);
                FolderSelection.addFolder(a, entry);
                ui.post(new Runnable() {
                    @Override public void run() {
                        Toast.makeText(a, "Added: " + entry.name,
                                Toast.LENGTH_SHORT).show();
                        cb.onAdded(entry);
                    }
                });
            }
        });
    }

    /** Native remove pattern: long-press opens this dialog. Removing takes
     *  the folder out of the gallery and releases its system grant; the
     *  folder and its images stay untouched on the phone. */
    public static void confirmRemove(final Activity a,
                                     final FolderSelection.AddedFolder entry,
                                     final Runnable onRemoved) {
        confirmRemove(a, entry != null ? entry.name : "", entry, false, onRemoved);
    }

    /** As above, but the long-pressed row may be a SUBFOLDER of the added
     *  root (e.g. "2" inside added root "Instagram"): the dialog title is
     *  the visible folder's name, while the message names the added root
     *  that is actually removed — removing always removes the ROOT entry. */
    public static void confirmRemove(final Activity a, String visibleName,
                                     final FolderSelection.AddedFolder entry,
                                     boolean partOfRoot,
                                     final Runnable onRemoved) {
        if (entry == null) return;
        String message;
        if (partOfRoot) {
            message = "This folder is part of '" + entry.name
                    + "', which you added. Remove '" + entry.name
                    + "' and everything under it from the gallery?\n\n"
                    + "The folders and images stay on your phone.";
        } else {
            message = "Remove this folder from the gallery?\n\n"
                    + "The folder and its images stay on your phone.";
        }
        String title = visibleName != null && !visibleName.isEmpty()
                ? visibleName : entry.name;
        new AlertDialog.Builder(a)
                .setTitle(title)
                .setMessage(message)
                .setPositiveButton("Remove", new DialogInterface.OnClickListener() {
                    @Override public void onClick(DialogInterface d, int which) {
                        ThumbLoader.BG.execute(new Runnable() {
                            @Override public void run() {
                                FolderSelection.removeFolder(a, entry);
                                final Handler ui =
                                        new Handler(Looper.getMainLooper());
                                ui.post(new Runnable() {
                                    @Override public void run() {
                                        Toast.makeText(a,
                                                "Removed: " + entry.name,
                                                Toast.LENGTH_SHORT).show();
                                        onRemoved.run();
                                    }
                                });
                            }
                        });
                    }
                })
                .setNegativeButton("Cancel", null)
                .show();
    }
}
