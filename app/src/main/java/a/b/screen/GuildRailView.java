package a.b.screen;

import android.view.Gravity;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import a.b.MainActivity;
import a.b.model.Guild;
import a.b.util.MentionStore;
import a.b.util.Ui;

import java.util.List;

/** The narrow left-hand rail containing server icons and bottom utility buttons. */
public class GuildRailView extends LinearLayout implements MentionStore.Listener {
    private final RecyclerView list;
    private final GuildRailAdapter adapter;
    private final MainActivity activity;
    private final GuildSessionMenu sessionMenu;

    private final TextView dmBadge;
    private final TextView notifBadge;
    private final View homeCircle;

    public GuildRailView(
            MainActivity activity,
            List<Guild> guilds,
            GuildRailAdapter.OnGuildSelectedListener listener,
            View.OnClickListener onDmClick) {
        super(activity);
        this.activity = activity;
        this.sessionMenu =
                new GuildSessionMenu(activity, activity.getResources().getDisplayMetrics().density);

        setOrientation(VERTICAL);
        setBackgroundColor(Ui.BG_TERTIARY);
        setLayoutParams(new LinearLayout.LayoutParams(Ui.dp(activity, 72), -1));

        FrameLayout homeBtn = buildRailButton(Ui.I_CHAT, Ui.TEXT_HEADER);
        homeCircle = homeBtn.getChildAt(0);
        homeBtn.setOnClickListener(onDmClick);
        dmBadge = (TextView) homeBtn.getTag();
        addView(homeBtn);

        addView(buildDivider());

        list = new RecyclerView(activity);
        list.setLayoutManager(new LinearLayoutManager(activity));
        list.setItemAnimator(null);
        list.setClipToPadding(false);
        list.setPadding(0, 0, 0, Ui.dp(activity, 8));
        adapter = new GuildRailAdapter(activity, guilds, listener);
        list.setAdapter(adapter);
        addView(list, new LinearLayout.LayoutParams(-1, 0, 1f));

        addView(buildDivider());

        FrameLayout notifBtn = buildRailButton(Ui.I_BELL, Ui.TEXT_HEADER);
        notifBtn.setOnClickListener(v -> activity.navigate(new NotificationsScreen()));
        notifBadge = (TextView) notifBtn.getTag();
        addView(notifBtn);

        FrameLayout settingsBtn = buildRailButton(Ui.I_GEAR, Ui.TEXT_HEADER);
        settingsBtn.setOnClickListener(
                v -> {
                    if (sessionMenu != null) sessionMenu.showSessionMenu();
                });
        LinearLayout.LayoutParams slp = new LinearLayout.LayoutParams(-1, Ui.dp(activity, 52));
        slp.bottomMargin = Ui.dp(activity, 8);
        addView(settingsBtn, slp);

        updateBadges();
    }

    public void setSelectedGuild(String id) {
        if (adapter != null) adapter.setSelected(id);
        if (homeCircle != null) {
            homeCircle.setBackground(Ui.oval(id == null ? Ui.BLURPLE : Ui.BG_SECONDARY));
        }
    }

    public void onResume() {
        if (activity != null) activity.mentions.addListener(this);
        updateBadges();
    }

    public void onPause() {
        if (activity != null) activity.mentions.removeListener(this);
    }

    private FrameLayout buildRailButton(String iconPath, int color) {
        FrameLayout btn = new FrameLayout(activity);
        btn.setLayoutParams(new LinearLayout.LayoutParams(-1, Ui.dp(activity, 52)));
        btn.setBackground(Ui.ripple(null, 0));
        btn.setClickable(true);

        View circle = new View(activity);
        circle.setBackground(Ui.oval(Ui.BG_SECONDARY));
        FrameLayout.LayoutParams clp =
                new FrameLayout.LayoutParams(
                        Ui.dp(activity, 40), Ui.dp(activity, 40), Gravity.CENTER);
        btn.addView(circle, clp);

        ImageView icon = new ImageView(activity);
        icon.setImageDrawable(new Ui.Icon(iconPath, color));
        FrameLayout.LayoutParams ilp =
                new FrameLayout.LayoutParams(
                        Ui.dp(activity, 24), Ui.dp(activity, 24), Gravity.CENTER);
        btn.addView(icon, ilp);

        TextView badge = Ui.badge(activity);
        FrameLayout.LayoutParams blp =
                new FrameLayout.LayoutParams(-2, -2, Gravity.BOTTOM | Gravity.END);
        blp.setMargins(0, 0, Ui.dp(activity, 10), Ui.dp(activity, 8));
        btn.addView(badge, blp);

        btn.setTag(badge);
        return btn;
    }

    private View buildDivider() {
        View div = new View(activity);
        div.setBackgroundColor(Ui.DIVIDER);
        LinearLayout.LayoutParams divLp =
                new LinearLayout.LayoutParams(Ui.dp(activity, 32), Ui.dp(activity, 2));
        divLp.gravity = Gravity.CENTER_HORIZONTAL;
        divLp.topMargin = Ui.dp(activity, 2);
        divLp.bottomMargin = Ui.dp(activity, 2);
        div.setLayoutParams(divLp);
        return div;
    }

    @Override
    public void onMentionsChanged() {
        updateBadges();
    }

    private void updateBadges() {
        if (activity == null) return;
        if (dmBadge != null) Ui.setBadge(dmBadge, activity.mentions.dmUnreadTotal());
        if (notifBadge != null) Ui.setBadge(notifBadge, activity.mentions.unreadCount());
        if (adapter != null && list != null) list.post(adapter::notifyDataSetChanged);
    }
}
