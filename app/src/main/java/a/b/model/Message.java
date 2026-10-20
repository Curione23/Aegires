package a.b.model;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class Message {
  public String id;
  public Author author;
  public String content;
  public String memberNick;
  public long timestamp;

  public String replyToMessageId;
  public String replyToChannelId;
  public String replyToAuthorName;
  public String replyToContent;
  public boolean replyToMissing;

  public List<String> mentionedUserIds = new ArrayList<String>();
  public Map<String, String> mentionedUsers = new HashMap<String, String>();
  public List<Attachment> attachments = new ArrayList<Attachment>();
  public List<Embed> embeds = new ArrayList<Embed>();
  public List<Reaction> reactions = new ArrayList<Reaction>();
  public List<String> roleIds = new ArrayList<>();

  public static Message fromJson(JSONObject m) throws Exception {
    Message msg = new Message();
    msg.id = m.getString("id");
    msg.content = m.has("content") ? m.optString("content", "") : null;
    msg.timestamp = extractTimestamp(msg.id);

    JSONObject authorJson = m.getJSONObject("author");
    msg.author = new Author();
    msg.author.id = authorJson.getString("id");
    msg.author.username = authorJson.getString("username");
    msg.author.globalName = authorJson.isNull("global_name") ? null : authorJson.optString("global_name", null);
    msg.author.avatar = authorJson.isNull("avatar") ? null : authorJson.optString("avatar", null);

    JSONObject member = m.optJSONObject("member");
    if (member != null) {
        if (!member.isNull("nick")) msg.memberNick = member.optString("nick", null);
    
        JSONArray rolesArr = member.optJSONArray("roles");
        if (rolesArr != null) {
            for (int i = 0; i < rolesArr.length(); i++) {
                msg.roleIds.add(rolesArr.optString(i));
            }
        }
    }
    parseMentions(m, msg);
    parseAttachments(m, msg);
    parseEmbeds(m, msg);
    parseReactions(m, msg);
    parseReference(m, msg);

    return msg;
  }

  private static void parseMentions(JSONObject m, Message msg) {
    JSONArray arr = m.optJSONArray("mentions");
    if (arr == null) return;
    for (int i = 0; i < arr.length(); i++) {
      JSONObject mention = arr.optJSONObject(i);
      if (mention == null) continue;
      String id = mention.optString("id", null);
      if (id == null) continue;
      String username = mention.optString("username", id);
      String globalName = mention.isNull("global_name") ? null : mention.optString("global_name", null);
      String display = (globalName != null && globalName.length() > 0 && !"null".equals(globalName))
          ? globalName : username;
      msg.mentionedUserIds.add(id);
      msg.mentionedUsers.put(id, display);
    }
  }

  private static void parseAttachments(JSONObject m, Message msg) {
    JSONArray arr = m.optJSONArray("attachments");
    if (arr == null) return;
    for (int i = 0; i < arr.length(); i++) {
      JSONObject a = arr.optJSONObject(i);
      if (a == null) continue;
      Attachment att = new Attachment(
          a.optString("url"),
          a.optString("content_type"),
          a.optString("filename"));
      att.proxyUrl = a.optString("proxy_url", null);
      att.width = a.optInt("width", 0);
      att.height = a.optInt("height", 0);
      msg.attachments.add(att);
    }
  }

  private static void parseEmbeds(JSONObject m, Message msg) {
    JSONArray arr = m.optJSONArray("embeds");
    if (arr == null) return;
    for (int i = 0; i < arr.length(); i++) {
      JSONObject e = arr.optJSONObject(i);
      if (e == null) continue;
      Embed embed = new Embed();
      embed.type = e.isNull("type") ? null : e.optString("type", null);
      embed.title = e.isNull("title") ? null : e.optString("title", null);
      embed.description = e.isNull("description") ? null : e.optString("description", null);
      embed.color = e.optInt("color", 0);

      JSONObject t = e.optJSONObject("thumbnail");
      if (t != null) {
        embed.thumbnailUrl = t.optString("url", null);
        embed.thumbnailProxyUrl = t.optString("proxy_url", null);
        embed.thumbnailWidth = t.optInt("width", 0);
        embed.thumbnailHeight = t.optInt("height", 0);
      }

      JSONObject im = e.optJSONObject("image");
      if (im != null) {
        embed.imageUrl = im.optString("url", null);
        embed.imageProxyUrl = im.optString("proxy_url", null);
        embed.imageWidth = im.optInt("width", 0);
        embed.imageHeight = im.optInt("height", 0);
      }

      JSONObject v = e.optJSONObject("video");
      if (v != null) {
        embed.videoUrl = v.optString("url", null);
        embed.videoProxyUrl = v.optString("proxy_url", null);
        embed.videoWidth = v.optInt("width", 0);
        embed.videoHeight = v.optInt("height", 0);
      }

      JSONArray fields = e.optJSONArray("fields");
      if (fields != null) {
        for (int j = 0; j < fields.length(); j++) {
          JSONObject f = fields.optJSONObject(j);
          if (f == null) continue;
          embed.fields.add(new EmbedField(
              f.optString("name", ""),
              f.optString("value", ""),
              f.optBoolean("inline", false)));
        }
      }

      JSONObject footer = e.optJSONObject("footer");
      if (footer != null) embed.footer = footer.optString("text", null);

      msg.embeds.add(embed);
    }
  }

  private static void parseReactions(JSONObject m, Message msg) {
    JSONArray arr = m.optJSONArray("reactions");
    if (arr == null) return;
    for (int i = 0; i < arr.length(); i++) {
      JSONObject r = arr.optJSONObject(i);
      if (r == null) continue;
      JSONObject emoji = r.optJSONObject("emoji");
      if (emoji == null) continue;
      msg.reactions.add(new Reaction(
          emoji.optString("name", emoji.optString("id", "")),
          r.optInt("count", 0),
          r.optBoolean("me", false)));
    }
  }

  private static void parseReference(JSONObject m, Message msg) {
    JSONObject ref = m.optJSONObject("message_reference");
    if (ref == null) return;
    msg.replyToMessageId = ref.optString("message_id", null);
    msg.replyToChannelId = ref.optString("channel_id", null);

    JSONObject refMsg = m.optJSONObject("referenced_message");
    if (refMsg == null) {
      if (msg.replyToMessageId != null) msg.replyToMissing = true;
      return;
    }

    JSONObject refAuthor = refMsg.optJSONObject("author");
    if (refAuthor != null) {
      String gn = refAuthor.isNull("global_name") ? null : refAuthor.optString("global_name", null);
      msg.replyToAuthorName = (gn != null && gn.length() > 0 && !"null".equals(gn))
          ? gn : refAuthor.optString("username", "unknown");
    } else {
      msg.replyToAuthorName = "unknown";
    }

    String c = refMsg.optString("content", "");
    msg.replyToContent = c == null ? "" : c;
  }

  public void mergeFrom(Message other) {
    if (other == null) return;
    if (other.content != null) this.content = other.content;
    if (other.memberNick != null) this.memberNick = other.memberNick;
    if (other.attachments != null && !other.attachments.isEmpty()) this.attachments = other.attachments;
    if (other.embeds != null && !other.embeds.isEmpty()) this.embeds = other.embeds;
    if (other.reactions != null && !other.reactions.isEmpty()) this.reactions = other.reactions;
    if (other.mentionedUsers != null && !other.mentionedUsers.isEmpty()) {
      this.mentionedUsers.putAll(other.mentionedUsers);
    }
    if (other.replyToMessageId != null) this.replyToMessageId = other.replyToMessageId;
    if (other.replyToAuthorName != null) this.replyToAuthorName = other.replyToAuthorName;
    if (other.replyToContent != null) this.replyToContent = other.replyToContent;
    if (other.replyToMissing) this.replyToMissing = true;
  }

  private static long extractTimestamp(String snowflake) {
    try { return (Long.parseLong(snowflake) >> 22) + 1420070400000L; }
    catch (Exception e) { return System.currentTimeMillis(); }
  }

  public static class Attachment {
    public String url, type, filename;
    public String proxyUrl;
    public int width, height;
    Attachment(String u, String t, String f) { url = u; type = t; filename = f; }
  }
}