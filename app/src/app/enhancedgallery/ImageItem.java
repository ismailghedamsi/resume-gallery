package app.enhancedgallery;

import android.net.Uri;

public class ImageItem {
    /** MediaStore row id (> 0), or a process-unique negative id for images
     *  enumerated through the Storage Access Framework. */
    public long id;
    /** For MediaStore images: the file path. For SAF images: the document's
     *  content Uri as a string. */
    public String path;
    public String name;
    /** Content Uri that can always open this image (MediaStore or document). */
    public Uri uri;

    public ImageItem(long id, String path, String name) {
        this(id, path, name, null);
    }

    public ImageItem(long id, String path, String name, Uri uri) {
        this.id = id;
        this.path = path;
        this.name = name;
        this.uri = uri;
    }
}
