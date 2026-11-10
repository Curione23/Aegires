package a.b.screen;

import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.TextView;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import a.b.api.ApiClient;
import a.b.model.Message;
import a.b.util.TimeUtils;

/** Renders compact previews for Discord message links found in message text. */
final class MessageLinkPreviewFactory {
    private static final Pattern MESSAGE_LINK = Pattern.compile(
        "https?://(?:(?:www|ptb|canary)\\.)?discord(?:app)?\\.com/channels/(@me|\\d+)/(\\d+)/(\\d+)",
        Pattern.CASE_INSENSITIVE);
    private static final int MAX_PREVIEWS = 3;
    private static final int MAX_CACHE = 100;
    private static final int ACCENT = 0xFF5865F2;

    private final MessageAdapter adapter;
    private final Map<String, Message> cache = new LinkedHashMap<String, Message>(16, 0.75f, true) {
        @Override protected boolean removeEldestEntry(Map.Entry<String, Message> eldest) {
            return size() > MAX_CACHE;
        }
    };
    private final Set<String> loading = new HashSet<>();
    private final Map<String, Long> retryAfter = new HashMap<>();
    private final Map<String, Integer> failures = new HashMap<>();

    MessageLinkPreviewFactory(MessageAdapter adapter) {
        this.adapter = adapter;
    }

    List<View> build(Message msg, ViewGroup parent) {
        if (adapter.activity == null || msg == null || msg.content == null) return Collections.emptyList();
        List<View> views = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        Matcher matcher = MESSAGE_LINK.matcher(msg.content);
        while (matcher.find() && views.size() < MAX_PREVIEWS) {
            String channelId = matcher.group(2);
            String messageId = matcher.group(3);
            String key = channelId + ":" + messageId;
            if (!seen.add(key)) continue;

            Message linked = cache.get(key);
            if (linked == null && channelId.equals(adapter.channelId)) linked = findLoaded(messageId);
            if (linked == null) linked = findCached(channelId, messageId);
            if (linked != null) {
                cache.put(key, linked);
                views.add(preview(linked));
                continue;
            }
            Long retryAt = retryAfter.get(key);
            if (retryAt != null && retryAt > System.currentTimeMillis()) {
                views.add(errorView(channelId, messageId, key));
                continue;
            }
            if (retryAt != null) retryAfter.remove(key);
            if (!loading.contains(key)) load(channelId, messageId, key);
            if (loading.contains(key)) views.add(placeholder());
        }
        return views;
    }

    private Message findLoaded(String messageId) {
        for (Message m : adapter.displayList) {
            if (m != null && messageId.equals(m.id)) return m;
        }
        return null;
    }

    private Message findCached(String channelId, String messageId) {
        if (adapter.activity == null || adapter.activity.messageCache == null) return null;
        List<Message> cached = adapter.activity.messageCache.getMessages(channelId);
        if (cached == null) return null;
        for (Message m : cached) {
            if (m != null && messageId.equals(m.id)) return m;
        }
        return null;
    }

    void invalidate(String messageId) {
        if (messageId == null) return;
        List<String> keys = new ArrayList<>();
        for (Map.Entry<String, Message> entry : cache.entrySet()) {
            Message cached = entry.getValue();
            if (cached != null && messageId.equals(cached.id)) keys.add(entry.getKey());
        }
        for (String key : keys) {
            cache.remove(key);
            int split = key.indexOf(':');
            if (split > 0) refreshOwners(key.substring(0, split), messageId);
        }
    }

    private void load(String channelId, String messageId, String key) {
        if (adapter.activity == null || adapter.activity.apiClient == null) {
            scheduleRetry(channelId, messageId, key);
            return;
        }
        loading.add(key);
        final ApiClient api = adapter.activity.apiClient;
        api.fetchMessage(channelId, messageId, new ApiClient.Callback<Message>() {
            @Override public void onSuccess(Message result) {
                if (result != null) loaded(channelId, messageId, key, result);
                else loadAround(api, channelId, messageId, key);
            }

            @Override public void onError(String message) {
                loadAround(api, channelId, messageId, key);
            }
        });
    }

    private void loadAround(ApiClient api, final String channelId, final String messageId, final String key) {
        api.fetchMessageAround(channelId, messageId, new ApiClient.Callback<Message>() {
            @Override public void onSuccess(Message result) {
                if (result != null) loaded(channelId, messageId, key, result);
                else failed(channelId, messageId, key);
            }

            @Override public void onError(String message) {
                failed(channelId, messageId, key);
            }
        });
    }

    private void loaded(String channelId, String messageId, String key, Message result) {
        loading.remove(key);
        retryAfter.remove(key);
        failures.remove(key);
        cache.put(key, result);
        refreshOwners(channelId, messageId);
    }

    private void failed(String channelId, String messageId, String key) {
        loading.remove(key);
        Integer count = failures.get(key);
        int attempts = count == null ? 1 : count + 1;
        failures.put(key, attempts);
        long delay = Math.min(15_000L * attempts, 300_000L);
        retryAfter.put(key, System.currentTimeMillis() + delay);
        refreshOwners(channelId, messageId);
        if (attempts <= 3) adapter.ui.postDelayed(() -> refreshOwners(channelId, messageId), delay);
    }

    private void scheduleRetry(String channelId, String messageId, String key) {
        long delay = 15_000L;
        retryAfter.put(key, System.currentTimeMillis() + delay);
        adapter.ui.postDelayed(() -> refreshOwners(channelId, messageId), delay);
    }

    private void refreshOwners(final String channelId, final String messageId) {
        adapter.ui.post(() -> {
            for (int i = 0; i < adapter.displayList.size(); i++) {
                Message m = adapter.displayList.get(i);
                if (m != null && references(m.content, channelId, messageId)) {
                    adapter.notifyItemChanged(i);
                }
            }
        });
    }

    private static boolean references(String content, String channelId, String messageId) {
        if (content == null) return false;
        Matcher matcher = MESSAGE_LINK.matcher(content);
        while (matcher.find()) {
            if (channelId.equals(matcher.group(2)) && messageId.equals(matcher.group(3))) return true;
        }
        return false;
    }

    private View preview(Message linked) {
        if (adapter.activity == null) return null;
        LinearLayout wrapper = new LinearLayout(adapter.activity);
        wrapper.setOrientation(LinearLayout.HORIZONTAL);
        wrapper.setPadding(0, adapter.dp(4), 0, adapter.dp(4));

        View bar = new View(adapter.activity);
        bar.setBackgroundColor(ACCENT);
        wrapper.addView(bar, new LinearLayout.LayoutParams(adapter.dp(4), ViewGroup.LayoutParams.MATCH_PARENT));

        LinearLayout card = new LinearLayout(adapter.activity);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setPadding(adapter.dp(12), adapter.dp(8), adapter.dp(12), adapter.dp(8));
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(MessageAdapter.EMBED_BG);
        bg.setCornerRadius(adapter.dp(4));
        card.setBackground(bg);
        card.setClipToOutline(true);
        wrapper.addView(card, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        LinearLayout header = new LinearLayout(adapter.activity);
        header.setOrientation(LinearLayout.HORIZONTAL);
        header.setGravity(Gravity.CENTER_VERTICAL);

        TextView name = new TextView(adapter.activity);
        name.setText(adapter.binder.displayName(linked));
        name.setTextColor(MessageAdapter.LINK_FG);
        name.setTextSize(13);
        name.setTypeface(null, Typeface.BOLD);
        name.setSingleLine();
        name.setEllipsize(TextUtils.TruncateAt.END);
        header.addView(name, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        TextView time = new TextView(adapter.activity);
        time.setText(TimeUtils.messageTime(linked.timestamp));
        time.setTextColor(MessageAdapter.MUTED);
        time.setTextSize(11);
        time.setSingleLine();
        time.setPadding(adapter.dp(8), 0, 0, 0);
        header.addView(time);
        card.addView(header);

        TextView body = new TextView(adapter.activity);
        CharSequence text;
        if (linked.encrypted) {
            text = "\uD83D\uDD12 Encrypted message";
        } else {
            text = adapter.renderer.render(linked.content, linked, false);
            if (text == null || text.length() == 0) text = attachmentSummary(linked);
        }
        body.setText(text);
        body.setTextColor(MessageAdapter.TEXT);
        body.setTextSize(14);
        body.setMaxLines(4);
        body.setEllipsize(TextUtils.TruncateAt.END);
        body.setLineSpacing(0, 1.1f);
        body.setPadding(0, adapter.dp(4), 0, 0);
        card.addView(body);
        return wrapper;
    }

    private CharSequence attachmentSummary(Message linked) {
        if (linked.attachments != null && !linked.attachments.isEmpty()) {
            Message.Attachment first = linked.attachments.get(0);
            if (first != null && first.filename != null && !first.filename.isEmpty()) {
                return "\uD83D\uDCCE " + first.filename;
            }
            return "\uD83D\uDCCE Attachment";
        }
        return "(no text)";
    }

    private View placeholder() {
        if (adapter.activity == null) return null;
        TextView text = new TextView(adapter.activity);
        text.setText("Loading linked message\u2026");
        text.setTextColor(MessageAdapter.MUTED);
        text.setTextSize(12);
        text.setPadding(adapter.dp(12), adapter.dp(4), 0, adapter.dp(4));
        return text;
    }

    private View errorView(final String channelId, final String messageId, final String key) {
        if (adapter.activity == null) return null;
        TextView text = new TextView(adapter.activity);
        text.setText("Couldn't load linked message \u00B7 Tap to retry");
        text.setTextColor(MessageAdapter.MUTED);
        text.setTextSize(12);
        text.setPadding(adapter.dp(12), adapter.dp(4), 0, adapter.dp(4));
        text.setOnClickListener(v -> {
            retryAfter.remove(key);
            failures.remove(key);
            refreshOwners(channelId, messageId);
        });
        return text;
    }
}
