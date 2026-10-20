package app.enhancedgallery;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Numeric-aware folder sorting: folders are ordered by the first number in
 *  their name ("0-5000" -> 0, "5001-10000" -> 5001). Folders without any
 *  number sort after numbered ones, alphabetically. */
public class SortUtil {
    private static final Pattern NUM = Pattern.compile("(\\d+)");

    public static long firstNumber(String name) {
        if (name == null) return Long.MAX_VALUE;
        Matcher m = NUM.matcher(name);
        if (m.find()) {
            try {
                return Long.parseLong(m.group(1));
            } catch (NumberFormatException e) {
                return Long.MAX_VALUE;
            }
        }
        return Long.MAX_VALUE;
    }

    public static int compareFolders(Folder a, Folder b) {
        long na = firstNumber(a.name), nb = firstNumber(b.name);
        if (na != nb) return na < nb ? -1 : 1;
        return compareNatural(a.name, b.name);
    }

    /** Natural order on folder paths RELATIVE to an added root: compare
     *  segment by segment with compareNatural; a shorter path (fewer
     *  segments) sorts before a longer one that starts with it. The root
     *  itself (empty relative path) therefore sorts first, then e.g.
     *  "1" < "2" < "2/x" < "10". */
    public static int compareRelativePath(String a, String b) {
        String[] sa = (a == null || a.isEmpty()) ? new String[0] : a.split("/");
        String[] sb = (b == null || b.isEmpty()) ? new String[0] : b.split("/");
        int n = Math.min(sa.length, sb.length);
        for (int i = 0; i < n; i++) {
            int c = compareNatural(sa[i], sb[i]);
            if (c != 0) return c;
        }
        if (sa.length != sb.length) return sa.length < sb.length ? -1 : 1;
        return 0;
    }

    /** Natural (numeric-aware) string order: compare chunk by chunk; runs of
     *  digits compare by their numeric value, non-digit chunks compare
     *  case-insensitively. So "1.jpg" < "2.jpg" < "10.jpg" and "a2" < "a10",
     *  unlike plain text order (where "10" sorts before "2"). Digit runs are
     *  compared after stripping leading zeros — first by length, then
     *  lexically — so arbitrarily long numbers never overflow. */
    public static int compareNatural(String a, String b) {
        if (a == null) a = "";
        if (b == null) b = "";
        int ia = 0, ib = 0;
        int tiebreak = 0; // applied only if everything else compares equal
        while (ia < a.length() && ib < b.length()) {
            char ca = a.charAt(ia), cb = b.charAt(ib);
            if (Character.isDigit(ca) && Character.isDigit(cb)) {
                int ea = ia, eb = ib;
                while (ea < a.length() && Character.isDigit(a.charAt(ea))) ea++;
                while (eb < b.length() && Character.isDigit(b.charAt(eb))) eb++;
                // strip leading zeros for the numeric comparison
                int sa = ia, sb = ib;
                while (sa < ea - 1 && a.charAt(sa) == '0') sa++;
                while (sb < eb - 1 && b.charAt(sb) == '0') sb++;
                int na = ea - sa, nb = eb - sb;
                if (na != nb) return na < nb ? -1 : 1; // longer run = bigger number
                for (int k = 0; k < na; k++) {
                    char da = a.charAt(sa + k), db = b.charAt(sb + k);
                    if (da != db) return da < db ? -1 : 1;
                }
                // numerically equal: prefer the run with fewer leading zeros,
                // but only as a last-resort tiebreak
                if (tiebreak == 0 && (ea - ia) != (eb - ib)) {
                    tiebreak = (ea - ia) < (eb - ib) ? -1 : 1;
                }
                ia = ea;
                ib = eb;
            } else {
                char la = Character.toLowerCase(ca), lb = Character.toLowerCase(cb);
                if (la != lb) return la < lb ? -1 : 1;
                ia++;
                ib++;
            }
        }
        if (ia < a.length()) return 1;  // b is a prefix of a -> b first
        if (ib < b.length()) return -1; // a is a prefix of b -> a first
        return tiebreak;
    }
}
