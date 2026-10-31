package a.b.util;

import android.content.Context;
import android.content.SharedPreferences;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import org.json.JSONArray;
import org.json.JSONObject;
import a.b.model.MentionItem;

/** Main-thread-only inbox of mentions plus per-DM unread counters. */
public final class MentionStore {
    public interface Listener { void onMentionsChanged(); }

    private static final String PREFS = "mentions";
    private static final String KEY_LAST_READ = "last_read";
    private static final String KEY_READ_IDS = "read_ids";
    private static final int MAX_ITEMS = 100;
    private static final int MAX_READ_IDS = 300;

    private final SharedPreferences prefs;
    private final List<MentionItem> items = new ArrayList<>();
    private final Map<String, Integer> dmUnread = new HashMap<>();
    private final List<Listener> listeners = new CopyOnWriteArrayList<>();
    private String lastRead;
    private Set<String> readIds;

    public MentionStore(Context ctx) {
        prefs = ctx.getApplicationContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        loadPrefs();
    }

    private void loadPrefs() {
        lastRead = prefs.getString(KEY_LAST_READ, null);
        readIds = new HashSet<>(prefs.getStringSet(KEY_READ_IDS, new HashSet<String>()));
    }

    private void savePrefs() {
        prefs.edit().putString(KEY_LAST_READ, lastRead).putStringSet(KEY_READ_IDS, new HashSet<>(readIds)).apply();
    }

    public void addListener(Listener l) { if (!listeners.contains(l)) listeners.add(l); }
    public void removeListener(Listener l) { listeners.remove(l); }
    private void fire() { for (Listener l : listeners) l.onMentionsChanged(); }

    public List<MentionItem> items() { return new ArrayList<>(items); }

    public int unreadCount() {
        int n = 0;
        for (MentionItem m : items) if (m.unread) n++;
        return n;
    }

    public int unreadForChannel(String channelId) {
        int n = 0;
        for (MentionItem m : items) if (m.unread && m.channelId.equals(channelId)) n++;
        Integer dm = dmUnread.get(channelId);
        return n + (dm == null ? 0 : dm);
    }

    public int dmUnreadTotal() {
        int n = 0;
        for (int v : dmUnread.values()) n += v;
        return n;
    }

    public void onMessageCreate(JSONObject d, String selfId, String viewingChannelId) {
        if (d == null || selfId == null) return;
        String channelId = d.optString("channel_id", null);
        JSONObject author = d.optJSONObject("author");
        if (channelId == null || author == null || selfId.equals(author.optString("id", null))) return;
        if (channelId.equals(viewingChannelId)) return;

        boolean dm = !d.has("guild_id") || d.isNull("guild_id");
        if (dm) {
            Integer cur = dmUnread.get(channelId);
            dmUnread.put(channelId, (cur == null ? 0 : cur) + 1);
            fire();
            return;
        }
        boolean mentioned = d.optBoolean("mention_everyone", false);
        JSONArray ms = d.optJSONArray("mentions");
        for (int i = 0; ms != null && i < ms.length(); i++) {
            JSONObject u = ms.optJSONObject(i);
            if (u != null && selfId.equals(u.optString("id", null))) { mentioned = true; break; }
        }
        if (!mentioned) return;
        try {
            MentionItem item = MentionItem.fromJson(d);
            if (contains(item.id)) return;
            item.unread = true;
            items.add(0, item);
            trim();
            fire();
        } catch (Exception ignored) {}
    }

    public void mergeRemote(List<MentionItem> remote) {
        boolean changed = false;
        if (lastRead == null && !remote.isEmpty()) {
            String newest = remote.get(0).id;
            for (MentionItem m : remote) if (compareIds(m.id, newest) > 0) newest = m.id;
            lastRead = newest;
            savePrefs();
        }
        for (MentionItem m : remote) {
            if (contains(m.id)) continue;
            m.unread = compareIds(m.id, lastRead) > 0 && !readIds.contains(m.id);
            items.add(m);
            changed = true;
        }
        if (!changed) return;
        java.util.Collections.sort(items, (a, b) -> compareIds(b.id, a.id));
        trim();
        fire();
    }

    public void remove(String messageId) {
        for (int i = 0; i < items.size(); i++) {
            if (items.get(i).id.equals(messageId)) { items.remove(i); fire(); return; }
        }
    }

    public void markRead(MentionItem m) {
        if (m == null || !m.unread) return;
        m.unread = false;
        rememberRead(m.id);
        fire();
    }

    public void markChannelRead(String channelId) {
        boolean changed = dmUnread.remove(channelId) != null;
        for (MentionItem m : items) {
            if (m.unread && m.channelId.equals(channelId)) {
                m.unread = false;
                rememberRead(m.id);
                changed = true;
            }
        }
        if (changed) fire();
    }

    public void markAllRead() {
        for (MentionItem m : items) {
            m.unread = false;
            if (lastRead == null || compareIds(m.id, lastRead) > 0) lastRead = m.id;
        }
        readIds.clear();
        savePrefs();
        fire();
    }

    public void clear() {
        items.clear();
        dmUnread.clear();
        lastRead = null;
        readIds.clear();
        savePrefs();
        fire();
    }

    private void rememberRead(String id) {
        if (readIds.size() >= MAX_READ_IDS) readIds.clear();
        readIds.add(id);
        savePrefs();
    }

    private boolean contains(String id) {
        for (MentionItem m : items) if (m.id.equals(id)) return true;
        return false;
    }

    private void trim() {
        while (items.size() > MAX_ITEMS) items.remove(items.size() - 1);
    }

    public static int compareIds(String a, String b) {
        if (a.length() != b.length()) return a.length() - b.length();
        return a.compareTo(b);
    }
}
