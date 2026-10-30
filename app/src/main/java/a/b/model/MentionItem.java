package a.b.model;

import org.json.JSONArray;
import org.json.JSONObject;

/** One entry of the mentions / notifications inbox. */
public class MentionItem {
    public String id, channelId, guildId;
    public String authorId, authorName, authorAvatar;
    public String content;
    public long timestamp;
    public boolean everyone;
    public boolean unread;
    public boolean guildResolved;

    public static MentionItem fromJson(JSONObject o) throws Exception {
        MentionItem m = new MentionItem();
        m.id = o.getString("id");
        m.channelId = o.getString("channel_id");
        if (o.has("guild_id") && !o.isNull("guild_id")) {
            m.guildId = o.optString("guild_id", null);
            m.guildResolved = true;
        }
        m.content = o.isNull("content") ? "" : o.optString("content", "");
        m.everyone = o.optBoolean("mention_everyone", false);
        JSONObject a = o.optJSONObject("author");
        if (a != null) {
            m.authorId = a.optString("id", null);
            String g = a.isNull("global_name") ? null : a.optString("global_name", null);
            m.authorName = Message.isRealName(g) ? g : a.optString("username", "unknown");
            m.authorAvatar = a.isNull("avatar") ? null : a.optString("avatar", null);
        } else {
            m.authorName = "unknown";
        }
        JSONArray atts = o.optJSONArray("attachments");
        if ((m.content == null || m.content.isEmpty()) && atts != null && atts.length() > 0) {
            m.content = "[attachment]";
        }
        try { m.timestamp = (Long.parseLong(m.id) >> 22) + 1420070400000L; }
        catch (Exception e) { m.timestamp = System.currentTimeMillis(); }
        return m;
    }
}
