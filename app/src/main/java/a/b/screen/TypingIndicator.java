package a.b.screen;

import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.TextView;

import a.b.api.ApiClient;
import a.b.util.Ui;

import org.json.JSONObject;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/** Live "X is typing..." strip. */
final class TypingIndicator {
    private static final int MATCH = -1;
    private static final long TYPING_EXPIRY_MS = 10000;
    private static final long NICK_RETRY_COOLDOWN_MS = 15_000;

    private static final class TypingEntry {
        final String name;
        final long at;

        TypingEntry(String name, long at) {
            this.name = name;
            this.at = at;
        }
    }

    private final MessageListScreen host;
    private final TextView typingText;
    private final Map<String, TypingEntry> typingUsers = new HashMap<>();
    private final Set<String> typingNickChecked = new HashSet<>();
    private final ConcurrentHashMap<String, Long> nickFailedUntil = new ConcurrentHashMap<>();
    private final Handler typingHandler = new Handler(Looper.getMainLooper());
    private final Runnable expiryCheck = this::expiryCheck;

    TypingIndicator(MessageListScreen host) {
        this.host = host;
        this.typingText = new TextView(host.activity);
        typingText.setText("");
        typingText.setTextColor(Ui.MUTED);
        typingText.setTextSize(12);
        typingText.setPadding(dp(16), dp(2), dp(16), dp(2));
        typingText.setBackgroundColor(Ui.BG);
        typingText.setGravity(Gravity.CENTER_VERTICAL);
        typingText.setLayoutParams(new LinearLayout.LayoutParams(MATCH, dp(20)));
        typingText.setVisibility(View.INVISIBLE);
    }

    View getView() {
        return typingText;
    }

    void handleTypingStart(JSONObject d) {
        if (host.activity == null || d == null) return;
        String userId = d.optString("user_id", null);
        if (userId == null || userId.equals(host.activity.selfId())) return;
        String nick = null;
        String globalName = null;
        JSONObject member = d.optJSONObject("member");
        if (member != null) {
            if (!member.isNull("nick")) nick = member.optString("nick", null);
            JSONObject user = member.optJSONObject("user");
            if (user != null) {
                String g = user.isNull("global_name") ? null : user.optString("global_name", null);
                globalName = isReal(g) ? g : user.optString("username", null);
            }
        }
        if (!isReal(nick) && host.guildId != null)
            nick = host.activity.userNickCache.get(host.guildId + "_" + userId);
        String name =
                isReal(nick)
                        ? nick
                        : isReal(globalName) ? globalName : host.activity.userNameCache.get(userId);
        if (!isReal(name)) name = "Someone";
        if (!isReal(nick)) lookupTypingNick(userId);
        typingUsers.put(userId, new TypingEntry(name, System.currentTimeMillis()));
        update();
        scheduleNextExpiry();
    }

    void removeUser(String userId) {
        if (userId != null && typingUsers.remove(userId) != null) {
            update();
            scheduleNextExpiry();
        }
    }

    private void lookupTypingNick(final String userId) {
        if (host.guildId == null || host.activity == null || host.activity.apiClient == null)
            return;
        final String key = host.guildId + "_" + userId;
        if (typingNickChecked.contains(key)) return;
        Long until = nickFailedUntil.get(key);
        if (until != null) {
            if (System.currentTimeMillis() < until) return;
            nickFailedUntil.remove(key);
        }
        host.activity.apiClient.fetchMember(
                host.guildId,
                userId,
                new ApiClient.Callback<JSONObject>() {
                    @Override
                    public void onSuccess(JSONObject member) {
                        if (host.activity == null || member == null) {
                            nickFailedUntil.put(
                                    key, System.currentTimeMillis() + NICK_RETRY_COOLDOWN_MS);
                            return;
                        }
                        String nick = member.isNull("nick") ? null : member.optString("nick", null);
                        if (!isReal(nick)) {
                            // Server acknowledged but has no nick — nothing to learn, don't retry.
                            typingNickChecked.add(key);
                            return;
                        }
                        host.activity.userNickCache.put(key, nick);
                        typingNickChecked.add(key);
                        TypingEntry existing = typingUsers.get(userId);
                        if (existing != null) {
                            typingUsers.put(userId, new TypingEntry(nick, existing.at));
                            update();
                        }
                    }

                    @Override
                    public void onError(String err) {
                        nickFailedUntil.put(
                                key, System.currentTimeMillis() + NICK_RETRY_COOLDOWN_MS);
                    }
                });
    }

    private void expiryCheck() {
        update();
        if (!typingUsers.isEmpty()) scheduleNextExpiry();
    }

    private void scheduleNextExpiry() {
        typingHandler.removeCallbacks(expiryCheck);
        long now = System.currentTimeMillis();
        long earliest = Long.MAX_VALUE;
        for (TypingEntry e : typingUsers.values()) {
            long expiresAt = e.at + TYPING_EXPIRY_MS;
            if (expiresAt < earliest) earliest = expiresAt;
        }
        if (earliest == Long.MAX_VALUE) return;
        typingHandler.postDelayed(expiryCheck, Math.max(0, earliest - now));
    }

    private void update() {
        if (host.activity == null || typingText == null) return;
        long cutoff = System.currentTimeMillis() - TYPING_EXPIRY_MS;
        Iterator<Map.Entry<String, TypingEntry>> it = typingUsers.entrySet().iterator();
        while (it.hasNext()) {
            if (it.next().getValue().at < cutoff) it.remove();
        }
        if (typingUsers.isEmpty()) {
            typingText.setVisibility(View.INVISIBLE);
            return;
        }
        List<String> names = new ArrayList<>(typingUsers.size());
        for (TypingEntry e : typingUsers.values()) names.add(e.name);
        StringBuilder sb = new StringBuilder();
        if (names.size() == 1) sb.append(names.get(0)).append(" is typing...");
        else if (names.size() == 2)
            sb.append(names.get(0)).append(" and ").append(names.get(1)).append(" are typing...");
        else sb.append("Several people are typing...");
        typingText.setText(sb.toString());
        typingText.setVisibility(View.VISIBLE);
        if (host.atBottom()) host.scrollToBottom();
    }

    void release() {
        typingHandler.removeCallbacksAndMessages(null);
        typingUsers.clear();
    }

    private static boolean isReal(String s) {
        return s != null && !s.isEmpty() && !"null".equals(s);
    }

    private int dp(int v) {
        return (int) (v * host.density);
    }
}
