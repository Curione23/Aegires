package a.b.util;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import org.json.JSONArray;
import org.json.JSONObject;
import a.b.model.Channel;
import a.b.model.Guild;

/**
 * Small JSON-on-disk cache for the server list and per-server channel lists.
 * Screens show this immediately, then refresh from the network (stale-while-revalidate).
 * Files are namespaced per account, so switching accounts never shows someone else's servers.
 */
public final class DiskCache {
    public interface Result<T> { void on(T valueOrNull); }

    private static final int VERSION = 1;

    private final File root;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final ExecutorService io = Executors.newSingleThreadExecutor();
    private volatile String account = "anon";

    public DiskCache(Context c) {
        root = new File(c.getApplicationContext().getFilesDir(), "cache");
    }

    public void setAccount(String id) {
        account = id == null || id.isEmpty() ? "anon" : safe(id);
    }

    private static String safe(String s) { return s.replaceAll("[^A-Za-z0-9_-]", "_"); }

    private static File file(File root, String acc, String name) {
        File d = new File(root, acc);
        if (!d.exists()) d.mkdirs();
        return new File(d, safe(name) + ".json");
    }


    public void saveGuilds(final List<Guild> guilds) {
        final String acc = account;
        final List<Guild> snap = new ArrayList<>(guilds);
        io.execute(() -> {
            try {
                JSONArray arr = new JSONArray();
                for (Guild g : snap) {
                    JSONObject o = new JSONObject();
                    o.put("id", g.id);
                    o.put("name", g.name);
                    o.put("icon", g.icon == null ? JSONObject.NULL : g.icon);
                    o.put("banner", g.banner == null ? JSONObject.NULL : g.banner);
                    o.put("owner", g.owner);
                    arr.put(o);
                }
                write(file(root, acc, "guilds"), arr);
            } catch (Exception ignored) {}
        });
    }

    public void loadGuilds(final Result<List<Guild>> cb) {
        final String acc = account;
        io.execute(() -> {
            List<Guild> out = null;
            File f = file(root, acc, "guilds");
            try {
                JSONArray arr = read(f);
                if (arr != null) {
                    out = new ArrayList<>();
                    for (int i = 0; i < arr.length(); i++) {
                        JSONObject o = arr.getJSONObject(i);
                        out.add(new Guild(o.getString("id"), o.optString("name", ""),
                            nn(o, "icon"), nn(o, "banner"), o.optBoolean("owner", false)));
                    }
                }
            } catch (Exception e) { out = null; f.delete(); }
            deliver(cb, out);
        });
    }

    public void deleteGuilds() {
        final String acc = account;
        io.execute(() -> file(root, acc, "guilds").delete());
    }


    public void saveChannels(final String guildId, final List<Channel> channels) {
        final String acc = account;
        final List<Channel> snap = new ArrayList<>(channels);
        io.execute(() -> {
            try {
                JSONArray arr = new JSONArray();
                for (Channel c : snap) {
                    JSONObject o = new JSONObject();
                    o.put("id", c.id);
                    o.put("name", c.name);
                    o.put("type", c.type);
                    o.put("parent", c.parent_id == null ? JSONObject.NULL : c.parent_id);
                    o.put("pos", c.position);
                    arr.put(o);
                }
                write(file(root, acc, "channels_" + guildId), arr);
            } catch (Exception ignored) {}
        });
    }

    public void loadChannels(final String guildId, final Result<List<Channel>> cb) {
        final String acc = account;
        io.execute(() -> {
            List<Channel> out = null;
            File f = file(root, acc, "channels_" + guildId);
            try {
                JSONArray arr = read(f);
                if (arr != null) {
                    out = new ArrayList<>();
                    for (int i = 0; i < arr.length(); i++) {
                        JSONObject o = arr.getJSONObject(i);
                        Channel c = new Channel(o.getString("id"), o.optString("name", ""),
                            o.optInt("type", 0), nn(o, "parent"));
                        c.position = o.optInt("pos", 0);
                        out.add(c);
                    }
                }
            } catch (Exception e) { out = null; f.delete(); }
            deliver(cb, out);
        });
    }

    public void deleteChannels(final String guildId) {
        final String acc = account;
        io.execute(() -> file(root, acc, "channels_" + guildId).delete());
    }

    public void clearAll() {
        io.execute(() -> deleteRecursive(root));
    }


    private <T> void deliver(final Result<T> cb, final T value) {
        main.post(() -> cb.on(value));
    }

    private static String nn(JSONObject o, String k) {
        return o.isNull(k) ? null : o.optString(k, null);
    }

    private static void write(File dest, JSONArray items) throws Exception {
        JSONObject wrap = new JSONObject();
        wrap.put("v", VERSION);
        wrap.put("saved", System.currentTimeMillis());
        wrap.put("items", items);
        File tmp = new File(dest.getParentFile(), dest.getName() + ".tmp");
        try (FileOutputStream out = new FileOutputStream(tmp)) {
            out.write(wrap.toString().getBytes("UTF-8"));
        }
        if (!tmp.renameTo(dest)) {
            dest.delete();
            if (!tmp.renameTo(dest)) tmp.delete();
        }
    }

    private static JSONArray read(File f) throws Exception {
        if (!f.exists()) return null;
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        try (InputStream in = new FileInputStream(f)) {
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) > 0) bos.write(buf, 0, n);
        }
        JSONObject wrap = new JSONObject(bos.toString("UTF-8"));
        if (wrap.optInt("v", 0) != VERSION) return null;
        return wrap.optJSONArray("items");
    }

    private static void deleteRecursive(File f) {
        File[] kids = f.listFiles();
        if (kids != null) for (File k : kids) deleteRecursive(k);
        f.delete();
    }
}
