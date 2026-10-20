package a.b.screen;

import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.text.TextUtils;
import android.util.TypedValue;
import android.view.*;
import android.widget.*;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import android.content.Intent;
import a.b.MainActivity;
import a.b.model.Channel;
import a.b.model.Guild;
import a.b.model.GuildInfo;
import a.b.BuildConfig;

public class ChannelListScreen implements Screen {
    private static final int BG = 0xFF000000;
    private static final int BANNER_PLACEHOLDER = 0xFF101010;
    private static final int BANNER_SCRIM = 0x88000000;
    private static final int TEXT_PRIMARY = 0xFFFFFFFF;
    private static final int TEXT_MUTED = 0xFF949BA4;
    private static final int CATEGORY_TEXT = 0xFF949BA4;

    private static final String PREFS_NAME = "channel_state";
    private static final String KEY_COLLAPSED = "collapsed_categories";

    MainActivity activity;
    final Guild guild;
    final String guildId;
    final boolean isOwner;
    View view;
    LinearLayout listContainer;
    TextView countsView;
    float density;

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

        ScrollView scroll = new ScrollView(activity);
        scroll.setFillViewport(true);
        scroll.setBackgroundColor(BG);

        listContainer = new LinearLayout(activity);
        listContainer.setOrientation(LinearLayout.VERTICAL);
        listContainer.setBackgroundColor(BG);
        scroll.addView(listContainer);

        listContainer.addView(buildGuildHeader());

        final ProgressBar loader = new ProgressBar(activity);
        loader.setIndeterminate(true);
        LinearLayout.LayoutParams loaderLp = new LinearLayout.LayoutParams(-2, -2);
        loaderLp.gravity = Gravity.CENTER_HORIZONTAL;
        loaderLp.topMargin = dp(20);
        listContainer.addView(loader, loaderLp);

        loadGuildInfo(loader);
        loadChannels(loader);

        return view = scroll;
    }

    // -------------------------------------------------------- Persistence Logic

    private void loadCollapsedState() {
        SharedPreferences prefs = activity.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        Set<String> saved = prefs.getStringSet(KEY_COLLAPSED, new HashSet<>());
        collapsedCategories = new HashSet<>(saved);
    }

    private void saveCollapsedState() {
        if (activity == null) return;
        SharedPreferences prefs = activity.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        prefs.edit().putStringSet(KEY_COLLAPSED, new HashSet<>(collapsedCategories)).apply();
    }

    // ------------------------------------------------------------------ Loading

    private void loadGuildInfo(final View loader) {
        GuildInfo cachedInfo = activity.guildInfoCache.get(guildId);
        if (cachedInfo != null) {
            applyGuildInfo(cachedInfo);
            return;
        }
        activity.apiClient.fetchGuildInfo(guildId, new a.b.api.ApiClient.Callback<GuildInfo>() {
            @Override public void onSuccess(GuildInfo info) {
                if (activity == null) return; // GUARD
                activity.guildInfoCache.put(guildId, info);
                applyGuildInfo(info);
            }
            @Override public void onError(String err) {
                if (activity == null) return;
                if (countsView != null) countsView.setText("");
            }
        });
    }

    private void loadChannels(final View loader) {
        List<Channel> cached = activity.channelCache.get(guildId);
        if (cached != null) {
            listContainer.removeView(loader);
            renderChannelTree(cached);
            return;
        }
        
        activity.apiClient.fetchChannels(guildId, isOwner, new a.b.api.ApiClient.Callback<List<Channel>>() {
            @Override public void onSuccess(List<Channel> channels) {
                if (activity == null || listContainer == null) return; // GUARD
                activity.channelCache.put(guildId, channels);
                listContainer.removeView(loader);
                renderChannelTree(channels);
            }
            @Override public void onError(String msg) {
                if (activity == null) return; // GUARD
                if (listContainer != null) listContainer.removeView(loader);
                activity.toast("Failed to load channels: " + msg);
            }
        });
    }

    // ------------------------------------------------------------------ Header

    private View buildGuildHeader() {
        LinearLayout header = new LinearLayout(activity);
        header.setOrientation(LinearLayout.VERTICAL);
        header.addView(buildBanner());
        header.addView(buildProfileRow());
        return header;
    }

    private View buildBanner() {
        FrameLayout bannerWrap = new FrameLayout(activity);
        bannerWrap.setLayoutParams(new LinearLayout.LayoutParams(-1, dp(120)));

        ImageView banner = new ImageView(activity);
        banner.setScaleType(ImageView.ScaleType.CENTER_CROP);
        banner.setLayoutParams(new FrameLayout.LayoutParams(-1, -1));
        
        if (!BuildConfig.SKIP_IMAGES && isReal(guild.banner)) {
            activity.imageLoader.load(
                    "https://cdn.discordapp.com/banners/" + guild.id + "/" + guild.banner + ".png?size=512",
                    banner);
        } else {
            banner.setBackgroundColor(BANNER_PLACEHOLDER);
        }
        bannerWrap.addView(banner);

        View overlay = new View(activity);
        overlay.setBackgroundColor(BANNER_SCRIM);
        overlay.setLayoutParams(new FrameLayout.LayoutParams(-1, -1));
        bannerWrap.addView(overlay);

        return bannerWrap;
    }

    private View buildProfileRow() {
        LinearLayout profileRow = new LinearLayout(activity);
        profileRow.setOrientation(LinearLayout.HORIZONTAL);
        profileRow.setGravity(Gravity.CENTER_VERTICAL);
        profileRow.setPadding(dp(20), dp(14), dp(20), dp(20));

        int iconSize = dp(64);
        LinearLayout.LayoutParams iconLp = new LinearLayout.LayoutParams(iconSize, iconSize);
        iconLp.setMarginEnd(dp(16));

        GradientDrawable iconBg = new GradientDrawable();
        iconBg.setShape(GradientDrawable.OVAL);
        iconBg.setColor(0xFF5865F2);

        if (!BuildConfig.SKIP_IMAGES && isReal(guild.icon)) {
            ImageView icon = new ImageView(activity);
            icon.setLayoutParams(iconLp);
            icon.setScaleType(ImageView.ScaleType.CENTER_CROP);
            icon.setBackground(iconBg);
            icon.setClipToOutline(true);
            activity.imageLoader.load(
                    "https://cdn.discordapp.com/icons/" + guild.id + "/" + guild.icon + ".png?size=256",
                    icon);
            profileRow.addView(icon);
        } else {
            FrameLayout wrap = new FrameLayout(activity);
            wrap.setLayoutParams(iconLp);
            wrap.setBackground(iconBg);
            wrap.setClipToOutline(true);
            
            TextView initial = new TextView(activity);
            initial.setText(guild.name.substring(0, 1).toUpperCase(Locale.US));
            initial.setTextColor(Color.WHITE);
            initial.setTextSize(28);
            initial.setTypeface(null, Typeface.BOLD);
            initial.setGravity(Gravity.CENTER);
            initial.setLayoutParams(new FrameLayout.LayoutParams(-1, -1));
            wrap.addView(initial);
            profileRow.addView(wrap);
        }

        LinearLayout textCol = new LinearLayout(activity);
        textCol.setOrientation(LinearLayout.VERTICAL);
        textCol.setLayoutParams(new LinearLayout.LayoutParams(0, -2, 1f));

        TextView name = new TextView(activity);
        name.setText(guild.name);
        name.setTextColor(TEXT_PRIMARY);
        name.setTextSize(20);
        name.setTypeface(null, Typeface.BOLD);
        name.setSingleLine(true);
        name.setEllipsize(TextUtils.TruncateAt.END);
        textCol.addView(name);

        countsView = new TextView(activity);
        countsView.setText("Loading...");
        countsView.setTextColor(TEXT_MUTED);
        countsView.setTextSize(13);
        countsView.setPadding(0, dp(2), 0, 0);
        textCol.addView(countsView);

        profileRow.addView(textCol);
        return profileRow;
    }

    private void applyGuildInfo(GuildInfo info) {
        if (countsView == null || info == null) return;
        if (info.memberCount == 0 && info.onlineCount == 0) {
            countsView.setText("");
        } else {
            countsView.setText(formatCounts(info.memberCount, info.onlineCount));
        }
    }

    private String formatCounts(int members, int online) {
        return formatNumber(members) + " members   •   " + formatNumber(online) + " online";
    }

    private static String formatNumber(int n) {
        if (n < 1000) return String.valueOf(n);
        if (n < 1000000) return String.format(Locale.US, "%.1fK", n / 1000.0);
        return String.format(Locale.US, "%.1fM", n / 1000000.0);
    }

    // ------------------------------------------------------------ Channel Tree

    private void renderChannelTree(List<Channel> channels) {
        List<Channel> categories = new ArrayList<>();
        List<Channel> uncategorized = new ArrayList<>();
        Map<String, List<Channel>> categoryChildren = new HashMap<>();

        for (Channel ch : channels) {
            if (ch.type == 4) {
                categories.add(ch);
                if (!categoryChildren.containsKey(ch.id)) {
                    categoryChildren.put(ch.id, new ArrayList<Channel>());
                }
            } else {
                String parent = ch.parent_id;
                boolean hasParent = parent != null && parent.length() > 0 && !"null".equals(parent);
                if (!hasParent) {
                    uncategorized.add(ch);
                } else {
                    if (!categoryChildren.containsKey(parent)) {
                        categoryChildren.put(parent, new ArrayList<Channel>());
                    }
                    categoryChildren.get(parent).add(ch);
                }
            }
        }

        Collections.sort(uncategorized, POSITION_COMPARATOR);
        for (Channel ch : uncategorized) {
            listContainer.addView(createChannelRow(ch));
        }

        Collections.sort(categories, POSITION_COMPARATOR);
        for (Channel cat : categories) {
            List<Channel> children = categoryChildren.get(cat.id);
            if (children == null || children.isEmpty()) continue;
            Collections.sort(children, CHANNEL_COMPARATOR);
            listContainer.addView(createCategoryDropdown(cat, children));
        }
    }

    private static final Comparator<Channel> POSITION_COMPARATOR = (a, b) -> Integer.compare(a.position, b.position);

    private static final Comparator<Channel> CHANNEL_COMPARATOR = (a, b) -> {
        int ag = isVoiceLike(a.type) ? 1 : 0;
        int bg = isVoiceLike(b.type) ? 1 : 0;
        if (ag != bg) return ag - bg;
        return Integer.compare(a.position, b.position);
    };

    private static boolean isVoiceLike(int type) { return type == 2 || type == 13; }
    
    private static boolean isTextLike(int type) {
        return type == 0 || type == 2 || type == 5 || type == 13;
    }

    // -------------------------------------------------------------------- Rows

    private void applySelectableBackground(View v) {
        TypedValue outValue = new TypedValue();
        if (activity.getTheme().resolveAttribute(android.R.attr.selectableItemBackground, outValue, true)) {
            v.setBackgroundResource(outValue.resourceId);
        }
    }

    private View createCategoryDropdown(final Channel category, List<Channel> children) {
        LinearLayout categoryLayout = new LinearLayout(activity);
        categoryLayout.setOrientation(LinearLayout.VERTICAL);

        LinearLayout headerRow = new LinearLayout(activity);
        headerRow.setOrientation(LinearLayout.HORIZONTAL);
        headerRow.setPadding(dp(20), dp(16), dp(20), dp(8));
        headerRow.setGravity(Gravity.CENTER_VERTICAL);
        applySelectableBackground(headerRow);

        final TextView arrow = new TextView(activity);
        arrow.setTextColor(CATEGORY_TEXT);
        arrow.setTextSize(10);
        arrow.setPadding(0, 0, dp(8), 0);

        TextView title = new TextView(activity);
        title.setText(category.name.toUpperCase(Locale.US));
        title.setTextColor(CATEGORY_TEXT);
        title.setTextSize(12);
        title.setTypeface(null, Typeface.BOLD);

        headerRow.addView(arrow);
        headerRow.addView(title);

        final LinearLayout childrenContainer = new LinearLayout(activity);
        childrenContainer.setOrientation(LinearLayout.VERTICAL);
        childrenContainer.setPadding(dp(16), 0, 0, 0); 

        for (Channel ch : children) {
            childrenContainer.addView(createChannelRow(ch));
        }

        final boolean isCollapsed = collapsedCategories.contains(category.id);
        if (isCollapsed) {
            childrenContainer.setVisibility(View.GONE);
            arrow.setText("▶");
        } else {
            childrenContainer.setVisibility(View.VISIBLE);
            arrow.setText("▼");
        }

        headerRow.setOnClickListener(v -> {
            if (childrenContainer.getVisibility() == View.VISIBLE) {
                childrenContainer.setVisibility(View.GONE);
                arrow.setText("▶");
                collapsedCategories.add(category.id);
            } else {
                childrenContainer.setVisibility(View.VISIBLE);
                arrow.setText("▼");
                collapsedCategories.remove(category.id);
            }
            saveCollapsedState();
        });

        categoryLayout.addView(headerRow);
        categoryLayout.addView(childrenContainer);
        return categoryLayout;
    }

    private View createChannelRow(final Channel ch) {
        TextView tv = new TextView(activity);
        tv.setText(channelPrefix(ch.type) + ch.name);
        tv.setTextColor(TEXT_MUTED);
        tv.setTextSize(16);
        tv.setTypeface(null, Typeface.BOLD);
        tv.setPadding(dp(20), dp(12), dp(20), dp(12));
        tv.setGravity(Gravity.CENTER_VERTICAL);
        applySelectableBackground(tv);

        if (isTextLike(ch.type)) {
            tv.setOnClickListener(v -> {
                if (activity != null) {
                    activity.navigate(new MessageListScreen(guildId, ch.id));
                }
            });
        } else {
            tv.setAlpha(0.6f);
        }

        return tv;
    }

    private static String channelPrefix(int type) {
        switch (type) {
            case 0:  return "# ";
            case 2:  return "🔊 ";
            case 5:  return "📢 ";
            case 10:
            case 11: return "🧵 ";
            case 12: return "🔒 ";
            case 13: return "🎙️ ";
            case 14: return "🧭 ";
            case 15: return "🗄️ ";
            case 16: return "🖼️ ";
            default: return "❓ ";
        }
    }

    // ----------------------------------------------------------------- Helpers

    private static boolean isReal(String s) {
        return s != null && !s.isEmpty() && !"null".equals(s);
    }

    private int dp(int v) { return (int) (v * density); }

    @Override public View getView() { return view; }
    @Override public void onActivityResult(int requestCode, int resultCode, Intent data) {}
    @Override public void onResume() {}
    @Override public void onPause() {}
    
    @Override 
    public void onDestroy() { 
        activity = null;
        listContainer = null;
        countsView = null;
    }
}