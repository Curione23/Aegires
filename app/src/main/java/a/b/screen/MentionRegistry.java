package a.b.screen;

import java.util.Collections;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import a.b.api.ApiClient;
import a.b.model.DiscordUser;
import a.b.model.Message;

/** Resolves <@user>, <#channel>, <@&role> mentions; lazy-fetches unknown names. */
final class MentionRegistry {
    private static final long RETRY_COOLDOWN_MS = 15_000;

    private final MessageAdapter adapter;
    private final Set<String> pendingMemberFetches = sync();
    private final Set<String> checkedMembers = sync();
    private final Set<String> pendingUserFetches = sync();
    private final Set<String> pendingChannelFetches = sync();
    private final ConcurrentHashMap<String, Long> failedUntil = new ConcurrentHashMap<>();

    MentionRegistry(MessageAdapter adapter) {
        this.adapter = adapter;
    }

    String resolveUser(String id, Message msg) {
        if (adapter.guildId != null && adapter.activity != null) {
            String nick = adapter.activity.userNickCache.get(adapter.guildId + "_" + id);
            if (!isReal(nick) && msg.author != null && id.equals(msg.author.id) && isReal(msg.memberNick)) nick = msg.memberNick;
            if (isReal(nick)) return nick;
            requestMember(id);
        }
        String name = msg.mentionedUsers != null ? msg.mentionedUsers.get(id) : null;
        if (!isReal(name) && adapter.activity != null && adapter.activity.userNameCache != null) name = adapter.activity.userNameCache.get(id);
        if (isReal(name)) return name;
        requestUserName(id);
        return "unknown";
    }

    String resolveChannel(String id) {
        String name = adapter.activity != null && adapter.activity.channelNameCache != null
            ? adapter.activity.channelNameCache.get(id) : null;
        if (isReal(name)) return name;
        requestChannelName(id);
        return "unknown-channel";
    }

    private void requestMember(final String id) {
        if (adapter.guildId == null || adapter.activity == null || adapter.activity.apiClient == null) return;
        final String key = adapter.guildId + "_" + id;
        if (checkedMembers.contains(key) || !canRetry("m:" + key) || !pendingMemberFetches.add(key)) return;
        final String gid = adapter.guildId;
        adapter.activity.apiClient.fetchMember(gid, id, new ApiClient.Callback<org.json.JSONObject>() {
            @Override public void onSuccess(org.json.JSONObject member) {
                pendingMemberFetches.remove(key);
                if (adapter.activity == null) return;
                String nick = member.isNull("nick") ? null : member.optString("nick", null);
                boolean has = isReal(nick);
                if (has) adapter.activity.userNickCache.put(key, nick);
                checkedMembers.add(key);
                if (has) adapter.ui.post(() -> { invalidate(id, true); adapter.refreshUser(id); });
            }
            @Override public void onError(String err) {
                pendingMemberFetches.remove(key);
                markFailed("m:" + key);
            }
        });
    }

    private void requestUserName(String id) {
        if (!claim("u:", id, pendingUserFetches)) return;
        adapter.activity.apiClient.fetchUserProfile(id, new ApiClient.Callback<DiscordUser>() {
            @Override public void onSuccess(DiscordUser u) {
                String name = u == null ? null : isReal(u.globalName) ? u.globalName : u.username;
                if (isReal(name) && adapter.activity != null && adapter.activity.userNameCache != null) adapter.activity.userNameCache.put(id, name);
                settle("u:", id, pendingUserFetches, isReal(name), true);
            }
            @Override public void onError(String err) { settle("u:", id, pendingUserFetches, false, true); }
        });
    }

    private void requestChannelName(String id) {
        if (!claim("c:", id, pendingChannelFetches)) return;
        adapter.activity.apiClient.fetchChannelName(id, new ApiClient.Callback<String>() {
            @Override public void onSuccess(String name) {
                if (isReal(name) && adapter.activity != null && adapter.activity.channelNameCache != null) adapter.activity.channelNameCache.put(id, name);
                settle("c:", id, pendingChannelFetches, isReal(name), false);
            }
            @Override public void onError(String err) { settle("c:", id, pendingChannelFetches, false, false); }
        });
    }

    private boolean claim(String prefix, String id, Set<String> pending) {
        return id != null && adapter.activity != null && adapter.activity.apiClient != null
            && canRetry(prefix + id) && pending.add(id);
    }

    private void settle(String prefix, String id, Set<String> pending, boolean ok, boolean isUser) {
        pending.remove(id);
        if (!ok) {
            markFailed(prefix + id);
            return;
        }
        adapter.ui.post(() -> invalidate(id, isUser));
    }

    private boolean canRetry(String key) {
        Long until = failedUntil.get(key);
        if (until == null) return true;
        if (System.currentTimeMillis() >= until) { failedUntil.remove(key); return true; }
        return false;
    }

    private void markFailed(String key) {
        failedUntil.put(key, System.currentTimeMillis() + RETRY_COOLDOWN_MS);
    }

    private void invalidate(String id, boolean isUser) {
        adapter.invalidateMentions(id, isUser);
    }

    private static boolean isReal(String s) { return s != null && !s.isEmpty() && !"null".equals(s); }
    private static Set<String> sync() { return Collections.synchronizedSet(new HashSet<>()); }
}