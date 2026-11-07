package a.b.screen;

import android.content.Intent;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;
import java.util.ArrayList;
import java.util.List;
import a.b.MainActivity;
import a.b.api.ApiClient;
import a.b.model.Guild;
import a.b.util.MentionStore;
import a.b.util.Ui;

public class GuildListScreen implements Screen, MentionStore.Listener {
    MainActivity activity;
    View view;
    RecyclerView recyclerView;
    GuildListAdapter adapter;
    TextView emptyView;
    Ui.ActionButton mentionsBtn, dmBtn;
    float density;
    boolean destroyed;
    private boolean loading;
    private final List<Guild> guilds = new ArrayList<>();
    private GuildSessionMenu sessionMenu;

    @Override
    public View onCreateView(final MainActivity activity, ViewGroup parent) {
        this.activity = activity;
        this.density = activity.getResources().getDisplayMetrics().density;
        this.sessionMenu = new GuildSessionMenu(activity, density);

        LinearLayout root = new LinearLayout(activity);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(Ui.BG_SECONDARY);

        Ui.AppBar bar = new Ui.AppBar(activity, "Servers", Ui.BG_SECONDARY, null);
        mentionsBtn = bar.addAction(Ui.I_BELL, v -> activity.navigate(new NotificationsScreen()));
        dmBtn = bar.addAction(Ui.I_CHAT, v -> activity.navigate(new DmListScreen()));
        bar.addAction(Ui.I_GEAR, v -> { if (sessionMenu != null) sessionMenu.showSessionMenu(); });
        root.addView(bar.view, new LinearLayout.LayoutParams(-1, -2));

        FrameLayout body = new FrameLayout(activity);
        recyclerView = new RecyclerView(activity);
        recyclerView.setLayoutManager(new LinearLayoutManager(activity));
        recyclerView.setItemAnimator(null);
        recyclerView.setClipToPadding(false);
        recyclerView.setPadding(0, dp(8), 0, dp(16));
        adapter = new GuildListAdapter(activity, guilds, density);
        recyclerView.setAdapter(adapter);
        body.addView(recyclerView, new FrameLayout.LayoutParams(-1, -1));

        emptyView = Ui.emptyState(activity, "Loading servers\u2026");
        body.addView(emptyView, new FrameLayout.LayoutParams(-1, -2, Gravity.CENTER));
        root.addView(body, new LinearLayout.LayoutParams(-1, 0, 1f));

        loadGuilds();
        updateBadges();
        return view = root;
    }

    private void loadGuilds() {
        List<Guild> cached = activity.guildCache.get("me");
        if (cached != null) {
            guilds.clear();
            guilds.addAll(cached);
            adapter.notifyDataSetChanged();
            updateEmpty(null);
            return;
        }
        if (loading) return;
        loading = true;
        activity.diskCache.loadGuilds(disk -> {
            if (destroyed || activity == null) return;
            boolean stale = disk != null && !disk.isEmpty() && guilds.isEmpty();
            if (stale) {
                guilds.clear();
                guilds.addAll(disk);
                adapter.notifyDataSetChanged();
                updateEmpty(null);
            }
            fetchGuilds(stale || !guilds.isEmpty());
        });
    }

    private void fetchGuilds(final boolean haveStale) {
        final MainActivity host = activity;
        host.apiClient.fetchGuilds(new ApiClient.Callback<List<Guild>>() {
            @Override public void onSuccess(List<Guild> result) {
                loading = false;
                host.guildCache.put("me", result);
                host.diskCache.saveGuilds(result);
                if (destroyed) return;
                guilds.clear();
                guilds.addAll(result);
                adapter.notifyDataSetChanged();
                updateEmpty(null);
            }
            @Override public void onError(String msg) {
                loading = false;
                if (destroyed) return;
                if (!haveStale) updateEmpty("Couldn't load servers.\nTap to retry.");
            }
        });
    }

    private void updateEmpty(String error) {
        if (emptyView == null) return;
        if (error != null) {
            emptyView.setText(error);
            emptyView.setVisibility(View.VISIBLE);
            emptyView.setOnClickListener(v -> {
                emptyView.setText("Loading servers\u2026");
                emptyView.setOnClickListener(null);
                loadGuilds();
            });
        } else if (guilds.isEmpty()) {
            emptyView.setText("You are not in any servers yet.");
            emptyView.setVisibility(View.VISIBLE);
        } else {
            emptyView.setVisibility(View.GONE);
        }
    }

    private void updateBadges() {
        if (activity == null || mentionsBtn == null) return;
        mentionsBtn.setCount(activity.mentions.unreadCount());
        dmBtn.setCount(activity.mentions.dmUnreadTotal());
    }

    @Override public void onMentionsChanged() { updateBadges(); }

    private int dp(int v) { return (int) (v * density); }

    @Override public View getView() { return view; }
    @Override public void onActivityResult(int requestCode, int resultCode, Intent data) {}

    @Override public void onResume() {
        if (activity == null) return;
        activity.mentions.addListener(this);
        updateBadges();
        if (activity.guildCache.get("me") == null || guilds.size() != activity.guildCache.get("me").size()) loadGuilds();
    }

    @Override public void onPause() {
        if (activity != null) activity.mentions.removeListener(this);
    }

    @Override public void onDestroy() {
        destroyed = true;
        if (activity != null) activity.mentions.removeListener(this);
        if (adapter != null) adapter.release();
        activity = null;
        adapter = null;
        sessionMenu = null;
    }
}