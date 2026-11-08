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
import a.b.MainActivity;
import a.b.model.DmChannel;
import a.b.util.Ui;

/** Builds a single row for the Direct Messages list. */
final class DmRowView {
    private static final int ROW_HEIGHT = 64;
    private static final int AVATAR_SIZE = 44;
    private static final String AVATAR_URL = "https://cdn.discordapp.com/avatars/";

    private final MainActivity activity;

    DmRowView(MainActivity activity) {
        this.activity = activity;
    }

    View build(final DmChannel dm, String self) {
        if (activity == null) return null;
        if (dm == null) return new View(activity);
        LinearLayout row = new LinearLayout(activity);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(dp(16), 0, dp(16), 0);
        row.setLayoutParams(new LinearLayout.LayoutParams(-1, dp(ROW_HEIGHT)));
        row.setClickable(true);
        row.setBackground(Ui.ripple(null, 0));
        row.addView(buildAvatar(dm, self));

        LinearLayout col = new LinearLayout(activity);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setLayoutParams(new LinearLayout.LayoutParams(0, -2, 1f));

        int unread = activity.mentions.unreadForChannel(dm.id);
        String display = dm.displayName(self);
        if (display == null || display.isEmpty()) display = "Unknown";
        TextView name = Ui.text(activity, display, unread > 0 ? Ui.TEXT_HEADER : Ui.TEXT, 16);
        name.setTypeface(null, unread > 0 ? Typeface.BOLD : Typeface.NORMAL);
        name.setSingleLine(true);
        name.setEllipsize(TextUtils.TruncateAt.END);
        col.addView(name);

        String sub = dm.subtitle(self);
        if (sub != null && !sub.isEmpty()) {
            TextView subtitle = Ui.text(activity, sub, Ui.MUTED, 12);
            subtitle.setSingleLine(true);
            subtitle.setEllipsize(TextUtils.TruncateAt.END);
            col.addView(subtitle);
        }
        row.addView(col);

        if (unread > 0) {
            TextView badge = Ui.badge(activity);
            Ui.setBadge(badge, unread);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-2, -2);
            lp.setMarginStart(dp(8));
            row.addView(badge, lp);
        }

        if (dm.id != null) {
            row.setOnClickListener(v -> {
                if (activity != null) activity.navigate(new MessageListScreen(null, dm.id));
            });
        }
        return row;
    }

    private View buildAvatar(DmChannel dm, String self) {
        DmChannel.Recipient r = dm.avatarSource(self);
        int size = dp(AVATAR_SIZE);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(size, size);
        lp.setMarginEnd(dp(12));

        FrameLayout wrap = new FrameLayout(activity);
        wrap.setLayoutParams(lp);
        wrap.setClipToOutline(true);

        ImageView icon = Ui.iconView(activity, Ui.I_USER, Color.WHITE, 24);
        icon.setScaleType(ImageView.ScaleType.CENTER_INSIDE);
        
        boolean hasAvatar = r != null && r.hasAvatar() && r.id != null;
        
        wrap.setBackground(Ui.oval(hasAvatar ? Ui.BG_SECONDARY : (r != null ? Ui.avatarColor(r.id) : Ui.AVATAR_COLORS[0])));
        
        icon.setVisibility(hasAvatar ? View.GONE : View.VISIBLE);
        wrap.addView(icon, new FrameLayout.LayoutParams(-1, -1));

        if (hasAvatar) {
            ImageView iv = new ImageView(activity);
            iv.setScaleType(ImageView.ScaleType.CENTER_CROP);
            activity.imageLoader.load(AVATAR_URL + r.id + "/" + r.avatar + ".png?size=128", iv);
            wrap.addView(iv, new FrameLayout.LayoutParams(-1, -1));
        }
        return wrap;
    }

    private int dp(int v) { return Ui.dp(activity, v); }
}