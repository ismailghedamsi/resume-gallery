package app.enhancedgallery;

import java.util.Locale;

/** Pure string matching for the folder and in-folder searches (v1.7).
 *  No Android imports, so the exact shipped logic can be exercised by a
 *  plain JVM test. Queries are case-insensitive substrings. */
public final class SearchFilter {

    private SearchFilter() {}

    /** Lowercased, trimmed query; null becomes "". */
    public static String normalize(String query) {
        if (query == null) return "";
        return query.trim().toLowerCase(Locale.ROOT);
    }

    /** Strip the storage-root prefix (e.g. "/storage/emulated/0") and any
     *  leading '/' from a filesystem path, so "Pictures/Instagram/2" is
     *  what queries are matched against. A path outside the root is
     *  returned unchanged. */
    public static String trimStorageRoot(String path, String root) {
        if (path == null) return "";
        String p = path;
        if (root != null && !root.isEmpty() && p.startsWith(root)) {
            p = p.substring(root.length());
        }
        while (p.startsWith("/")) p = p.substring(1);
        return p;
    }

    /** The matchable path of a tree-marker folder path
     *  ("tree:<treeUri>" or "tree:<treeUri>\n<docId>"): the directory's
     *  document id, e.g. "primary:Pictures/Instagram/2". A subfolder marker
     *  already carries the raw doc id after the newline; for the root it
     *  is recovered by percent-decoding the "/tree/" segment of the Uri. */
    public static String treePathForMatch(String marker, String treePrefix) {
        if (marker == null) return "";
        String rest = marker.startsWith(treePrefix)
                ? marker.substring(treePrefix.length()) : marker;
        int nl = rest.indexOf('\n');
        if (nl >= 0) return rest.substring(nl + 1);
        String decoded = percentDecode(rest);
        int t = decoded.indexOf("/tree/");
        return t >= 0 ? decoded.substring(t + "/tree/".length()) : decoded;
    }

    /** Percent-decode %XX sequences (Uri-style; '+' is left alone). */
    public static String percentDecode(String s) {
        if (s == null || s.indexOf('%') < 0) return s;
        StringBuilder out = new StringBuilder(s.length());
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '%' && i + 2 < s.length()) {
                int hi = Character.digit(s.charAt(i + 1), 16);
                int lo = Character.digit(s.charAt(i + 2), 16);
                if (hi >= 0 && lo >= 0) {
                    out.append((char) ((hi << 4) + lo));
                    i += 2;
                    continue;
                }
            }
            out.append(c);
        }
        return out.toString();
    }

    /** Folder search rule: the (already normalized) query is a substring
     *  of the folder name OR of its matchable path (storage prefix
     *  trimmed — see trimStorageRoot / treePathForMatch). So "instagram"
     *  finds Instagram/1, Instagram/2..., and "instagram/2" works too.
     *  An empty query matches everything. */
    public static boolean folderMatches(String name, String matchPath, String query) {
        String q = normalize(query);
        if (q.isEmpty()) return true;
        if (name != null && name.toLowerCase(Locale.ROOT).contains(q)) return true;
        return matchPath != null && matchPath.toLowerCase(Locale.ROOT).contains(q);
    }

    /** In-folder photo search rule: the query is a substring of the
     *  image's file name. An empty query matches everything. */
    public static boolean imageMatches(String fileName, String query) {
        String q = normalize(query);
        if (q.isEmpty()) return true;
        return fileName != null && fileName.toLowerCase(Locale.ROOT).contains(q);
    }
}
