package a.b.util;

import android.graphics.Bitmap;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;

/** Puts images into ImageViews; all fetching, caching and decoding lives in MediaCache. */
public final class ImageLoader {
    private static final int DEFAULT_TARGET = 256;

    private static final class Binding {
        final String url;
        MediaCache.Handle handle = MediaCache.NOOP;
        Binding(String url) { this.url = url; }
    }

    public void load(String url, ImageView imageView) { load(url, null, imageView); }

    public void loadNoCrop(String url, ImageView imageView) { load(url, null, imageView); }

    public void load(final String url, String fallbackUrl, final ImageView imageView) {
        if (imageView == null) return;
        cancel(imageView);
        if (url == null) {
            imageView.setTag(null);
            imageView.setImageDrawable(null);
            return;
        }
        final Binding binding = new Binding(url);
        imageView.setTag(binding);
        imageView.setImageDrawable(null);

        MediaCache.Spec spec = MediaCache.Spec.plain(url, resolveTarget(imageView, true), resolveTarget(imageView, false));
        spec.fallbackUrl = fallbackUrl;
        binding.handle = MediaCache.get(imageView.getContext()).loadBitmap(spec, new MediaCache.SimpleCallback() {
            @Override public void onBitmap(Bitmap bitmap, int srcW, int srcH) {
                if (imageView.getTag() == binding) imageView.setImageBitmap(bitmap);
            }
        });
    }

    public static void cancel(ImageView imageView) {
        Object tag = imageView.getTag();
        if (tag instanceof Binding) {
            ((Binding) tag).handle.cancel();
            imageView.setTag(null);
        }
    }

    public static void cancelTree(View root) {
        if (root == null) return;
        Object tag = root.getTag();
        if (tag instanceof MediaCache.Handle) {
            ((MediaCache.Handle) tag).cancel();
            root.setTag(null);
        } else if (root instanceof ImageView) {
            cancel((ImageView) root);
        }
        if (root instanceof ViewGroup) {
            ViewGroup g = (ViewGroup) root;
            for (int i = 0; i < g.getChildCount(); i++) cancelTree(g.getChildAt(i));
        }
    }

    public long cacheBytes() { return lastCache != null ? lastCache.memoryBytes() : 0; }
    public long cacheMax() { return lastCache != null ? lastCache.memoryMax() : 0; }

    private static volatile MediaCache lastCache;

    static void remember(MediaCache c) { lastCache = c; }

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
