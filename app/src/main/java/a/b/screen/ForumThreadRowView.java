package a.b.screen;

import android.graphics.Typeface;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.TextView;
import java.util.List;
import java.util.Map;
import a.b.MainActivity;
import a.b.model.Channel;
import a.b.util.TimeUtils;
import a.b.util.Ui;

/** Rich row for a forum thread, showing title, state, activity and tags. */
final class ForumThreadRowView {
    private final MainActivity activity;
    private final String guildId;
    private final Map<String, Channel.ForumTag> tagsById;

    ForumThreadRowView(MainActivity activity, String guildId, Map<String, Channel.ForumTag> tagsById) {
        this.activity = activity;
        this.guildId = guildId;
        this.tagsById = tagsById;
    }

    View build(final Channel thread) {
        LinearLayout wrap = new LinearLayout(activity);
        wrap.setOrientation(LinearLayout.VERTICAL);

        LinearLayout row = new LinearLayout(activity);
        row.setOrientation(LinearLayout.VERTICAL);
        row.setPadding(dp(16), dp(12), dp(16), dp(12));
        row.setBackground(Ui.ripple(null, 0));
        row.setClickable(true);
        row.setOnClickListener(v -> {
            if (activity != null && thread.id != null) {
                activity.navigate(new MessageListScreen(guildId, thread.id));
            }
        });

        LinearLayout head = new LinearLayout(activity);
        head.setGravity(Gravity.CENTER_VERTICAL);

        if (thread.pinned) {
            TextView pin = Ui.text(activity, "\uD83D\uDCCC", Ui.YELLOW, 13);
            pin.setPadding(0, 0, dp(6), 0);
            head.addView(pin);
        }

        TextView title = Ui.text(activity, thread.name == null || thread.name.isEmpty() ? "(untitled)" : thread.name,
            Ui.TEXT_HEADER, 16);
        title.setTypeface(null, Typeface.BOLD);
        title.setMaxLines(2);
        title.setEllipsize(TextUtils.TruncateAt.END);
        head.addView(title, new LinearLayout.LayoutParams(0, -2, 1f));

        if (thread.locked) {
            head.addView(pill("LOCKED", 0x33F0B232, Ui.YELLOW));
        }
        row.addView(head, new LinearLayout.LayoutParams(-1, -2));

        String meta = metaLine(thread);
        if (meta.length() > 0) {
            TextView metaView = Ui.text(activity, meta, Ui.MUTED, 12);
            metaView.setPadding(0, dp(4), 0, 0);
            metaView.setSingleLine(true);
            metaView.setEllipsize(TextUtils.TruncateAt.END);
            row.addView(metaView);
        }

        List<String> applied = thread.appliedTagIds;
        if (applied != null && !applied.isEmpty()) {
            LinearLayout tagRow = new LinearLayout(activity);
            tagRow.setGravity(Gravity.CENTER_VERTICAL);
            tagRow.setPadding(0, dp(6), 0, 0);
            int shown = 0;
            for (String id : applied) {
                Channel.ForumTag tag = tagsById != null ? tagsById.get(id) : null;
                if (tag == null) continue;
                tagRow.addView(tagChip(tag));
                shown++;
                if (shown == 3) break;
            }
            if (shown > 0) row.addView(tagRow);
        }

        wrap.addView(row, new LinearLayout.LayoutParams(-1, -2));
        View divider = new View(activity);
        divider.setBackgroundColor(0x22000000);
        wrap.addView(divider, new LinearLayout.LayoutParams(-1, dp(1)));
        return wrap;
    }

    private String metaLine(Channel thread) {
        StringBuilder out = new StringBuilder();
        int messages = thread.totalMessageSent > 0 ? thread.totalMessageSent : thread.messageCount;
        if (messages > 0) out.append(messages).append(messages == 1 ? " message" : " messages");
        if (thread.memberCount > 0) {
            if (out.length() > 0) out.append(" \u00B7 ");
            out.append(thread.memberCount).append(thread.memberCount == 1 ? " member" : " members");
        }
        if (thread.lastActivity > 0) {
            if (out.length() > 0) out.append(" \u00B7 ");
            String relative = TimeUtils.relativeTime(thread.lastActivity);
            out.append("Active ").append(relative);
            if (relative.matches("\\d+[mhd]")) out.append(" ago");
        }
        return out.toString();
    }

    private TextView tagChip(Channel.ForumTag tag) {
        TextView chip = Ui.text(activity, tag.name, Ui.TEXT, 11);
        chip.setSingleLine(true);
        chip.setEllipsize(TextUtils.TruncateAt.END);
        chip.setPadding(dp(7), dp(2), dp(7), dp(2));
        int bg = tag.color == 0 ? Ui.BG_TERTIARY : (0x33000000 | (tag.color & 0xFFFFFF));
        chip.setBackground(Ui.rounded(bg, dp(5)));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-2, -2);
        lp.setMarginEnd(dp(6));
        chip.setLayoutParams(lp);
        return chip;
    }

    private TextView pill(String text, int bg, int fg) {
        TextView view = Ui.text(activity, text, fg, 9);
        view.setTypeface(null, Typeface.BOLD);
        view.setLetterSpacing(0.05f);
        view.setPadding(dp(5), dp(2), dp(5), dp(2));
        view.setBackground(Ui.rounded(bg, dp(4)));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-2, -2);
        lp.setMarginStart(dp(8));
        view.setLayoutParams(lp);
        return view;
    }

    private int dp(int v) { return Ui.dp(activity, v); }
}
