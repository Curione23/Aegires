package a.b.screen;

import android.graphics.Bitmap;
import android.graphics.Color;
import android.net.Uri;
import android.view.Gravity;
import android.view.View;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.TextView;

import a.b.model.Message;
import a.b.util.MediaCache;

import java.io.File;
import java.util.Collections;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/** Encrypted attachment tile: consent, thumbnail, progress, full-screen. */
final class EncryptedMediaSlot {
    private static final int MATCH = -1, WRAP = -2;
    private static final int LOADING = 0,
            CONFIRM = 1,
            FAILED = 2,
            LOADED = 3,
            TOO_LARGE = 4,
            OPENING = 5;
    private static final int EMBED_BG = MessageAdapter.EMBED_BG;
    private static final int MUTED = MessageAdapter.MUTED;
    private static final int APPROVED_MAX = 500;

    private static final Set<String> approvedMedia =
            Collections.newSetFromMap(
                    new java.util.LinkedHashMap<String, Boolean>(16, 0.75f, true) {
                        @Override
                        protected boolean removeEldestEntry(Map.Entry<String, Boolean> eldest) {
                            return size() > APPROVED_MAX;
                        }
                    });

    private final MessageAdapter adapter;
    private final Message.Attachment a;
    private final Message msg;
    private final boolean video;
    private final FrameLayout frame;
    private final ImageView iv;
    private final TextView status, playBtn;
    private final int[] size;
    private MediaCache.Handle loadHandle = MediaCache.NOOP, openHandle = MediaCache.NOOP;
    private int state = LOADING;
    private final String consentKey;

    EncryptedMediaSlot(
            MessageAdapter adapter,
            Message.Attachment a,
            Message msg,
            android.view.ViewGroup parent,
            boolean video) {
        this.adapter = adapter;
        this.a = a;
        this.msg = msg;
        this.video = video;
        String base = a.url == null ? "" : a.url;
        int q = base.indexOf('?');
        consentKey = q >= 0 ? base.substring(0, q) : base;
        int w = a.width, h = a.height;
        if (msg != null && msg.id != null) {
            int[] dim = MessageMediaViewFactory.messageDimensionCache.get(msg.id);
            if (dim != null) {
                w = dim[0];
                h = dim[1];
            }
        }
        size = adapter.mediaFactory.size(parent, msg, w, h, video);
        frame = new FrameLayout(adapter.activity);
        frame.setLayoutParams(lp(size[0], size[1], 0, adapter.dp(4), 0, adapter.dp(4)));
        frame.setBackgroundColor(EMBED_BG);

        iv = new ImageView(adapter.activity);
        iv.setScaleType(video ? ImageView.ScaleType.CENTER_CROP : ImageView.ScaleType.FIT_CENTER);
        frame.addView(iv, new FrameLayout.LayoutParams(MATCH, MATCH));

        status = new TextView(adapter.activity);
        status.setText("");
        status.setTextColor(MUTED);
        status.setTextSize(12);
        status.setGravity(Gravity.CENTER);
        status.setPadding(adapter.dp(12), 0, adapter.dp(12), 0);
        frame.addView(status, new FrameLayout.LayoutParams(MATCH, MATCH, Gravity.CENTER));

        playBtn = new TextView(adapter.activity);
        playBtn.setText("\u25B6");
        playBtn.setTextColor(Color.WHITE);
        playBtn.setTextSize(30);
        playBtn.setVisibility(View.GONE);
        frame.addView(playBtn, new FrameLayout.LayoutParams(WRAP, WRAP, Gravity.CENTER));

        frame.setOnClickListener(v -> onClick());
        frame.setTag(
                (MediaCache.Handle)
                        () -> {
                            loadHandle.cancel();
                            openHandle.cancel();
                        });
    }

    View begin() {
        start(approvedMedia.contains(consentKey));
        return frame;
    }

    private MediaCache.Spec spec(boolean force) {
        int tw = Math.max(size[0], adapter.dp(120));
        int th = Math.max(size[1], adapter.dp(120));
        String sender = msg != null && msg.author != null ? msg.author.id : null;
        MediaCache.Spec s =
                video
                        ? MediaCache.Spec.encryptedVideo(
                                a.url, adapter.key(), adapter.channelId, sender, a.size, tw)
                        : MediaCache.Spec.encryptedImage(
                                a.url, adapter.key(), adapter.channelId, sender, a.size, tw, th);
        s.force = force;
        return s;
    }

    private void start(boolean force) {
        if (adapter.activity == null) return;
        loadHandle.cancel();
        state = LOADING;
        status.setVisibility(View.VISIBLE);
        status.setText(force ? "\uD83D\uDD12 Downloading\u2026" : "\uD83D\uDD12 Loading\u2026");
        MediaCache cache = MediaCache.get(adapter.activity.getApplicationContext());
        MediaCache.Callback cb =
                new MediaCache.Callback() {
                    @Override
                    public void onProgress(long done, long total) {
                        if (state != LOADING) return;
                        status.setText(
                                "\uD83D\uDD12 Downloading\u2026 "
                                        + (total > 0
                                                ? (int) (done * 100 / total) + "%"
                                                : fmtBytes(done)));
                    }

                    @Override
                    public void onNeedsConfirm(long bytes) {
                        state = CONFIRM;
                        status.setText(
                                "\uD83D\uDD12 "
                                        + (video ? "Video" : "Image")
                                        + (bytes > 0 ? " \u00B7 " + fmtBytes(bytes) : "")
                                        + "\nTap to download");
                    }

                    @Override
                    public void onBitmap(Bitmap bitmap, int w, int h) {
                        state = LOADED;
                        iv.setImageBitmap(bitmap);
                        status.setVisibility(View.GONE);
                        if (video) playBtn.setVisibility(View.VISIBLE);
                    }

                    @Override
                    public void onFailure(MediaCache.Failure f) {
                        fail(f);
                    }
                };
        loadHandle =
                video ? cache.loadVideoThumb(spec(force), cb) : cache.loadBitmap(spec(force), cb);
    }

    private void fail(MediaCache.Failure f) {
        state = FAILED;
        status.setVisibility(View.VISIBLE);
        switch (f.kind) {
            case DECRYPT:
                status.setText("\u26A0 Can't decrypt (wrong key?)\nTap to retry");
                break;
            case DECODE:
                state = TOO_LARGE;
                status.setText("\u26A0 Can't display this " + (video ? "video" : "image"));
                break;
            case TOO_LARGE:
                state = TOO_LARGE;
                status.setText("\u26A0 Too large (" + fmtBytes(f.bytes) + ")");
                break;
            case HTTP:
                status.setText(
                        "\u26A0 "
                                + (f.httpCode == 403 || f.httpCode == 404
                                        ? "Expired or deleted"
                                        : "Download failed")
                                + "\nTap to retry");
                break;
            default:
                status.setText("\u26A0 Download failed\nTap to retry");
        }
    }

    private void onClick() {
        if (state == LOADED) openFull();
        else if (state == CONFIRM || state == FAILED) {
            if (!consentKey.isEmpty()) approvedMedia.add(consentKey);
            start(true);
        }
        // LOADING / OPENING / TOO_LARGE: no-op.
    }

    private void openFull() {
        if (adapter.activity == null) return;
        state = OPENING;
        status.setVisibility(View.VISIBLE);
        status.setText("\uD83D\uDD12 Preparing\u2026");
        openHandle.cancel();
        openHandle =
                MediaCache.get(adapter.activity.getApplicationContext())
                        .decryptToTemp(
                                spec(true),
                                new MediaCache.TempCallback() {
                                    @Override
                                    public void onProgress(long done, long total) {}

                                    @Override
                                    public void onNeedsConfirm(long bytes) {}

                                    @Override
                                    public void onFile(File plaintext) {
                                        state = LOADED;
                                        status.setVisibility(View.GONE);
                                        if (video) showFullscreenVideo(plaintext);
                                        else showFullscreenFile(plaintext, a.filename);
                                    }

                                    @Override
                                    public void onFailure(MediaCache.Failure f) {
                                        state = LOADED;
                                        status.setText("\u26A0 Couldn't open");
                                    }
                                });
    }

    private void showFullscreenVideo(File videoFile) {
        if (adapter.activity == null || videoFile == null || !videoFile.exists()) {
            MediaCache.deleteTemp(videoFile);
            return;
        }
        adapter.hideKeyboard();
        WebView web = new WebView(adapter.activity);
        deleteWhenClosed(web, videoFile);
        WebSettings s = web.getSettings();
        s.setJavaScriptEnabled(true);
        s.setAllowFileAccess(true);
        s.setAllowFileAccessFromFileURLs(true);
        s.setAllowUniversalAccessFromFileURLs(true);
        s.setMediaPlaybackRequiresUserGesture(false);

        String fileUri = Uri.fromFile(videoFile).toString();
        String media =
                "<video controls autoplay playsinline style='width:100%;max-height:100vh'><source src=\""
                        + fileUri
                        + "\" type=\"video/mp4\"></video>";
        web.loadDataWithBaseURL(
                "file:///",
                "<html><body style='margin:0;height:100vh;background:#000;display:flex;"
                        + "justify-content:center;align-items:center'>"
                        + media
                        + "</body></html>",
                "text/html",
                "UTF-8",
                null);
        adapter.showFullscreen(web);
    }

    private void showFullscreenFile(File file, String filename) {
        if (adapter.activity == null || file == null || !file.exists()) {
            MediaCache.deleteTemp(file);
            return;
        }
        adapter.hideKeyboard();
        WebView web = new WebView(adapter.activity);
        deleteWhenClosed(web, file);
        WebSettings s = web.getSettings();
        s.setJavaScriptEnabled(true);
        s.setAllowFileAccess(true);
        s.setAllowFileAccessFromFileURLs(true);
        s.setAllowUniversalAccessFromFileURLs(true);
        String fileUri = Uri.fromFile(file).toString();
        String media =
                "<img src=\""
                        + fileUri
                        + "\" style='max-width:100%;max-height:100vh;object-fit:contain'>";
        web.loadDataWithBaseURL(
                "file:///",
                "<html><body style='margin:0;height:100vh;background:#000;display:flex;"
                        + "justify-content:center;align-items:center'>"
                        + media
                        + "</body></html>",
                "text/html",
                "UTF-8",
                null);
        adapter.showFullscreen(web);
    }

    private static void deleteWhenClosed(WebView web, final File file) {
        web.addOnAttachStateChangeListener(
                new View.OnAttachStateChangeListener() {
                    @Override
                    public void onViewAttachedToWindow(View v) {}

                    @Override
                    public void onViewDetachedFromWindow(View v) {
                        MediaCache.deleteTemp(file);
                    }
                });
    }

    private static String fmtBytes(long b) {
        if (b < 1024) return b + " B";
        if (b < 1024 * 1024) return String.format(Locale.US, "%.0f KB", b / 1024.0);
        return String.format(Locale.US, "%.1f MB", b / (1024.0 * 1024.0));
    }

    private static android.widget.LinearLayout.LayoutParams lp(
            int w, int h, int l, int t, int r, int b) {
        android.widget.LinearLayout.LayoutParams p =
                new android.widget.LinearLayout.LayoutParams(w, h);
        p.setMargins(l, t, r, b);
        return p;
    }
}
