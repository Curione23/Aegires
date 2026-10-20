package a.b.screen;

import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.text.TextUtils;
import android.text.method.LinkMovementMethod;
import android.view.*;
import android.widget.*;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import org.json.JSONArray;
import org.json.JSONObject;
import java.text.SimpleDateFormat;
import java.util.*;
import a.b.MainActivity;
import a.b.model.DiscordUser;
import a.b.model.Role;
import a.b.api.ApiClient;
import a.b.util.SimpleMarkdown;

public class ProfileScreen implements Screen, ApiClient.Callback<DiscordUser> {
    private static final int BG = 0xFF000000;
    private static final int BANNER_FALLBACK = 0xFF0A0A0A;
    private static final int CARD_BG = 0xFF101010;
    private static final int CARD_BORDER = 0xFF2B2D31;
    private static final int TEXT_PRIMARY = 0xFFFFFFFF;
    private static final int TEXT_SECONDARY = 0xFFB9BBBE;
    private static final int TEXT_MUTED = 0xFF72767D;
    private static final int ACCENT = 0xFF5865F2;
    private static final int COPY_BG = 0xFF1A1A1A;

    MainActivity activity;
    final String userId;
    final String guildId;
    View view;
    float density;
    ImageView bannerIv, avatarIv;
    TextView globalNameTv, usernameTv, pronounsTv, bioTv, botTag;
    LinearLayout aboutSection, detailsSection;
    LinearLayout badgeSection, rolesSection;
    LinearLayout badgesRow, rolesRow;

    public ProfileScreen(String userId, String guildId) {
        this.userId = userId;
        this.guildId = guildId;
    }

    @Override
    public View onCreateView(final MainActivity activity, ViewGroup parent) {
        this.activity = activity;
        this.density = activity.getResources().getDisplayMetrics().density;
        
        ScrollView scroll = new ScrollView(activity);
        scroll.setFillViewport(true);
        scroll.setBackgroundColor(BG);
        
        LinearLayout root = new LinearLayout(activity);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(0, 0, 0, dp(24));
        scroll.addView(root);
        
        root.addView(buildBanner());
        root.addView(buildAvatar());
        root.addView(buildNameBlock());
        root.addView(buildBadgeSection());
        root.addView(buildRolesSection());
        root.addView(buildAboutSection());
        root.addView(buildDetailsSection());
        
        activity.apiClient.fetchUserProfileFull(userId, this);
        loadRoles();
        return view = scroll;
    }

    private View buildBanner() {
        FrameLayout frame = new FrameLayout(activity);
        frame.setLayoutParams(new LinearLayout.LayoutParams(-1, dp(180)));
        frame.setBackgroundColor(BANNER_FALLBACK);
        
        bannerIv = new ImageView(activity);
        bannerIv.setScaleType(ImageView.ScaleType.CENTER_CROP);
        bannerIv.setLayoutParams(new FrameLayout.LayoutParams(-1, -1));
        frame.addView(bannerIv);
        return frame;
    }

    private View buildAvatar() {
        int size = dp(110);
        FrameLayout frame = new FrameLayout(activity);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(size, size);
        lp.gravity = Gravity.CENTER_HORIZONTAL;
        lp.topMargin = -size / 2;
        frame.setLayoutParams(lp);
        
        avatarIv = new ImageView(activity);
        avatarIv.setScaleType(ImageView.ScaleType.CENTER_CROP);
        avatarIv.setLayoutParams(new FrameLayout.LayoutParams(-1, -1));
        
        GradientDrawable bg = new GradientDrawable();
        bg.setShape(GradientDrawable.OVAL);
        bg.setStroke(dp(5), BG);
        bg.setColor(ACCENT);
        avatarIv.setBackground(bg);
        avatarIv.setClipToOutline(true);
        frame.addView(avatarIv);
        return frame;
    }

    private View buildNameBlock() {
        LinearLayout block = new LinearLayout(activity);
        block.setOrientation(LinearLayout.VERTICAL);
        block.setGravity(Gravity.CENTER_HORIZONTAL);
        block.setPadding(dp(24), dp(12), dp(24), 0);
        
        globalNameTv = new TextView(activity);
        globalNameTv.setTextColor(TEXT_PRIMARY);
        globalNameTv.setTextSize(22);
        globalNameTv.setTypeface(null, Typeface.BOLD);
        globalNameTv.setGravity(Gravity.CENTER_HORIZONTAL);
        globalNameTv.setSingleLine(true);
        globalNameTv.setEllipsize(TextUtils.TruncateAt.END);
        block.addView(globalNameTv);

        LinearLayout row = new LinearLayout(activity);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_HORIZONTAL);
        row.setPadding(0, dp(4), 0, 0);

        usernameTv = new TextView(activity);
        usernameTv.setTextColor(TEXT_SECONDARY);
        usernameTv.setTextSize(14);
        row.addView(usernameTv);

        botTag = new TextView(activity);
        botTag.setText("BOT");
        botTag.setTextColor(Color.WHITE);
        botTag.setTextSize(10);
        botTag.setTypeface(null, Typeface.BOLD);
        botTag.setPadding(dp(6), dp(2), dp(6), dp(2));
        
        LinearLayout.LayoutParams tagLp = new LinearLayout.LayoutParams(-2, -2);
        tagLp.setMarginStart(dp(6));
        tagLp.gravity = Gravity.CENTER_VERTICAL;
        botTag.setLayoutParams(tagLp);
        
        GradientDrawable tagBg = new GradientDrawable();
        tagBg.setColor(ACCENT);
        tagBg.setCornerRadius(dp(4));
        botTag.setBackground(tagBg);
        botTag.setVisibility(View.GONE);
        row.addView(botTag);
        block.addView(row);

        pronounsTv = new TextView(activity);
        pronounsTv.setTextColor(TEXT_MUTED);
        pronounsTv.setTextSize(13);
        pronounsTv.setGravity(Gravity.CENTER_HORIZONTAL);
        pronounsTv.setPadding(0, dp(4), 0, 0);
        pronounsTv.setVisibility(View.GONE);
        block.addView(pronounsTv);
        return block;
    }

    private View buildBadgeSection() {
        badgeSection = new LinearLayout(activity);
        badgeSection.setOrientation(LinearLayout.VERTICAL);
        badgeSection.setPadding(dp(20), dp(16), dp(20), 0);
        badgeSection.setVisibility(View.GONE);
        
        badgesRow = new LinearLayout(activity);
        badgesRow.setOrientation(LinearLayout.VERTICAL);
        badgesRow.setLayoutParams(new LinearLayout.LayoutParams(-1, -2));
        badgeSection.addView(badgesRow);
        return badgeSection;
    }

    private View buildRolesSection() {
        rolesSection = new LinearLayout(activity);
        rolesSection.setOrientation(LinearLayout.VERTICAL);
        rolesSection.setVisibility(View.GONE);
        
        LinearLayout card = buildCard();
        card.setLayoutParams(cardLp());
        card.addView(sectionHeader("ROLES"));
        
        rolesRow = new LinearLayout(activity);
        rolesRow.setOrientation(LinearLayout.VERTICAL);
        rolesRow.setPadding(0, dp(8), 0, 0);
        card.addView(rolesRow);
        
        rolesSection.addView(card);
        return rolesSection;
    }

    private View buildAboutSection() {
        aboutSection = buildCard();
        aboutSection.setLayoutParams(cardLp());
        aboutSection.addView(sectionHeader("ABOUT ME"));
        
        bioTv = new TextView(activity);
        bioTv.setTextColor(TEXT_SECONDARY);
        bioTv.setTextSize(14);
        bioTv.setLineSpacing(0, 1.2f);
        bioTv.setPadding(0, dp(6), 0, 0);
        bioTv.setMovementMethod(LinkMovementMethod.getInstance());
        aboutSection.addView(bioTv);
        return aboutSection;
    }

    private View buildDetailsSection() {
        detailsSection = buildCard();
        detailsSection.setLayoutParams(cardLp());
        detailsSection.addView(sectionHeader("DETAILS"));
        return detailsSection;
    }

    private void loadRoles() {
        if (guildId == null || activity == null) return;
        activity.apiClient.fetchMember(guildId, userId, new ApiClient.Callback<JSONObject>() {
            @Override public void onSuccess(JSONObject member) {
                JSONArray rolesArray = member.optJSONArray("roles");
                if (rolesArray == null || rolesArray.length() == 0) return;
                
                final List<String> roleIds = new ArrayList<>();
                for (int i = 0; i < rolesArray.length(); i++) roleIds.add(rolesArray.optString(i));
                
                activity.apiClient.fetchRoles(guildId, new ApiClient.Callback<Map<String, Role>>() {
                    @Override public void onSuccess(Map<String, Role> roles) {
                        if (activity == null) return;
                        activity.roleCache.putAll(roles);
                        renderRoleChips(roleIds, roles);
                    }
                    @Override public void onError(String err) {}
                });
            }
            @Override public void onError(String err) {}
        });
    }

    private void renderRoleChips(List<String> roleIds, Map<String, Role> allRoles) {
        rolesRow.removeAllViews();
        boolean any = false;
        List<Role> memberRoles = new ArrayList<>();
        
        for (String id : roleIds) {
            Role r = allRoles.get(id);
            if (r != null) memberRoles.add(r);
        }
        Collections.sort(memberRoles, (a, b) -> Integer.compare(b.position, a.position));
        
        int maxWidth = activity.getResources().getDisplayMetrics().widthPixels - dp(72);
        LinearLayout currentRow = null;
        int usedWidth = 0;
        
        for (Role role : memberRoles) {
            if ("@everyone".equals(role.name)) continue;
            LinearLayout chip = buildChip(role.name, role.color, 8, 11);
            chip.measure(View.MeasureSpec.UNSPECIFIED, View.MeasureSpec.UNSPECIFIED);
            int w = chip.getMeasuredWidth() + dp(6);
            if (currentRow == null || usedWidth + w > maxWidth) {
                currentRow = new LinearLayout(activity);
                currentRow.setOrientation(LinearLayout.HORIZONTAL);
                currentRow.setLayoutParams(new LinearLayout.LayoutParams(-1, -2));
                rolesRow.addView(currentRow);
                usedWidth = 0;
            }
            currentRow.addView(chip);
            usedWidth += w;
            any = true;
        }
        if (any) rolesSection.setVisibility(View.VISIBLE);
    }

    private void populateBadges(int flags, boolean bot) {
        badgesRow.removeAllViews();
        boolean any = false;
        int maxWidth = activity.getResources().getDisplayMetrics().widthPixels - dp(40);
        LinearLayout currentRow = null;
        int usedWidth = 0;
        List<String[]> badgeList = new ArrayList<>();
        
        if (bot) badgeList.add(new String[]{"\uD83E\uDD16", "Bot"});
        if ((flags & (1 << 0)) != 0) badgeList.add(new String[]{"\uD83D\uDEE1", "Staff"});
        if ((flags & (1 << 1)) != 0) badgeList.add(new String[]{"\uD83E\uDD1D", "Partner"});
        if ((flags & (1 << 2)) != 0) badgeList.add(new String[]{"\uD83C\uDFC6", "HypeSquad"});
        if ((flags & (1 << 3)) != 0) badgeList.add(new String[]{"\uD83D\uDC1B", "Bug Hunter"});
        if ((flags & (1 << 6)) != 0) badgeList.add(new String[]{"\uD83C\uDFE0", "Bravery"});
        if ((flags & (1 << 7)) != 0) badgeList.add(new String[]{"\uD83C\uDFE0", "Brilliance"});
        if ((flags & (1 << 8)) != 0) badgeList.add(new String[]{"\uD83C\uDFE0", "Balance"});
        if ((flags & (1 << 9)) != 0) badgeList.add(new String[]{"\u2B50", "Early Supporter"});
        if ((flags & (1 << 14)) != 0) badgeList.add(new String[]{"\uD83D\uDC1B", "Bug Hunter Gold"});
        if ((flags & (1 << 16)) != 0) badgeList.add(new String[]{"\u2611", "Verified Bot"});
        if ((flags & (1 << 17)) != 0) badgeList.add(new String[]{"\uD83D\uDEE0", "Verified Dev"});
        if ((flags & (1 << 18)) != 0) badgeList.add(new String[]{"\uD83D\uDEE1", "Mod Alumni"});
        if ((flags & (1 << 22)) != 0) badgeList.add(new String[]{"\u26A1", "Active Dev"});

        for (String[] badge : badgeList) {
            LinearLayout chip = buildChip(badge[0] + " " + badge[1], 0, 0, 11);
            chip.measure(View.MeasureSpec.UNSPECIFIED, View.MeasureSpec.UNSPECIFIED);
            int w = chip.getMeasuredWidth() + dp(6);
            if (currentRow == null || usedWidth + w > maxWidth) {
                currentRow = new LinearLayout(activity);
                currentRow.setOrientation(LinearLayout.HORIZONTAL);
                currentRow.setLayoutParams(new LinearLayout.LayoutParams(-1, -2));
                badgesRow.addView(currentRow);
                usedWidth = 0;
            }
            currentRow.addView(chip);
            usedWidth += w;
            any = true;
        }
        badgeSection.setVisibility(any ? View.VISIBLE : View.GONE);
    }

    private LinearLayout buildChip(String text, int dotColor, int dotSizeDp, int textSizeSp) {
        LinearLayout chip = new LinearLayout(activity);
        chip.setOrientation(LinearLayout.HORIZONTAL);
        chip.setGravity(Gravity.CENTER_VERTICAL);
        chip.setPadding(dp(8), dp(4), dp(10), dp(4));
        
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-2, -2);
        lp.setMargins(dp(3), dp(3), dp(3), dp(3));
        chip.setLayoutParams(lp);
        
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(CARD_BG);
        bg.setCornerRadius(dp(14));
        bg.setStroke(dp(1), CARD_BORDER);
        chip.setBackground(bg);
        
        if (dotSizeDp > 0) {
            View dot = new View(activity);
            GradientDrawable dotBg = new GradientDrawable();
            dotBg.setShape(GradientDrawable.OVAL);
            dotBg.setColor(dotColor != 0 ? dotColor | 0xFF000000 : 0xFF949BA4);
            dot.setBackground(dotBg);
            
            LinearLayout.LayoutParams dotLp = new LinearLayout.LayoutParams(dp(dotSizeDp), dp(dotSizeDp));
            dotLp.setMarginEnd(dp(6));
            dot.setLayoutParams(dotLp);
            chip.addView(dot);
        }
        
        TextView t = new TextView(activity);
        t.setText(text);
        t.setTextColor(TEXT_SECONDARY);
        t.setTextSize(textSizeSp);
        chip.addView(t);
        return chip;
    }

    private void populateDetails(DiscordUser user) {
        if (detailsSection.getChildCount() > 1) {
            detailsSection.removeViews(1, detailsSection.getChildCount() - 1);
        }
        if (user.createdAtMs > 0) {
            SimpleDateFormat sdf = new SimpleDateFormat("MMM d, yyyy", Locale.US);
            detailsSection.addView(detailRow("Account created", sdf.format(new Date(user.createdAtMs))));
            detailsSection.addView(detailRow("Account age", formatAge(user.createdAtMs)));
        }
        if (user.premiumType > 0) {
            detailsSection.addView(detailRow("Subscription", user.premiumType == 2 ? "Nitro" : "Nitro Classic"));
        }
        if (user.mutualGuildsCount > 0) {
            String label = user.mutualGuildsCount == 1 ? "1 server" : user.mutualGuildsCount + " servers";
            detailsSection.addView(detailRow("Mutual servers", label));
        }
        detailsSection.addView(buildCopyButton());
    }

    private View buildCopyButton() {
        Button b = new Button(activity);
        b.setText("Copy user ID");
        b.setAllCaps(false);
        b.setTextColor(ACCENT);
        b.setTextSize(13);
        b.setTypeface(Typeface.MONOSPACE);
        
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(COPY_BG);
        bg.setCornerRadius(dp(6));
        b.setBackground(bg);
        
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
        lp.setMargins(0, dp(12), 0, 0);
        b.setLayoutParams(lp);
        
        b.setOnClickListener(v -> {
            ClipboardManager cm = (ClipboardManager) activity.getSystemService(Context.CLIPBOARD_SERVICE);
            cm.setPrimaryClip(ClipData.newPlainText("user id", userId));
            activity.toast("Copied user ID");
        });
        return b;
    }

    private LinearLayout buildCard() {
        LinearLayout card = new LinearLayout(activity);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setPadding(dp(16), dp(14), dp(16), dp(14));
        
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(CARD_BG);
        bg.setCornerRadius(dp(10));
        bg.setStroke(dp(1), CARD_BORDER);
        card.setBackground(bg);
        return card;
    }

    private LinearLayout.LayoutParams cardLp() {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
        lp.setMargins(dp(20), dp(16), dp(20), 0);
        return lp;
    }

    private TextView sectionHeader(String text) {
        TextView tv = new TextView(activity);
        tv.setText(text);
        tv.setTextColor(TEXT_MUTED);
        tv.setTextSize(11);
        tv.setTypeface(null, Typeface.BOLD);
        tv.setLetterSpacing(0.05f);
        return tv;
    }

    private View detailRow(String label, String value) {
        LinearLayout row = new LinearLayout(activity);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
        lp.topMargin = dp(10);
        row.setLayoutParams(lp);
        
        TextView labelTv = new TextView(activity);
        labelTv.setText(label);
        labelTv.setTextColor(TEXT_MUTED);
        labelTv.setTextSize(13);
        labelTv.setLayoutParams(new LinearLayout.LayoutParams(0, -2, 1f));
        row.addView(labelTv);
        
        TextView valueTv = new TextView(activity);
        valueTv.setText(value);
        valueTv.setTextColor(TEXT_PRIMARY);
        valueTv.setTextSize(13);
        valueTv.setGravity(Gravity.END);
        row.addView(valueTv);
        return row;
    }

    private static String formatAge(long createdAtMs) {
        long now = System.currentTimeMillis();
        long days = (now - createdAtMs) / (1000L * 60 * 60 * 24);
        if (days < 0) days = 0;
        if (days < 30) return days + (days == 1 ? " day" : " days");
        long months = days / 30;
        if (months < 12) return months + (months == 1 ? " month" : " months");
        long years = days / 365;
        long rem = (days - years * 365) / 30;
        if (rem == 0) return years + (years == 1 ? " year" : " years");
        return years + "y " + rem + "mo";
    }

    @Override
    public void onSuccess(DiscordUser user) {
        if (user == null || activity == null) return;
        usernameTv.setText("@" + user.username);
        globalNameTv.setText(isReal(user.globalName) ? user.globalName : user.username);
        botTag.setVisibility(user.bot ? View.VISIBLE : View.GONE);
        
        if (isReal(user.pronouns)) {
            pronounsTv.setText(user.pronouns);
            pronounsTv.setVisibility(View.VISIBLE);
        }
        if (isReal(user.banner)) {
            activity.imageLoader.load("https://cdn.discordapp.com/banners/" + user.id + "/" + user.banner + ".png?size=600", bannerIv);
        } else if (user.accentColor != 0) {
            bannerIv.setImageBitmap(null);
            bannerIv.setBackgroundColor(user.accentColor | 0xFF000000);
        }
        if (isReal(user.avatar)) {
            activity.imageLoader.load("https://cdn.discordapp.com/avatars/" + user.id + "/" + user.avatar + ".png?size=256", avatarIv);
        }
        if (isReal(user.bio)) {
            bioTv.setText(SimpleMarkdown.render(user.bio));
        } else {
            aboutSection.setVisibility(View.GONE);
        }
        populateBadges(user.publicFlags, user.bot);
        populateDetails(user);
    }

    @Override
    public void onError(String msg) {
        if (activity != null) activity.toast("Failed to load profile: " + msg);
    }

    private static boolean isReal(String s) { return s != null && !s.isEmpty() && !"null".equals(s); }
    private int dp(int v) { return (int) (v * density); }

    @Override public void onActivityResult(int requestCode, int resultCode, Intent data) {}
    @Override public View getView() { return view; }
    @Override public void onResume() {}
    @Override public void onPause() {}
    @Override public void onDestroy() { activity = null; }
}