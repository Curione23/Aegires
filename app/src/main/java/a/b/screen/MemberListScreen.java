package a.b.screen;

import android.content.Intent;
import android.graphics.Color;
import android.graphics.Typeface;
import android.os.Handler;
import android.os.Looper;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;
import java.util.Locale;
import a.b.MainActivity;
import a.b.model.Role;
import a.b.util.MemberListStore;
import a.b.util.MemberListStore.Entry;
import a.b.util.Ui;

public class MemberListScreen implements Screen, MemberListStore.Listener {
    private static final int TYPE_GROUP = 0, TYPE_MEMBER = 1, TYPE_PLACEHOLDER = 2;
    private static final int PAGE_BG = Ui.BG_SECONDARY;
    private static final int BLOCK = 100;

    MainActivity activity;
    final String guildId;
    final String channelId;
    View view;
    Ui.AppBar bar;
    RecyclerView list;
    TextView emptyView;
    Adapter adapter;
    private final Handler ui = new Handler(Looper.getMainLooper());
    private int lastBlock = -1;
    private long lastRequestAt;
    private final Runnable refresh = this::refreshNow;

    public MemberListScreen(String guildId, String channelId) {
        this.guildId = guildId;
        this.channelId = channelId;
    }

    @Override
    public View onCreateView(final MainActivity activity, ViewGroup parent) {
        this.activity = activity;

        LinearLayout root = new LinearLayout(activity);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(PAGE_BG);

        bar = new Ui.AppBar(activity, "Members", PAGE_BG, activity::goBack);
        root.addView(bar.view, new LinearLayout.LayoutParams(-1, -2));

        FrameLayout body = new FrameLayout(activity);
        list = new RecyclerView(activity);
        final LinearLayoutManager lm = new LinearLayoutManager(activity);
        list.setLayoutManager(lm);
        list.setItemAnimator(null);
        adapter = new Adapter();
        list.setAdapter(adapter);
        list.addOnScrollListener(new RecyclerView.OnScrollListener() {
            @Override public void onScrolled(RecyclerView rv, int dx, int dy) {
                ensureLoaded();
            }
        });
        body.addView(list, new FrameLayout.LayoutParams(-1, -1));

        emptyView = Ui.emptyState(activity, "Loading members\u2026");
        body.addView(emptyView, new FrameLayout.LayoutParams(-1, -2, Gravity.CENTER));
        root.addView(body, new LinearLayout.LayoutParams(-1, 0, 1f));

        if (activity.apiClient.isBotAccount()) {
            emptyView.setText("The member list isn't available for bot accounts.");
        }
        refreshNow();
        return view = root;
    }

    private void ensureLoaded() {
        if (activity == null || activity.gateway == null) return;
        MemberListStore store = activity.memberList;
        if (!guildId.equals(store.guildId())) return;
        LinearLayoutManager lm = (LinearLayoutManager) list.getLayoutManager();
        int first = lm.findFirstVisibleItemPosition();
        int last = lm.findLastVisibleItemPosition();
        if (first < 0 || last < 0) return;

        int from = Math.max(0, first - 10);
        int to = Math.min(store.total() - 1, last + 25);
        int missing = -1;
        for (int i = from; i <= to; i++) {
            if (store.get(i) == null) { missing = i; break; }
        }
        if (missing < 0) return;

        int block = missing / BLOCK;
        long now = System.currentTimeMillis();
        if (block == lastBlock && now - lastRequestAt < 2000) return;
        lastBlock = block;
        lastRequestAt = now;
        activity.gateway.requestMemberRanges(guildId, channelId, block * BLOCK);
        ui.postDelayed(this::ensureLoaded, 2100);
    }

    @Override public void onMemberListChanged() {
        ui.removeCallbacks(refresh);
        ui.postDelayed(refresh, 120);
    }

    private void refreshNow() {
        if (activity == null) return;
        MemberListStore store = activity.memberList;
        boolean mine = guildId.equals(store.guildId());
        int total = mine ? store.total() : 0;
        if (!activity.apiClient.isBotAccount()) {
            emptyView.setText("Loading members\u2026");
        }
        emptyView.setVisibility(total == 0 ? View.VISIBLE : View.GONE);
        if (mine && store.memberCount() > 0) {
            bar.setSubtitle(store.memberCount() + " members \u00B7 " + store.onlineCount() + " online");
        }
        adapter.notifyDataSetChanged();
        if (mine) ensureLoaded();
    }

    private Entry entryAt(int pos) {
        if (activity == null || !guildId.equals(activity.memberList.guildId())) return null;
        return activity.memberList.get(pos);
    }

    private int nameColor(Entry e) {
        Role best = null;
        for (String id : e.roles) {
            Role r = activity.roleCache.get(id);
            if (r != null && r.color != 0 && (best == null || r.position > best.position)) best = r;
        }
        return best != null ? best.color | 0xFF000000 : Ui.TEXT_HEADER;
    }

    private String groupTitle(Entry e) {
        String name;
        if ("online".equals(e.groupId)) name = "Online";
        else if ("offline".equals(e.groupId)) name = "Offline";
        else {
            Role r = activity.roleCache.get(e.groupId);
            name = r != null ? r.name : "Role";
        }
        int count = activity.memberList.groupCount(e.groupId, e.count);
        return name.toUpperCase(Locale.US) + " \u2014 " + count;
    }

    private static int statusColor(String status) {
        if ("online".equals(status)) return Ui.GREEN;
        if ("idle".equals(status)) return Ui.YELLOW;
        if ("dnd".equals(status)) return Ui.RED;
        return 0xFF80848E;
    }

    private class Adapter extends RecyclerView.Adapter<RecyclerView.ViewHolder> {
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
    }

    private class GroupHolder extends RecyclerView.ViewHolder {
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
        void bind(Entry e) { title.setText(groupTitle(e)); }
    }

    private class MemberHolder extends RecyclerView.ViewHolder {
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
            name.setTextColor(nameColor(e));
            botTag.setVisibility(e.bot ? View.VISIBLE : View.GONE);
            itemView.setAlpha("offline".equals(e.status) ? 0.45f : 1f);
            dot.setBackground(Ui.oval(statusColor(e.status)));

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

    private class PlaceholderHolder extends RecyclerView.ViewHolder {
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

    private int dp(int v) { return Ui.dp(activity, v); }

    @Override public View getView() { return view; }
    @Override public void onActivityResult(int requestCode, int resultCode, Intent data) {}

    @Override public void onResume() {
        if (activity == null) return;
        activity.memberList.addListener(this);
        refreshNow();
    }

    @Override public void onPause() {
        if (activity != null) activity.memberList.removeListener(this);
    }

    @Override public void onDestroy() {
        ui.removeCallbacksAndMessages(null);
        if (activity != null) activity.memberList.removeListener(this);
        activity = null;
    }
}
