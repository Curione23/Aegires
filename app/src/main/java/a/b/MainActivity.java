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
import a.b.util.Crypto;
import a.b.util.DiskCache;
import a.b.util.MemberListStore;
import a.b.util.MentionStore;
import a.b.util.MessageCacheManager;
import a.b.util.Ui;
import a.b.model.MentionItem;
import a.b.model.Message;
import a.b.util.PermissionUtil;
import a.b.util.TokenStore;
import java.util.List;
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
    public Crypto.Preset e2eePreset = Crypto.Preset.AES256;
    public GatewayClient gateway;
    public MentionStore mentions;
    public MemberListStore memberList = new MemberListStore();
    public DiskCache diskCache;
    public MessageCacheManager messageCache;
    public String selfUserId;
    public boolean gatewayConnected;
    public ConcurrentHashMap<String, String> userNameCache = new ConcurrentHashMap<>();
    public ConcurrentHashMap<String, String> channelNameCache = new ConcurrentHashMap<>();
    public ConcurrentHashMap<String, Role> roleCache = new ConcurrentHashMap<>();
    public final ConcurrentHashMap<String, List<Channel>> channelCache = new ConcurrentHashMap<>();
    public final ConcurrentHashMap<String, List<Guild>> guildCache = new ConcurrentHashMap<>();
    public final ConcurrentHashMap<String, GuildInfo> guildInfoCache = new ConcurrentHashMap<>();
    public final ConcurrentHashMap<String, List<String>> userRoleCache = new ConcurrentHashMap<>();
    public final ConcurrentHashMap<String, String> userNickCache = new ConcurrentHashMap<>();
    public final java.util.concurrent.atomic.AtomicInteger derivationGen = new java.util.concurrent.atomic.AtomicInteger(0);
    private boolean firstResume = true;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setTheme(android.R.style.Theme_DeviceDefault_NoActionBar);
        androidx.core.view.WindowCompat.setDecorFitsSystemWindows(getWindow(), false);
        getWindow().setSoftInputMode(android.view.WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE);

        rootFrame = new FrameLayout(this);
        rootFrame.setBackgroundColor(Ui.BG_TERTIARY);
        getWindow().setStatusBarColor(Ui.BG_TERTIARY);
        getWindow().setNavigationBarColor(Ui.BG_TERTIARY);
        
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
        mentions = new MentionStore(this);
        diskCache = new DiskCache(this);
        messageCache = new MessageCacheManager(this);
        
        Crypto.Preset saved = Crypto.Preset.fromIndex(getSharedPreferences("e2ee", MODE_PRIVATE).getInt("preset", Crypto.Preset.AES256.index));
        if (saved != null) e2eePreset = saved;

        String savedToken = TokenStore.load(this);
        if (savedToken != null) {
            setToken(savedToken);
            resetTo(new HomeScreen(this));
        } else {
            navigate(new LoginScreen());
        }
    }

    public void setToken(String token) {
        this.token = token;
        String bare = token.trim();
        int sp = bare.indexOf(' ');
        this.userId = PermissionUtil.getUserIdFromToken(sp >= 0 ? bare.substring(sp + 1).trim() : bare);
        diskCache.setAccount(userId);
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
                JSONObject u = ready != null ? ready.optJSONObject("user") : null;
                if (u != null) selfUserId = u.optString("id", null);
                gatewayConnected = true;
                refreshMentions();
                for (Screen s : screenStack.toArray(new Screen[0])) {
                    if (s instanceof MessageListScreen) ((MessageListScreen) s).onGatewayReady();
                }
            }

            @Override public void onDispatch(String type, JSONObject d) {
                if (d != null) invalidateCaches(type, d);
                
                String chId = d != null ? d.optString("channel_id", null) : null;
                boolean isActive = chId != null && chId.equals(currentChannelId());
                
                if (d != null && "MESSAGE_CREATE".equals(type)) {
                    if (chId != null && !isActive) {
                        try { messageCache.appendMessage(chId, Message.fromJson(d)); } catch (Exception ignored) {}
                    }
                    mentions.onMessageCreate(d, selfId(), currentChannelId());
                } else if (d != null && "MESSAGE_UPDATE".equals(type)) {
                    if (chId != null) {
                        try { messageCache.updateMessage(chId, Message.fromJson(d)); } catch (Exception ignored) {}
                        if (isActive) saveActiveCache();
                    }
                } else if (d != null && "MESSAGE_DELETE".equals(type)) {
                    if (chId != null) {
                        messageCache.deleteMessage(chId, d.optString("id", ""));
                        if (isActive) saveActiveCache();
                    }
                    mentions.remove(d.optString("id", ""));
                } else if (d != null && "GUILD_MEMBER_LIST_UPDATE".equals(type)) {
                    memberList.handle(d, userNickCache, userNameCache);
                }

                for (Screen s : screenStack.toArray(new Screen[0])) {
                    if (s instanceof MessageListScreen) ((MessageListScreen) s).onGatewayEvent(type, d);
                }
            }
            
            private void saveActiveCache() {
                if (screenStack.isEmpty()) return;
                Screen top = screenStack.peek();
                if (top instanceof MessageListScreen) {
                    MessageListScreen msgScreen = (MessageListScreen) top;
                    messageCache.saveLastChannelState(msgScreen.guildId, msgScreen.channelId, messageCache.getMessages(msgScreen.channelId));
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
                if ("GUILD_CREATE".equals(type)) {
                    channelCache.remove(guildId);
                    guildCache.clear();
                    return;
                }
                if ("GUILD_DELETE".equals(type)) {
                    channelCache.remove(guildId);
                    diskCache.deleteChannels(guildId);
                    guildCache.clear();
                    diskCache.deleteGuilds();
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

    public void setE2eePreset(Crypto.Preset preset) {
        if (preset == null) return;
        e2eePreset = preset;
        getSharedPreferences("e2ee", MODE_PRIVATE).edit().putInt("preset", preset.index).apply();
    }

    public String selfId() { return selfUserId != null ? selfUserId : userId; }

    public String currentChannelId() {
        if (!screenStack.isEmpty() && screenStack.peek() instanceof MessageListScreen) {
            return ((MessageListScreen) screenStack.peek()).getChannelId();
        }
        return null;
    }

    public void refreshMentions() {
        if (apiClient == null || apiClient.isBotAccount()) return;
        apiClient.fetchMentions(new ApiClient.Callback<List<MentionItem>>() {
            @Override public void onSuccess(List<MentionItem> result) { mentions.mergeRemote(result); }
            @Override public void onError(String msg) {}
        });
    }

    public void logout() {
        derivationGen.incrementAndGet();
        if (gateway != null) { gateway.shutdown(); gateway = null; }
        gatewayConnected = false;
        selfUserId = null; e2eeKey = null; e2eeEnabled = false;
        TokenStore.clear(this);

        channelCache.clear(); guildCache.clear(); guildInfoCache.clear();
        userNameCache.clear(); channelNameCache.clear(); roleCache.clear();
        userNickCache.clear(); mentions.clear(); memberList.clear();
        diskCache.clearAll(); diskCache.setAccount(null);

        if (apiClient != null) apiClient.shutdown();
        apiClient = new ApiClient(handler);
        token = null; userId = null;
        resetTo(new LoginScreen());
    }

    public void navigate(Screen screen) {
        if (fullscreenOverlay != null) hideFullscreen();
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
                wv.stopLoading(); wv.loadUrl("about:blank"); wv.clearHistory();
                wv.clearCache(true); wv.destroy();
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

    @Override public void onBackPressed() {
        if (fullscreenOverlay != null) { hideFullscreen(); return; }
        goBack();
    }

    public void resetTo(Screen screen) {
        if (fullscreenOverlay != null) hideFullscreen();
        while (!screenStack.isEmpty()) {
            Screen s = screenStack.pop();
            try { s.onPause(); } catch (Exception ignored) {}
            View v = s.getView();
            if (v != null) rootFrame.removeView(v);
            try { s.onDestroy(); } catch (Exception ignored) {}
        }
        navigate(screen);
    }

    @Override protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (!screenStack.isEmpty()) screenStack.peek().onActivityResult(requestCode, resultCode, data);
    }

    @Override protected void onResume() {
        super.onResume();
        if (firstResume) { firstResume = false; return; }
        if (screenStack.isEmpty()) return;
        try { screenStack.peek().onResume(); } catch (Exception ignored) {}
    }

    @Override protected void onPause() {
        super.onPause();
        if (screenStack.isEmpty()) return;
        try { screenStack.peek().onPause(); } catch (Exception ignored) {}
    }

    @Override protected void onDestroy() {
        if (gateway != null) gateway.shutdown();
        if (apiClient != null) apiClient.shutdown();
        super.onDestroy();
    }

    public void toast(String msg) { Toast.makeText(this, msg, Toast.LENGTH_SHORT).show(); }
}