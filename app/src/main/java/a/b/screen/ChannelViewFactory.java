package a.b.screen;

import android.graphics.Color;
import android.graphics.Typeface;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import java.util.List;
import java.util.Locale;
import a.b.MainActivity;
import a.b.model.Channel;
import a.b.model.Guild;
import a.b.util.Ui;

/** Builds the views used by {@link ChannelListScreen}. */
final class ChannelViewFactory {
    private final MainActivity activity;
    private final String guildId;
    private final float density;
    private final CollapsedCategoryStore collapsedStore;

    ChannelViewFactory(MainActivity activity, String guildId, float density, CollapsedCategoryStore collapsedStore) {
        this.activity = activity;
        this.guildId = guildId;
        this.density = density;
        this.collapsedStore = collapsedStore;
    }

    View buildGuildIcon(Guild guild) {
        if (guild == null) return sizedWrap();
        boolean hasIcon = Ui.isReal(guild.icon);
        FrameLayout wrap = sizedWrap();
        wrap.setBackground(Ui.rounded(hasIcon ? Ui.BG_TERTIARY : Ui.avatarColor(guildId), dp(10)));
        wrap.setOutlineProvider(android.view.ViewOutlineProvider.BACKGROUND);
        wrap.setClipToOutline(true);

        String name = guild.name == null ? "" : guild.name.trim();
        TextView initial = Ui.text(activity, name.isEmpty() ? "?" : name.substring(0, name.offsetByCodePoints(0, 1)).toUpperCase(Locale.US), Color.WHITE, 14);
        initial.setTypeface(null, Typeface.BOLD);
        initial.setGravity(Gravity.CENTER);
        initial.setVisibility(hasIcon ? View.GONE : View.VISIBLE); // Hide placeholder if icon exists
        wrap.addView(initial, new FrameLayout.LayoutParams(-1, -1));
    
        if (hasIcon && activity != null && activity.imageLoader != null) {
            ImageView icon = new ImageView(activity);
            icon.setScaleType(ImageView.ScaleType.CENTER_CROP);
            activity.imageLoader.load("https://cdn.discordapp.com/icons/" + guild.id + "/" + guild.icon + ".png?size=64", icon);
            wrap.addView(icon, new FrameLayout.LayoutParams(-1, -1));
        }
        return wrap;
    }

    private FrameLayout sizedWrap() {
        FrameLayout wrap = new FrameLayout(activity);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(dp(32), dp(32));
        lp.setMarginEnd(dp(10));
        wrap.setLayoutParams(lp);
        return wrap;
    }

    View createCategory(final Channel category, List<Channel> kids) {
        LinearLayout layout = new LinearLayout(activity);
        layout.setOrientation(LinearLayout.VERTICAL);
        if (category == null || kids == null) return layout;

        LinearLayout header = new LinearLayout(activity);
        header.setGravity(Gravity.CENTER_VERTICAL);
        header.setPadding(dp(12), dp(16), dp(16), dp(4));
        header.setClickable(true);
        header.setBackground(Ui.ripple(null, 0));

        final ImageView arrow = Ui.iconView(activity, Ui.I_CHEVRON_DOWN, Ui.MUTED, 16);
        header.addView(arrow);
        TextView title = Ui.sectionLabel(activity, category.name == null ? "Category" : category.name);
        title.setPadding(dp(2), 0, 0, 0);
        title.setSingleLine(true);
        title.setEllipsize(TextUtils.TruncateAt.END);
        header.addView(title);

        final LinearLayout box = new LinearLayout(activity);
        box.setOrientation(LinearLayout.VERTICAL);
        for (Channel ch : kids) if (ch != null) box.addView(createChannelRow(ch));

        final boolean canPersist = category.id != null;
        final boolean collapsed = canPersist && collapsedStore != null && collapsedStore.isCollapsed(category.id);
        box.setVisibility(collapsed ? View.GONE : View.VISIBLE);
        arrow.setRotation(collapsed ? -90f : 0f);

        header.setOnClickListener(v -> {
            boolean show = box.getVisibility() != View.VISIBLE;
            box.setVisibility(show ? View.VISIBLE : View.GONE);
            arrow.setRotation(show ? 0f : -90f);
            if (canPersist && collapsedStore != null) collapsedStore.setCollapsed(category.id, !show);
        });

        layout.addView(header);
        layout.addView(box);
        return layout;
    }

    View createChannelRow(final Channel ch) {
        int unread = activity != null ? activity.mentions.unreadForChannel(ch.id) : 0;
        LinearLayout row = new LinearLayout(activity);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(dp(16), 0, dp(16), 0);
        row.setLayoutParams(new LinearLayout.LayoutParams(-1, dp(40)));

        TextView prefix = Ui.text(activity, channelPrefix(ch.type), Ui.MUTED, 18);
        prefix.setGravity(Gravity.CENTER);
        row.addView(prefix, new LinearLayout.LayoutParams(dp(24), -2));

        TextView name = Ui.text(activity, ch.name, unread > 0 ? Ui.TEXT_HEADER : Ui.MUTED, 16);
        name.setTypeface(null, unread > 0 ? Typeface.BOLD : Typeface.NORMAL);
        name.setSingleLine(true);
        name.setEllipsize(TextUtils.TruncateAt.END);
        LinearLayout.LayoutParams nlp = new LinearLayout.LayoutParams(0, -2, 1f);
        nlp.setMarginStart(dp(8));
        row.addView(name, nlp);

        if (unread > 0) {
            TextView badge = Ui.badge(activity);
            Ui.setBadge(badge, unread);
            row.addView(badge);
        }

        if (ChannelTreeBuilder.isOpenable(ch.type)) {
            row.setClickable(true);
            row.setBackground(Ui.ripple(null, 0));
            row.setOnClickListener(v -> {
                if (activity != null) activity.navigate(new MessageListScreen(guildId, ch.id));
            });
        } else {
            row.setAlpha(0.5f);
        }
        return row;
    }

    private static String channelPrefix(int type) {
        switch (type) {
            case 0:  return "#";
            case 2:  return "\uD83D\uDD0A";
            case 5:  return "\uD83D\uDCE2";
            case 10:
            case 11:
            case 12: return "\u21B3";
            case 13: return "\uD83C\uDF99";
            case 15: return "\uD83D\uDDC4";
            default: return "#";
        }
    }

    private int dp(int v) { return (int) (v * density); }
}