package a.b.screen;

import android.graphics.Color;
import android.graphics.Typeface;
import android.text.TextUtils;
import android.text.method.LinkMovementMethod;
import android.view.Gravity;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.TextView;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import a.b.MainActivity;
import a.b.model.DiscordUser;
import a.b.model.Guild;
import a.b.util.SimpleMarkdown;
import a.b.util.Ui;

/** "Member since" block and the About Me card. */
final class ProfileDetailsView {
    private static final int CARD_BG = Ui.BG_TERTIARY;

    private final MainActivity activity;
    private final LinearLayout detailsSection;
    private final LinearLayout aboutCard;

    ProfileDetailsView(MainActivity activity) {
        this.activity = activity;
        detailsSection = new LinearLayout(activity);
        detailsSection.setOrientation(LinearLayout.VERTICAL);
        detailsSection.setVisibility(View.GONE);

        aboutCard = new LinearLayout(activity);
        aboutCard.setOrientation(LinearLayout.VERTICAL);
        aboutCard.setPadding(dp(16), dp(14), dp(16), dp(14));
        aboutCard.setBackground(Ui.rounded(CARD_BG, dp(12)));
        aboutCard.setVisibility(View.GONE);
    }

    View getDetailsView() { return detailsSection; }
    View getAboutCard() { return aboutCard; }

    void renderDetails(DiscordUser user, String guildId, long joinedMs) {
        if (user == null || activity == null) return;
        detailsSection.removeAllViews();

        detailsSection.addView(Ui.sectionLabel(activity, "Member since"));
        if (user.createdAtMs > 0) {
            detailsSection.addView(sinceRow(Ui.BLURPLE, "D", formatDate(user.createdAtMs), "Discord"));
        }
        if (guildId != null && joinedMs > 0) {
            String name = guildName(guildId);
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

    void renderAbout(DiscordUser user) {
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

    private String guildName(String guildId) {
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

    private static String formatDate(long ms) {
        return new SimpleDateFormat("MMM d, yyyy", Locale.US).format(new Date(ms));
    }

    private int dp(int v) { return Ui.dp(activity, v); }
}