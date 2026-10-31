package a.b.api;

import android.os.Handler;
import android.util.Log;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;
import java.io.ByteArrayOutputStream;
import java.util.Objects;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.zip.DataFormatException;
import java.util.zip.Inflater;
import a.b.util.SuperProperties;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.WebSocket;
import okhttp3.WebSocketListener;
import okio.ByteString;

public class GatewayClient extends WebSocketListener {
    private static final String TAG = "GatewayClient";
    private static final String DEFAULT_URL = "wss://gateway.discord.gg/?v=10&encoding=json&compress=zlib-stream";
    private static final int CAPABILITIES = 1734653;
    private static final int OP_DISPATCH = 0;
    private static final int OP_HEARTBEAT = 1;
    private static final int OP_IDENTIFY = 2;
    private static final int OP_RESUME = 6;
    private static final int OP_RECONNECT = 7;
    private static final int OP_INVALID_SESSION = 9;
    private static final int OP_HELLO = 10;
    private static final int OP_HEARTBEAT_ACK = 11;
    private static final int OP_GUILD_SUBSCRIBE = 14;
    private static final long DEFAULT_HEARTBEAT_INTERVAL_MS = 45000L;
    private static final long MAX_BACKOFF_MS = 30000L;

    public interface Listener {
        void onReady(JSONObject readyPayload);
        void onDispatch(String eventType, JSONObject data);
        void onDisconnected(String reason);
    }

    public static final int INTENT_GUILDS = 1 << 0;
    public static final int INTENT_GUILD_MESSAGES = 1 << 9;
    public static final int INTENT_DIRECT_MESSAGES = 1 << 12;
    public static final int INTENT_MESSAGE_CONTENT = 1 << 15;

    private final String token;
    private final int intents;
    private final Handler handler;
    private final Listener listener;
    private final OkHttpClient httpClient = new OkHttpClient.Builder().readTimeout(0, TimeUnit.MILLISECONDS).build();
    
    private volatile boolean shouldConnect;
    private volatile boolean running;
    private volatile WebSocket webSocket;
    private volatile int attempt;
    private volatile ScheduledExecutorService heartbeat;
    private volatile boolean heartbeatAcked;
    private volatile long heartbeatSentNanos;
    private final ZlibStream zlib = new ZlibStream();
    private volatile long lastSeq = -1L;
    private volatile String sessionId;
    private volatile String resumeUrl;
    private volatile boolean ready;
    private volatile String subscribedGuild, subscribedChannel;
    private volatile String pendingGuild, pendingChannel;
    private final java.util.concurrent.atomic.AtomicInteger socketGen = new java.util.concurrent.atomic.AtomicInteger();
    private final Runnable reconnectRunnable = this::doConnect;

    public GatewayClient(String rawToken, int intents, Handler handler, Listener listener) {
        this.token = rawToken;
        this.intents = intents;
        this.handler = handler;
        this.listener = listener;
    }

    public void connect() {
        if (shouldConnect) return;
        shouldConnect = true;
        attempt = 0;
        doConnect();
    }

    public void shutdown() {
        shouldConnect = false;
        running = false;
        socketGen.incrementAndGet();
        if (handler != null) handler.removeCallbacks(reconnectRunnable);
        if (webSocket != null) {
            webSocket.close(1000, "shutdown");
            webSocket = null;
        }
        stopHeartbeat();
        zlib.end();
    }

    private void doConnect() {
        if (!shouldConnect) return;
        running = true;
        attempt++;
        zlib.end();
        stopHeartbeat();
        WebSocket old = webSocket;
        final int gen = socketGen.incrementAndGet();
        if (old != null) { try { old.cancel(); } catch (Exception ignored) {} }
        String url = (resumeUrl != null) ? resumeUrl : DEFAULT_URL;
        Request request = new Request.Builder().url(url)
            .header("User-Agent", SuperProperties.USER_AGENT)
            .header("X-Super-Properties", SuperProperties.getBase64()).build();
        webSocket = httpClient.newWebSocket(request, new WebSocketListener() {
            private boolean live() { return gen == socketGen.get(); }
            @Override public void onOpen(WebSocket ws, Response r) { if (live()) GatewayClient.this.onOpen(ws, r); }
            @Override public void onMessage(WebSocket ws, String t) { if (live()) GatewayClient.this.onMessage(ws, t); }
            @Override public void onMessage(WebSocket ws, ByteString b) { if (live()) GatewayClient.this.onMessage(ws, b); }
            @Override public void onClosing(WebSocket ws, int c, String r) { if (live()) GatewayClient.this.onClosing(ws, c, r); }
            @Override public void onFailure(WebSocket ws, Throwable t, Response r) { if (live()) GatewayClient.this.onFailure(ws, t, r); }
        });
    }

    private void scheduleReconnect(String reason) {
        if (!shouldConnect) return;
        long backoff = Math.min(MAX_BACKOFF_MS, 1000L << Math.min(attempt, 5));
        if (handler != null) {
            handler.removeCallbacks(reconnectRunnable);
            handler.postDelayed(reconnectRunnable, backoff);
        }
    }

    public void subscribeToGuild(String guildId, String channelId) {
        if (guildId == null) return;
        if (guildId.equals(subscribedGuild) && Objects.equals(channelId, subscribedChannel)) return;
        if (!ready) {
            pendingGuild = guildId;
            pendingChannel = channelId;
            return;
        }
        Object channels = channelId == null ? null : obj(channelId, new JSONArray().put(new JSONArray().put(0).put(99)));
        send(OP_GUILD_SUBSCRIBE, obj("guild_id", guildId, "typing", true, "activities", true, "threads", true, "channels", channels));
        subscribedGuild = guildId;
        subscribedChannel = channelId;
    }

    public void requestMemberRanges(String guildId, String channelId, int startIndex) {
        if (!ready || guildId == null || channelId == null) return;
        java.util.LinkedHashSet<Integer> starts = new java.util.LinkedHashSet<>();
        starts.add(0);
        starts.add(startIndex);
        starts.add(startIndex + 100);
        if (startIndex == 0) starts.add(200);
        JSONArray ranges = new JSONArray();
        for (int s : starts) ranges.put(new JSONArray().put(s).put(s + 99));
        send(OP_GUILD_SUBSCRIBE, obj("guild_id", guildId, "typing", true, "activities", true, "threads", true,
            "channels", obj(channelId, ranges)));
    }

    public void resetSubscriptionCache() {
        subscribedGuild = null;
        subscribedChannel = null;
    }

    @Override
    public void onOpen(WebSocket webSocket, Response response) {
        attempt = 0;
    }

    @Override
    public void onMessage(WebSocket webSocket, String text) {
        handlePayload(text);
    }

    @Override
    public void onMessage(WebSocket webSocket, ByteString bytes) {
        try {
            String json = zlib.feed(bytes.toByteArray());
            if (json != null) handlePayload(json);
        } catch (DataFormatException e) {
            closeAndReconnect(webSocket, "zlib corruption");
        }
    }

    @Override
    public void onClosing(WebSocket webSocket, int code, String reason) {
        webSocket.close(1000, null);
        onDisconnected("closed (" + code + ")");
    }

    @Override
    public void onFailure(WebSocket webSocket, Throwable t, Response response) {
        String msg = t.getMessage() != null ? t.getMessage() : t.toString();
        onDisconnected("failure: " + msg);
    }

    private void closeAndReconnect(WebSocket ws, String reason) {
        if (ws != null) {
            try { ws.close(4000, reason); } catch (Exception ignored) {}
        }
        onDisconnected(reason);
    }

    private void onDisconnected(String reason) {
        running = false;
        ready = false;
        stopHeartbeat();
        if (handler != null && shouldConnect) handler.post(() -> listener.onDisconnected(reason));
        scheduleReconnect(reason);
    }

    private void handlePayload(String raw) {
        try {
            JSONObject p = new JSONObject(raw);
            JSONObject d = p.optJSONObject("d");
            switch (p.optInt("op", -1)) {
                case OP_HELLO: onHello(d); break;
                case OP_HEARTBEAT: sendHeartbeat(); break;
                case OP_HEARTBEAT_ACK:
                    heartbeatAcked = true;
                    long sent = heartbeatSentNanos;
                    if (sent > 0L) {
                        heartbeatSentNanos = 0L;
                    }
                    break;
                case OP_RECONNECT: closeAndReconnect(webSocket, "op7 reconnect"); break;
                case OP_INVALID_SESSION:
                    if (!p.optBoolean("d", false)) dropSession();
                    closeAndReconnect(webSocket, "op9 invalid session");
                    break;
                case OP_DISPATCH: onDispatch(p, d); break;
            }
        } catch (Exception ignored) {}
    }

    private void onHello(JSONObject d) {
        long interval = d != null ? d.optLong("heartbeat_interval", DEFAULT_HEARTBEAT_INTERVAL_MS) : DEFAULT_HEARTBEAT_INTERVAL_MS;
        startHeartbeat(interval);
        if (sessionId != null && lastSeq >= 0) {
            send(OP_RESUME, obj("token", token, "session_id", sessionId, "seq", lastSeq));
        } else {
            JSONObject props = obj("os", "Android", "browser", "Discord Android", "device", "Discord Android",
                "system_locale", "en-US", "client_version", SuperProperties.CLIENT_VERSION,
                "release_channel", "google-play", "client_build_number", SuperProperties.CLIENT_BUILD);
            send(OP_IDENTIFY, obj("token", token, "intents", intents, "properties", props, "capabilities", CAPABILITIES,
                "client_state", obj("guild_versions", new JSONObject(), "api_code_version", 0)));
        }
    }

    private void onDispatch(JSONObject p, JSONObject d) {
        if (!p.isNull("s")) lastSeq = p.optLong("s", lastSeq);
        String type = p.optString("t", "");
        boolean isReady = "READY".equals(type);
        if (isReady || "RESUMED".equals(type)) {
            if (isReady && d != null) {
                if (!d.isNull("session_id")) sessionId = d.optString("session_id");
                if (!d.isNull("resume_gateway_url")) resumeUrl = d.optString("resume_gateway_url") + "/?v=10&encoding=json&compress=zlib-stream";
            }
            resetSubscriptionCache();
            ready = true;
            attempt = 0;
            String g = pendingGuild, c = pendingChannel;
            pendingGuild = pendingChannel = null;
            if (g != null) subscribeToGuild(g, c);
            handler.post(() -> listener.onReady(d));
        }
        handler.post(() -> listener.onDispatch(type, d));
    }

    private void dropSession() {
        sessionId = null;
        resumeUrl = null;
        lastSeq = -1L;
        resetSubscriptionCache();
    }

    private void sendHeartbeat() {
        heartbeatSentNanos = System.nanoTime();
        send(OP_HEARTBEAT, lastSeq < 0 ? null : lastSeq);
    }

    private void startHeartbeat(long intervalMs) {
        stopHeartbeat();
        heartbeatAcked = true;
        ScheduledExecutorService ex = Executors.newSingleThreadScheduledExecutor();
        heartbeat = ex;
        long jitter = (long) (intervalMs * Math.random());
        ex.scheduleWithFixedDelay(() -> {
            if (!running) return;
            if (!heartbeatAcked) {
                closeAndReconnect(webSocket, "heartbeat timeout");
                return;
            }
            heartbeatAcked = false;
            sendHeartbeat();
        }, jitter, intervalMs, TimeUnit.MILLISECONDS);
    }

    private void stopHeartbeat() {
        ScheduledExecutorService ex = heartbeat;
        heartbeat = null;
        if (ex != null) ex.shutdownNow();
    }

    private void send(int op, Object d) {
        WebSocket ws = webSocket;
        if (ws == null) return;
        JSONObject p = obj("op", op, "d", d == null ? JSONObject.NULL : d);
        ws.send(p.toString());
    }

    private static JSONObject obj(Object... keyValues) {
        JSONObject o = new JSONObject();
        try {
            for (int i = 0; i < keyValues.length; i += 2) o.put((String) keyValues[i], keyValues[i + 1]);
        } catch (JSONException e) {
            throw new IllegalArgumentException(e);
        }
        return o;
    }

    private static final class ZlibStream {
        private final Inflater inflater = new Inflater();
        private final ByteArrayOutputStream decoded = new ByteArrayOutputStream(64 * 1024);
        private final byte[] buf = new byte[8192];
        private int tail;

        String feed(byte[] chunk) throws DataFormatException {
            inflater.setInput(chunk);
            int n;
            while ((n = inflater.inflate(buf)) > 0) decoded.write(buf, 0, n);
            for (int i = Math.max(0, chunk.length - 4); i < chunk.length; i++) {
                tail = (tail << 8) | (chunk[i] & 0xFF);
            }
            if (tail != 0x0000FFFF) return null;
            String json = new String(decoded.toByteArray(), java.nio.charset.StandardCharsets.UTF_8);
            decoded.reset();
            tail = 0;
            return json;
        }

        void end() {
            try { inflater.reset(); } catch (Exception ignored) {}
            decoded.reset();
            tail = 0;
        }
    }
}