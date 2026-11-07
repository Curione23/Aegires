package a.b.screen;

import android.graphics.Color;
import android.graphics.Typeface;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import androidx.recyclerview.widget.RecyclerView;
import java.util.List;
import java.util.Locale;
import a.b.MainActivity;
import a.b.model.Guild;
import a.b.util.Ui;

/** RecyclerView adapter for the "Servers" list. */
final class GuildListAdapter extends RecyclerView.Adapter<GuildListAdapter.GuildViewHolder> {
    private static final int ROW_HEIGHT_DP = 60;
    private static final int ICON_SIZE_DP = 44;

    private MainActivity activity;
    private final List<Guild> guilds;
    private final float density;

    GuildListAdapter(MainActivity activity, List<Guild> guilds, float density) {
        this.activity = activity;
        this.guilds = guilds;
        this.density = density;
    }

    /** Called from the screen's onDestroy to drop the activity reference. */
    void release() {
        activity = null;
    }

    @Override public int getItemCount() { return guilds.size(); }
    @Override public GuildViewHolder onCreateViewHolder(ViewGroup parent, int viewType) { return new GuildViewHolder(); }
    @Override public void onBindViewHolder(GuildViewHolder holder, int pos) { holder.bind(guilds.get(pos)); }

    final class GuildViewHolder extends RecyclerView.ViewHolder {
        final FrameLayout iconWrap;
        final ImageView icon;
        final TextView initial, name;

        GuildViewHolder() {
            super(new LinearLayout(activity));
            LinearLayout row = (LinearLayout) itemView;
            row.setLayoutParams(new RecyclerView.LayoutParams(-1, dp(ROW_HEIGHT_DP)));
            row.setOrientation(LinearLayout.HORIZONTAL);
            row.setGravity(Gravity.CENTER_VERTICAL);
            row.setPadding(dp(16), 0, dp(16), 0);
            row.setClickable(true);
            row.setBackground(Ui.ripple(null, 0));

            iconWrap = new FrameLayout(activity);
            iconWrap.setClipToOutline(true);
            LinearLayout.LayoutParams iconLp = new LinearLayout.LayoutParams(dp(ICON_SIZE_DP), dp(ICON_SIZE_DP));
            iconLp.setMarginEnd(dp(14));
            icon = new ImageView(activity);
            icon.setScaleType(ImageView.ScaleType.CENTER_CROP);
            initial = Ui.text(activity, "", Color.WHITE, 16);
            initial.setTypeface(null, Typeface.BOLD);
            initial.setGravity(Gravity.CENTER);
            iconWrap.addView(initial, new FrameLayout.LayoutParams(-1, -1));
            iconWrap.addView(icon, new FrameLayout.LayoutParams(-1, -1));
            row.addView(iconWrap, iconLp);

            name = Ui.text(activity, "", Ui.TEXT_HEADER, 16);
            name.setSingleLine(true);
            name.setEllipsize(TextUtils.TruncateAt.END);
            row.addView(name, new LinearLayout.LayoutParams(0, -2, 1f));
        }

        void bind(final Guild g) {
            name.setText(g.name);
            boolean hasIcon = Ui.isReal(g.icon);
            iconWrap.setBackground(Ui.rounded(hasIcon ? Ui.BG_TERTIARY : Ui.avatarColor(g.id), dp(16)));
            iconWrap.setOutlineProvider(android.view.ViewOutlineProvider.BACKGROUND);
            initial.setText(initialOf(g.name));
            initial.setVisibility(hasIcon ? View.GONE : View.VISIBLE);
            activity.imageLoader.load(hasIcon
                ? "https://cdn.discordapp.com/icons/" + g.id + "/" + g.icon + ".png?size=128" : null, icon);
            itemView.setOnClickListener(v -> {
                if (activity != null) activity.navigate(new ChannelListScreen(g, g.owner));
            });
        }
    }

    private static String initialOf(String name) {
        if (name == null || name.trim().isEmpty()) return "?";
        return name.trim().substring(0, name.trim().offsetByCodePoints(0, 1)).toUpperCase(Locale.US);
    }

    private int dp(int v) { return (int) (v * density); }
}