package a.b.screen;

import android.graphics.Typeface;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.View;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import java.util.List;
import java.util.function.Consumer;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import a.b.MainActivity;
import a.b.model.Guild;
import a.b.model.MentionItem;
import a.b.util.Crypto;
import a.b.util.TimeUtils;
import a.b.util.Ui;

/** Builds a single row for the Mentions inbox. */
final class MentionRowView {
    private static final Pattern USER = Pattern.compile("<@!?(\\d+)>");
    private static final Pattern ROLE = Pattern.compile("<@&(\\d+)>");
    private static final Pattern CHANNEL = Pattern.compile("<#(\\d+)>");
    private static final Pattern EMOJI = Pattern.compile("<a?:(\\w+):\\d+>");

    private final MainActivity activity;
    private final MentionChannelFetcher channelFetcher;
    private final Consumer<MentionItem> onOpen;

    MentionRowView(MainActivity activity, MentionChannelFetcher channelFetcher, Consumer<MentionItem> onOpen) {
        this.activity = activity;
        this.channelFetcher = channelFetcher;
        this.onOpen = onOpen;
    }

    View build(final MentionItem m) {
        LinearLayout row = new LinearLayout(activity);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setPadding(dp(16), dp(12), dp(16), dp(12));
        row.setClickable(true);
        row.setBackground(Ui.ripple(m.unread ? Ui.rounded(0x14F0B232, 0) : null, 0));
        row.setOnClickListener(v -> { if (onOpen != null) onOpen.accept(m); });

        View bar = new View(activity);
        bar.setBackgroundColor(m.unread ? Ui.YELLOW : 0);
        LinearLayout.LayoutParams barLp = new LinearLayout.LayoutParams(dp(3), -1);
        barLp.setMargins(-dp(16), -dp(12), 0, -dp(12));
        barLp.setMarginEnd(dp(13));
        row.addView(bar, barLp);

        ImageView avatar = new ImageView(activity);
        avatar.setScaleType(ImageView.ScaleType.CENTER_CROP);
        avatar.setClipToOutline(true);
        
        boolean hasAvatar = Ui.isReal(m.authorAvatar) && m.authorId != null;
        
        avatar.setBackground(Ui.oval(hasAvatar ? Ui.BG_SECONDARY : Ui.avatarColor(m.authorId)));
        
        LinearLayout.LayoutParams avLp = new LinearLayout.LayoutParams(dp(40), dp(40));
        avLp.setMarginEnd(dp(12));
        
        if (hasAvatar) {
            activity.imageLoader.load("https://cdn.discordapp.com/avatars/" + m.authorId + "/" + m.authorAvatar + ".png?size=128", avatar);
        }
        row.addView(avatar, avLp);

        LinearLayout col = new LinearLayout(activity);
        col.setOrientation(LinearLayout.VERTICAL);

        LinearLayout head = new LinearLayout(activity);
        head.setGravity(Gravity.CENTER_VERTICAL);
        String author = Ui.isReal(m.authorName) ? m.authorName : "Unknown";
        TextView name = Ui.text(activity, author, Ui.TEXT_HEADER, 15);
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
        if (m.channelId == null) {
            sb.append("(unknown channel)");
        } else {
            String ch = activity.channelNameCache.get(m.channelId);
            if (ch == null) {
                channelFetcher.request(m.channelId);
                sb.append(m.guildResolved && m.guildId == null ? "Direct message" : "#\u2026");
            } else {
                sb.append('#').append(ch);
            }
        }
        if (m.guildId != null) {
            List<Guild> gs = activity.guildCache.get("me");
            if (gs != null) {
                for (Guild g : gs) if (g != null && g.id != null && g.id.equals(m.guildId)) { sb.append(" \u00B7 ").append(g.name); break; }
            }
        }
        return sb.toString();
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
            String roleName = (role != null && Ui.isReal(role.name)) ? role.name : "role";
            r.appendReplacement(sb, Matcher.quoteReplacement("@" + roleName));
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

    private int dp(int v) { return Ui.dp(activity, v); }
}