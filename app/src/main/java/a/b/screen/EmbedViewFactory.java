package a.b.screen;

import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.TextView;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import a.b.model.Embed;
import a.b.model.EmbedField;
import a.b.model.Message;
import a.b.util.MediaCache;
import a.b.util.Ui;

/** Builds Discord-style embed cards. */
final class EmbedViewFactory {
    private static final Pattern ATTACHMENT_ID = Pattern.compile("/attachments/[^/]+/([^/]+)/");

    private final MessageAdapter adapter;

    EmbedViewFactory(MessageAdapter adapter) {
        this.adapter = adapter;
    }

    View build(Embed e, Message msg, ViewGroup parent) {
        boolean title = isReal(e.title), desc = isReal(e.description);
        boolean fields = e.fields != null && !e.fields.isEmpty();
        boolean footer = isReal(e.footer);
        if (!title && !desc && !fields && !footer) return embedMedia(e, parent);
        LinearLayout box = linear(LinearLayout.VERTICAL);
        box.setPadding(adapter.dp(12), adapter.dp(8), adapter.dp(12), adapter.dp(8));
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(MessageAdapter.EMBED_BG);
        bg.setCornerRadius(adapter.dp(4));
        box.setBackground(bg);
        box.setClipToOutline(true);
        if (title) {
            TextView t = text(adapter.renderer.render(e.title, msg, false), MessageAdapter.LINK_FG, 14);
            t.setTypeface(null, Typeface.BOLD);
            box.addView(t);
        }
        if (desc) {
            TextView d = text(adapter.renderer.render(e.description, msg), MessageAdapter.TEXT, 14);
            if (title) d.setPadding(0, adapter.dp(4), 0, 0);
            box.addView(d);
        }
        if (fields) {
            for (EmbedField f : e.fields) {
                if (f == null) continue;
                TextView name = text(f.name, 0xFFB9BBBE, 13);
                name.setTypeface(null, Typeface.BOLD);
                name.setPadding(0, adapter.dp(8), 0, 0);
                box.addView(name);
                box.addView(text(adapter.renderer.render(f.value, msg), MessageAdapter.TEXT, 13));
            }
        }
        View media = embedMedia(e, parent);
        if (media != null) box.addView(media);
        if (footer) {
            TextView f = text(e.footer, MessageAdapter.MUTED, 12);
            f.setPadding(0, adapter.dp(6), 0, 0);
            box.addView(f);
        }
        View bar = new View(adapter.activity);
        bar.setBackgroundColor(e.color | 0xFF000000);
        LinearLayout wrapper = linear(LinearLayout.HORIZONTAL);
        wrapper.setPadding(0, adapter.dp(4), 0, adapter.dp(4));
        wrapper.addView(bar, lp(adapter.dp(4), -1, 0, 0, adapter.dp(12), 0));
        wrapper.addView(box, new LinearLayout.LayoutParams(0, -2, 1f));
        return wrapper;
    }

    private View embedMedia(Embed e, ViewGroup parent) {
        boolean video = isReal(e.videoUrl);
        String preview;
        int w, h;
        if (video) {
            boolean own = e.videoWidth > 0 && e.videoHeight > 0;
            w = own ? e.videoWidth : e.thumbnailWidth;
            h = own ? e.videoHeight : e.thumbnailHeight;
            preview = isReal(e.thumbnailUrl) ? e.thumbnailUrl : isReal(e.imageUrl) ? e.imageUrl : null;
        } else if (isReal(e.imageUrl)) {
            preview = e.imageUrl;
            w = e.imageWidth;
            h = e.imageHeight;
        } else if (isReal(e.thumbnailUrl)) {
            preview = e.thumbnailUrl;
            w = e.thumbnailWidth;
            h = e.thumbnailHeight;
        } else {
            return null;
        }
        int[] sz = adapter.mediaFactory.size(parent, null, w, h, video);
        return adapter.mediaFactory.media(MediaCache.previewUrl(preview, null, sz[0], sz[1]),
            video, video ? e.videoUrl : preview, video, sz);
    }

    static boolean duplicatesAttachment(Embed e, List<Message.Attachment> atts) {
        if (atts == null || atts.isEmpty()) return false;
        Set<String> ids = new HashSet<>();
        for (Message.Attachment a : atts) {
            if (a == null) continue;
            ids.add(attachmentId(a.url));
            ids.add(attachmentId(a.proxyUrl));
        }
        return refs(e.thumbnailUrl, e.thumbnailProxyUrl, ids)
            || refs(e.imageUrl, e.imageProxyUrl, ids)
            || refs(e.videoUrl, e.videoProxyUrl, ids);
    }

    private static boolean refs(String url, String proxyUrl, Set<String> ids) {
        if (url != null && url.startsWith("attachment://")) return true;
        String a = attachmentId(url), b = attachmentId(proxyUrl);
        return (a != null && ids.contains(a)) || (b != null && ids.contains(b));
    }

    private static String attachmentId(String url) {
        if (url == null) return null;
        Matcher m = ATTACHMENT_ID.matcher(url);
        return m.find() ? m.group(1) : null;
    }

    private LinearLayout linear(int orientation) {
        LinearLayout l = new LinearLayout(adapter.activity);
        l.setOrientation(orientation);
        return l;
    }

    private TextView text(CharSequence s, int color, float sp) {
        TextView t = new TextView(adapter.activity);
        t.setText(s);
        t.setTextColor(color);
        t.setTextSize(sp);
        t.setLinkTextColor(MessageAdapter.LINK_FG);
        t.setMovementMethod(android.text.method.LinkMovementMethod.getInstance());
        return t;
    }

    private static LinearLayout.LayoutParams lp(int w, int h, int l, int t, int r, int b) {
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(w, h);
        p.setMargins(l, t, r, b);
        return p;
    }

    private static boolean isReal(String s) { return s != null && !s.isEmpty() && !"null".equals(s); }
}