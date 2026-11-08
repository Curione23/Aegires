package a.b.screen;

import android.content.Context;
import android.graphics.Color;
import android.graphics.Typeface;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.TextView;
import androidx.recyclerview.widget.RecyclerView;
import java.util.List;
import java.util.Locale;
import a.b.MainActivity;
import a.b.model.Guild;
import a.b.util.Ui;

/** Renders the narrow left-hand server rail. */
final class GuildRailAdapter extends RecyclerView.Adapter<GuildRailAdapter.Holder> {
    public interface OnGuildSelectedListener {
        void onGuildSelected(Guild guild);
    }

    private MainActivity activity;
    private final List<Guild> guilds;
    private final OnGuildSelectedListener listener;
    private String selectedGuildId;

    GuildRailAdapter(MainActivity activity, List<Guild> guilds, OnGuildSelectedListener listener) {
        this.activity = activity;
        this.guilds = guilds;
        this.listener = listener;
        setHasStableIds(true);
    }

    void setSelected(String id) {
        if (id == null || !id.equals(selectedGuildId)) {
            selectedGuildId = id;
            notifyDataSetChanged();
        }
    }

    @Override public int getItemCount() { return guilds.size(); }
    @Override public long getItemId(int position) { return guilds.get(position).id.hashCode(); }

    @Override public Holder onCreateViewHolder(ViewGroup parent, int viewType) {
        return new Holder(parent.getContext());
    }

    @Override public void onBindViewHolder(Holder holder, int position) {
        holder.bind(guilds.get(position));
    }

    final class Holder extends RecyclerView.ViewHolder {
        final FrameLayout wrap;
        final ImageView icon;
        final TextView initial;
        final View indicator;

        Holder(Context c) {
            super(new FrameLayout(c));
            FrameLayout root = (FrameLayout) itemView;
            root.setLayoutParams(new RecyclerView.LayoutParams(-1, Ui.dp(c, 52)));
            
            indicator = new View(c);
            indicator.setBackground(Ui.rounded(Color.WHITE, Ui.dp(c, 2)));
            FrameLayout.LayoutParams ilp = new FrameLayout.LayoutParams(Ui.dp(c, 4), Ui.dp(c, 20), Gravity.START | Gravity.CENTER_VERTICAL);
            ilp.leftMargin = Ui.dp(c, 4);
            indicator.setVisibility(View.GONE);
            root.addView(indicator, ilp);

            wrap = new FrameLayout(c);
            wrap.setClipToOutline(true);
            wrap.setOutlineProvider(android.view.ViewOutlineProvider.BACKGROUND);
            FrameLayout.LayoutParams wlp = new FrameLayout.LayoutParams(Ui.dp(c, 40), Ui.dp(c, 40), Gravity.CENTER);
            root.addView(wrap, wlp);

            initial = Ui.text(c, "", Color.WHITE, 16);
            initial.setTypeface(null, Typeface.BOLD);
            initial.setGravity(Gravity.CENTER);
            wrap.addView(initial, new FrameLayout.LayoutParams(-1, -1));

            icon = new ImageView(c);
            icon.setScaleType(ImageView.ScaleType.CENTER_CROP);
            wrap.addView(icon, new FrameLayout.LayoutParams(-1, -1));
            
            itemView.setOnClickListener(v -> {
                if (listener != null) listener.onGuildSelected(guilds.get(getAdapterPosition()));
            });
        }

        void bind(final Guild g) {
            if (g == null) return;
            boolean isSelected = g.id.equals(selectedGuildId);
            indicator.setVisibility(isSelected ? View.VISIBLE : View.GONE);
            
            boolean hasIcon = Ui.isReal(g.id) && Ui.isReal(g.icon);
            int radius = isSelected ? Ui.dp(itemView.getContext(), 12) : Ui.dp(itemView.getContext(), 20);
            wrap.setBackground(Ui.rounded(hasIcon ? Ui.BG_TERTIARY : Ui.avatarColor(g.id), radius));
            
            initial.setText(initialOf(g.name));
            initial.setVisibility(hasIcon ? View.GONE : View.VISIBLE);
            
            if (activity != null && activity.imageLoader != null) {
                if (hasIcon) {
                    activity.imageLoader.load("https://cdn.discordapp.com/icons/" + g.id + "/" + g.icon + ".png?size=128", icon);
                } else {
                    icon.setImageDrawable(null);
                }
            }
        }
    }

    private static String initialOf(String name) {
        if (name == null) return "?";
        String trimmed = name.trim();
        if (trimmed.isEmpty()) return "?";
        return trimmed.substring(0, trimmed.offsetByCodePoints(0, 1)).toUpperCase(Locale.US);
    }
}