package app.enhancedgallery;

public class Folder {
    public String name;
    /** Filesystem path of the folder, or for a tree-only added root a
     *  "tree:<treeUri>" marker (a subdirectory of the root appends the
     *  directory's document id). Still unique per folder, still usable
     *  as an id. */
    public String path;
    public int count;
    public long thumbId;
    /** Representative image path for fast fallback thumbnail decoding. */
    public String thumbPath;
    /** The system folder-selector tree Uri this folder was added with
     *  (persistable grant held); null/empty for MediaStore-only folders. */
    public String treeUri;

    public Folder(String name, String path) {
        this.name = name;
        this.path = path;
    }
}
