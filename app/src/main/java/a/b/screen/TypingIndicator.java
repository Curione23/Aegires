package a.b.screen;

import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.TextView;
import org.json.JSONObject;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import a.b.api.ApiClient;
import a.b.util.Ui;

/** Live "X is typing..." strip. */
final class TypingIndicator {
    private static final int MATCH = -1;
    private static final long TYPING_EXPIRY_MS = 10000;

    private final MessageListScreen host;
    private final TextView typingText;
    private final Map<String, String> typingUsers = new HashMap<>();
    private final Set<String> typingNickChecked = new HashSet<>();
    private final Handler typingHandler = new Handler(Looper.getMainLooper());
    private final Runnable clearTypingRunnable = () -> {
        typingUsers.clear();
        update();
    };

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

    View getView() { return typingText; }

    void handleTypingStart(JSONObject d) {
        if (host.activity == null) return;
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
        if (!isReal(nick) && host.guildId != null) nick = host.activity.userNickCache.get(host.guildId + "_" + userId);
        String name = isReal(nick) ? nick
                    : isReal(globalName) ? globalName
                    : host.activity.userNameCache.get(userId);
        if (!isReal(name)) name = "Someone";
        if (!isReal(nick)) lookupTypingNick(userId);
        typingUsers.put(userId, name);
        update();
        typingHandler.removeCallbacks(clearTypingRunnable);
        typingHandler.postDelayed(clearTypingRunnable, TYPING_EXPIRY_MS);
    }

    void removeUser(String userId) {
        if (userId != null && typingUsers.remove(userId) != null) update();
    }

    private void lookupTypingNick(final String userId) {
        if (host.guildId == null || host.activity == null || host.activity.apiClient == null) return;
        final String key = host.guildId + "_" + userId;
        if (!typingNickChecked.add(key)) return;
        host.activity.apiClient.fetchMember(host.guildId, userId, new ApiClient.Callback<JSONObject>() {
            @Override public void onSuccess(JSONObject member) {
                if (host.activity == null) return;
                String nick = member.isNull("nick") ? null : member.optString("nick", null);
                if (!isReal(nick)) return;
                host.activity.userNickCache.put(key, nick);
                if (typingUsers.containsKey(userId)) {
                    typingUsers.put(userId, nick);
                    update();
                }
            }
            @Override public void onError(String err) {}
        });
    }

    private void update() {
        if (host.activity == null || typingText == null) return;
        if (typingUsers.isEmpty()) {
            typingText.setVisibility(View.INVISIBLE);
            return;
        }
        List<String> names = new ArrayList<>(typingUsers.values());
        StringBuilder sb = new StringBuilder();
        if (names.size() == 1) sb.append(names.get(0)).append(" is typing...");
        else if (names.size() == 2) sb.append(names.get(0)).append(" and ").append(names.get(1)).append(" are typing...");
        else sb.append("Several people are typing...");
        typingText.setText(sb.toString());
        typingText.setVisibility(View.VISIBLE);
        if (host.atBottom()) host.scrollToBottom();
    }

    void release() {
        typingHandler.removeCallbacksAndMessages(null);
    }

    private static boolean isReal(String s) { return s != null && !s.isEmpty() && !"null".equals(s); }

    private int dp(int v) { return (int) (v * host.density); }
}