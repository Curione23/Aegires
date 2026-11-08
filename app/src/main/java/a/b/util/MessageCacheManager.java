package a.b.util;

import android.content.Context;
import android.content.SharedPreferences;
import android.util.LruCache;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import a.b.model.Embed;
import a.b.model.EmbedField;
import a.b.model.Message;

public class MessageCacheManager {
    private static final int MAX_CHANNELS = 30;
    private static final int DISK_SAVE_LIMIT = 20;
    private static final String PREFS = "chat_state";
    private static final String CACHE_FILE = "last_channel_cache.json";

    private final LruCache<String, List<Message>> memoryCache;
    private final Context context;
    private final SharedPreferences prefs;
    private final java.util.concurrent.ExecutorService diskExecutor = java.util.concurrent.Executors.newSingleThreadExecutor();

    public MessageCacheManager(Context context) {
        this.context = context;
        this.prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        this.memoryCache = new LruCache<String, List<Message>>(MAX_CHANNELS) {
            @Override
            protected int sizeOf(String key, List<Message> value) { return 1; }
        };
    }

    public synchronized List<Message> getMessages(String channelId) {
        List<Message> cached = memoryCache.get(channelId);
        return cached != null ? new ArrayList<>(cached) : null;
    }

    public synchronized void putMessages(String channelId, List<Message> messages) {
        if (channelId == null || messages == null) return;
        memoryCache.put(channelId, new ArrayList<>(messages));
    }

    public synchronized void appendMessage(String channelId, Message message) {
        if (channelId == null || message == null) return;
        List<Message> cached = memoryCache.get(channelId);
        if (cached == null) {
            cached = new ArrayList<>();
            memoryCache.put(channelId, cached);
        }
        for (Message m : cached) {
            if (m.id.equals(message.id)) return;
        }
        cached.add(message);
        Collections.sort(cached, (a, b) -> a.id.compareTo(b.id));
    }

    public synchronized void updateMessage(String channelId, Message updated) {
        if (channelId == null || updated == null) return;
        List<Message> cached = memoryCache.get(channelId);
        if (cached != null) {
            for (int i = 0; i < cached.size(); i++) {
                if (cached.get(i).id.equals(updated.id)) {
                    cached.set(i, updated);
                    return;
                }
            }
        }
    }

    public synchronized void deleteMessage(String channelId, String messageId) {
        if (channelId == null || messageId == null) return;
        List<Message> cached = memoryCache.get(channelId);
        if (cached != null) {
            for (int i = 0; i < cached.size(); i++) {
                if (cached.get(i).id.equals(messageId)) {
                    cached.remove(i);
                    return;
                }
            }
        }
    }

    public void saveLastChannelState(String guildId, String channelId, List<Message> messages) {
        prefs.edit().putString("last_guild", guildId).putString("last_channel", channelId).apply();
        if (messages == null || messages.isEmpty()) return;

        final List<Message> snapshot = new ArrayList<>(messages);
        diskExecutor.execute(() -> {
            JSONArray arr = new JSONArray();
            int start = Math.max(0, snapshot.size() - DISK_SAVE_LIMIT);
            for (int i = start; i < snapshot.size(); i++) {
                arr.put(messageToJson(snapshot.get(i)));
            }
            try (FileOutputStream fos = context.openFileOutput(CACHE_FILE, Context.MODE_PRIVATE)) {
                fos.write(arr.toString().getBytes(StandardCharsets.UTF_8));
            } catch (Exception ignored) {}
        });
    }

    public List<Message> loadLastChannelMessages(String channelId) {
        String savedChannel = prefs.getString("last_channel", null);
        if (channelId == null || !channelId.equals(savedChannel)) return null;

        File file = new File(context.getFilesDir(), CACHE_FILE);
        if (!file.exists()) return null;

        try (FileInputStream fis = new FileInputStream(file)) {
            byte[] bytes = new byte[(int) file.length()];
            fis.read(bytes);
            String json = new String(bytes, StandardCharsets.UTF_8);
            JSONArray arr = new JSONArray(json);
            List<Message> list = new ArrayList<>();
            for (int i = 0; i < arr.length(); i++) list.add(Message.fromJson(arr.getJSONObject(i)));
            return list;
        } catch (Exception e) { return null; }
    }

    public String getLastGuildId() { return prefs.getString("last_guild", null); }
    public String getLastChannelId() { return prefs.getString("last_channel", null); }

    private JSONObject messageToJson(Message m) {
        JSONObject o = new JSONObject();
        try {
            o.put("id", m.id); 
            o.put("content", m.content); 
            o.put("timestamp", m.timestamp);
            
            if (m.author != null) {
                JSONObject a = new JSONObject();
                a.put("id", m.author.id); a.put("username", m.author.username);
                a.put("global_name", m.author.globalName); a.put("avatar", m.author.avatar);
                o.put("author", a);
            }
            
            if (m.memberNick != null || (m.roleIds != null && !m.roleIds.isEmpty())) {
                JSONObject mem = new JSONObject();
                if (m.memberNick != null) mem.put("nick", m.memberNick);
                if (m.roleIds != null && !m.roleIds.isEmpty()) {
                    JSONArray rolesArr = new JSONArray();
                    for (String r : m.roleIds) rolesArr.put(r);
                    mem.put("roles", rolesArr);
                }
                o.put("member", mem);
            }
            
            if (m.replyToMessageId != null) {
                JSONObject ref = new JSONObject();
                ref.put("message_id", m.replyToMessageId);
                if (m.replyToChannelId != null) ref.put("channel_id", m.replyToChannelId);
                o.put("message_reference", ref);

                if (!m.replyToMissing) {
                    JSONObject refMsg = new JSONObject();
                    if (m.replyToAuthorId != null) {
                        JSONObject refAuthor = new JSONObject();
                        refAuthor.put("id", m.replyToAuthorId);
                        String name = m.replyToAuthorName != null ? m.replyToAuthorName : "unknown";
                        refAuthor.put("username", name);
                        refAuthor.put("global_name", name);
                        refMsg.put("author", refAuthor);
                    }
                    if (m.replyToContent != null) refMsg.put("content", m.replyToContent);
                    o.put("referenced_message", refMsg);
                }
            }

            if (m.mentionedUserIds != null && !m.mentionedUserIds.isEmpty()) {
                JSONArray mentionsArr = new JSONArray();
                for (String uid : m.mentionedUserIds) {
                    JSONObject uObj = new JSONObject();
                    uObj.put("id", uid);
                    String name = m.mentionedUsers.get(uid);
                    if (name != null) {
                        uObj.put("username", name);
                        uObj.put("global_name", name);
                    }
                    mentionsArr.put(uObj);
                }
                o.put("mentions", mentionsArr);
            }

            if (m.embeds != null && !m.embeds.isEmpty()) {
                JSONArray embedsArr = new JSONArray();
                for (Embed e : m.embeds) {
                    JSONObject eo = new JSONObject();
                    if (e.title != null) eo.put("title", e.title);
                    if (e.description != null) eo.put("description", e.description);
                    eo.put("color", e.color);
                    if (e.type != null) eo.put("type", e.type);
                    if (e.thumbnailUrl != null) {
                        JSONObject t = new JSONObject();
                        t.put("url", e.thumbnailUrl);
                        if (e.thumbnailProxyUrl != null) t.put("proxy_url", e.thumbnailProxyUrl);
                        t.put("width", e.thumbnailWidth); t.put("height", e.thumbnailHeight);
                        eo.put("thumbnail", t);
                    }
                    if (e.imageUrl != null) {
                        JSONObject i = new JSONObject();
                        i.put("url", e.imageUrl);
                        if (e.imageProxyUrl != null) i.put("proxy_url", e.imageProxyUrl);
                        i.put("width", e.imageWidth); i.put("height", e.imageHeight);
                        eo.put("image", i);
                    }
                    if (e.videoUrl != null) {
                        JSONObject v = new JSONObject();
                        v.put("url", e.videoUrl);
                        if (e.videoProxyUrl != null) v.put("proxy_url", e.videoProxyUrl);
                        v.put("width", e.videoWidth); v.put("height", e.videoHeight);
                        eo.put("video", v);
                    }
                    if (e.footer != null) {
                        JSONObject f = new JSONObject();
                        f.put("text", e.footer);
                        eo.put("footer", f);
                    }
                    
                    if (e.fields != null && !e.fields.isEmpty()) {
                        JSONArray fieldsArr = new JSONArray();
                        for (EmbedField field : e.fields) {
                            JSONObject fo = new JSONObject();
                            if (field.name != null) fo.put("name", field.name);
                            if (field.value != null) fo.put("value", field.value);
                            fo.put("inline", field.inline);
                            fieldsArr.put(fo);
                        }
                        eo.put("fields", fieldsArr);
                    }
                    embedsArr.put(eo);
                }
                o.put("embeds", embedsArr);
            }

            if (m.attachments != null && !m.attachments.isEmpty()) {
                JSONArray attsArr = new JSONArray();
                for (Message.Attachment a : m.attachments) {
                    JSONObject ao = new JSONObject();
                    ao.put("url", a.url);
                    if (a.proxyUrl != null) ao.put("proxy_url", a.proxyUrl);
                    ao.put("filename", a.filename);
                    if (a.type != null) ao.put("content_type", a.type);
                    ao.put("width", a.width); ao.put("height", a.height); ao.put("size", a.size);
                    attsArr.put(ao);
                }
                o.put("attachments", attsArr);
            }

        } catch (JSONException ignored) {}
        return o;
    }
}