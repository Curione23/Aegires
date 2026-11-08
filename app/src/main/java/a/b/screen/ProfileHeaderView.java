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
import java.util.Locale;
import a.b.MainActivity;
import a.b.model.DiscordUser;
import a.b.util.Ui;

/** Banner + avatar header and the name block beneath it. */
final class ProfileHeaderView {
    private static final int BANNER_H = 120;
    private static final int AVATAR = 88;
    private static final int AVATAR_RING = 5;

    private final MainActivity activity;
    private final FrameLayout header;
    private final LinearLayout nameBlock;
    private ImageView bannerIv, avatarIv;
    private TextView avatarInitial, nameTv, userLineTv, botTag;

    ProfileHeaderView(MainActivity activity, String userId) {
        this.activity = activity;
        this.header = buildHeader(userId);
        this.nameBlock = buildNameBlock();
        String cached = (activity != null && userId != null) ? activity.userNameCache.get(userId) : null;
        if (Ui.isReal(cached)) nameTv.setText(cached);
    }

    View getHeaderView() { return header; }
    View getNameBlock() { return nameBlock; }

    void renderUser(DiscordUser user) {
        if (user == null || activity == null) return;
        botTag.setVisibility(user.bot ? View.VISIBLE : View.GONE);
    
        boolean hasAvatar = Ui.isReal(user.avatar) && user.id != null;
    
        avatarInitial.setText("\uD83D\uDC64");
        avatarInitial.setTextSize(44); 
    
        avatarInitial.setVisibility(hasAvatar ? View.GONE : View.VISIBLE);
        avatarIv.setVisibility(hasAvatar ? View.VISIBLE : View.GONE);

        if (Ui.isReal(user.banner) && user.id != null) {
            activity.imageLoader.load("https://cdn.discordapp.com/banners/" + user.id + "/" + user.banner + ".png?size=600", bannerIv);
        } else if (user.accentColor != 0) {
            bannerIv.setBackgroundColor(user.accentColor | 0xFF000000);
        }
    
        if (hasAvatar) {
            activity.imageLoader.load("https://cdn.discordapp.com/avatars/" + user.id + "/" + user.avatar + ".png?size=256", avatarIv);
        } else {
            avatarIv.setImageDrawable(null);
        }
    }

    void applyName(DiscordUser user, String nick) {
        if (user == null || activity == null) return;
        String global = Ui.isReal(user.globalName) ? user.globalName : user.username;
        nameTv.setText(Ui.isReal(nick) ? nick : global);
        StringBuilder line = new StringBuilder(user.username == null ? "" : user.username);
        if (Ui.isReal(user.pronouns)) line.append(" \u2022 ").append(user.pronouns);
        userLineTv.setText(line.toString());
    }

    private FrameLayout buildHeader(String userId) {
        FrameLayout header = new FrameLayout(activity);
        header.setLayoutParams(new LinearLayout.LayoutParams(-1, dp(BANNER_H + AVATAR / 2 + 4)));

        bannerIv = new ImageView(activity);
        bannerIv.setScaleType(ImageView.ScaleType.CENTER_CROP);
        bannerIv.setBackgroundColor(Ui.avatarColor(userId));
        header.addView(bannerIv, new FrameLayout.LayoutParams(-1, dp(BANNER_H)));

        int outer = dp(AVATAR);
        FrameLayout ring = new FrameLayout(activity);
        ring.setBackground(Ui.oval(Ui.BG_SECONDARY));
        ring.setPadding(dp(AVATAR_RING), dp(AVATAR_RING), dp(AVATAR_RING), dp(AVATAR_RING));
        FrameLayout.LayoutParams ringLp = new FrameLayout.LayoutParams(outer, outer, Gravity.TOP | Gravity.START);
        ringLp.leftMargin = dp(16);
        ringLp.topMargin = dp(BANNER_H - AVATAR / 2);

        FrameLayout inner = new FrameLayout(activity);
        inner.setBackground(Ui.oval(Ui.avatarColor(userId)));
        inner.setClipToOutline(true);
        avatarInitial = Ui.text(activity, "", Color.WHITE, 30);
        avatarInitial.setTypeface(null, Typeface.BOLD);
        avatarInitial.setGravity(Gravity.CENTER);
        inner.addView(avatarInitial, new FrameLayout.LayoutParams(-1, -1));
        avatarIv = new ImageView(activity);
        avatarIv.setScaleType(ImageView.ScaleType.CENTER_CROP);
        inner.addView(avatarIv, new FrameLayout.LayoutParams(-1, -1));
        ring.addView(inner, new FrameLayout.LayoutParams(-1, -1));
        header.addView(ring, ringLp);
        return header;
    }

    private LinearLayout buildNameBlock() {
        LinearLayout block = new LinearLayout(activity);
        block.setOrientation(LinearLayout.VERTICAL);
        block.setPadding(dp(16), dp(4), dp(16), dp(4));

        LinearLayout row = new LinearLayout(activity);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);

        nameTv = Ui.text(activity, "", Ui.TEXT_HEADER, 22);
        nameTv.setTypeface(null, Typeface.BOLD);
        nameTv.setSingleLine(true);
        nameTv.setEllipsize(TextUtils.TruncateAt.END);
        nameTv.setMaxWidth((int) (activity.getResources().getDisplayMetrics().widthPixels * 0.7f));
        row.addView(nameTv);

        botTag = Ui.text(activity, "BOT", Color.WHITE, 10);
        botTag.setTypeface(null, Typeface.BOLD);
        botTag.setPadding(dp(6), dp(2), dp(6), dp(2));
        botTag.setBackground(Ui.rounded(Ui.BLURPLE, dp(4)));
        botTag.setVisibility(View.GONE);
        LinearLayout.LayoutParams tagLp = new LinearLayout.LayoutParams(-2, -2);
        tagLp.setMarginStart(dp(8));
        row.addView(botTag, tagLp);
        block.addView(row);

        userLineTv = Ui.text(activity, "", Ui.TEXT, 14);
        userLineTv.setSingleLine(true);
        userLineTv.setEllipsize(TextUtils.TruncateAt.END);
        block.addView(userLineTv);
        return block;
    }

    private int dp(int v) { return Ui.dp(activity, v); }
}