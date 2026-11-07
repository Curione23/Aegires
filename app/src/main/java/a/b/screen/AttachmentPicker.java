package a.b.screen;

import android.app.Activity;
import android.content.ActivityNotFoundException;
import android.content.ContentResolver;
import android.content.Intent;
import android.database.Cursor;
import android.media.ExifInterface;
import android.net.Uri;
import android.provider.OpenableColumns;
import java.io.InputStream;
import a.b.util.FileNames;

/** File/image/video picker + dimension probe. */
final class AttachmentPicker {
    private static final int PICK_IMAGE_REQUEST = 1001;

    private final MessageListScreen host;

    AttachmentPicker(MessageListScreen host) {
        this.host = host;
    }

    void pickImage() {
        if (host.activity == null) return;
        Intent intent = new Intent(Intent.ACTION_GET_CONTENT);
        intent.setType("*/*");
        intent.putExtra(Intent.EXTRA_MIME_TYPES, new String[]{"image/*", "video/*"});
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        try {
            host.activity.startActivityForResult(intent, PICK_IMAGE_REQUEST);
        } catch (ActivityNotFoundException e) {
            host.activity.toast("No file picker available");
        }
    }

    void onActivityResult(int requestCode, int resultCode, Intent data) {
        if (requestCode != PICK_IMAGE_REQUEST || resultCode != Activity.RESULT_OK || data == null || host.activity == null) return;
        Uri uri = data.getData();
        if (uri == null) return;

        ContentResolver cr = host.activity.getContentResolver();
        String mime = null;
        try { mime = cr.getType(uri); } catch (Exception ignored) {}

        String baseName;
        try {
            baseName = fileName(cr, uri, mime);
        } catch (Exception e) {
            baseName = defaultNameFor(mime);
        }
        String name = FileNames.randomFor(baseName, mime != null ? mime : "application/octet-stream");

        int w = 0, h = 0;
        if (mime != null && mime.startsWith("video/")) {
            android.media.MediaMetadataRetriever retriever = new android.media.MediaMetadataRetriever();
            try {
                retriever.setDataSource(host.activity, uri);
                String ws = retriever.extractMetadata(android.media.MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH);
                String hs = retriever.extractMetadata(android.media.MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT);
                String rotation = retriever.extractMetadata(android.media.MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION);
                if (ws != null && hs != null) {
                    int width = Integer.parseInt(ws);
                    int height = Integer.parseInt(hs);
                    if ("90".equals(rotation) || "270".equals(rotation)) { w = height; h = width; }
                    else { w = width; h = height; }
                }
            } catch (Exception ignored) {
            } finally {
                try { retriever.release(); } catch (Exception ignored) {}
            }
        } else {
            try (InputStream is = cr.openInputStream(uri)) {
                if (is != null) {
                    android.graphics.BitmapFactory.Options bounds = new android.graphics.BitmapFactory.Options();
                    bounds.inJustDecodeBounds = true;
                    android.graphics.BitmapFactory.decodeStream(is, null, bounds);
                    w = bounds.outWidth;
                    h = bounds.outHeight;
                }
            } catch (Exception ignored) {}
            int orientation = ExifInterface.ORIENTATION_NORMAL;
            try (InputStream is = cr.openInputStream(uri)) {
                if (is != null) {
                    ExifInterface exif = new ExifInterface(is);
                    orientation = exif.getAttributeInt(
                        ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL);
                }
            } catch (Exception ignored) {}
            if (orientation == ExifInterface.ORIENTATION_ROTATE_90
                || orientation == ExifInterface.ORIENTATION_ROTATE_270
                || orientation == ExifInterface.ORIENTATION_TRANSPOSE
                || orientation == ExifInterface.ORIENTATION_TRANSVERSE) {
                int tmp = w;
                w = h;
                h = tmp;
            }
        }

        MessageComposerView composer = host.composer();
        if (composer == null) return;
        composer.setAttachment(uri, name, mime != null ? mime : "application/octet-stream", w, h);
    }

    private static String fileName(ContentResolver cr, Uri uri, String mime) {
        try (Cursor c = cr.query(uri, null, null, null, null)) {
            if (c != null && c.moveToFirst()) {
                int col = c.getColumnIndex(OpenableColumns.DISPLAY_NAME);
                if (col >= 0 && !c.isNull(col)) {
                    String display = c.getString(col);
                    if (display != null && !display.isEmpty()) return display;
                }
            }
        }
        return defaultNameFor(mime);
    }

    private static String defaultNameFor(String mime) {
        if (mime == null) return "attachment";
        if (mime.startsWith("video/")) return "video.mp4";
        if (mime.startsWith("image/")) return "image.png";
        return "attachment";
    }
}