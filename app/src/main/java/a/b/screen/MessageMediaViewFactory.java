package a.b.screen;

import android.graphics.Color;
import android.util.LruCache;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;

import a.b.model.Message;

import java.util.concurrent.ConcurrentHashMap;

/** Media thumbnails, sizing, and full-screen WebView. */
final class MessageMediaViewFactory {
    static final LruCache<String, int[]> mediaSizeCache = new LruCache<>(200);
    static final ConcurrentHashMap<String, int[]> messageDimensionCache = new ConcurrentHashMap<>();

    private final MessageAdapter adapter;

    MessageMediaViewFactory(MessageAdapter adapter) {
        this.adapter = adapter;
    }

    int[] size(ViewGroup parent, Message msg, int w, int h, boolean fill) {
        if (msg != null && msg.id != null && (w <= 0 || h <= 0)) {
            int[] dim = messageDimensionCache.get(msg.id);
            if (dim != null) {
                w = dim[0];
                h = dim[1];
            }
        }
        int pw = parent != null ? parent.getWidth() : 0;
        if (pw <= 0 && adapter.activity != null)
            pw = adapter.activity.getResources().getDisplayMetrics().widthPixels;

        int avail = Math.max(pw > 0 ? pw - adapter.dp(112) : adapter.dp(248), adapter.dp(120));
        int maxH = adapter.dp(400);
        boolean known = w > 0 && h > 0;
        String cacheKey = w + "_" + h + "_" + fill + "_" + avail;
        int[] cached = mediaSizeCache.get(cacheKey);
        if (cached != null) return cached;

        int[] result;
        if (fill) {
            if (!known) {
                w = 16;
                h = 9;
            }
            result =
                    new int[] {
                        avail,
                        Math.max(adapter.dp(80), Math.min(maxH, (int) ((long) avail * h / w)))
                    };
        } else if (!known) {
            result = new int[] {avail, Math.min(avail * 9 / 16, maxH)};
        } else {
            float scale = Math.min((float) avail / w, (float) maxH / h);
            if (scale > 2f) scale = 2f;
            result =
                    new int[] {
                        Math.max(1, Math.round(w * scale)), Math.max(1, Math.round(h * scale))
                    };
        }
        mediaSizeCache.put(cacheKey, result);
        return result;
    }

    View media(String preview, boolean crop, String openUrl, boolean video, int[] size) {
        if (adapter.activity == null || size == null || size.length < 2) return null;
        boolean show = preview != null && adapter.imageLoader != null;
        FrameLayout frame = new FrameLayout(adapter.activity);
        frame.setLayoutParams(lp(size[0], size[1], 0, adapter.dp(4), 0, adapter.dp(4)));
        frame.setBackgroundColor(show ? Color.BLACK : MessageAdapter.EMBED_BG);
        if (show) {
            ImageView iv = new ImageView(adapter.activity);
            iv.setScaleType(
                    crop ? ImageView.ScaleType.CENTER_CROP : ImageView.ScaleType.FIT_CENTER);
            adapter.imageLoader.load(preview, video ? null : openUrl, iv);
            frame.addView(
                    iv, new FrameLayout.LayoutParams(MessageAdapter.MATCH, MessageAdapter.MATCH));
        } else if (preview != null) {
            frame.addView(
                    overlay("\uD83D\uDDBC", 0xAAFFFFFF, 14),
                    new FrameLayout.LayoutParams(
                            MessageAdapter.WRAP, MessageAdapter.WRAP, Gravity.CENTER));
        }
        if (video) {
            frame.addView(
                    overlay("\u25B6", Color.WHITE, 30),
                    new FrameLayout.LayoutParams(
                            MessageAdapter.WRAP, MessageAdapter.WRAP, Gravity.CENTER));
        }
        frame.setOnClickListener(v -> showFullscreen(openUrl, video));
        return frame;
    }

    void showFullscreen(String url, boolean video) {
        if (adapter.activity == null || !isHttpsUrl(url)) return;
        adapter.hideKeyboard();
        WebView web = new WebView(adapter.activity);
        WebSettings settings = web.getSettings();
        settings.setJavaScriptEnabled(false);
        settings.setAllowFileAccess(false);
        settings.setAllowContentAccess(false);
        settings.setMediaPlaybackRequiresUserGesture(false);
        String src = escapeHtmlAttribute(url);
        String media =
                video
                        ? "<video controls autoplay playsinline style='width:100%'><source src=\""
                                + src
                                + "\" type=\"video/mp4\"></video>"
                        : "<img src=\"" + src + "\" style='max-width:100%'>";
        web.loadDataWithBaseURL(
                null,
                "<html><body style='margin:0;height:100vh;background:#000;display:flex;"
                        + "justify-content:center;align-items:center'>"
                        + media
                        + "</body></html>",
                "text/html",
                "UTF-8",
                null);
        adapter.showFullscreen(web);
    }

    static boolean isHttpsUrl(String value) {
        if (!isReal(value)) return false;
        try {
            java.net.URL url = new java.net.URL(value);
            return "https".equalsIgnoreCase(url.getProtocol())
                    && url.getHost() != null
                    && !url.getHost().isEmpty();
        } catch (Exception ignored) {
            return false;
        }
    }

    static String escapeHtmlAttribute(String value) {
        return value.replace("&", "&amp;")
                .replace("\"", "&quot;")
                .replace("<", "&lt;")
                .replace(">", "&gt;")
                .replace("'", "&#39;");
    }

    private android.widget.TextView overlay(CharSequence text, int color, float sp) {
        android.widget.TextView view = new android.widget.TextView(adapter.activity);
        view.setText(text);
        view.setTextColor(color);
        view.setTextSize(sp);
        return view;
    }

    private static LinearLayout.LayoutParams lp(int w, int h, int l, int t, int r, int b) {
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(w, h);
        p.setMargins(l, t, r, b);
        return p;
    }

    private static boolean isReal(String s) {
        return s != null && !s.isEmpty() && !"null".equals(s);
    }
}
