package a.b.util;

import android.os.Handler;
import android.os.Looper;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import javax.crypto.SecretKey;

/* Channels that always use a built-in encryption password. */
public final class ForcedKeys {
    private ForcedKeys() {}

    private static final Map<String, String> PRESETS = new HashMap<>();
    static {
        PRESETS.put("1562876012675465326", "mojoen");
    }

    private static final Map<String, SecretKey> DERIVED = new ConcurrentHashMap<>();
    private static final Handler MAIN = new Handler(Looper.getMainLooper());

    public interface Ready { void onKey(SecretKey keyOrNull); }

    public static boolean isForced(String channelId) {
        return channelId != null && PRESETS.containsKey(channelId);
    }

    public static SecretKey keyFor(String channelId) {
        return channelId == null ? null : DERIVED.get(channelId);
    }

    public static void prepare(final String channelId, final Ready cb) {
        final String password = channelId == null ? null : PRESETS.get(channelId);
        if (password == null) { MAIN.post(() -> cb.onKey(null)); return; }
        SecretKey have = DERIVED.get(channelId);
        if (have != null) { MAIN.post(() -> cb.onKey(have)); return; }
        new Thread(() -> {
            final SecretKey k = Crypto.deriveKey(password, "forced|" + channelId);
            if (k != null) DERIVED.put(channelId, k);
            MAIN.post(() -> cb.onKey(k));
        }, "forced-key").start();
    }
}
