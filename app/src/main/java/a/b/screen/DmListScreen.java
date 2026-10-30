package a.b.screen;

import android.content.Intent;
import android.graphics.Color;
import android.graphics.Typeface;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import a.b.MainActivity;
import a.b.api.ApiClient;
import a.b.model.DmChannel;
import a.b.util.MentionStore;
import a.b.util.Ui;

public class DmListScreen implements Screen, MentionStore.Listener {
    private static final int ROW_HEIGHT = 64;
    private static final int AVATAR_SIZE = 44;
    private static final String AVATAR_URL = "https://cdn.discordapp.com/avatars/";

    MainActivity activity;
    View view;
    LinearLayout listContainer;
    boolean destroyed;
    private List<DmChannel> dms;
    private String error;

    @Override
    public View onCreateView(final MainActivity host, ViewGroup parent) {
        this.activity = host;

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

        render();
        host.apiClient.fetchDms(new ApiClient.Callback<List<DmChannel>>() {
            @Override public void onSuccess(List<DmChannel> result) {
                if (destroyed) return;
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
        return view = root;
    }

    private void render() {
        if (listContainer == null || activity == null) return;
        listContainer.removeAllViews();
        if (error != null) {
            listContainer.addView(Ui.emptyState(activity, "Failed to load DMs.\n" + error));
        } else if (dms == null) {
            listContainer.addView(Ui.emptyState(activity, "Loading\u2026"));
        } else if (dms.isEmpty()) {
            listContainer.addView(Ui.emptyState(activity, "No direct messages yet."));
        } else {
            String self = activity.selfId();
            for (DmChannel dm : dms) listContainer.addView(buildRow(dm, self));
        }
    }

    private View buildRow(final DmChannel dm, String self) {
        LinearLayout row = new LinearLayout(activity);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(dp(16), 0, dp(16), 0);
        row.setLayoutParams(new LinearLayout.LayoutParams(-1, dp(ROW_HEIGHT)));
        row.setClickable(true);
        row.setBackground(Ui.ripple(null, 0));
        row.addView(buildAvatar(dm, self));

        LinearLayout col = new LinearLayout(activity);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setLayoutParams(new LinearLayout.LayoutParams(0, -2, 1f));

        int unread = activity.mentions.unreadForChannel(dm.id);
        TextView name = Ui.text(activity, dm.displayName(self), unread > 0 ? Ui.TEXT_HEADER : Ui.TEXT, 16);
        name.setTypeface(null, unread > 0 ? Typeface.BOLD : Typeface.NORMAL);
        name.setSingleLine(true);
        name.setEllipsize(TextUtils.TruncateAt.END);
        col.addView(name);

        String sub = dm.subtitle(self);
        if (sub != null && !sub.isEmpty()) {
            TextView subtitle = Ui.text(activity, sub, Ui.MUTED, 12);
            subtitle.setSingleLine(true);
            subtitle.setEllipsize(TextUtils.TruncateAt.END);
            col.addView(subtitle);
        }
        row.addView(col);

        if (unread > 0) {
            TextView badge = Ui.badge(activity);
            Ui.setBadge(badge, unread);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-2, -2);
            lp.setMarginStart(dp(8));
            row.addView(badge, lp);
        }

        row.setOnClickListener(v -> {
            if (activity != null) activity.navigate(new MessageListScreen(null, dm.id));
        });
        return row;
    }

    private View buildAvatar(DmChannel dm, String self) {
        DmChannel.Recipient r = dm.avatarSource(self);
        int size = dp(AVATAR_SIZE);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(size, size);
        lp.setMarginEnd(dp(12));

        FrameLayout wrap = new FrameLayout(activity);
        wrap.setLayoutParams(lp);
        wrap.setBackground(Ui.oval(r != null ? Ui.avatarColor(r.id) : Ui.AVATAR_COLORS[0]));
        wrap.setClipToOutline(true);

        TextView initial = Ui.text(activity, initialOf(r), Color.WHITE, 18);
        initial.setTypeface(null, Typeface.BOLD);
        initial.setGravity(Gravity.CENTER);
        wrap.addView(initial, new FrameLayout.LayoutParams(-1, -1));

        if (r != null && r.hasAvatar()) {
            ImageView iv = new ImageView(activity);
            iv.setScaleType(ImageView.ScaleType.CENTER_CROP);
            activity.imageLoader.load(AVATAR_URL + r.id + "/" + r.avatar + ".png?size=128", iv);
            wrap.addView(iv, new FrameLayout.LayoutParams(-1, -1));
        }
        return wrap;
    }

    private static String initialOf(DmChannel.Recipient r) {
        if (r == null) return "?";
        String s = r.display();
        return (s == null || s.isEmpty()) ? "?" : s.substring(0, s.offsetByCodePoints(0, 1)).toUpperCase(Locale.US);
    }

    @Override public void onMentionsChanged() { render(); }

    private int dp(int v) { return Ui.dp(activity, v); }

    @Override public View getView() { return view; }
    @Override public void onActivityResult(int requestCode, int resultCode, Intent data) {}
    @Override public void onResume() { if (activity != null) { activity.mentions.addListener(this); render(); } }
    @Override public void onPause() { if (activity != null) activity.mentions.removeListener(this); }
    @Override public void onDestroy() {
        destroyed = true;
        if (activity != null) activity.mentions.removeListener(this);
        activity = null;
        listContainer = null;
    }
}
