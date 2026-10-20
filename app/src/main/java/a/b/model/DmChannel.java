package a.b.model;

import org.json.JSONArray;
import org.json.JSONObject;
import java.util.ArrayList;
import java.util.List;

public class DmChannel {
    public static final int TYPE_DM = 1;
    public static final int TYPE_GROUP = 3;

    public String id;
    public int type;
    public List<Recipient> recipients = new ArrayList<>();
    public String name;
    public String icon;

    public static class Recipient {
        public String id;
        public String username;
        public String globalName;
        public String avatar;

        public boolean hasAvatar() {
            return avatar != null && !avatar.isEmpty() && !"null".equals(avatar);
        }

        public String display() {
            if (globalName != null && !globalName.isEmpty()) return globalName;
            if (username != null && !username.isEmpty()) return username;
            return "Unknown User";
        }
    }

    public static DmChannel fromJson(JSONObject o) {
        DmChannel d = new DmChannel();
        d.id = o.optString("id", null);
        d.type = o.optInt("type", TYPE_DM);
        d.name = o.optString("name", null);
        d.icon = o.optString("icon", null);
        JSONArray arr = o.optJSONArray("recipients");
        if (arr != null) {
            for (int i = 0; i < arr.length(); i++) {
                JSONObject u = arr.optJSONObject(i);
                if (u == null) continue;
                Recipient r = new Recipient();
                r.id = u.optString("id", null);
                r.username = u.optString("username", null);
                r.globalName = u.optString("global_name", null);
                r.avatar = u.optString("avatar", null);
                d.recipients.add(r);
            }
        }
        return d;
    }

    public String displayName(String selfUserId) {
        if (name != null && !name.isEmpty()) return name;
        if (recipients.isEmpty()) return "Direct Message";
        if (recipients.size() == 1) return recipients.get(0).display();
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < recipients.size(); i++) {
            Recipient r = recipients.get(i);
            if (selfUserId != null && selfUserId.equals(r.id)) continue;
            if (sb.length() > 0) sb.append(", ");
            sb.append(r.display());
        }
        return sb.length() > 0 ? sb.toString() : "Group DM";
    }

    public String subtitle(String selfUserId) {
        if (type == TYPE_DM) {
            if (recipients.isEmpty()) return "@unknown";
            String uname = recipients.get(0).username;
            return uname != null && !uname.isEmpty() ? "@" + uname : "@unknown";
        }
        int n = recipients.size();
        return n == 1 ? "Group DM · 1 member" : "Group DM · " + n + " members";
    }

    public Recipient avatarSource(String selfUserId) {
        if (type == TYPE_DM) {
            return recipients.isEmpty() ? null : recipients.get(0);
        }
        for (Recipient r : recipients) {
            if (selfUserId == null || !selfUserId.equals(r.id)) return r;
        }
        return recipients.isEmpty() ? null : recipients.get(0);
    }
}