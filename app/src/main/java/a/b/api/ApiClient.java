package a.b.api;

import android.content.Context;
import android.database.Cursor;
import android.net.Uri;
import android.os.Handler;
import android.provider.OpenableColumns;
import android.util.Log;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;
import java.io.IOException;
import java.io.InputStream;
import java.util.AbstractMap;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.TimeZone;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import a.b.model.Channel;
import a.b.model.DiscordUser;
import a.b.model.DmChannel;
import a.b.model.Guild;
import a.b.model.GuildInfo;
import a.b.model.MentionItem;
import a.b.model.Message;
import a.b.model.Role;
import a.b.util.PermissionUtil;
import a.b.util.SuperProperties;
import okhttp3.MediaType;
import okhttp3.MultipartBody;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;

public class ApiClient {
    private static final String TAG = "ApiClient";
    private static final String BASE_URL = "https://discord.com/api/v10";
    private static final long MAX_UPLOAD_BYTES = 25L * 1024 * 1024;
    private static final long MAX_RETRY_WAIT_MS = 5000;
    private static final MediaType JSON = MediaType.parse("application/json; charset=utf-8");
    
    private volatile String token;
    private volatile String gatewayToken;
    private volatile String userId;
    private volatile boolean botAccount;
    private final Handler handler;
    private final AtomicInteger requestGeneration = new AtomicInteger();
    public final Map<String, Boolean> manageMessagesCache = new java.util.concurrent.ConcurrentHashMap<>();
    
    private final ThreadPoolExecutor executor = new ThreadPoolExecutor(
        4, 4, 30, TimeUnit.SECONDS, new LinkedBlockingQueue<>(), r -> {
            Thread t = new Thread(r, "api-http");
            t.setDaemon(true);
            return t;
        });
    
    private final OkHttpClient httpClient = new OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .build();

    public ApiClient(Handler handler) {
        this.handler = handler;
        executor.allowCoreThreadTimeOut(true);
    }

    public interface Callback<T> {
        void onSuccess(T result);
        void onError(String message);
    }

    public void setToken(String raw) {
        String t = raw.trim();
        int space = t.indexOf(' ');
        token = t;
        botAccount = t.startsWith("Bot ");
        gatewayToken = (space >= 0 ? t.substring(space + 1) : t).trim();
        userId = PermissionUtil.getUserIdFromToken(gatewayToken);
    }

    public boolean isBotAccount() { return botAccount; }
    public String getGatewayToken() { return gatewayToken; }
    public GatewayClient createGateway(int intents, GatewayClient.Listener listener) {
        return new GatewayClient(gatewayToken, intents, handler, listener);
    }
    public int newRequestGeneration() { return requestGeneration.incrementAndGet(); }
    public int currentRequestGeneration() { return requestGeneration.get(); }
    public void shutdown() { executor.shutdownNow(); }

    public void fetchGuilds(Callback<List<Guild>> cb) {
        get("/users/@me/guilds", json -> list(json, o -> new Guild(
            o.getString("id"), o.getString("name"), str(o, "icon"), str(o, "banner"),
            o.optBoolean("owner", false)), "fetchGuilds"), cb);
    }

    public void fetchDms(Callback<List<DmChannel>> cb) {
        get("/users/@me/channels", json -> list(json, o -> {
            DmChannel d = DmChannel.fromJson(o);
            return d.type == DmChannel.TYPE_DM || d.type == DmChannel.TYPE_GROUP ? d : null;
        }, "fetchDms"), cb);
    }

    public void fetchGuildInfo(String guildId, Callback<GuildInfo> cb) {
        get("/guilds/" + enc(guildId) + "?with_counts=true", json -> {
            JSONObject o = new JSONObject(json);
            GuildInfo info = new GuildInfo();
            info.memberCount = o.optInt("approximate_member_count", 0);
            info.onlineCount = o.optInt("approximate_presence_count", 0);
            return info;
        }, cb);
    }

    public void fetchMessages(String channelId, String before, String after, int limit, Callback<List<Message>> cb) {
        String path = "/channels/" + enc(channelId) + "/messages?limit=" + limit +
            (before != null ? "&before=" + enc(before) : "") +
            (after != null ? "&after=" + enc(after) : "");
        get(path, json -> list(json, Message::fromJson, "fetchMessages"), cb);
    }

    public void fetchMentions(Callback<List<MentionItem>> cb) {
        get("/users/@me/mentions?limit=50&roles=true&everyone=true", json -> list(json, MentionItem::fromJson, "fetchMentions"), cb);
    }

    public void fetchChannelMeta(String channelId, Callback<JSONObject> cb) {
        get("/channels/" + enc(channelId), json -> new JSONObject(json), cb);
    }

    public void fetchChannelName(String channelId, Callback<String> cb) {
        get("/channels/" + enc(channelId), json -> str(new JSONObject(json), "name"), cb);
    }

    public void fetchRoles(String guildId, Callback<Map<String, Role>> cb) {
        get("/guilds/" + enc(guildId) + "/roles", json -> map(json,
            o -> new AbstractMap.SimpleEntry<>(o.getString("id"),
                new Role(o.getString("id"), o.optString("name", "role"), o.optInt("color", 0), o.optInt("position", 0))),
            "fetchRoles"), cb);
    }

    public void fetchMember(String guildId, String userId, Callback<JSONObject> cb) {
        get("/guilds/" + enc(guildId) + "/members/" + enc(userId), json -> new JSONObject(json), cb);
    }

    public void fetchUserProfile(String id, Callback<DiscordUser> cb) {
        get("/users/" + enc(id), json -> DiscordUser.fromJson(new JSONObject(json)), cb);
    }

    public void fetchUserProfileFull(String id, Callback<DiscordUser> cb) {
        if (botAccount) {
            fetchUserProfile(id, cb);
            return;
        }
        get("/users/" + enc(id) + "/profile?with_mutual_guilds=true", json -> DiscordUser.fromJson(new JSONObject(json)), new Callback<DiscordUser>() {
            @Override public void onSuccess(DiscordUser user) { cb.onSuccess(user); }
            @Override public void onError(String error) { fetchUserProfile(id, cb); }
        });
    }

    public void fetchChannels(String guildId, boolean isOwner, Callback<List<Channel>> cb) {
        int gen = requestGeneration.get();
        async("fetchChannels", () -> {
            List<String> memberRoles = memberRoles(guildId);
            Map<String, Long> rolePerms = rolePermissions(guildId);
            String json = request("GET", "/guilds/" + enc(guildId) + "/channels", null);
            return list(json, o -> {
                if (userId == null || !PermissionUtil.canViewChannel(o, guildId, userId, memberRoles, rolePerms, isOwner)) return null;
                Channel ch = new Channel(o.getString("id"), o.getString("name"), o.getInt("type"), o.isNull("parent_id") ? null : o.getString("parent_id"));
                ch.position = o.optInt("position", 0);
                manageMessagesCache.put(ch.id, PermissionUtil.canManageMessages(o, guildId, userId, memberRoles, rolePerms, isOwner));
                return ch;
            }, "fetchChannels");
        }, ifCurrent(gen, cb));
    }

    private List<String> memberRoles(String guildId) throws IOException {
        try {
            if (botAccount && userId == null) throw new IOException("Bot User ID is null.");
            String path = botAccount ? "/guilds/" + enc(guildId) + "/members/" + enc(userId) : "/users/@me/guilds/" + enc(guildId) + "/member";
            JSONArray arr = new JSONObject(request("GET", path, null)).optJSONArray("roles");
            List<String> roles = new ArrayList<>();
            for (int i = 0; arr != null && i < arr.length(); i++) roles.add(arr.optString(i));
            return roles;
        } catch (Exception e) {
            throw new IOException("Failed to fetch member: " + e.getMessage(), e);
        }
    }

    private Map<String, Long> rolePermissions(String guildId) throws IOException {
        try {
            return map(request("GET", "/guilds/" + enc(guildId) + "/roles", null),
                o -> new AbstractMap.SimpleEntry<>(o.getString("id"), Long.parseLong(o.optString("permissions", "0"))), "fetchGuildRoles");
        } catch (Exception e) {
            throw new IOException("Failed to fetch roles: " + e.getMessage(), e);
        }
    }

    public boolean canManageMessages(String channelId) {
        return Boolean.TRUE.equals(manageMessagesCache.get(channelId));
    }

    public void sendMessage(String channelId, String content, Callback<Void> cb) {
        sendMessage(channelId, content, null, null, cb);
    }

    public void sendMessage(String channelId, String content, String replyToMessageId, String replyToGuildId, Callback<Void> cb) {
        async("POST messages", () -> {
            JSONObject payload = new JSONObject().put("content", content);
            if (replyToMessageId != null) {
                JSONObject ref = new JSONObject().put("message_id", replyToMessageId).put("channel_id", channelId);
                if (replyToGuildId != null) ref.put("guild_id", replyToGuildId);
                payload.put("message_reference", ref);
            }
            request("POST", "/channels/" + enc(channelId) + "/messages", payload.toString());
            return null;
        }, cb);
    }

    public void editMessage(String channelId, String messageId, String content, Callback<Void> cb) {
        async("PATCH messages", () -> {
            request("PATCH", messagePath(channelId, messageId), new JSONObject().put("content", content).toString());
            return null;
        }, cb);
    }

    public void deleteMessage(String channelId, String messageId, Callback<Void> cb) {
        async("DELETE messages", () -> {
            request("DELETE", messagePath(channelId, messageId), null);
            return null;
        }, cb);
    }

    public void sendFile(Context ctx, String channelId, String content, Uri fileUri, String mimeType, String fileName, Callback<Void> cb) {
        async("http.UPLOAD", () -> {
            if (fileSize(ctx, fileUri) > MAX_UPLOAD_BYTES) throw new IOException("File too large (max 25 MB)");
            RequestBody fileBody = new RequestBody() {
                @Override public MediaType contentType() { return MediaType.parse(mimeType); }
                @Override public void writeTo(okio.BufferedSink sink) throws IOException {
                    try (InputStream in = ctx.getContentResolver().openInputStream(fileUri)) {
                        if (in == null) throw new IOException("Could not open " + fileUri);
                        sink.writeAll(okio.Okio.source(in));
                    }
                }
            };
            MultipartBody body = new MultipartBody.Builder().setType(MultipartBody.FORM)
                .addFormDataPart("content", content).addFormDataPart("file", fileName, fileBody).build();
            Request request = new Request.Builder().url(BASE_URL + "/channels/" + enc(channelId) + "/messages").post(body)
                .header("Authorization", token).header("User-Agent", SuperProperties.USER_AGENT)
                .header("X-Super-Properties", SuperProperties.getBase64()).build();
            try (Response response = httpClient.newCall(request).execute()) {
                if (!response.isSuccessful()) throw new IOException("Upload failed: HTTP " + response.code());
            }
            return null;
        }, cb);
    }

    public void sendEncryptedFile(Context ctx, String channelId, String content, Uri fileUri, String fileName, javax.crypto.SecretKey key, Callback<Void> cb) {
        async("http.UPLOAD.enc", () -> {
            long size = fileSize(ctx, fileUri);
            if (size >= 0 && size + 28 > MAX_UPLOAD_BYTES) throw new IOException("File too large for E2EE upload");
            if (key == null) throw new IOException("Encryption key missing");
            RequestBody fileBody = new RequestBody() {
                @Override public MediaType contentType() { return MediaType.parse("application/octet-stream"); }
                @Override public void writeTo(okio.BufferedSink sink) throws IOException {
                    try {
                        byte[] iv = new byte[12];
                        new java.security.SecureRandom().nextBytes(iv);
                        sink.write(iv);
                        javax.crypto.Cipher cipher = javax.crypto.Cipher.getInstance("AES/GCM/NoPadding");
                        cipher.init(javax.crypto.Cipher.ENCRYPT_MODE, key, new javax.crypto.spec.GCMParameterSpec(128, iv));
                        java.io.OutputStream sinkOut = new java.io.OutputStream() {
                            @Override public void write(int b) throws IOException { sink.writeByte((byte) b); }
                            @Override public void write(byte[] b, int off, int len) throws IOException { sink.write(b, off, len); }
                        };
                        try (InputStream in = ctx.getContentResolver().openInputStream(fileUri);
                             javax.crypto.CipherOutputStream cos = new javax.crypto.CipherOutputStream(sinkOut, cipher)) {
                            if (in == null) throw new IOException("Could not open " + fileUri);
                            byte[] buf = new byte[8192];
                            int n;
                            while ((n = in.read(buf)) > 0) cos.write(buf, 0, n);
                        }
                    } catch (java.security.GeneralSecurityException e) {
                        throw new IOException("Encryption init failed", e);
                    }
                }
            };
            MultipartBody body = new MultipartBody.Builder().setType(MultipartBody.FORM)
                .addFormDataPart("content", content).addFormDataPart("file", fileName, fileBody).build();
            Request request = new Request.Builder().url(BASE_URL + "/channels/" + enc(channelId) + "/messages").post(body)
                .header("Authorization", token).header("User-Agent", SuperProperties.USER_AGENT)
                .header("X-Super-Properties", SuperProperties.getBase64()).build();
            try (Response response = httpClient.newCall(request).execute()) {
                if (!response.isSuccessful()) throw new IOException("Upload failed: HTTP " + response.code());
            }
            return null;
        }, cb);
    }

    private static String messagePath(String channelId, String messageId) {
        return "/channels/" + enc(channelId) + "/messages/" + enc(messageId);
    }

    private interface Task<T> { T run() throws Exception; }
    private interface Parse<T> { T apply(String json) throws Exception; }
    private interface Item<T> { T parse(JSONObject o) throws Exception; }

    private <T> void async(String label, Task<T> task, Callback<T> cb) {
        try {
            executor.execute(() -> {
                try {
                    T result = task.run();
                    handler.post(() -> cb.onSuccess(result));
                } catch (Exception e) {
                    String msg = e.getMessage() != null ? e.getMessage() : e.toString();
                    handler.post(() -> cb.onError(msg));
                }
            });
        } catch (RejectedExecutionException e) {
            handler.post(() -> cb.onError("API client is shut down"));
        }
    }

    private <T> void get(String path, Parse<T> parse, Callback<T> cb) {
        int q = path.indexOf('?');
        async("GET " + (q >= 0 ? path.substring(0, q) : path), () -> parse.apply(request("GET", path, null)), cb);
    }

    private <T> Callback<T> ifCurrent(int gen, Callback<T> cb) {
        return new Callback<T>() {
            @Override public void onSuccess(T result) { if (gen == requestGeneration.get()) cb.onSuccess(result); }
            @Override public void onError(String message) { if (gen == requestGeneration.get()) cb.onError(message); }
        };
    }

    private String request(String method, String path, String jsonBody) throws Exception {
        RequestBody body = jsonBody == null ? null : RequestBody.create(jsonBody, JSON);
        Request.Builder builder = new Request.Builder().url(BASE_URL + path).method(method, body)
            .header("Authorization", token).header("User-Agent", SuperProperties.USER_AGENT)
            .header("X-Super-Properties", SuperProperties.getBase64()).header("X-Discord-Locale", "en-US")
            .header("X-Discord-Timezone", TimeZone.getDefault().getID()).header("Accept-Language", "en-US,en;q=0.9");
        for (int attempt = 0; ; attempt++) {
            try (Response response = httpClient.newCall(builder.build()).execute()) {
                String respBody = response.body() != null ? response.body().string() : "";
                if (response.isSuccessful()) return respBody;
                if (response.code() == 429 && attempt < 1) {
                    long retryAfter = parseRetryAfter(response);
                    if (retryAfter <= MAX_RETRY_WAIT_MS) {
                        Thread.sleep(retryAfter);
                        continue;
                    }
                }
                throw new IOException("HTTP " + response.code() + ": " + respBody);
            }
        }
    }

    private static long parseRetryAfter(Response response) {
        String h = response.header("Retry-After");
        if (h != null) {
            try { return (long) (Double.parseDouble(h) * 1000); } catch (NumberFormatException ignored) {}
        }
        return 1000;
    }

    private static String str(JSONObject o, String key) {
        return o.isNull(key) ? null : o.optString(key, null);
    }

    private static <T> List<T> list(String json, Item<T> item, String label) throws JSONException {
        JSONArray arr = new JSONArray(json);
        List<T> out = new ArrayList<>(arr.length());
        for (int i = 0; i < arr.length(); i++) {
            try {
                T v = item.parse(arr.getJSONObject(i));
                if (v != null) out.add(v);
            } catch (Exception e) {
                Log.w(TAG, label + ": parse failure at " + i, e);
            }
        }
        return out;
    }

    private static <V> Map<String, V> map(String json, Item<Map.Entry<String, V>> item, String label) throws JSONException {
        List<Map.Entry<String, V>> entries = list(json, item, label);
        Map<String, V> out = new HashMap<>(entries.size() * 2);
        for (Map.Entry<String, V> e : entries) out.put(e.getKey(), e.getValue());
        return out;
    }

    private static String enc(String s) { return Uri.encode(s); }

    private static long fileSize(Context ctx, Uri uri) {
        try (Cursor c = ctx.getContentResolver().query(uri, null, null, null, null)) {
            if (c != null && c.moveToFirst()) {
                int col = c.getColumnIndex(OpenableColumns.SIZE);
                if (col >= 0 && !c.isNull(col)) return c.getLong(col);
            }
        } catch (Exception ignored) {}
        return -1;
    }
}