package a.b.screen;

import android.content.Context;
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

    void release() {
        activity = null;
    }

    @Override public int getItemCount() { return guilds.size(); }

    @Override public GuildViewHolder onCreateViewHolder(ViewGroup parent, int viewType) {
        return new GuildViewHolder(parent.getContext());
    }

    @Override public void onBindViewHolder(GuildViewHolder holder, int pos) {
        Guild g = (pos >= 0 && pos < guilds.size()) ? guilds.get(pos) : null;
        holder.bind(g);
    }

    final class GuildViewHolder extends RecyclerView.ViewHolder {
        final FrameLayout iconWrap;
        final ImageView icon;
        final TextView initial, name;

        GuildViewHolder(Context context) {
            super(new LinearLayout(context));
            LinearLayout row = (LinearLayout) itemView;
            row.setLayoutParams(new RecyclerView.LayoutParams(-1, dp(ROW_HEIGHT_DP)));
            row.setOrientation(LinearLayout.HORIZONTAL);
            row.setGravity(Gravity.CENTER_VERTICAL);
            row.setPadding(dp(16), 0, dp(16), 0);
            row.setClickable(true);
            row.setBackground(Ui.ripple(null, 0));

            iconWrap = new FrameLayout(context);
            iconWrap.setClipToOutline(true);
            iconWrap.setOutlineProvider(android.view.ViewOutlineProvider.BACKGROUND);
            LinearLayout.LayoutParams iconLp = new LinearLayout.LayoutParams(dp(ICON_SIZE_DP), dp(ICON_SIZE_DP));
            iconLp.setMarginEnd(dp(14));
            icon = new ImageView(context);
            icon.setScaleType(ImageView.ScaleType.CENTER_CROP);
            initial = Ui.text(context, "", Color.WHITE, 16);
            initial.setTypeface(null, Typeface.BOLD);
            initial.setGravity(Gravity.CENTER);
            iconWrap.addView(initial, new FrameLayout.LayoutParams(-1, -1));
            iconWrap.addView(icon, new FrameLayout.LayoutParams(-1, -1));
            row.addView(iconWrap, iconLp);

            name = Ui.text(context, "", Ui.TEXT_HEADER, 16);
            name.setSingleLine(true);
            name.setEllipsize(TextUtils.TruncateAt.END);
            row.addView(name, new LinearLayout.LayoutParams(0, -2, 1f));
        }

        void bind(final Guild g) {
            if (g == null) return;
            name.setText(g.name);
            boolean hasIcon = Ui.isReal(g.id) && Ui.isReal(g.icon);
            iconWrap.setBackground(Ui.rounded(hasIcon ? Ui.BG_TERTIARY : Ui.avatarColor(g.id), dp(16)));
            initial.setText(initialOf(g.name));
            initial.setVisibility(hasIcon ? View.GONE : View.VISIBLE);
            if (activity != null && activity.imageLoader != null) {
                activity.imageLoader.load(hasIcon
                    ? "https://cdn.discordapp.com/icons/" + g.id + "/" + g.icon + ".png?size=128" : null, icon);
            } else {
                icon.setImageDrawable(null);
            }
            itemView.setOnClickListener(v -> {
                if (activity != null) activity.navigate(new ChannelListScreen(g, g.owner));
            });
        }
    }

    private static String initialOf(String name) {
        if (name == null) return "?";
        String trimmed = name.trim();
        if (trimmed.isEmpty()) return "?";
        return trimmed.substring(0, trimmed.offsetByCodePoints(0, 1)).toUpperCase(Locale.US);
    }

    private int dp(int v) { return (int) (v * density); }
}