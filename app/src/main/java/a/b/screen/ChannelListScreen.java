package a.b.screen;

import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
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
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import a.b.MainActivity;
import a.b.api.ApiClient;
import a.b.model.Channel;
import a.b.model.Guild;
import a.b.model.GuildInfo;
import a.b.util.MentionStore;
import a.b.util.Ui;

public class ChannelListScreen implements Screen, MentionStore.Listener {
    private static final String PREFS_NAME = "channel_state";
    private static final String KEY_COLLAPSED = "collapsed_categories";

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
    private Set<String> collapsedCategories = new HashSet<>();

    public ChannelListScreen(Guild guild, boolean isOwner) {
        this.guild = guild;
        this.guildId = guild.id;
        this.isOwner = isOwner;
    }

    @Override
    public View onCreateView(final MainActivity activity, ViewGroup parent) {
        this.activity = activity;
        this.density = activity.getResources().getDisplayMetrics().density;
        loadCollapsedState();

        LinearLayout root = new LinearLayout(activity);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(Ui.BG_SECONDARY);

        bar = new Ui.AppBar(activity, guild.name, Ui.BG_SECONDARY, activity::goBack);
        bar.title.setTextSize(16);
        bar.bar.addView(buildGuildIcon(), 1);
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

    private void loadCollapsedState() {
        SharedPreferences prefs = activity.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        collapsedCategories = new HashSet<>(prefs.getStringSet(KEY_COLLAPSED, new HashSet<String>()));
    }

    private void saveCollapsedState() {
        if (activity == null) return;
        activity.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit()
            .putStringSet(KEY_COLLAPSED, new HashSet<>(collapsedCategories)).apply();
    }


    private void loadGuildInfo() {
        GuildInfo cached = activity.guildInfoCache.get(guildId);
        if (cached != null) { applyGuildInfo(cached); return; }
        activity.apiClient.fetchGuildInfo(guildId, new ApiClient.Callback<GuildInfo>() {
            @Override public void onSuccess(GuildInfo info) {
                if (activity == null) return;
                activity.guildInfoCache.put(guildId, info);
                applyGuildInfo(info);
            }
            @Override public void onError(String err) {}
        });
    }

    private void loadChannels() {
        List<Channel> cached = activity.channelCache.get(guildId);
        if (cached != null) { setChannels(cached); return; }
        activity.diskCache.loadChannels(guildId, disk -> {
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
        host.apiClient.fetchChannels(guildId, isOwner, new ApiClient.Callback<List<Channel>>() {
            @Override public void onSuccess(List<Channel> result) {
                refreshing = false;
                if (result.isEmpty() && channels != null && !channels.isEmpty()) return;
                host.channelCache.put(guildId, result);
                host.diskCache.saveChannels(guildId, result);
                if (activity == null) return;
                setChannels(result);
            }
            @Override public void onError(String msg) {
                refreshing = false;
                if (activity == null || haveStale) return;
                if (!retried) {
                    retried = true;
                    host.handler.postDelayed(() -> {
                        if (activity != null && channels == null) fetchChannels(false);
                    }, 3000);
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
        for (Channel c : list) if (c.name != null) activity.channelNameCache.put(c.id, c.name);
        render();
    }

    private void applyGuildInfo(GuildInfo info) {
        if (bar == null || info == null) return;
        if (info.memberCount == 0 && info.onlineCount == 0) { bar.setSubtitle(null); return; }
        bar.setSubtitle(formatNumber(info.memberCount) + " members \u00B7 " + formatNumber(info.onlineCount) + " online");
    }

    private static String formatNumber(int n) {
        if (n < 1000) return String.valueOf(n);
        if (n < 1000000) return String.format(Locale.US, "%.1fK", n / 1000.0);
        return String.format(Locale.US, "%.1fM", n / 1000000.0);
    }


    private View buildGuildIcon() {
        FrameLayout wrap = new FrameLayout(activity);
        boolean hasIcon = Ui.isReal(guild.icon);
        wrap.setBackground(Ui.rounded(hasIcon ? Ui.BG_TERTIARY : Ui.avatarColor(guildId), dp(10)));
        wrap.setOutlineProvider(android.view.ViewOutlineProvider.BACKGROUND);
        wrap.setClipToOutline(true);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(dp(32), dp(32));
        lp.setMarginEnd(dp(10));
        wrap.setLayoutParams(lp);

        String name = guild.name == null ? "" : guild.name.trim();
        TextView initial = Ui.text(activity, name.isEmpty() ? "?" : name.substring(0, name.offsetByCodePoints(0, 1)).toUpperCase(Locale.US), Color.WHITE, 14);
        initial.setTypeface(null, Typeface.BOLD);
        initial.setGravity(Gravity.CENTER);
        wrap.addView(initial, new FrameLayout.LayoutParams(-1, -1));
        if (hasIcon) {
            ImageView icon = new ImageView(activity);
            icon.setScaleType(ImageView.ScaleType.CENTER_CROP);
            activity.imageLoader.load("https://cdn.discordapp.com/icons/" + guild.id + "/" + guild.icon + ".png?size=64", icon);
            wrap.addView(icon, new FrameLayout.LayoutParams(-1, -1));
        }
        return wrap;
    }


    private void render() {
        if (listContainer == null || activity == null) return;
        listContainer.removeAllViews();
        if (error != null) {
            TextView t = Ui.emptyState(activity, "Couldn't load channels.\nTap to retry.");
            t.setOnClickListener(v -> { error = null; retried = false; render(); loadChannels(); });
            listContainer.addView(t);
            return;
        }
        if (channels == null) { listContainer.addView(Ui.emptyState(activity, "Loading\u2026")); return; }
        if (channels.isEmpty()) { listContainer.addView(Ui.emptyState(activity, "No channels you can see.")); return; }
        renderChannelTree(channels);
    }

    private void renderChannelTree(List<Channel> all) {
        List<Channel> categories = new ArrayList<>();
        List<Channel> uncategorized = new ArrayList<>();
        Map<String, List<Channel>> children = new HashMap<>();

        for (Channel ch : all) {
            if (ch.type == 4) {
                categories.add(ch);
                if (!children.containsKey(ch.id)) children.put(ch.id, new ArrayList<Channel>());
            }
        }
        for (Channel ch : all) {
            if (ch.type == 4) continue;
            String parent = ch.parent_id;
            boolean hasParent = parent != null && parent.length() > 0 && !"null".equals(parent) && children.containsKey(parent);
            if (!hasParent) uncategorized.add(ch);
            else children.get(parent).add(ch);
        }

        Collections.sort(uncategorized, CHANNEL_COMPARATOR);
        for (Channel ch : uncategorized) listContainer.addView(createChannelRow(ch));

        Collections.sort(categories, POSITION_COMPARATOR);
        for (Channel cat : categories) {
            List<Channel> kids = children.get(cat.id);
            if (kids == null || kids.isEmpty()) continue;
            Collections.sort(kids, CHANNEL_COMPARATOR);
            listContainer.addView(createCategory(cat, kids));
        }
    }

    private static final Comparator<Channel> POSITION_COMPARATOR = (a, b) -> Integer.compare(a.position, b.position);
    private static final Comparator<Channel> CHANNEL_COMPARATOR = (a, b) -> {
        int ag = isVoiceLike(a.type) ? 1 : 0, bg = isVoiceLike(b.type) ? 1 : 0;
        if (ag != bg) return ag - bg;
        return Integer.compare(a.position, b.position);
    };

    private static boolean isVoiceLike(int type) { return type == 2 || type == 13; }
    private static boolean isOpenable(int type) { return type == 0 || type == 2 || type == 5 || type == 13; }

    private View createCategory(final Channel category, List<Channel> kids) {
        LinearLayout layout = new LinearLayout(activity);
        layout.setOrientation(LinearLayout.VERTICAL);

        LinearLayout header = new LinearLayout(activity);
        header.setGravity(Gravity.CENTER_VERTICAL);
        header.setPadding(dp(12), dp(16), dp(16), dp(4));
        header.setClickable(true);

        final ImageView arrow = Ui.iconView(activity, Ui.I_CHEVRON_DOWN, Ui.MUTED, 16);
        header.addView(arrow);
        TextView title = Ui.sectionLabel(activity, category.name);
        title.setPadding(dp(2), 0, 0, 0);
        title.setSingleLine(true);
        title.setEllipsize(TextUtils.TruncateAt.END);
        header.addView(title);

        final LinearLayout box = new LinearLayout(activity);
        box.setOrientation(LinearLayout.VERTICAL);
        for (Channel ch : kids) box.addView(createChannelRow(ch));

        final boolean collapsed = collapsedCategories.contains(category.id);
        box.setVisibility(collapsed ? View.GONE : View.VISIBLE);
        arrow.setRotation(collapsed ? -90f : 0f);

        header.setOnClickListener(v -> {
            boolean show = box.getVisibility() != View.VISIBLE;
            box.setVisibility(show ? View.VISIBLE : View.GONE);
            arrow.setRotation(show ? 0f : -90f);
            if (show) collapsedCategories.remove(category.id); else collapsedCategories.add(category.id);
            saveCollapsedState();
        });

        layout.addView(header);
        layout.addView(box);
        return layout;
    }

    private View createChannelRow(final Channel ch) {
        int unread = activity.mentions.unreadForChannel(ch.id);
        LinearLayout row = new LinearLayout(activity);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(dp(16), 0, dp(16), 0);
        row.setLayoutParams(new LinearLayout.LayoutParams(-1, dp(40)));

        TextView prefix = Ui.text(activity, channelPrefix(ch.type), Ui.MUTED, 18);
        prefix.setGravity(Gravity.CENTER);
        row.addView(prefix, new LinearLayout.LayoutParams(dp(24), -2));

        TextView name = Ui.text(activity, ch.name, unread > 0 ? Ui.TEXT_HEADER : Ui.MUTED, 16);
        name.setTypeface(null, unread > 0 ? Typeface.BOLD : Typeface.NORMAL);
        name.setSingleLine(true);
        name.setEllipsize(TextUtils.TruncateAt.END);
        LinearLayout.LayoutParams nlp = new LinearLayout.LayoutParams(0, -2, 1f);
        nlp.setMarginStart(dp(8));
        row.addView(name, nlp);

        if (unread > 0) {
            TextView badge = Ui.badge(activity);
            Ui.setBadge(badge, unread);
            row.addView(badge);
        }

        if (isOpenable(ch.type)) {
            row.setClickable(true);
            row.setBackground(Ui.ripple(null, 0));
            row.setOnClickListener(v -> {
                if (activity != null) activity.navigate(new MessageListScreen(guildId, ch.id));
            });
        } else {
            row.setAlpha(0.5f);
        }
        return row;
    }

    private static String channelPrefix(int type) {
        switch (type) {
            case 0:  return "#";
            case 2:  return "\uD83D\uDD0A";
            case 5:  return "\uD83D\uDCE2";
            case 10:
            case 11:
            case 12: return "\u21B3";
            case 13: return "\uD83C\uDF99";
            case 15: return "\uD83D\uDDC4";
            default: return "#";
        }
    }

    @Override public void onMentionsChanged() { render(); }

    private int dp(int v) { return (int) (v * density); }

    @Override public View getView() { return view; }
    @Override public void onActivityResult(int requestCode, int resultCode, Intent data) {}
    @Override public void onResume() {
        if (activity == null) return;
        activity.mentions.addListener(this);
        List<Channel> cached = activity.channelCache.get(guildId);
        if (cached == null && channels != null) fetchChannels(true);
        render();
    }
    @Override public void onPause() { if (activity != null) activity.mentions.removeListener(this); }
    @Override public void onDestroy() {
        if (activity != null) activity.mentions.removeListener(this);
        activity = null;
        listContainer = null;
        bar = null;
    }
}
