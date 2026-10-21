# Resume Gallery — from-scratch Android gallery

Original, from-scratch gallery app for Ismail. Plain Java, Android framework
only — no libraries, no Gradle. Package `app.enhancedgallery`, launcher label
"Resume Gallery". minSdk 29, targetSdk 35. Local storage only via
MediaStore (READ_MEDIA_IMAGES / READ_EXTERNAL_STORAGE, no internet permission).
Installs side-by-side with any other gallery; nothing needs to be uninstalled.

## Built with AI

Resume Gallery is an AI-assisted ("vibe coded") project: it was designed
and specified by Ismail Ghedamsi, and its code was written by Muse
(Meta's Muse Spark) working from those specifications, then iterated
build by build against the author's testing and corrections.

## Features

1. **Folder browsing** — home screen lists the folders you added (see v1.4:
   folders are added via the Android folder selector; un-added folders are
   never shown), each with thumbnail, name and image count. Folders sorted numerically by the first number in the
   name ("0-5000" → 0, "5001-10000" → 5001); numberless folders sort after
   numbered ones, by name. Folder grid uses a recycling GridView with async
   downsampled thumbnails (~192px) and an LruCache — full-size images are
   never decoded for the grid.
2. **Persistent resume** — folder path, image path, index and timestamp are
   written to SharedPreferences with commit() (synchronous) on EVERY image
   change in the viewer and in onPause. On cold start, if the saved image
   still exists, the viewer opens directly at that image. Viewer Back goes
   to that folder's grid, then the folder list; a folders button jumps
   straight to the folder list.
3. **Cross-folder auto-advance** — NEXT past a folder's last image loads the
   next folder (same numeric order) at its first image, with a Toast naming
   the new folder. PREVIOUS past the first image loads the previous folder
   at its last image. "Last folder" / "First folder" toasts at the ends.
   Neighbour folders are queried from MediaStore on demand, off the UI thread.
4. **Multi-way navigation** (active only when NOT zoomed, scale == 1):
   - swipe left = next, swipe right = previous
   - swipe up = next, swipe down = previous (vertical fling, ~120dp)
   - tap left 35% = previous, tap right 35% = next, tap top 20% strip =
     previous, tap bottom 20% strip = next, tap centre = show/hide info bar
     (folder name + position "123 / 5000" + filename)
   - double tap = zoom to 2.5x centred on the tap point; double tap again =
     back to 1x. Pinch zoom (1x–5x) and drag-to-pan when zoomed.
5. **Performance** — viewer decodes downsampled to at most 2048px on the long
   edge, off the UI thread; current + prev/next images cached in a small
   LruCache; tap/swipe targets disabled while zoomed so gestures never fight.

## v1.1 (versionCode 2) — performance fixes

Cold start and image display could take ~10s on slower phones. Fixed:
- Folder and image queries now select only that folder's rows (BUCKET_ID,
  with a DATA-LIKE fallback) instead of scanning the whole image table with
  a per-row File allocation; the folder scan drops its full-table ORDER BY
  and derives parents with plain string ops.
- Folder list is scanned once per process (no rescan on every onResume) and
  persisted to a small disk cache, so the folder screen shows instantly on
  cold start and refreshes in the background only if data changed.
- Thumbnails come from MediaStore's system thumbnail cache
  (ContentResolver.loadThumbnail, 256px), with a downsampled file decode as
  fallback; decoding reads from the file path instead of slow provider
  streams.
- The downsampler now picks the sample whose result is closest to the target
  (decoded bitmaps stay within [target/2, target] instead of up to 2x the
  target); the viewer has its own single-thread decoder so neighbour
  prefetches (at 1280px) queue behind — never compete with — the current
  image; the small grid thumbnail shows instantly as a placeholder while the
  full decode runs.

## v1.2 (versionCode 3) — natural filename sorting

Images inside a folder now sort in natural (numeric-aware) order —
1.jpg, 2.jpg, 10.jpg — instead of plain text order (1.jpg, 10.jpg, 2.jpg)
that MediaStore's ORDER BY produced. MediaRepo.getImages() no longer
requests a provider sort; the list is re-sorted in Java with
SortUtil.compareNatural() (digit runs compared by value, leading zeros
stripped, no integer parsing so numbers of any length are safe). Grid
order and viewer swipe order both come from getImages(), so one fix
covers both. The folder comparator keeps its first-number rule and now
uses the same natural comparator as its tiebreak (e.g. "Set 2 Disc 2"
before "Set 2 Disc 10"). Verified with a JVM self-check:
[1.jpg, 2.jpg, 10.jpg, 10a.jpg, a2.jpg, a02.jpg, a10.jpg, ...] sorts in
exactly that order, and 1 < 2 < 10 / a2 < a10 hold.

## v2.0.3 (versionCode 14) — resume survives Android reclaiming the app

Bug (user report): closing the app and reopening resumed correctly,
but when Android reclaimed the backgrounded app for inactivity and
later restored the task, the viewer came back on the photo it had
been OPENED on, not the last one viewed. The session point was being
saved all along — the recreated viewer simply never read it: Android
recreates a killed activity from its original launch intent, and the
viewer kept no recreation state of its own. The viewer now writes
its current folder + photo into onSaveInstanceState on every photo
shown (showImage keeps a pending path/index in sync) and prefers
that state over the intent extras when recreated. switchFolder also
assigns the new folder only once its images are on screen, so the
saved folder/photo pair is never half-switched.

## v2.0.2 (versionCode 13) — renamed to Resume Gallery

The app is now called **Resume Gallery**. This is a display-name change
only: the Android package remains `app.enhancedgallery` and the signing key
is unchanged, so it installs as a normal update and all existing sessions,
folders, and saved positions carry over.

## v2.0.1 (versionCode 12) — switcher create-flow fix

Bug fix: creating a session from the ☰ switcher left the sessions
dialog on screen with its pre-creation list — the new session never
appeared in it and nothing showed it was selected. The switcher now
dismisses before the name prompt, re-shows itself freshly built after
a creation (new session listed, ✓ on it, "Done" to close), and only
then — dialog closed, new session active and folderless — asks the
host to open the folder picker for it. The list is always built from
the store at show time and at most one switcher dialog exists.

## v2.0 (versionCode 11) — named browse sessions

A **session** is an independent browsing context: its own set of added
folders and its own remembered place (the last photo viewed while it
was active). With many big folders, each "reading" gets a session and
keeps its place — sessions never disturb each other.

The flow, in the user's own example: on a fresh install the app asks
you to name your first session ("animals"); creating a session means
picking its folders with the Android folder selector (the cats folder,
the dogs folder, …) — the native picker decides what a session
includes. Later, a second session ("weddings") is created the same
way. Browse session 1 deep into a huge folder, switch to animals,
browse there, switch BACK to session 1 — and you continue from session
1's latest seen image, exactly where you left it.

- **Data model** — SessionStore (SharedPreferences
  "gallery_enhanced_sessions", commit()ed): the session list
  [{id, name}] in creation order, the active id, and one resume point
  per session ("point:<id>" = folder + image + index + timestamp),
  saved by the viewer on every photo shown, under whichever session is
  active. FolderSelection's added-folder sets are stored per session
  ("added_v3:<sessionId>"); every existing call site resolves the
  active session internally, so nested-root expansion, search, the
  manager and cross-folder advance are all session-scoped for free.
  The same folder can belong to several sessions, with a different
  remembered place in each. System folder grants are per folder and
  unaffected by sessions.
- **The switcher (☰)** is present in all three views: the folder list
  ActionBar, the grid ActionBar, and the viewer's always-visible top
  bar. It lists the sessions (active one marked ✓) plus "New session".
  Switching from the folder list reloads it for the new session
  (title = the session's name); from the grid it goes back to the
  folder list; from the viewer it jumps straight to the new session's
  remembered photo — or exits to its folder list when that session has
  no place yet. Auto swipe never survives a switch. Creating a session
  from the switcher activates it and immediately opens the system
  folder picker to add its first folder (the picker is launched by
  the screen that showed the switcher, which also handles the result).
- **Places are shown, not just kept** — the folder row holding the
  active session's point reads "5,000 images · at 500", and that
  folder's grid opens scrolled to the remembered photo, marked with a
  "▶ LAST SEEN" chip. Cold start still resumes the active session's
  point directly into the viewer, validated as before (image exists,
  folder still in the session).
- **First run** — with no sessions, a name dialog ("Create your first
  session", prefilled "Session 1") comes first; Create continues into
  the system folder picker. Cancelling still creates a default empty
  "Session 1" so the app is usable, without throwing the picker at
  you, and the dialog never loops.
- **Managing sessions** — Settings gains a "Sessions" row ("<active
  name> · <N> sessions") opening a Sessions screen: tap a row to
  switch to it, long-press for Rename / Manage folders / Delete
  session (delete is refused for the last remaining session; it wipes
  that session's folder set and its point). Its "+" creates a session
  without switching to it (unless it is the first). The folder manager
  ("Folders to show — <session name>") always edits the active
  session's set.
- **Migration** — a pre-v2.0 install's single global folder set and
  resume state become "Session 1" on first v2.0 launch: same folders,
  same place, now owned by that session. The legacy storage is left in
  place, read-only, after the one-time copy.

## v1.9 (versionCode 10) — top bar always visible in the viewer

The viewer's top bar (file info + the auto-swipe play/pause button) is
now permanently pinned at the top of the screen: it is created visible
and no code path hides it. The centre tap no longer toggles it —
centre tap is simply no action now (left/top = previous, right/bottom
= next, double-tap zoom unchanged). The v1.8 standalone corner pause
button is removed as redundant: the bar's own play/pause button is
always reachable, in both states. The photo still fills the whole
screen underneath the semi-transparent bar.

## v1.8 (versionCode 9) — auto swipe (slideshow)

The viewer can advance on its own, using the exact same next-navigation
path as swipes and taps — natural order, cross-folder advance with the
folder-name toast, and resume saved on every photo shown:

- **Controls**: the info bar (centre tap) carries a play/pause button
  (framework `ic_media_play` / `ic_media_pause`). While auto swipe runs
  with the info bar hidden, a small standalone pause button sits in the
  top-right corner, so the show can always be stopped. `onPause` stops
  auto swipe completely; no wakelock and no service — the only screen
  flag is `FLAG_KEEP_SCREEN_ON` while the show runs.
- **Engine**: a main-thread `Handler` timer re-reads the interval from
  preferences on every schedule. A tick while zoomed is skipped (auto
  swipe waits for the unzoom). A manual swipe/tap restarts the wait, so
  a jump is never followed by an instant automatic one. At the last
  photo of the last visible folder the show stops and toasts "End of
  the last folder" — no looping; a tick also verifies the previous
  advance actually moved, so an empty next folder ends the show
  instead of spinning on it.
- **Speed**: Settings gains an "Auto swipe speed" row (subtitle:
  "Every 3 seconds"); tapping it opens a single-choice dialog — 2, 3,
  5 or 10 seconds — saved immediately to `autoswipe_interval_ms`
  (default 3000) and applied the next time play is pressed.

## v1.7 (versionCode 8) — search, the standard way

Both searches are the plain platform pattern — an ActionBar search
action with a framework `SearchView`, nothing bespoke:

- **Folder search** (folder screen): typing filters the added-folder
  list by case-insensitive substring on the folder name OR its path
  with the storage prefix trimmed — "instagram" finds Instagram/1,
  Instagram/2..., and "instagram/2" narrows to that one. Only the
  displayed copy is filtered: resume checks, long-press remove and
  everything else keep using the full list, and the rows are the same
  Folder objects (thumbnails/counts untouched). Collapsing the search
  restores the full list.
- **Photo search inside a folder** (grid screen, which now also uses
  the platform ActionBar — title = folder name + count, Up = back):
  typing filters the grid by file-name substring; while a query is
  active every result shows its file name under the thumbnail, and
  collapsing restores the plain full grid.
- **Open at the found photo**: a grid tap passes `startImagePath`;
  the viewer resolves it by path in the folder's FULL list and starts
  there (fallback: first image). Next/previous from the found photo
  walk the whole folder in natural order, including cross-folder
  advance at the ends — the viewer never sees the filtered list.
  Viewing from search updates the resume point like any viewing.

Matching rules live in `SearchFilter` (pure string code) and are
covered by a JVM check of name/path/filename substrings, storage-root
trimming and tree-marker paths.

## v1.6 (versionCode 7) — nested folders: an added folder is a root

Adding a folder with the system picker now adds its whole subtree. Each
added folder acts as a **root**: the gallery shows the root itself (when it
directly contains images) plus every descendant directory, at any depth,
that directly contains images — each as its own folder row. His case:
adding "Instagram" (containing subfolders "1", "2", "3") shows "1", "2",
"3" as the gallery's folders.

- **Expansion for real-path roots (the normal case)** — MediaRepo already
  enumerates every folder on the device, so expanding is pure filtering:
  for each added entry, every MediaStore folder whose path equals the
  root's or starts with `root + "/"`. No new scanning; per-folder image
  queries, the grid and the viewer are untouched. A root that MediaStore
  has not indexed at all still shows as itself (never silently dropped).
- **Tree-only roots (rare fallback, real path unknown)** — the document
  tree is walked once per process (cached, 100k-row cap) and every
  directory directly containing images becomes a folder; `getImages` for
  these folders now lists only that directory's direct image children
  (recursion is used only for discovery, not for images).
- **Order** — roots in the order they were added; inside a root, the root
  itself first, then descendants by relative path, segment-by-segment
  with the natural comparator ("1" < "2" < "2/x" < "10"). Cross-folder
  advance and resume follow this order (resume is path-based, unchanged).
- **Removal** — long-pressing any row that belongs to a root (including a
  subfolder like "2") removes that root entry: the dialog is titled with
  the visible folder's name and its message names the root, e.g. "This
  folder is part of 'Instagram', which you added. Remove 'Instagram' and
  everything under it from the gallery? The folders and images stay on
  your phone." (`FolderSelection.findAdded` resolves by exact path, then
  longest root prefix; "Folders to show" in Settings lists roots only and
  is unchanged).

## v1.5 (versionCode 6) — native folder-selection chrome

The v1.4 data model was correct (added-folders list, SAF grants, path
derivation, MediaStore fast path, natural sort, resume); only the
interaction pattern was homemade, and it has been replaced with the
standard native patterns:

- **First launch goes straight to the system picker.** When no folders
  are added, FolderActivity opens Android's system folder selector
  (ACTION_OPEN_DOCUMENT_TREE) directly after the permission step — no
  intermediate manager screen. The grant is taken and the folder is
  registered (PickerHelper, shared with the manager so the launch and
  grant handling live in one place). If the user cancels the picker,
  a plain empty state shows centered "No folders yet" + one button
  "Select a folder" — the picker is never relaunched in a loop.
- **Standard ActionBar on the folder screen** (platform Theme.Material):
  title "Gallery Enhanced", a "+" action (always shown) that opens the
  system selector again to add more folders, and "Settings" in the
  overflow menu. Returning from the picker or Settings refreshes the
  list; it never re-triggers the first-launch picker. The homemade
  header row ("Folders" title + "Add folders"/"Settings" buttons) is
  gone.
- **Remove the native way:** long-press a folder row on the main list
  (and in "Folders to show") opens a dialog — title is the folder name,
  message "Remove this folder from the gallery? The folder and its
  images stay on your phone." — with Remove / Cancel. Removing drops
  the folder from the gallery and releases its system grant. The
  per-row Remove button and the bottom "+ Add folder" button on the
  manager are gone (adding is the "+" action; the empty state text
  says: "No folders added yet. Use + to pick a folder with the Android
  folder selector.").
- SettingsActivity and ManageFoldersActivity use the platform ActionBar
  too (Settings has an Up affordance; "Folders to show" subtitle
  unchanged in behaviour). Grid and Viewer are untouched.

## v1.4 (versionCode 5) — add folders with the Android folder selector

Replaces the v1.3 checkbox picker (the user rejected it: showing every
folder and then asking which to keep is the wrong way round). The app now
starts knowing NO folders. You add them one at a time with "+ Add folder",
which opens Android's SYSTEM folder selector
(Intent.ACTION_OPEN_DOCUMENT_TREE). A persistable read grant is taken for
each added folder, so added folders stay usable across restarts.

- "Folders to show" manager (ManageFoldersActivity, from Settings): lists
  ONLY added folders, each with thumbnail (borrowed from the MediaStore
  lookup when the folder is indexed), image count when known, and path.
  Removing is by long-press on a row (v1.5: the per-row "Remove" button
  and the bottom "+ Add folder" button are gone; adding is the "+" action).
- The folder screen, the viewer's cross-folder advance and the resume
  check all use MediaRepo.getVisibleFolders(), which is built FROM the
  added entries (each borrowing count/thumbnail from the MediaStore folder
  of the same path when one exists, synthesized from the stored entry
  otherwise — an added folder is never silently dropped), in numeric
  folder order. Backing out of the manager with nothing added shows an
  empty-state screen with its own "Add folders" button — never a full
  folder list.
- Folders that were never added are never displayed anywhere. Nothing is
  auto-discovered or auto-added, ever.
- Image loading: a folder with a real filesystem path keeps the fast
  MediaStore path (BUCKET_ID / DATA LIKE + natural sort); only when no
  real path could be derived from the tree Uri are the folder's documents
  enumerated through DocumentsContract (recursive, image/* only,
  naturally sorted). Thumbnails and the viewer decode from the document
  Uri directly in that case.
- A v1.3 checkbox selection saved on the device is migrated once: its
  paths become added folders (working through the MediaStore fast path).

## Settings screen (v1.4+)

SettingsActivity, opened from the "Settings" overflow item in the folder
screen's ActionBar (v1.5). One tappable row, "Folders to show", whose subtitle reports the
current state ("N folders added", or "None added yet"); tapping it opens
the folder manager, and the subtitle refreshes on return. Returning from
Settings to the folder screen reloads the folder list, so changes take
effect immediately. An About section at the bottom shows the app name
and version (read from PackageManager, not hardcoded). There are
deliberately no other toggles — resume stays always-on.

## Build

One command (uses the shared JDK 21 + Android SDK 36 toolchain):

    bash ~/workspace/enhanced-gallery/build.sh

Steps inside: aapt package (resources + R.java) → javac → jar → d8
(min-api 29) → aapt package → aapt add classes.dex → zipalign → apksigner
sign with ~/workspace/fossify-gallery-mod/enhanced-gallery.jks
(alias "enhancedgallery", password in keystore.pass).

Output: ~/workspace/your_files/enhancedgallery.apk
