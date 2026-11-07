package a.b.screen;

import android.content.Intent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import java.util.List;
import org.json.JSONObject;
import a.b.MainActivity;
import a.b.api.ApiClient;
import a.b.model.MentionItem;
import a.b.util.MentionStore;
import a.b.util.Ui;

/** Inbox of recent @mentions / @everyone pings. */
public class NotificationsScreen implements Screen, MentionStore.Listener {
    MainActivity activity;
    View view;
    LinearLayout list;
    private boolean destroyed, loading;
    private String error;
    private MentionChannelFetcher channelFetcher;
    private MentionRowView rowView;

    private final Runnable renderRunnable = this::render;

    @Override
    public View onCreateView(final MainActivity activity, ViewGroup parent) {
        this.activity = activity;
        LinearLayout root = new LinearLayout(activity);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(Ui.BG_SECONDARY);

        Ui.AppBar bar = new Ui.AppBar(activity, "Mentions", Ui.BG_SECONDARY, activity::goBack);
        bar.addAction(Ui.I_DONE_ALL, v -> {
            activity.mentions.markAllRead();
            activity.toast("Marked all as read");
        });
        root.addView(bar.view, new LinearLayout.LayoutParams(-1, -2));

        ScrollView scroll = new ScrollView(activity);
        scroll.setFillViewport(true);
        list = new LinearLayout(activity);
        list.setOrientation(LinearLayout.VERTICAL);
        scroll.addView(list, new ViewGroup.LayoutParams(-1, -2));
        root.addView(scroll, new LinearLayout.LayoutParams(-1, 0, 1f));

        channelFetcher = new MentionChannelFetcher(activity, id -> { if (!destroyed) render(); });
        rowView = new MentionRowView(activity, channelFetcher, this::open);

        render();
        fetch();
        return view = root;
    }

    private void fetch() {
        if (activity == null || destroyed || loading) return;
        if (activity.apiClient.isBotAccount()) { render(); return; }
        loading = true;
        error = null;
        render();
        activity.apiClient.fetchMentions(new ApiClient.Callback<List<MentionItem>>() {
            @Override public void onSuccess(List<MentionItem> result) {
                loading = false;
                if (destroyed || activity == null) return;
                if (result != null) activity.mentions.mergeRemote(result);
                render();
            }
            @Override public void onError(String msg) {
                loading = false;
                if (destroyed) return;
                error = msg;
                render();
            }
        });
    }

    private void render() {
        if (destroyed || list == null || activity == null || rowView == null) return;
        list.removeAllViews();
        List<MentionItem> items = activity.mentions.items();
        if (items.isEmpty()) {
            String msg;
            boolean retry = false;
            if (loading) {
                msg = "Loading\u2026";
            } else if (error != null) {
                msg = "Couldn't load mentions.\nTap to retry.";
                retry = true;
            } else if (activity.apiClient.isBotAccount()) {
                msg = "Mentions are only available for user accounts.\nLive mentions will still show up here.";
            } else {
                msg = "No recent mentions.\nYou'll see @mentions and @everyone pings here.";
            }
            TextView empty = Ui.emptyState(activity, msg);
            if (retry) {
                empty.setOnClickListener(v -> {
                    if (destroyed || activity == null) return;
                    error = null;
                    render();
                    fetch();
                });
            }
            list.addView(empty);
            return;
        }
        for (MentionItem m : items) {
            if (m == null) continue;
            list.addView(rowView.build(m));
        }
    }

    private void open(final MentionItem m) {
        if (activity == null || m == null) return;
        activity.mentions.markRead(m);
        if (m.guildResolved) {
            activity.navigate(new MessageListScreen(m.guildId, m.channelId));
            return;
        }
        activity.apiClient.fetchChannelMeta(m.channelId, new ApiClient.Callback<JSONObject>() {
            @Override public void onSuccess(JSONObject meta) {
                if (activity == null) return;
                m.guildId = meta.isNull("guild_id") ? null : meta.optString("guild_id", null);
                m.guildResolved = true;
                activity.navigate(new MessageListScreen(m.guildId, m.channelId));
            }
            @Override public void onError(String msg) {
                if (activity == null) return;
                activity.toast("Couldn't open channel: " + msg);
            }
        });
    }

    @Override public void onMentionsChanged() {
        if (activity == null || destroyed) return;
        activity.handler.removeCallbacks(renderRunnable);
        activity.handler.postDelayed(renderRunnable, 120);
    }

    @Override public View getView() { return view; }
    @Override public void onActivityResult(int requestCode, int resultCode, Intent data) {}
    @Override public void onResume() { if (activity != null) activity.mentions.addListener(this); render(); }
    @Override public void onPause() { if (activity != null) activity.mentions.removeListener(this); }
    @Override public void onDestroy() {
        destroyed = true;
        if (activity != null) {
            activity.mentions.removeListener(this);
            activity.handler.removeCallbacks(renderRunnable);
        }
        activity = null;
        list = null;
        view = null;
        channelFetcher = null;
        rowView = null;
    }
}