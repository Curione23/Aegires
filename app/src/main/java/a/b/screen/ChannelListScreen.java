package a.b.screen;

import android.content.Intent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import a.b.MainActivity;
import a.b.api.ApiClient;
import a.b.model.Channel;
import a.b.model.Guild;
import a.b.model.GuildInfo;
import a.b.util.MentionStore;
import a.b.util.Ui;

import java.util.List;
import java.util.Locale;

public class ChannelListScreen implements Screen, MentionStore.Listener {
    MainActivity activity;
    final Guild guild;
    final String guildId;
    final boolean isOwner;
    View view;
    Ui.AppBar bar;
    LinearLayout listContainer;
    float density;
    private List<Channel> channels;
    private String error;
    private boolean refreshing, retried;
    private CollapsedCategoryStore collapsedStore;
    private ChannelViewFactory viewFactory;

    private final Runnable retryRunnable =
            () -> {
                if (activity != null && channels == null) fetchChannels(false);
            };

    private final Runnable renderRunnable = this::render;

    public ChannelListScreen(Guild guild, boolean isOwner) {
        this.guild = guild;
        this.guildId = guild.id;
        this.isOwner = isOwner;
    }

    @Override
    public View onCreateView(final MainActivity activity, ViewGroup parent) {
        this.activity = activity;
        this.density = activity.getResources().getDisplayMetrics().density;
        collapsedStore = new CollapsedCategoryStore(activity);
        viewFactory = new ChannelViewFactory(activity, guildId, density, collapsedStore);

        LinearLayout root = new LinearLayout(activity);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(Ui.BG_SECONDARY);

        bar = new Ui.AppBar(activity, guild.name, Ui.BG_SECONDARY, null);
        bar.title.setTextSize(16);
        bar.bar.addView(viewFactory.buildGuildIcon(guild), 1);
        root.addView(bar.view, new LinearLayout.LayoutParams(-1, -2));

        ScrollView scroll = new ScrollView(activity);
        scroll.setFillViewport(true);
        listContainer = new LinearLayout(activity);
        listContainer.setOrientation(LinearLayout.VERTICAL);
        listContainer.setPadding(0, dp(4), 0, dp(16));
        scroll.addView(listContainer, new ViewGroup.LayoutParams(-1, -2));
        root.addView(scroll, new LinearLayout.LayoutParams(-1, 0, 1f));

        render();
        loadGuildInfo();
        loadChannels();
        return view = root;
    }

    private void loadGuildInfo() {
        GuildInfo cached = activity.guildInfoCache.get(guildId);
        if (cached != null) {
            applyGuildInfo(cached);
            return;
        }
        activity.apiClient.fetchGuildInfo(
                guildId,
                new ApiClient.Callback<GuildInfo>() {
                    @Override
                    public void onSuccess(GuildInfo info) {
                        if (activity == null) return;
                        activity.guildInfoCache.put(guildId, info);
                        applyGuildInfo(info);
                    }

                    @Override
                    public void onError(String err) {}
                });
    }

    private void loadChannels() {
        List<Channel> cached = activity.channelCache.get(guildId);
        if (cached != null) {
            setChannels(cached);
            return;
        }
        activity.diskCache.loadChannels(
                guildId,
                disk -> {
                    if (activity == null) return;
                    boolean stale = disk != null && channels == null;
                    if (stale) setChannels(disk);
                    fetchChannels(stale || channels != null);
                });
    }

    private void fetchChannels(final boolean haveStale) {
        if (refreshing || activity == null) return;
        refreshing = true;
        final MainActivity host = activity;
        host.apiClient.fetchChannels(
                guildId,
                isOwner,
                new ApiClient.Callback<List<Channel>>() {
                    @Override
                    public void onSuccess(List<Channel> result) {
                        refreshing = false;
                        if (result.isEmpty() && channels != null && !channels.isEmpty()) return;
                        host.channelCache.put(guildId, result);
                        host.diskCache.saveChannels(guildId, result);
                        if (activity == null) return;
                        setChannels(result);
                    }

                    @Override
                    public void onError(String msg) {
                        refreshing = false;
                        if (activity == null || haveStale) return;
                        if (!retried) {
                            retried = true;
                            host.handler.postDelayed(retryRunnable, 3000);
                            return;
                        }
                        error = msg;
                        render();
                    }
                });
    }

    private void setChannels(List<Channel> list) {
        channels = list;
        error = null;
        if (activity != null) {
            for (Channel c : list) if (c.name != null) activity.channelNameCache.put(c.id, c.name);
        }
        render();
    }

    private void applyGuildInfo(GuildInfo info) {
        if (bar == null || info == null) return;
        if (info.memberCount == 0 && info.onlineCount == 0) {
            bar.setSubtitle(null);
            return;
        }
        bar.setSubtitle(
                formatNumber(info.memberCount)
                        + " members \u00B7 "
                        + formatNumber(info.onlineCount)
                        + " online");
    }

    private static String formatNumber(int n) {
        if (n < 1000) return String.valueOf(n);
        if (n < 1000000) return String.format(Locale.US, "%.1fK", n / 1000.0);
        return String.format(Locale.US, "%.1fM", n / 1000000.0);
    }

    private void render() {
        if (listContainer == null || activity == null) return;
        listContainer.removeAllViews();
        if (error != null) {
            TextView t = Ui.emptyState(activity, "Couldn't load channels.\nTap to retry.");
            t.setOnClickListener(
                    v -> {
                        error = null;
                        retried = false;
                        render();
                        loadChannels();
                    });
            listContainer.addView(t);
            return;
        }
        if (channels == null) {
            listContainer.addView(Ui.emptyState(activity, "Loading\u2026"));
            return;
        }
        if (channels.isEmpty()) {
            listContainer.addView(Ui.emptyState(activity, "No channels you can see."));
            return;
        }
        renderChannelTree(channels);
    }

    private void renderChannelTree(List<Channel> all) {
        ChannelTreeBuilder.Result tree = ChannelTreeBuilder.build(all);
        for (Channel ch : tree.uncategorized)
            listContainer.addView(viewFactory.createChannelRow(ch));
        for (ChannelTreeBuilder.CategoryGroup g : tree.categories) {
            listContainer.addView(viewFactory.createCategory(g.category, g.children));
        }
    }

    @Override
    public void onMentionsChanged() {
        if (activity == null) return;
        activity.handler.removeCallbacks(renderRunnable);
        activity.handler.postDelayed(renderRunnable, 120);
    }

    private int dp(int v) {
        return (int) (v * density);
    }

    @Override
    public View getView() {
        return view;
    }

    @Override
    public void onActivityResult(int requestCode, int resultCode, Intent data) {}

    @Override
    public void onResume() {
        if (activity == null) return;
        activity.mentions.addListener(this);
        List<Channel> cached = activity.channelCache.get(guildId);
        if (cached == null && channels != null) fetchChannels(true);
        render();
    }

    @Override
    public void onPause() {
        if (activity != null) activity.mentions.removeListener(this);
    }

    @Override
    public void onDestroy() {
        if (activity != null) {
            activity.mentions.removeListener(this);
            activity.handler.removeCallbacks(retryRunnable);
            activity.handler.removeCallbacks(renderRunnable);
        }
        activity = null;
        view = null;
        listContainer = null;
        bar = null;
        collapsedStore = null;
        viewFactory = null;
    }
}
