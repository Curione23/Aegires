package a.b.screen;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import a.b.api.ApiClient;
import a.b.model.DiscordUser;
import a.b.model.Message;

/** Resolves <@user>, <#channel>, <@&role> mentions; lazy-fetches unknown names. */
final class MentionRegistry {
    private final MessageAdapter adapter;
    private final Set<String> pendingMemberFetches = sync();
    private final Set<String> checkedMembers = sync();
    private final Set<String> pendingUserFetches = sync();
    private final Set<String> failedUserFetches = sync();
    private final Set<String> pendingChannelFetches = sync();
    private final Set<String> failedChannelFetches = sync();

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
        if (checkedMembers.contains(key) || !pendingMemberFetches.add(key)) return;
        final String gid = adapter.guildId;
        adapter.activity.apiClient.fetchMember(gid, id, new ApiClient.Callback<org.json.JSONObject>() {
            @Override public void onSuccess(org.json.JSONObject member) {
                pendingMemberFetches.remove(key);
                checkedMembers.add(key);
                if (adapter.activity == null) return;
                String nick = member.isNull("nick") ? null : member.optString("nick", null);
                boolean has = isReal(nick);
                if (has) adapter.activity.userNickCache.put(key, nick);
                if (has) adapter.ui.post(() -> { invalidate(id, true); adapter.refreshUser(id); });
            }
            @Override public void onError(String err) {
                pendingMemberFetches.remove(key);
                checkedMembers.add(key);
            }
        });
    }

    private void requestUserName(String id) {
        if (!claim(id, pendingUserFetches, failedUserFetches)) return;
        adapter.activity.apiClient.fetchUserProfile(id, new ApiClient.Callback<DiscordUser>() {
            @Override public void onSuccess(DiscordUser u) {
                String name = u == null ? null : isReal(u.globalName) ? u.globalName : u.username;
                if (isReal(name) && adapter.activity != null && adapter.activity.userNameCache != null) adapter.activity.userNameCache.put(id, name);
                settle(id, pendingUserFetches, failedUserFetches, isReal(name), true);
            }
            @Override public void onError(String err) { settle(id, pendingUserFetches, failedUserFetches, false, true); }
        });
    }

    private void requestChannelName(String id) {
        if (!claim(id, pendingChannelFetches, failedChannelFetches)) return;
        adapter.activity.apiClient.fetchChannelName(id, new ApiClient.Callback<String>() {
            @Override public void onSuccess(String name) {
                if (isReal(name) && adapter.activity != null && adapter.activity.channelNameCache != null) adapter.activity.channelNameCache.put(id, name);
                settle(id, pendingChannelFetches, failedChannelFetches, isReal(name), false);
            }
            @Override public void onError(String err) { settle(id, pendingChannelFetches, failedChannelFetches, false, false); }
        });
    }

    private boolean claim(String id, Set<String> pending, Set<String> failed) {
        return id != null && adapter.activity != null && adapter.activity.apiClient != null
            && !failed.contains(id) && pending.add(id);
    }

    private void settle(String id, Set<String> pending, Set<String> failed, boolean ok, boolean isUser) {
        pending.remove(id);
        if (!ok) { failed.add(id); return; }
        adapter.ui.post(() -> invalidate(id, isUser));
    }

    private void invalidate(String id, boolean isUser) {
        adapter.invalidateMentions(id, isUser);
    }

    private static boolean isReal(String s) { return s != null && !s.isEmpty() && !"null".equals(s); }
    private static Set<String> sync() { return Collections.synchronizedSet(new HashSet<>()); }
}