package a.b.screen;

import android.graphics.Color;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.TextView;
import a.b.model.Message;
import a.b.util.MediaCache;

/** Builds a view for a single message attachment (image, video, file, encrypted). */
final class AttachmentViewFactory {
    private final MessageAdapter adapter;

    AttachmentViewFactory(MessageAdapter adapter) {
        this.adapter = adapter;
    }

    View build(Message.Attachment a, Message msg, ViewGroup parent) {
        if (isEncrypted(a)) {
            if (adapter.activity == null || adapter.key() == null) {
                TextView t = text("\uD83D\uDD12 " + a.filename + " (encrypted - key missing)", MessageAdapter.MUTED, 14);
                t.setPadding(adapter.dp(4), adapter.dp(4), adapter.dp(4), adapter.dp(4));
                return t;
            }
            return new EncryptedMediaSlot(adapter, a, msg, parent, isVideoFilename(a.filename)).begin();
        }
        boolean image = a.type != null && a.type.startsWith("image");
        boolean video = a.type != null && a.type.startsWith("video");
        if (!image && !video) {
            TextView t = text("\uD83D\uDCCE " + a.filename, MessageAdapter.LINK_FG, 14);
            t.setPadding(adapter.dp(4), adapter.dp(4), adapter.dp(4), adapter.dp(4));
            return t;
        }
        int[] sz = adapter.mediaFactory.size(parent, msg, a.width, a.height, false);
        return adapter.mediaFactory.media(image ? MediaCache.previewUrl(a.url, a.proxyUrl, sz[0], sz[1]) : null,
            false, a.url, video, sz);
    }

    static boolean isEncrypted(Message.Attachment a) {
        return a != null && a.filename != null && (a.filename.contains(".enc.") || a.filename.endsWith(".enc"));
    }

    static boolean isVideoFilename(String name) {
        if (name == null) return false;
        String n = name.toLowerCase();
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