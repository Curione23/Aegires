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
import java.util.Locale;
import a.b.MainActivity;
import a.b.util.MemberListStore.Entry;
import a.b.util.Ui;

/** RecyclerView adapter for the server member list. */
final class MemberListAdapter extends RecyclerView.Adapter<RecyclerView.ViewHolder> {
    private static final int TYPE_GROUP = 0, TYPE_MEMBER = 1, TYPE_PLACEHOLDER = 2;
    private static final int PAGE_BG = Ui.BG_SECONDARY;

    private MainActivity activity;
    private final String guildId;

    MemberListAdapter(MainActivity activity, String guildId) {
        this.activity = activity;
        this.guildId = guildId;
    }

    /** Called from the screen's onDestroy to drop the activity reference. */
    void release() {
        activity = null;
    }

    @Override public int getItemCount() {
        if (activity == null || !guildId.equals(activity.memberList.guildId())) return 0;
        return activity.memberList.total();
    }

    @Override public int getItemViewType(int pos) {
        Entry e = entryAt(pos);
        if (e == null) return TYPE_PLACEHOLDER;
        return e.group ? TYPE_GROUP : TYPE_MEMBER;
    }

    @Override public RecyclerView.ViewHolder onCreateViewHolder(ViewGroup parent, int type) {
        if (type == TYPE_GROUP) return new GroupHolder();
        if (type == TYPE_MEMBER) return new MemberHolder();
        return new PlaceholderHolder();
    }

    @Override public void onBindViewHolder(RecyclerView.ViewHolder h, int pos) {
        Entry e = entryAt(pos);
        if (h instanceof GroupHolder && e != null) ((GroupHolder) h).bind(e);
        else if (h instanceof MemberHolder && e != null) ((MemberHolder) h).bind(e);
    }

    private Entry entryAt(int pos) {
        if (activity == null || !guildId.equals(activity.memberList.guildId())) return null;
        return activity.memberList.get(pos);
    }

    private int dp(int v) { return Ui.dp(activity, v); }

    final class GroupHolder extends RecyclerView.ViewHolder {
        final TextView title;
        GroupHolder() {
            super(new TextView(activity));
            title = (TextView) itemView;
            title.setTextColor(Ui.MUTED);
            title.setTextSize(12);
            title.setTypeface(null, Typeface.BOLD);
            title.setLetterSpacing(0.04f);
            title.setSingleLine(true);
            title.setEllipsize(TextUtils.TruncateAt.END);
            title.setPadding(dp(16), dp(18), dp(16), dp(6));
            title.setLayoutParams(new RecyclerView.LayoutParams(-1, -2));
        }
        void bind(Entry e) { title.setText(MemberPresentation.groupTitle(activity, e)); }
    }

    final class MemberHolder extends RecyclerView.ViewHolder {
        final FrameLayout avatarWrap;
        final ImageView avatar;
        final TextView initial, name, botTag;
        final View dot;

        MemberHolder() {
            super(new LinearLayout(activity));
            LinearLayout row = (LinearLayout) itemView;
            row.setLayoutParams(new RecyclerView.LayoutParams(-1, dp(52)));
            row.setOrientation(LinearLayout.HORIZONTAL);
            row.setGravity(Gravity.CENTER_VERTICAL);
            row.setPadding(dp(16), 0, dp(16), 0);
            row.setClickable(true);
            row.setBackground(Ui.ripple(null, 0));

            FrameLayout holder = new FrameLayout(activity);
            LinearLayout.LayoutParams hlp = new LinearLayout.LayoutParams(dp(40), dp(40));
            hlp.setMarginEnd(dp(12));

            avatarWrap = new FrameLayout(activity);
            avatarWrap.setClipToOutline(true);
            initial = Ui.text(activity, "", Color.WHITE, 16);
            initial.setTypeface(null, Typeface.BOLD);
            initial.setGravity(Gravity.CENTER);
            avatarWrap.addView(initial, new FrameLayout.LayoutParams(-1, -1));
            avatar = new ImageView(activity);
            avatar.setScaleType(ImageView.ScaleType.CENTER_CROP);
            avatarWrap.addView(avatar, new FrameLayout.LayoutParams(-1, -1));
            holder.addView(avatarWrap, new FrameLayout.LayoutParams(-1, -1));

            FrameLayout ring = new FrameLayout(activity);
            ring.setBackground(Ui.oval(PAGE_BG));
            ring.setPadding(dp(3), dp(3), dp(3), dp(3));
            dot = new View(activity);
            ring.addView(dot, new FrameLayout.LayoutParams(-1, -1));
            holder.addView(ring, new FrameLayout.LayoutParams(dp(16), dp(16), Gravity.BOTTOM | Gravity.END));
            row.addView(holder, hlp);

            name = Ui.text(activity, "", Ui.TEXT_HEADER, 16);
            name.setSingleLine(true);
            name.setEllipsize(TextUtils.TruncateAt.END);
            row.addView(name, new LinearLayout.LayoutParams(0, -2, 1f));

            botTag = Ui.text(activity, "BOT", Color.WHITE, 10);
            botTag.setTypeface(null, Typeface.BOLD);
            botTag.setPadding(dp(5), dp(1), dp(5), dp(1));
            botTag.setBackground(Ui.rounded(Ui.BLURPLE, dp(4)));
            LinearLayout.LayoutParams tlp = new LinearLayout.LayoutParams(-2, -2);
            tlp.setMarginStart(dp(8));
            row.addView(botTag, tlp);
        }

        void bind(final Entry e) {
            String display = e.displayName();
            name.setText(display);
            name.setTextColor(MemberPresentation.nameColor(activity, e));
            botTag.setVisibility(e.bot ? View.VISIBLE : View.GONE);
            itemView.setAlpha("offline".equals(e.status) ? 0.45f : 1f);
            dot.setBackground(Ui.oval(MemberPresentation.statusColor(e.status)));

            avatarWrap.setBackground(Ui.oval(Ui.avatarColor(e.userId)));
            initial.setText(display.isEmpty() ? "?" : display.substring(0, display.offsetByCodePoints(0, 1)).toUpperCase(Locale.US));
            boolean has = Ui.isReal(e.avatar) && e.userId != null;
            activity.imageLoader.load(has
                ? "https://cdn.discordapp.com/avatars/" + e.userId + "/" + e.avatar + ".png?size=64" : null, avatar);

            itemView.setOnClickListener(v -> {
                if (activity != null && e.userId != null) activity.navigate(new ProfileScreen(e.userId, guildId));
            });
        }
    }

    final class PlaceholderHolder extends RecyclerView.ViewHolder {
        PlaceholderHolder() {
            super(new LinearLayout(activity));
            LinearLayout row = (LinearLayout) itemView;
            row.setLayoutParams(new RecyclerView.LayoutParams(-1, dp(52)));
            row.setGravity(Gravity.CENTER_VERTICAL);
            row.setPadding(dp(16), 0, dp(16), 0);
            View circle = new View(activity);
            circle.setBackground(Ui.oval(Ui.BG_INPUT));
            LinearLayout.LayoutParams clp = new LinearLayout.LayoutParams(dp(40), dp(40));
            clp.setMarginEnd(dp(12));
            row.addView(circle, clp);
            View bar = new View(activity);
            bar.setBackground(Ui.rounded(Ui.BG_INPUT, dp(6)));
            row.addView(bar, new LinearLayout.LayoutParams(dp(130), dp(12)));
        }
    }
}