package a.b.screen;

import android.view.View;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;
import org.json.JSONArray;
import org.json.JSONObject;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import a.b.api.ApiClient;
import a.b.model.Message;

/** Paged message feed: load, merge, prepend, lazy roles. */
final class MessageFeedController {
    private static final int PAGE_SIZE = 30;
    private static final int MAX_MESSAGES = 1000;
    private static final long MEMBER_RETRY_COOLDOWN_MS = 15_000;

    private static final Comparator<Message> BY_ID = (a, b) ->
        a.id.length() != b.id.length() ? a.id.length() - b.id.length() : a.id.compareTo(b.id);

    private final MessageListScreen host;
    private final MessageAdapter adapter;
    private final RecyclerView messageList;
    private final LinearLayoutManager layoutManager;
    private final List<Message> messages;
    private final Set<String> messageIds;

    private boolean loadingNew, loadingOld, allLoaded;
    private final Set<String> pendingNickFetches = new HashSet<>();
    private final Set<String> memberChecked = new HashSet<>();
    private final ConcurrentHashMap<String, Long> memberFailedUntil = new ConcurrentHashMap<>();

    MessageFeedController(MessageListScreen host, MessageAdapter adapter,
                          RecyclerView messageList, LinearLayoutManager layoutManager,
                          List<Message> messages, Set<String> messageIds) {
        this.host = host;
        this.adapter = adapter;
        this.messageList = messageList;
        this.layoutManager = layoutManager;
        this.messages = messages;
        this.messageIds = messageIds;
    }

    boolean isAllLoaded() { return allLoaded; }

    void loadNewMessages() {
        if (loadingNew || host.activity == null || host.activity.apiClient == null) return;
        loadingNew = true;
        String after = messages.isEmpty() ? null : messages.get(messages.size() - 1).id;
        host.activity.apiClient.fetchMessages(host.channelId, null, after, PAGE_SIZE, new ApiClient.Callback<List<Message>>() {
            @Override public void onSuccess(List<Message> batch) {
                loadingNew = false;
                if (host.activity == null) return;
                boolean isInitialLoad = messages.isEmpty();
                boolean stick = isInitialLoad || host.atBottom();
                boolean changed = merge(batch, false);
                if (changed) {
                    messageList.post(() -> {
                        adapter.submitList(new ArrayList<>(messages));
                        if (stick) host.scrollToBottom();
                    });
                    fetchMissingRoles();
                }
                if (changed && after != null && batch.size() >= PAGE_SIZE) loadNewMessages();
                updateLoadMoreButton();
            }
            @Override public void onError(String err) {
                loadingNew = false;
                updateLoadMoreButton();
                if (host.activity != null && messages.isEmpty()) host.activity.toast("Couldn't load messages: " + err);
            }
        });
    }

    void loadOlderMessages() {
        if (loadingOld || allLoaded || messages.isEmpty() || host.activity == null || host.activity.apiClient == null) return;
        loadingOld = true;
        updateLoadMoreButton();
        host.activity.apiClient.fetchMessages(host.channelId, messages.get(0).id, null, PAGE_SIZE, new ApiClient.Callback<List<Message>>() {
            @Override public void onSuccess(List<Message> batch) {
                if (host.activity == null) { loadingOld = false; return; }
                if (batch == null || batch.isEmpty()) {
                    loadingOld = false;
                    allLoaded = true;
                    updateLoadMoreButton();
                } else {
                    prepend(batch);
                    fetchMissingRoles();
                }
            }
            @Override public void onError(String err) {
                loadingOld = false;
                if (host.activity != null) host.activity.toast("Error: " + err);
                updateLoadMoreButton();
            }
        });
    }

    void prepend(List<Message> batch) {
        if (batch == null || batch.isEmpty()) { loadingOld = false; updateLoadMoreButton(); return; }
        if (host.loadingAdapter == null || layoutManager == null) { loadingOld = false; return; }
        final int spinnerCount = host.loadingAdapter.getItemCount();
        String tempAnchorId = null;
        int tempOffset = 0;
        for (int i = 0; i < messageList.getChildCount(); i++) {
            View child = messageList.getChildAt(i);
            if (child == null) continue;
            int pos = messageList.getChildAdapterPosition(child);
            if (pos == RecyclerView.NO_POSITION) continue;
            int msgIdx = pos - spinnerCount;
            if (msgIdx >= 0 && msgIdx < messages.size()) {
                tempAnchorId = messages.get(msgIdx).id;
                tempOffset = child.getTop();
                break;
            }
        }
        final String anchorId = tempAnchorId;
        final int finalOffset = tempOffset;

        loadingOld = false;
        updateLoadMoreButton();
        if (!merge(batch, true)) return;

        final ArrayList<Message> newList = new ArrayList<>(messages);
        messageList.post(() -> {
            adapter.submitList(newList);
            if (anchorId == null || host.loadingAdapter == null) return;
            final int newMsgIdx = indexOf(anchorId);
            if (newMsgIdx < 0) return;
            int spinnerAfter = host.loadingAdapter.getItemCount();
            int concatPos = spinnerAfter + newMsgIdx;
            if (layoutManager.getItemCount() > concatPos) {
                layoutManager.scrollToPositionWithOffset(concatPos, finalOffset);
            }
        });
    }

    boolean merge(List<Message> incoming, boolean older) {
        if (incoming == null || incoming.isEmpty()) return false;
        boolean changed = false;
        for (Message m : incoming) {
            if (m == null) continue;
            MessageCryptoHelper.decryptMessageInPlace(host, m);
            if (m.author != null && m.author.id != null && isReal(m.memberNick) && host.guildId != null && host.activity != null && host.activity.userNickCache != null) {
                host.activity.userNickCache.put(host.guildId + "_" + m.author.id, m.memberNick);
            }
            if (m.id != null && messageIds.add(m.id)) {
                messages.add(m);
                changed = true;
            }
        }
        if (!changed) return false;
        Collections.sort(messages, BY_ID);
        while (messages.size() > MAX_MESSAGES) {
            Message gone = messages.remove(older ? messages.size() - 1 : 0);
            if (gone.id != null) {
                messageIds.remove(gone.id);
                if (adapter != null) adapter.parsedContentCache.remove(gone.id);
            }
        }
        return true;
    }

    boolean removeMessage(String id) {
        if (id == null || !messageIds.remove(id)) return false;
        int i = indexOf(id);
        if (i >= 0) messages.remove(i);
        return true;
    }

    int indexOf(String id) {
        for (int i = messages.size() - 1; i >= 0; i--) {
            if (id.equals(messages.get(i).id)) return i;
        }
        return -1;
    }

    void fetchMissingRoles() {
        if (host.guildId == null || host.activity == null || host.activity.apiClient == null) return;
        for (Message msg : messages) {
            if (msg.author == null || msg.author.id == null) continue;
            String userId = msg.author.id;
            String cacheKey = host.guildId + "_" + userId;
            boolean hasRole = host.activity.userRoleCache.containsKey(userId);
            boolean hasNick = host.activity.userNickCache != null && host.activity.userNickCache.containsKey(cacheKey);
            if (hasRole && hasNick) continue;
            if (memberChecked.contains(userId) || !canRetryMember(userId) || !pendingNickFetches.add(userId)) continue;

            host.activity.apiClient.fetchMember(host.guildId, userId, new ApiClient.Callback<JSONObject>() {
                @Override public void onSuccess(JSONObject member) {
                    pendingNickFetches.remove(userId);
                    if (host.activity == null) return;
                    boolean updated = false;
                    JSONArray rolesArr = member.optJSONArray("roles");
                    if (rolesArr != null) {
                        List<String> roleIds = new ArrayList<>();
                        for (int i = 0; i < rolesArr.length(); i++) roleIds.add(rolesArr.optString(i));
                        host.activity.userRoleCache.put(userId, roleIds);
                        updated = true;
                    }
                    if (member.has("nick")) {
                        String nick = member.isNull("nick") ? null : member.optString("nick", null);
                        if (isReal(nick)) host.activity.userNickCache.put(cacheKey, nick);
                        else host.activity.userNickCache.remove(cacheKey);
                        updated = true;
                    }
                    memberChecked.add(userId);
                    memberFailedUntil.remove(userId);
                    if (updated) adapter.refreshUser(userId);
                }
                @Override public void onError(String err) {
                    pendingNickFetches.remove(userId);
                    memberFailedUntil.put(userId, System.currentTimeMillis() + MEMBER_RETRY_COOLDOWN_MS);
                }
            });
        }
    }

    private boolean canRetryMember(String userId) {
        Long until = memberFailedUntil.get(userId);
        if (until == null) return true;
        if (System.currentTimeMillis() >= until) { memberFailedUntil.remove(userId); return true; }
        return false;
    }

    void updateLoadMoreButton() {
        if (host.activity == null || host.loadingAdapter == null) return;
        host.loadingAdapter.setVisible(loadingOld);
    }

    private static boolean isReal(String s) { return s != null && !s.isEmpty() && !"null".equals(s); }
}