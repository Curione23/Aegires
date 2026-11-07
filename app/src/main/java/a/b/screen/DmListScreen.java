package a.b.screen;

import android.content.Intent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import a.b.MainActivity;
import a.b.api.ApiClient;
import a.b.model.DmChannel;
import a.b.util.MentionStore;
import a.b.util.Ui;

public class DmListScreen implements Screen, MentionStore.Listener {
    MainActivity activity;
    View view;
    LinearLayout listContainer;
    boolean destroyed;
    private List<DmChannel> dms;
    private String error;
    private DmRowView rowView;

    /** Debounced re-render for mention-store pings; canceled in onDestroy. */
    private final Runnable renderRunnable = this::render;

    @Override
    public View onCreateView(final MainActivity host, ViewGroup parent) {
        this.activity = host;
        this.rowView = new DmRowView(host);

        LinearLayout root = new LinearLayout(host);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(Ui.BG_SECONDARY);
        Ui.AppBar bar = new Ui.AppBar(host, "Direct Messages", Ui.BG_SECONDARY, host::goBack);
        root.addView(bar.view, new LinearLayout.LayoutParams(-1, -2));

        ScrollView scroll = new ScrollView(host);
        scroll.setFillViewport(true);
        listContainer = new LinearLayout(host);
        listContainer.setOrientation(LinearLayout.VERTICAL);
        listContainer.setPadding(0, dp(8), 0, dp(16));
        scroll.addView(listContainer, new ViewGroup.LayoutParams(-1, -2));
        root.addView(scroll, new LinearLayout.LayoutParams(-1, 0, 1f));

        fetch();
        return view = root;
    }

    private void fetch() {
        if (activity == null || destroyed) return;
        error = null;
        render();
        activity.apiClient.fetchDms(new ApiClient.Callback<List<DmChannel>>() {
            @Override public void onSuccess(List<DmChannel> result) {
                if (destroyed || activity == null) return;
                dms = new ArrayList<>(result);
                Collections.sort(dms, (a, b) -> {
                    String x = a.lastMessageId == null ? "0" : a.lastMessageId;
                    String y = b.lastMessageId == null ? "0" : b.lastMessageId;
                    return MentionStore.compareIds(y, x);
                });
                render();
            }
            @Override public void onError(String msg) {
                if (destroyed) return;
                error = msg;
                render();
            }
        });
    }

    private void render() {
        if (destroyed || listContainer == null || activity == null) return;
        listContainer.removeAllViews();
        if (error != null) {
            android.widget.TextView t = Ui.emptyState(activity, "Failed to load DMs.\nTap to retry.");
            t.setOnClickListener(v -> {
                if (destroyed || activity == null) return;
                fetch();
            });
            listContainer.addView(t);
        } else if (dms == null) {
            listContainer.addView(Ui.emptyState(activity, "Loading\u2026"));
        } else if (dms.isEmpty()) {
            listContainer.addView(Ui.emptyState(activity, "No direct messages yet."));
        } else {
            String self = activity.selfId();
            for (DmChannel dm : dms) listContainer.addView(rowView.build(dm, self));
        }
    }

    @Override public void onMentionsChanged() {
        if (activity == null || destroyed) return;
        activity.handler.removeCallbacks(renderRunnable);
        activity.handler.postDelayed(renderRunnable, 120);
    }

    private int dp(int v) { return Ui.dp(activity, v); }

    @Override public View getView() { return view; }
    @Override public void onActivityResult(int requestCode, int resultCode, Intent data) {}
    @Override public void onResume() { if (activity != null) { activity.mentions.addListener(this); render(); } }
    @Override public void onPause() { if (activity != null) activity.mentions.removeListener(this); }
    @Override public void onDestroy() {
        destroyed = true;
        if (activity != null) {
            activity.mentions.removeListener(this);
            activity.handler.removeCallbacks(renderRunnable);
        }
        activity = null;
        view = null;
        listContainer = null;
        rowView = null;
    }
}