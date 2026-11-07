package a.b.screen;

import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;
import java.util.Locale;
import a.b.model.Message;
import a.b.util.MediaCache;

/** Builds a view for a single message attachment (image, video, file, encrypted). */
final class AttachmentViewFactory {
    private final MessageAdapter adapter;

    AttachmentViewFactory(MessageAdapter adapter) {
        this.adapter = adapter;
    }

    View build(Message.Attachment a, Message msg, ViewGroup parent) {
        if (a == null || adapter.activity == null) return null;
        if (isEncrypted(a)) {
            if (adapter.key() == null) {
                TextView t = text("\uD83D\uDD12 " + displayName(a) + " (encrypted - key missing)", MessageAdapter.MUTED, 14);
                t.setPadding(adapter.dp(4), adapter.dp(4), adapter.dp(4), adapter.dp(4));
                return t;
            }
            return new EncryptedMediaSlot(adapter, a, msg, parent, isVideoFilename(a.filename)).begin();
        }
        boolean image = a.type != null && a.type.startsWith("image");
        boolean video = a.type != null && a.type.startsWith("video");
        if (!image && !video) {
            TextView t = text("\uD83D\uDCCE " + displayName(a), MessageAdapter.LINK_FG, 14);
            t.setPadding(adapter.dp(4), adapter.dp(4), adapter.dp(4), adapter.dp(4));
            return t;
        }
        int[] sz = adapter.mediaFactory.size(parent, msg, a.width, a.height, false);
        String preview;
        if (image) {
            preview = MediaCache.previewUrl(a.url, a.proxyUrl, sz[0], sz[1]);
        } else if (video && isCdnPreviewable(a)) {
            preview = MediaCache.previewUrl(a.url, a.proxyUrl, sz[0], sz[1]);
        } else {
            preview = null;
        }
        return adapter.mediaFactory.media(preview, false, a.url, video, sz);
    }

    private static boolean isCdnPreviewable(Message.Attachment a) {
        String base = (a.proxyUrl != null && !a.proxyUrl.isEmpty() && !"null".equals(a.proxyUrl)) ? a.proxyUrl : a.url;
        if (base == null) return false;
        String lower = base.toLowerCase(Locale.US);
        return lower.startsWith("https://cdn.discordapp.com/") || lower.startsWith("https://media.discordapp.net/");
    }

    private static String displayName(Message.Attachment a) {
        return a.filename != null && !a.filename.isEmpty() ? a.filename : "attachment";
    }

    static boolean isEncrypted(Message.Attachment a) {
        return a != null && a.filename != null && (a.filename.contains(".enc.") || a.filename.endsWith(".enc"));
    }

    static boolean isVideoFilename(String name) {
        if (name == null) return false;
        String n = name.toLowerCase(Locale.US);
        return n.endsWith(".mp4") || n.endsWith(".mov") || n.endsWith(".webm") || n.endsWith(".m4v") || n.endsWith(".3gp");
    }

    private TextView text(CharSequence s, int color, float sp) {
        TextView t = new TextView(adapter.activity);
        t.setText(s);
        t.setTextColor(color);
        t.setTextSize(sp);
        return t;
    }
}