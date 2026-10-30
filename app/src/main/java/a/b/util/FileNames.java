package a.b.util;

import android.webkit.MimeTypeMap;
import java.security.SecureRandom;
import java.util.Locale;

public final class FileNames {
    private static final SecureRandom RANDOM = new SecureRandom();
    private static final char[] HEX = "0123456789abcdef".toCharArray();

    private FileNames() {}

    public static String randomFor(String originalName, String mime) {
        byte[] b = new byte[10];
        RANDOM.nextBytes(b);
        char[] c = new char[b.length * 2];
        for (int i = 0; i < b.length; i++) {
            c[i * 2] = HEX[(b[i] >> 4) & 0xF];
            c[i * 2 + 1] = HEX[b[i] & 0xF];
        }
        String ext = null;
        if (mime != null && !mime.isEmpty()) {
            ext = MimeTypeMap.getSingleton().getExtensionFromMimeType(mime.toLowerCase(Locale.US));
        }
        if (ext == null && originalName != null) {
            int dot = originalName.lastIndexOf('.');
            if (dot >= 0) ext = originalName.substring(dot + 1);
        }
        if (ext != null) {
            ext = ext.toLowerCase(Locale.US).replaceAll("[^a-z0-9]", "");
            if (ext.isEmpty() || ext.length() > 5) ext = null;
        }
        return new String(c) + (ext != null ? "." + ext : "");
    }
}
