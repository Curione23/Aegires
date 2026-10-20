package a.b;

import android.app.Activity;
import android.os.Bundle;
import android.os.Handler;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.Toast;
import android.content.Intent;
import org.json.JSONObject;
import java.util.Stack;
import java.util.concurrent.ConcurrentHashMap;
import a.b.api.ApiClient;
import a.b.api.GatewayClient;
import a.b.screen.*;
import a.b.util.ImageLoader;
import a.b.util.PermissionUtil;
import a.b.util.TokenStore;
import java.util.List;
import java.util.Map;
import a.b.model.Channel;
import a.b.model.Guild;
import a.b.model.GuildInfo;
import a.b.model.Role;

public class MainActivity extends Activity {
    public FrameLayout rootFrame;
    public View fullscreenOverlay;
    public Stack<Screen> screenStack = new Stack<>();
    public Handler handler = new Handler();
    public ApiClient apiClient;
    public ImageLoader imageLoader;
    public String token;
    public String userId;
    public javax.crypto.SecretKey e2eeKey;
    public boolean e2eeEnabled;
    public String uploadHost = "discord";
    private static final long HEAP_REPORT_MS = 30000L;
    public GatewayClient gateway;
    public String selfUserId;
    public boolean gatewayConnected;
    public ConcurrentHashMap<String, String> userNameCache = new ConcurrentHashMap<>();
    public ConcurrentHashMap<String, String> channelNameCache = new ConcurrentHashMap<>();
    public ConcurrentHashMap<String, Role> roleCache = new ConcurrentHashMap<>();
    public final ConcurrentHashMap<String, List<Channel>> channelCache = new ConcurrentHashMap<>();
    public final ConcurrentHashMap<String, List<Guild>> guildCache = new ConcurrentHashMap<>();
    public final ConcurrentHashMap<String, GuildInfo> guildInfoCache = new ConcurrentHashMap<>();
    public final ConcurrentHashMap<String, List<String>> userRoleCache = new ConcurrentHashMap<>();

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setTheme(android.R.style.Theme_DeviceDefault_NoActionBar);
        androidx.core.view.WindowCompat.setDecorFitsSystemWindows(getWindow(), false);
        getWindow().setSoftInputMode(android.view.WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE);
        
        rootFrame = new FrameLayout(this);
        rootFrame.setBackgroundColor(0xFF000000);
        androidx.core.view.ViewCompat.setOnApplyWindowInsetsListener(rootFrame, (v, windowInsets) -> {
            androidx.core.graphics.Insets bars = windowInsets.getInsets(
                    androidx.core.view.WindowInsetsCompat.Type.systemBars() | androidx.core.view.WindowInsetsCompat.Type.displayCutout());
            androidx.core.graphics.Insets ime = windowInsets.getInsets(androidx.core.view.WindowInsetsCompat.Type.ime());
            v.setPadding(bars.left, bars.top, bars.right, Math.max(bars.bottom, ime.bottom));
            return windowInsets;
        });
        setContentView(rootFrame);

        apiClient = new ApiClient(handler);
        imageLoader = new ImageLoader();

        String saved = TokenStore.load(this);
        if (saved != null) {
            setToken(saved);
            resetTo(new GuildListScreen());
        } else {
            navigate(new LoginScreen());
        }
    }

    public void setToken(String token) {
        this.token = token;
        this.userId = PermissionUtil.getUserIdFromToken(token);
        apiClient.setToken(token);
        startGateway();
    }

    private void startGateway() {
        if (gateway != null) {
            gateway.shutdown();
            gateway = null;
        }
        int intents = GatewayClient.INTENT_GUILDS | GatewayClient.INTENT_GUILD_MESSAGES | 
                      GatewayClient.INTENT_DIRECT_MESSAGES | GatewayClient.INTENT_MESSAGE_CONTENT | (1 << 11);
        
        gateway = apiClient.createGateway(intents, new GatewayClient.Listener() {
            @Override public void onReady(JSONObject ready) {
                JSONObject u = ready.optJSONObject("user");
                if (u != null) selfUserId = u.optString("id", null);
                gatewayConnected = true;
                for (Screen s : screenStack.toArray(new Screen[0])) {
                    if (s instanceof MessageListScreen) ((MessageListScreen) s).onGatewayReady();
                }
            }

            @Override public void onDispatch(String type, JSONObject d) {
                if (d != null) invalidateCaches(type, d);
                for (Screen s : screenStack.toArray(new Screen[0])) {
                    if (s instanceof MessageListScreen) ((MessageListScreen) s).onGatewayEvent(type, d);
                }
            }

            private void invalidateCaches(String type, JSONObject d) {
                if (type == null) return;
                String guildId = d.optString("guild_id", null);
                if (guildId == null || guildId.isEmpty()) guildId = d.optString("id", null);
                if (guildId == null || guildId.isEmpty()) return;

                if ("CHANNEL_CREATE".equals(type) || "CHANNEL_UPDATE".equals(type) || "CHANNEL_DELETE".equals(type) ||
                    "GUILD_ROLE_CREATE".equals(type) || "GUILD_ROLE_UPDATE".equals(type) || "GUILD_ROLE_DELETE".equals(type) ||
                    "GUILD_UPDATE".equals(type)) {
                    channelCache.remove(guildId);
                    guildInfoCache.remove(guildId);
                    return;
                }
                if ("GUILD_CREATE".equals(type) || "GUILD_DELETE".equals(type)) {
                    channelCache.remove(guildId);
                    guildCache.clear();
                    return;
                }
                if ("GUILD_MEMBER_UPDATE".equals(type)) {
                    JSONObject user = d.optJSONObject("user");
                    if (user != null && selfUserId != null && selfUserId.equals(user.optString("id", null))) {
                        channelCache.remove(guildId);
                    }
                }
            }

            @Override public void onDisconnected(String reason) {
                gatewayConnected = false;
            }
        });
        gateway.connect();
    }

    public void logout() {
        if (gateway != null) {
            gateway.shutdown();
            gateway = null;
        }
        gatewayConnected = false;
        selfUserId = null;
        e2eeKey = null;
        e2eeEnabled = false;
        TokenStore.clear(this);
        
        channelCache.clear();
        guildCache.clear();
        guildInfoCache.clear();
        userNameCache.clear();
        channelNameCache.clear();
        roleCache.clear();
        
        apiClient = new ApiClient(handler);
        token = null;
        userId = null;
        resetTo(new LoginScreen());
    }

    public void navigate(Screen screen) {
        if (!screenStack.isEmpty()) {
            Screen current = screenStack.peek();
            current.onPause();
            rootFrame.removeView(current.getView());
        }
        screenStack.push(screen);
        rootFrame.addView(screen.onCreateView(this, rootFrame));
        screen.onResume();
    }

    public void goBack() {
        if (!screenStack.isEmpty()) {
            Screen current = screenStack.pop();
            current.onPause();
            rootFrame.removeView(current.getView());
            current.onDestroy();
        }
        if (!screenStack.isEmpty()) {
            Screen previous = screenStack.peek();
            rootFrame.addView(previous.getView());
            previous.onResume();
        } else {
            finish();
        }
    }

    public void showFullscreen(View content) {
        if (fullscreenOverlay != null) rootFrame.removeView(fullscreenOverlay);
        fullscreenOverlay = content;
        rootFrame.addView(fullscreenOverlay, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
    }

    public void hideFullscreen() {
        if (fullscreenOverlay == null) return;
        android.webkit.WebView wv = findWebView(fullscreenOverlay);
        if (wv != null) {
            try {
                wv.stopLoading();
                wv.loadUrl("about:blank");
                wv.clearHistory();
                wv.clearCache(true);
                wv.destroy();
            } catch (Exception ignored) {}
        }
        rootFrame.removeView(fullscreenOverlay);
        fullscreenOverlay = null;
    }

    private android.webkit.WebView findWebView(View v) {
        if (v instanceof android.webkit.WebView) return (android.webkit.WebView) v;
        if (v instanceof ViewGroup) {
            ViewGroup g = (ViewGroup) v;
            for (int i = 0; i < g.getChildCount(); i++) {
                android.webkit.WebView found = findWebView(g.getChildAt(i));
                if (found != null) return found;
            }
        }
        return null;
    }

    @Override
    public void onBackPressed() {
        if (fullscreenOverlay != null) hideFullscreen();
        else goBack();
    }

    public void resetTo(Screen screen) {
        while (!screenStack.isEmpty()) {
            Screen s = screenStack.pop();
            try { s.onPause(); } catch (Exception ignored) {}
            View v = s.getView();
            if (v != null) rootFrame.removeView(v);
            try { s.onDestroy(); } catch (Exception ignored) {}
        }
        navigate(screen);
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (!screenStack.isEmpty()) screenStack.peek().onActivityResult(requestCode, resultCode, data);
    }

    @Override
    protected void onDestroy() {
        if (gateway != null) gateway.shutdown();
        if (apiClient != null) apiClient.shutdown();
        super.onDestroy();
    }

    public void toast(String msg) {
        Toast.makeText(this, msg, Toast.LENGTH_SHORT).show();
    }

    private String cacheSummary() {
        StringBuilder sb = new StringBuilder(64);
        if (imageLoader != null) {
            sb.append(" imgCache=").append(fmtBytes(imageLoader.cacheBytes())).append('/').append(fmtBytes(imageLoader.cacheMax()));
        }
        if (!screenStack.isEmpty() && screenStack.peek() instanceof MessageListScreen) {
            MessageListScreen s = (MessageListScreen) screenStack.peek();
            if (s.adapter != null) {
                sb.append(" msgs=").append(s.messages.size())
                  .append(" parsed=").append(s.adapter.parsedContentCache.size())
                  .append(" emoji=").append(s.adapter.emojiCache.size());
            }
        }
        if (gateway != null) sb.append(" gw=").append(gatewayConnected ? "up" : "down");
        return sb.toString();
    }

    private static String fmtBytes(long bytes) {
        if (bytes < 1024) return bytes + "B";
        if (bytes < 1024L * 1024L) return (bytes / 1024) + "K";
        return String.format(java.util.Locale.US, "%.1fM", bytes / (1024.0 * 1024.0));
    }
}