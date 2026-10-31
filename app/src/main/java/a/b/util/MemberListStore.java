package a.b.util;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import org.json.JSONArray;
import org.json.JSONObject;

/** Mirrors the gateway's GUILD_MEMBER_LIST_UPDATE state for the currently subscribed channel. */
public final class MemberListStore {
    public static final class Entry {
        public boolean group;
        public String groupId;
        public int count;
        public String userId, username, globalName, nick, avatar;
        public String status = "offline";
        public boolean bot;
        public final List<String> roles = new ArrayList<>();

        public String displayName() {
            if (Ui.isReal(nick)) return nick;
            if (Ui.isReal(globalName)) return globalName;
            return username == null ? "unknown" : username;
        }
    }

    public interface Listener { void onMemberListChanged(); }

    private final ArrayList<Entry> items = new ArrayList<>();
    private final List<Listener> listeners = new CopyOnWriteArrayList<>();
    private final java.util.HashMap<String, Integer> groupCounts = new java.util.HashMap<>();
    private String guildId, listId;
    private int memberCount, onlineCount, listSize;

    public void addListener(Listener l) { if (!listeners.contains(l)) listeners.add(l); }
    public void removeListener(Listener l) { listeners.remove(l); }

    public String guildId() { return guildId; }
    public int memberCount() { return memberCount; }
    public int onlineCount() { return onlineCount; }

    /** Live count for a group, taken from the latest event's group list (items can carry stale or no counts). */
    public int groupCount(String groupId, int fallback) {
        Integer c = groupCounts.get(groupId);
        return c != null ? c : fallback;
    }

    public Entry get(int i) { return i >= 0 && i < items.size() ? items.get(i) : null; }

    public int total() {
        return listSize > 0 ? listSize : items.size();
    }

    public void clear() {
        items.clear();
        guildId = listId = null;
        groupCounts.clear();
        memberCount = onlineCount = listSize = 0;
        fire();
    }

    public void handle(JSONObject d, Map<String, String> nickCache, Map<String, String> nameCache) {
        String g = d.optString("guild_id", null);
        if (g == null) return;
        String id = d.optString("id", "everyone");
        if (!g.equals(guildId) || !id.equals(listId)) {
            items.clear();
            groupCounts.clear();
            guildId = g;
            listId = id;
        }
        memberCount = d.optInt("member_count", memberCount);
        onlineCount = d.optInt("online_count", onlineCount);
        JSONArray groups = d.optJSONArray("groups");
        if (groups != null) {
            int sum = groups.length();
            groupCounts.clear();
            for (int i = 0; i < groups.length(); i++) {
                JSONObject gr = groups.optJSONObject(i);
                if (gr == null) continue;
                int c = gr.optInt("count", 0);
                groupCounts.put(gr.optString("id", ""), c);
                sum += c;
            }
            listSize = sum;
        }

        JSONArray ops = d.optJSONArray("ops");
        for (int i = 0; ops != null && i < ops.length(); i++) {
            JSONObject op = ops.optJSONObject(i);
            if (op != null) apply(op, nickCache, nameCache);
        }
        fire();
    }

    private void apply(JSONObject op, Map<String, String> nickCache, Map<String, String> nameCache) {
        String kind = op.optString("op", "");
        switch (kind) {
            case "SYNC": {
                JSONArray range = op.optJSONArray("range");
                JSONArray arr = op.optJSONArray("items");
                if (range == null || arr == null) return;
                int start = range.optInt(0), end = range.optInt(1);
                pad(start);
                for (int i = 0; i < arr.length(); i++) {
                    set(start + i, parse(arr.optJSONObject(i), nickCache, nameCache));
                }
                if (arr.length() < end - start + 1) {
                    while (items.size() > start + arr.length()) items.remove(items.size() - 1);
                }
                break;
            }
            case "INSERT": {
                int index = op.optInt("index");
                pad(index);
                items.add(index, parse(op.optJSONObject("item"), nickCache, nameCache));
                break;
            }
            case "UPDATE":
                set(op.optInt("index"), parse(op.optJSONObject("item"), nickCache, nameCache));
                break;
            case "DELETE": {
                int index = op.optInt("index");
                if (index >= 0 && index < items.size()) items.remove(index);
                break;
            }
            case "INVALIDATE": {
                JSONArray range = op.optJSONArray("range");
                if (range == null) return;
                for (int i = range.optInt(0); i <= range.optInt(1) && i < items.size(); i++) items.set(i, null);
                break;
            }
            default:
        }
    }

    private void pad(int size) {
        while (items.size() < size) items.add(null);
    }

    private void set(int index, Entry e) {
        pad(index + 1);
        items.set(index, e);
    }

    private Entry parse(JSONObject item, Map<String, String> nickCache, Map<String, String> nameCache) {
        if (item == null) return null;
        Entry e = new Entry();
        JSONObject grp = item.optJSONObject("group");
        if (grp != null) {
            e.group = true;
            e.groupId = grp.optString("id", "");
            e.count = grp.optInt("count", 0);
            return e;
        }
        JSONObject m = item.optJSONObject("member");
        if (m == null) return null;
        JSONObject u = m.optJSONObject("user");
        if (u == null) return null;
        e.userId = u.optString("id", null);
        e.username = u.optString("username", null);
        e.globalName = u.isNull("global_name") ? null : u.optString("global_name", null);
        e.avatar = u.isNull("avatar") ? null : u.optString("avatar", null);
        e.bot = u.optBoolean("bot", false);
        e.nick = m.isNull("nick") ? null : m.optString("nick", null);
        JSONObject p = m.optJSONObject("presence");
        if (p != null) e.status = p.optString("status", "offline");
        JSONArray roles = m.optJSONArray("roles");
        for (int i = 0; roles != null && i < roles.length(); i++) e.roles.add(roles.optString(i));

        if (e.userId != null) {
            String base = Ui.isReal(e.globalName) ? e.globalName : e.username;
            if (nameCache != null && Ui.isReal(base)) nameCache.put(e.userId, base);
            if (nickCache != null && Ui.isReal(e.nick) && guildId != null) nickCache.put(guildId + "_" + e.userId, e.nick);
        }
        return e;
    }

    private void fire() {
        for (Listener l : listeners) l.onMemberListChanged();
    }
}
