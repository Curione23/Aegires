package a.b.screen;

import android.content.Intent;
import android.graphics.Typeface;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.json.JSONObject;
import a.b.MainActivity;
import a.b.api.ApiClient;
import a.b.model.Guild;
import a.b.model.MentionItem;
import a.b.util.Crypto;
import a.b.util.MentionStore;
import a.b.util.TimeUtils;
import a.b.util.Ui;

/** Inbox of recent @mentions / @everyone pings. */
public class NotificationsScreen implements Screen, MentionStore.Listener {
    private static final Pattern USER = Pattern.compile("<@!?(\\d+)>");
    private static final Pattern ROLE = Pattern.compile("<@&(\\d+)>");
    private static final Pattern CHANNEL = Pattern.compile("<#(\\d+)>");
    private static final Pattern EMOJI = Pattern.compile("<a?:(\\w+):\\d+>");

    MainActivity activity;
    View view;
    LinearLayout list;
    TextView status;
    private boolean destroyed, loading;
    private final Set<String> pendingChannels = new HashSet<>();

    @Override
    public View onCreateView(final MainActivity activity, ViewGroup parent) {
        this.activity = activity;
        LinearLayout root = new LinearLayout(activity);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(Ui.BG_SECONDARY);

        Ui.AppBar bar = new Ui.AppBar(activity, "Mentions", Ui.BG_SECONDARY, activity::goBack);
        bar.addAction(Ui.I_DONE_ALL, v -> {
            activity.mentions.markAllRead();
            activity.toast("Marked all as read");
        });
        root.addView(bar.view, new LinearLayout.LayoutParams(-1, -2));

        ScrollView scroll = new ScrollView(activity);
        scroll.setFillViewport(true);
        list = new LinearLayout(activity);
        list.setOrientation(LinearLayout.VERTICAL);
        scroll.addView(list, new ViewGroup.LayoutParams(-1, -2));
        root.addView(scroll, new LinearLayout.LayoutParams(-1, 0, 1f));

        render();
        fetch();
        return view = root;
    }

    private void fetch() {
        if (activity == null || loading) return;
        if (activity.apiClient.isBotAccount()) { render(); return; }
        loading = true;
        render();
        activity.apiClient.fetchMentions(new ApiClient.Callback<List<MentionItem>>() {
            @Override public void onSuccess(List<MentionItem> result) {
                loading = false;
                if (destroyed || activity == null) return;
                activity.mentions.mergeRemote(result);
                render();
            }
            @Override public void onError(String msg) {
                loading = false;
                if (destroyed) return;
                render();
            }
        });
    }

    private void render() {
        if (list == null || activity == null) return;
        list.removeAllViews();
        List<MentionItem> items = activity.mentions.items();
        if (items.isEmpty()) {
            String msg = loading ? "Loading\u2026"
                : activity.apiClient.isBotAccount() ? "Mentions are only available for user accounts.\nLive mentions will still show up here."
                : "No recent mentions.\nYou'll see @mentions and @everyone pings here.";
            list.addView(Ui.emptyState(activity, msg));
            return;
        }
        for (MentionItem m : items) list.addView(buildRow(m));
    }

    private View buildRow(final MentionItem m) {
        LinearLayout row = new LinearLayout(activity);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setPadding(dp(16), dp(12), dp(16), dp(12));
        row.setClickable(true);
        row.setBackground(Ui.ripple(m.unread ? Ui.rounded(0x14F0B232, 0) : null, 0));
        row.setOnClickListener(v -> open(m));

        View bar = new View(activity);
        bar.setBackgroundColor(m.unread ? Ui.YELLOW : 0);
        LinearLayout.LayoutParams barLp = new LinearLayout.LayoutParams(dp(3), -1);
        barLp.setMarginEnd(dp(10));
        barLp.setMargins(-dp(16), -dp(12), 0, -dp(12));
        barLp.setMarginEnd(dp(13));
        row.addView(bar, barLp);

        ImageView avatar = new ImageView(activity);
        avatar.setScaleType(ImageView.ScaleType.CENTER_CROP);
        avatar.setBackground(Ui.oval(Ui.avatarColor(m.authorId)));
        avatar.setClipToOutline(true);
        LinearLayout.LayoutParams avLp = new LinearLayout.LayoutParams(dp(40), dp(40));
        avLp.setMarginEnd(dp(12));
        boolean hasAvatar = Ui.isReal(m.authorAvatar) && m.authorId != null;
        activity.imageLoader.load(hasAvatar
            ? "https://cdn.discordapp.com/avatars/" + m.authorId + "/" + m.authorAvatar + ".png?size=128" : null, avatar);
        row.addView(avatar, avLp);

        LinearLayout col = new LinearLayout(activity);
        col.setOrientation(LinearLayout.VERTICAL);

        LinearLayout head = new LinearLayout(activity);
        head.setGravity(Gravity.CENTER_VERTICAL);
        TextView name = Ui.text(activity, m.authorName, Ui.TEXT_HEADER, 15);
        name.setTypeface(null, Typeface.BOLD);
        name.setSingleLine(true);
        name.setEllipsize(TextUtils.TruncateAt.END);
        head.addView(name, new LinearLayout.LayoutParams(0, -2, 1f));
        TextView time = Ui.text(activity, TimeUtils.relativeTime(m.timestamp), Ui.MUTED, 12);
        time.setPadding(dp(8), 0, 0, 0);
        head.addView(time);
        col.addView(head);

        TextView where = Ui.text(activity, whereLabel(m), Ui.MUTED, 12);
        where.setSingleLine(true);
        where.setEllipsize(TextUtils.TruncateAt.END);
        col.addView(where);

        TextView body = Ui.text(activity, plain(m), Ui.TEXT, 14);
        body.setMaxLines(3);
        body.setEllipsize(TextUtils.TruncateAt.END);
        body.setPadding(0, dp(4), 0, 0);
        col.addView(body);

        row.addView(col, new LinearLayout.LayoutParams(0, -2, 1f));

        LinearLayout wrap = new LinearLayout(activity);
        wrap.setOrientation(LinearLayout.VERTICAL);
        wrap.addView(row);
        View div = new View(activity);
        div.setBackgroundColor(0x22000000);
        wrap.addView(div, new LinearLayout.LayoutParams(-1, 1));
        return wrap;
    }

    private String whereLabel(MentionItem m) {
        StringBuilder sb = new StringBuilder();
        if (m.everyone) sb.append("@everyone \u00B7 ");
        String ch = activity.channelNameCache.get(m.channelId);
        if (ch == null) {
            requestChannelName(m.channelId);
            sb.append(m.guildResolved && m.guildId == null ? "Direct message" : "#\u2026");
        } else {
            sb.append('#').append(ch);
        }
        if (m.guildId != null) {
            List<Guild> gs = activity.guildCache.get("me");
            if (gs != null) {
                for (Guild g : gs) if (g.id.equals(m.guildId)) { sb.append(" \u00B7 ").append(g.name); break; }
            }
        }
        return sb.toString();
    }

    private void requestChannelName(final String channelId) {
        if (activity == null || !pendingChannels.add(channelId)) return;
        activity.apiClient.fetchChannelName(channelId, new ApiClient.Callback<String>() {
            @Override public void onSuccess(String name) {
                if (name != null && activity != null) {
                    activity.channelNameCache.put(channelId, name);
                    if (!destroyed) render();
                }
            }
            @Override public void onError(String msg) {}
        });
    }

    private String plain(MentionItem m) {
        String c = m.content == null ? "" : m.content;
        if (Crypto.looksEncrypted(c)) {
            javax.crypto.SecretKey k = a.b.util.ForcedKeys.keyFor(m.channelId);
            if (k == null) k = activity.e2eeKey;
            String dec = k != null ? Crypto.decrypt(c, k, m.channelId, m.authorId) : null;
            if (dec == null) return "Encrypted message";
            c = dec;
        }
        c = replace(USER, c, "@", activity.userNameCache, "user");
        c = replace(CHANNEL, c, "#", activity.channelNameCache, "channel");
        StringBuffer sb = new StringBuffer();
        Matcher r = ROLE.matcher(c);
        while (r.find()) {
            a.b.model.Role role = activity.roleCache.get(r.group(1));
            r.appendReplacement(sb, Matcher.quoteReplacement("@" + (role != null ? role.name : "role")));
        }
        r.appendTail(sb);
        c = sb.toString();
        c = EMOJI.matcher(c).replaceAll(":$1:");
        return c.trim().isEmpty() ? "(no text)" : c;
    }

    private static String replace(Pattern p, String in, String prefix, java.util.Map<String, String> names, String fallback) {
        Matcher m = p.matcher(in);
        StringBuffer sb = new StringBuffer();
        while (m.find()) {
            String n = names.get(m.group(1));
            m.appendReplacement(sb, Matcher.quoteReplacement(prefix + (n != null ? n : fallback)));
        }
        m.appendTail(sb);
        return sb.toString();
    }

    private void open(final MentionItem m) {
        if (activity == null) return;
        activity.mentions.markRead(m);
        if (m.guildResolved) {
            activity.navigate(new MessageListScreen(m.guildId, m.channelId));
            return;
        }
        activity.apiClient.fetchChannelMeta(m.channelId, new ApiClient.Callback<JSONObject>() {
            @Override public void onSuccess(JSONObject meta) {
                if (activity == null) return;
                m.guildId = meta.isNull("guild_id") ? null : meta.optString("guild_id", null);
                m.guildResolved = true;
                activity.navigate(new MessageListScreen(m.guildId, m.channelId));
            }
            @Override public void onError(String msg) {
                if (activity == null) return;
                activity.toast("Couldn't open channel: " + msg);
            }
        });
    }

    @Override public void onMentionsChanged() { render(); }

    private int dp(int v) { return Ui.dp(activity, v); }

    @Override public View getView() { return view; }
    @Override public void onActivityResult(int requestCode, int resultCode, Intent data) {}
    @Override public void onResume() { if (activity != null) activity.mentions.addListener(this); render(); }
    @Override public void onPause() { if (activity != null) activity.mentions.removeListener(this); }
    @Override public void onDestroy() {
        destroyed = true;
        if (activity != null) activity.mentions.removeListener(this);
        activity = null;
    }
}
