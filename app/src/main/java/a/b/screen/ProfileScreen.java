package a.b.screen;

import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.Typeface;
import android.text.TextUtils;
import android.text.method.LinkMovementMethod;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TimeZone;
import org.json.JSONArray;
import org.json.JSONObject;
import a.b.MainActivity;
import a.b.api.ApiClient;
import a.b.model.DiscordUser;
import a.b.model.Guild;
import a.b.model.Role;
import a.b.util.SimpleMarkdown;
import a.b.util.Ui;

public class ProfileScreen implements Screen, ApiClient.Callback<DiscordUser> {
    private static final int PAGE_BG = Ui.BG_SECONDARY;
    private static final int CARD_BG = Ui.BG_TERTIARY;
    private static final int CHIP_BG = Ui.BG_TERTIARY;
    private static final int BANNER_H = 120;
    private static final int AVATAR = 88;
    private static final int AVATAR_RING = 5;

    MainActivity activity;
    final String userId;
    final String guildId;
    View view;

    private ImageView bannerIv, avatarIv;
    private TextView avatarInitial, nameTv, userLineTv, botTag;
    private LinearLayout detailsSection, rolesSection, rolesRow, aboutCard;

    private DiscordUser user;
    private String nick;
    private long joinedMs;

    public ProfileScreen(String userId, String guildId) {
        this.userId = userId;
        this.guildId = guildId;
    }

    @Override
    public View onCreateView(final MainActivity activity, ViewGroup parent) {
        this.activity = activity;

        FrameLayout frame = new FrameLayout(activity);
        frame.setBackgroundColor(PAGE_BG);

        ScrollView scroll = new ScrollView(activity);
        scroll.setFillViewport(true);
        LinearLayout col = new LinearLayout(activity);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setPadding(0, 0, 0, dp(24));

        col.addView(buildHeader());
        col.addView(buildNameBlock());

        detailsSection = section();
        detailsSection.setVisibility(View.GONE);
        col.addView(detailsSection, sectionLp());

        aboutCard = new LinearLayout(activity);
        aboutCard.setOrientation(LinearLayout.VERTICAL);
        aboutCard.setPadding(dp(16), dp(14), dp(16), dp(14));
        aboutCard.setBackground(Ui.rounded(CARD_BG, dp(12)));
        aboutCard.setVisibility(View.GONE);
        LinearLayout.LayoutParams aboutLp = new LinearLayout.LayoutParams(-1, -2);
        aboutLp.setMargins(dp(16), dp(20), dp(16), 0);
        col.addView(aboutCard, aboutLp);

        rolesSection = section();
        rolesSection.setVisibility(View.GONE);
        rolesSection.addView(Ui.sectionLabel(activity, "Roles"));
        rolesRow = new LinearLayout(activity);
        rolesRow.setOrientation(LinearLayout.VERTICAL);
        rolesRow.setPadding(0, dp(6), 0, 0);
        rolesSection.addView(rolesRow);
        col.addView(rolesSection, sectionLp());

        scroll.addView(col, new ViewGroup.LayoutParams(-1, -2));
        frame.addView(scroll, new FrameLayout.LayoutParams(-1, -1));

        FrameLayout back = Ui.iconButton(activity, Ui.I_BACK, 0xFFFFFFFF);
        back.setBackground(Ui.ripple(Ui.oval(0x99000000), -1));
        FrameLayout.LayoutParams backLp = new FrameLayout.LayoutParams(dp(40), dp(40));
        backLp.setMargins(dp(8), dp(8), 0, 0);
        back.setOnClickListener(v -> { if (this.activity != null) this.activity.goBack(); });
        frame.addView(back, backLp);

        FrameLayout copy = Ui.iconButton(activity, Ui.I_COPY, 0xFFFFFFFF);
        copy.setBackground(Ui.ripple(Ui.oval(0x99000000), -1));
        FrameLayout.LayoutParams copyLp = new FrameLayout.LayoutParams(dp(40), dp(40), Gravity.TOP | Gravity.END);
        copyLp.setMargins(0, dp(8), dp(8), 0);
        copy.setOnClickListener(v -> copyId());
        frame.addView(copy, copyLp);

        String cached = activity.userNameCache.get(userId);
        if (Ui.isReal(cached)) nameTv.setText(cached);

        activity.apiClient.fetchUserProfileFull(userId, this);
        loadMember();
        return view = frame;
    }

    private void copyId() {
        if (activity == null) return;
        ClipboardManager cm = (ClipboardManager) activity.getSystemService(Context.CLIPBOARD_SERVICE);
        cm.setPrimaryClip(ClipData.newPlainText("user id", userId));
        activity.toast("Copied user ID");
    }

    private View buildHeader() {
        FrameLayout header = new FrameLayout(activity);
        header.setLayoutParams(new LinearLayout.LayoutParams(-1, dp(BANNER_H + AVATAR / 2 + 4)));

        bannerIv = new ImageView(activity);
        bannerIv.setScaleType(ImageView.ScaleType.CENTER_CROP);
        bannerIv.setBackgroundColor(Ui.avatarColor(userId));
        header.addView(bannerIv, new FrameLayout.LayoutParams(-1, dp(BANNER_H)));

        int outer = dp(AVATAR);
        FrameLayout ring = new FrameLayout(activity);
        ring.setBackground(Ui.oval(PAGE_BG));
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

    private View buildNameBlock() {
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

    private LinearLayout section() {
        LinearLayout s = new LinearLayout(activity);
        s.setOrientation(LinearLayout.VERTICAL);
        return s;
    }

    private LinearLayout.LayoutParams sectionLp() {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
        lp.setMargins(dp(16), dp(20), dp(16), 0);
        return lp;
    }

    @Override
    public void onSuccess(DiscordUser u) {
        if (u == null || activity == null) return;
        user = u;
        applyName();
        botTag.setVisibility(u.bot ? View.VISIBLE : View.GONE);

        String base = Ui.isReal(u.globalName) ? u.globalName : u.username;
        avatarInitial.setText(base == null || base.isEmpty() ? "?"
            : base.substring(0, base.offsetByCodePoints(0, 1)).toUpperCase(Locale.US));

        if (Ui.isReal(u.banner)) {
            activity.imageLoader.load("https://cdn.discordapp.com/banners/" + u.id + "/" + u.banner + ".png?size=600", bannerIv);
        } else if (u.accentColor != 0) {
            bannerIv.setBackgroundColor(u.accentColor | 0xFF000000);
        }
        if (Ui.isReal(u.avatar)) {
            activity.imageLoader.load("https://cdn.discordapp.com/avatars/" + u.id + "/" + u.avatar + ".png?size=256", avatarIv);
        }
        renderDetails();
        renderAbout();
    }

    @Override
    public void onError(String msg) {
        if (activity != null) activity.toast("Failed to load profile: " + msg);
    }

    private void applyName() {
        if (user == null) return;
        String global = Ui.isReal(user.globalName) ? user.globalName : user.username;
        nameTv.setText(Ui.isReal(nick) ? nick : global);
        StringBuilder line = new StringBuilder(user.username == null ? "" : user.username);
        if (Ui.isReal(user.pronouns)) line.append(" \u2022 ").append(user.pronouns);
        userLineTv.setText(line.toString());
    }

    private void loadMember() {
        if (guildId == null || activity == null) return;
        activity.apiClient.fetchMember(guildId, userId, new ApiClient.Callback<JSONObject>() {
            @Override public void onSuccess(JSONObject member) {
                if (activity == null) return;
                nick = member.isNull("nick") ? null : member.optString("nick", null);
                joinedMs = parseIso(member.isNull("joined_at") ? null : member.optString("joined_at", null));
                applyName();
                renderDetails();

                JSONArray rolesArray = member.optJSONArray("roles");
                if (rolesArray == null || rolesArray.length() == 0) return;
                final List<String> roleIds = new ArrayList<>();
                for (int i = 0; i < rolesArray.length(); i++) roleIds.add(rolesArray.optString(i));
                activity.apiClient.fetchRoles(guildId, new ApiClient.Callback<Map<String, Role>>() {
                    @Override public void onSuccess(Map<String, Role> roles) {
                        if (activity == null) return;
                        activity.roleCache.putAll(roles);
                        renderRoles(roleIds, roles);
                    }
                    @Override public void onError(String err) {}
                });
            }
            @Override public void onError(String err) {}
        });
    }

    private void renderDetails() {
        if (user == null || activity == null) return;
        detailsSection.removeAllViews();

        detailsSection.addView(Ui.sectionLabel(activity, "Member since"));
        if (user.createdAtMs > 0) {
            detailsSection.addView(sinceRow(Ui.BLURPLE, "D", formatDate(user.createdAtMs), "Discord"));
        }
        if (guildId != null && joinedMs > 0) {
            String name = guildName();
            detailsSection.addView(sinceRow(Ui.avatarColor(guildId),
                name.isEmpty() ? "?" : name.substring(0, name.offsetByCodePoints(0, 1)).toUpperCase(Locale.US),
                formatDate(joinedMs), name));
        }
        if (user.mutualGuildsCount > 0) {
            detailsSection.addView(infoRow("Mutual servers", String.valueOf(user.mutualGuildsCount), dp(14)));
        }
        if (user.premiumType > 0) {
            detailsSection.addView(infoRow("Subscription", user.premiumType == 2 ? "Nitro" : "Nitro Classic", user.mutualGuildsCount > 0 ? 0 : dp(14)));
        }
        detailsSection.setVisibility(View.VISIBLE);
    }

    private void renderAbout() {
        if (user == null || activity == null) return;
        aboutCard.removeAllViews();
        if (!Ui.isReal(user.bio)) {
            aboutCard.setVisibility(View.GONE);
            return;
        }
        TextView title = Ui.text(activity, "About Me", Ui.TEXT_HEADER, 14);
        title.setTypeface(null, Typeface.BOLD);
        aboutCard.addView(title);
        TextView bio = Ui.text(activity, "", Ui.TEXT, 14);
        bio.setText(SimpleMarkdown.render(user.bio));
        bio.setLineSpacing(0, 1.15f);
        bio.setPadding(0, dp(6), 0, 0);
        bio.setLinkTextColor(Ui.LINK);
        bio.setMovementMethod(LinkMovementMethod.getInstance());
        aboutCard.addView(bio);
        aboutCard.setVisibility(View.VISIBLE);
    }

    private String guildName() {
        List<Guild> gs = activity.guildCache.get("me");
        if (gs != null) for (Guild g : gs) if (g.id.equals(guildId)) return g.name == null ? "" : g.name;
        return "Server";
    }

    private View sinceRow(int tileColor, String letter, String date, String label) {
        LinearLayout row = new LinearLayout(activity);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout.LayoutParams rlp = new LinearLayout.LayoutParams(-1, -2);
        rlp.topMargin = dp(8);
        row.setLayoutParams(rlp);

        TextView tile = Ui.text(activity, letter, Color.WHITE, 11);
        tile.setTypeface(null, Typeface.BOLD);
        tile.setGravity(Gravity.CENTER);
        tile.setBackground(Ui.rounded(tileColor, dp(6)));
        LinearLayout.LayoutParams tlp = new LinearLayout.LayoutParams(dp(22), dp(22));
        tlp.setMarginEnd(dp(10));
        row.addView(tile, tlp);

        row.addView(Ui.text(activity, date, Ui.TEXT, 14));
        TextView l = Ui.text(activity, "  \u00B7  " + label, Ui.MUTED, 13);
        l.setSingleLine(true);
        l.setEllipsize(TextUtils.TruncateAt.END);
        row.addView(l, new LinearLayout.LayoutParams(0, -2, 1f));
        return row;
    }

    private View infoRow(String label, String value, int topMargin) {
        LinearLayout row = new LinearLayout(activity);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
        lp.topMargin = topMargin == 0 ? dp(6) : topMargin;
        row.setLayoutParams(lp);

        row.addView(Ui.text(activity, label, Ui.MUTED, 14),
            new LinearLayout.LayoutParams(-2, -2));

        TextView v = Ui.text(activity, value, Ui.TEXT, 14);
        LinearLayout.LayoutParams vlp = new LinearLayout.LayoutParams(-2, -2);
        vlp.setMarginStart(dp(8));
        row.addView(v, vlp);
        return row;
    }

    private void renderRoles(List<String> roleIds, Map<String, Role> allRoles) {
        rolesRow.removeAllViews();
        List<Role> memberRoles = new ArrayList<>();
        for (String id : roleIds) {
            Role r = allRoles.get(id);
            if (r != null && !"@everyone".equals(r.name)) memberRoles.add(r);
        }
        Collections.sort(memberRoles, (a, b) -> Integer.compare(b.position, a.position));

        int maxWidth = activity.getResources().getDisplayMetrics().widthPixels - dp(40);
        LinearLayout currentRow = null;
        int used = 0;
        for (Role role : memberRoles) {
            LinearLayout chip = buildRoleChip(role.name, role.color);
            chip.measure(View.MeasureSpec.UNSPECIFIED, View.MeasureSpec.UNSPECIFIED);
            int w = chip.getMeasuredWidth() + dp(6);
            if (currentRow == null || used + w > maxWidth) {
                currentRow = new LinearLayout(activity);
                currentRow.setOrientation(LinearLayout.HORIZONTAL);
                rolesRow.addView(currentRow, new LinearLayout.LayoutParams(-1, -2));
                used = 0;
            }
            currentRow.addView(chip);
            used += w;
        }
        rolesSection.setVisibility(memberRoles.isEmpty() ? View.GONE : View.VISIBLE);
    }

    private LinearLayout buildRoleChip(String name, int color) {
        LinearLayout chip = new LinearLayout(activity);
        chip.setOrientation(LinearLayout.HORIZONTAL);
        chip.setGravity(Gravity.CENTER_VERTICAL);
        chip.setPadding(dp(8), dp(5), dp(10), dp(5));
        chip.setBackground(Ui.rounded(CHIP_BG, dp(8)));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-2, -2);
        lp.setMargins(0, dp(3), dp(6), dp(3));
        chip.setLayoutParams(lp);

        View dot = new View(activity);
        dot.setBackground(Ui.oval(color != 0 ? color | 0xFF000000 : Ui.MUTED));
        LinearLayout.LayoutParams dlp = new LinearLayout.LayoutParams(dp(12), dp(12));
        dlp.setMarginEnd(dp(6));
        chip.addView(dot, dlp);

        TextView t = Ui.text(activity, name, Ui.TEXT, 12);
        t.setSingleLine(true);
        chip.addView(t);
        return chip;
    }

    private static String formatDate(long ms) {
        return new SimpleDateFormat("MMM d, yyyy", Locale.US).format(new Date(ms));
    }

    private static long parseIso(String s) {
        if (s == null || s.length() < 19) return 0;
        try {
            SimpleDateFormat f = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", Locale.US);
            f.setTimeZone(TimeZone.getTimeZone("UTC"));
            Date d = f.parse(s.substring(0, 19));
            return d == null ? 0 : d.getTime();
        } catch (Exception e) {
            return 0;
        }
    }

    private int dp(int v) { return Ui.dp(activity, v); }

    @Override public void onActivityResult(int requestCode, int resultCode, Intent data) {}
    @Override public View getView() { return view; }
    @Override public void onResume() {}
    @Override public void onPause() {}
    @Override public void onDestroy() { activity = null; }
}
