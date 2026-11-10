package a.b.screen;

import android.graphics.Bitmap;
import android.util.LruCache;

import a.b.util.MediaCache;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/** Caches custom emoji bitmaps; lazy-loads missing ones and re-renders affected messages. */
final class EmojiRegistry {
    private final MessageAdapter adapter;
    private final LruCache<String, Bitmap> cache = new LruCache<>(100);
    private final ConcurrentHashMap<String, Set<String>> messageMap = new ConcurrentHashMap<>();
    private final Set<String> loading = Collections.synchronizedSet(new HashSet<>());

    EmojiRegistry(MessageAdapter adapter) {
        this.adapter = adapter;
    }

    Bitmap get(String id) {
        return cache.get(id);
    }

    int size() {
        return cache.size();
    }

    void track(String emojiId, String msgId) {
        if (msgId == null) return;
        Set<String> waiting = messageMap.get(emojiId);
        if (waiting == null) {
            Set<String> fresh = Collections.synchronizedSet(new HashSet<>());
            waiting = messageMap.putIfAbsent(emojiId, fresh);
            if (waiting == null) waiting = fresh;
        }
        waiting.add(msgId);
    }

    void load(final String id, String url) {
        if (adapter.activity == null || !loading.add(id)) return;
        MediaCache.Spec spec = MediaCache.Spec.plain(url, adapter.jumboSize, adapter.jumboSize);
        spec.maxBytes = 4L << 20;
        MediaCache.get(adapter.activity.getApplicationContext())
                .loadBitmap(
                        spec,
                        new MediaCache.SimpleCallback() {
                            @Override
                            public void onBitmap(Bitmap bmp, int srcW, int srcH) {
                                loading.remove(id);
                                if (adapter.activity == null) return;
                                Bitmap scaled = bmp;
                                if (bmp.getWidth() != adapter.jumboSize
                                        || bmp.getHeight() != adapter.jumboSize) {
                                    try {
                                        scaled =
                                                Bitmap.createScaledBitmap(
                                                        bmp,
                                                        adapter.jumboSize,
                                                        adapter.jumboSize,
                                                        true);
                                    } catch (OutOfMemoryError | IllegalArgumentException ignored) {
                                        scaled = bmp;
                                    }
                                }
                                cache.put(id, scaled);
                                Set<String> affected = messageMap.remove(id);
                                if (affected == null) return;
                                List<String> ids;
                                synchronized (affected) {
                                    ids =
                                            affected.isEmpty()
                                                    ? Collections.<String>emptyList()
                                                    : new ArrayList<>(affected);
                                }
                                if (!ids.isEmpty()) adapter.invalidateEmojis(ids);
                            }

                            @Override
                            public void onFailure(MediaCache.Failure f) {
                                loading.remove(id);
                                messageMap.remove(id);
                            }

                            @Override
                            public void onNeedsConfirm(long bytes) {
                                loading.remove(id);
                                messageMap.remove(id);
                            }
                        });
    }
}
