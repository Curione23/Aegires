package a.b.util;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.util.LruCache;
import android.view.ViewGroup;
import android.widget.ImageView;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.URL;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public final class ImageLoader {
    private static final int CACHE_BYTES = computeCacheBytes();
    private static final int DEFAULT_TARGET = 256;
    private static final int MIN_HEAP_CACHE = 8 * 1024 * 1024;
    private static final int MAX_HEAP_CACHE = 64 * 1024 * 1024;
    private static final int COPY_BUF = 8192;

    private static int computeCacheBytes() {
        long target = Runtime.getRuntime().maxMemory() / 6;
        return (int) Math.max(MIN_HEAP_CACHE, Math.min(MAX_HEAP_CACHE, target));
    }

    private final LruCache<String, Bitmap> cache = new LruCache<String, Bitmap>(CACHE_BYTES) {
        @Override protected int sizeOf(String key, Bitmap value) { return value.getByteCount(); }
    };
    
    private final ExecutorService executor = Executors.newFixedThreadPool(4);

    public void load(String url, ImageView imageView) { enqueue(url, imageView); }
    public void loadNoCrop(String url, ImageView imageView) { enqueue(url, imageView); }
    public long cacheBytes() { return cache.size(); }
    public long cacheMax() { return CACHE_BYTES; }

    private void enqueue(final String url, final ImageView imageView) {
        if (url == null || imageView == null) return;
        imageView.setTag(url);
        Bitmap cached = cache.get(url);
        if (cached != null) {
            imageView.setImageBitmap(cached);
            return;
        }
        final int targetW = resolveTarget(imageView, true);
        final int targetH = resolveTarget(imageView, false);
        executor.submit(() -> {
            Bitmap bitmap = fetchAndDecode(url, targetW, targetH);
            if (bitmap == null) return;
            cache.put(url, bitmap);
            postIfStillBound(url, imageView, bitmap);
        });
    }

    private static Bitmap fetchAndDecode(String url, int targetW, int targetH) {
        try (InputStream is = new URL(url).openStream()) {
            byte[] data = readAll(is);
            return decodeSampled(data, targetW, targetH);
        } catch (Exception e) {
            return null;
        }
    }

    private static byte[] readAll(InputStream is) throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream(16 * 1024);
        byte[] buf = new byte[COPY_BUF];
        int n;
        while ((n = is.read(buf)) != -1) out.write(buf, 0, n);
        return out.toByteArray();
    }

    private static Bitmap decodeSampled(byte[] data, int targetW, int targetH) {
        BitmapFactory.Options bounds = new BitmapFactory.Options();
        bounds.inJustDecodeBounds = true;
        BitmapFactory.decodeByteArray(data, 0, data.length, bounds);
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null;
        BitmapFactory.Options opts = new BitmapFactory.Options();
        opts.inSampleSize = computeSampleSize(bounds.outWidth, bounds.outHeight, targetW, targetH);
        return BitmapFactory.decodeByteArray(data, 0, data.length, opts);
    }

    private static void postIfStillBound(final String url, final ImageView imageView, final Bitmap bitmap) {
        if (!url.equals(imageView.getTag())) return;
        imageView.post(() -> {
            if (url.equals(imageView.getTag())) imageView.setImageBitmap(bitmap);
        });
    }

    private static int computeSampleSize(int inW, int inH, int reqW, int reqH) {
        if (inW <= 0 || inH <= 0 || reqW <= 0 || reqH <= 0) return 1;
        if (inW <= reqW && inH <= reqH) return 1;
        int sample = 1;
        int halfW = inW / 2;
        int halfH = inH / 2;
        while (halfW / sample >= reqW && halfH / sample >= reqH) sample *= 2;
        return sample;
    }

    private static int resolveTarget(ImageView imageView, boolean horizontal) {
        int laidOut = horizontal ? imageView.getWidth() : imageView.getHeight();
        if (laidOut > 0) return laidOut;
        ViewGroup.LayoutParams lp = imageView.getLayoutParams();
        if (lp != null) {
            int declared = horizontal ? lp.width : lp.height;
            if (declared > 0) return declared;
        }
        return DEFAULT_TARGET;
    }
}