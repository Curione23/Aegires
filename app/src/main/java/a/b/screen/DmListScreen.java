package a.b.screen;

import android.content.Intent;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.text.TextUtils;
import android.view.*;
import android.widget.*;
import java.util.List;
import java.util.Locale;
import a.b.MainActivity;
import a.b.model.DmChannel;
import a.b.BuildConfig;

public class DmListScreen implements Screen {
    private static final int BG = 0xFF000000;
    private static final int CARD_BG = 0xFF1E1F22;
    private static final int TEXT_PRIMARY = 0xFFFFFFFF;
    private static final int TEXT_MUTED = 0xFF949BA4;
    private static final int CARD_HEIGHT = 76;
    private static final int CARD_RADIUS = 12;
    private static final int AVATAR_SIZE = 48;
    private static final int[] FALLBACK_COLORS = {
        0xFF5865F2, 0xFF3BA55D, 0xFFFAA81A, 0xFFED4245,
        0xFFEB459E, 0xFF9B59B6, 0xFF1ABC9C, 0xFFE67E22,
    };
    private static final String AVATAR_URL = "https://cdn.discordapp.com/avatars/";

    MainActivity activity;
    View view;
    LinearLayout listContainer;
    float density;
    boolean destroyed;

    @Override
    public View onCreateView(final MainActivity host, ViewGroup parent) {
        this.activity = host;
        this.density = host.getResources().getDisplayMetrics().density;
        
        ScrollView scroll = new ScrollView(host);
        scroll.setFillViewport(true);
        scroll.setBackgroundColor(BG);
        
        listContainer = new LinearLayout(host);
        listContainer.setOrientation(LinearLayout.VERTICAL);
        listContainer.setBackgroundColor(BG);
        listContainer.setPadding(dp(16), dp(24), dp(16), dp(24));
        scroll.addView(listContainer);
        
        listContainer.addView(buildHeader());
        final View loader = buildLoader();
        listContainer.addView(loader);

        host.apiClient.fetchDms(new a.b.api.ApiClient.Callback<List<DmChannel>>() {
            @Override public void onSuccess(List<DmChannel> dms) {
                if (destroyed) return;
                removeLoader(loader);
                if (dms == null || dms.isEmpty()) {
                    listContainer.addView(centeredMessage("No direct messages yet."));
                    return;
                }
                String self = host != null ? host.selfUserId : null;
                for (DmChannel dm : dms) listContainer.addView(buildDmCard(dm, self));
            }

            @Override public void onError(String msg) {
                if (destroyed) return;
                removeLoader(loader);
                listContainer.addView(centeredMessage("Failed to load DMs.\n" + msg));
            }
        });
        return view = scroll;
    }

    private View buildHeader() {
        TextView tv = new TextView(activity);
        tv.setText("DIRECT MESSAGES");
        tv.setTextColor(TEXT_MUTED);
        tv.setTextSize(12);
        tv.setTypeface(null, Typeface.BOLD);
        tv.setLetterSpacing(0.08f);
        tv.setPadding(dp(4), 0, 0, dp(16));
        return tv;
    }

    private View buildLoader() {
        ProgressBar p = new ProgressBar(activity);
        p.setIndeterminate(true);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-2, -2);
        lp.gravity = Gravity.CENTER_HORIZONTAL;
        lp.topMargin = dp(24);
        p.setLayoutParams(lp);
        return p;
    }

    private View centeredMessage(String text) {
        TextView tv = new TextView(activity);
        tv.setText(text);
        tv.setTextColor(TEXT_MUTED);
        tv.setTextSize(14);
        tv.setGravity(Gravity.CENTER);
        tv.setPadding(dp(24), dp(40), dp(24), dp(40));
        tv.setLayoutParams(new LinearLayout.LayoutParams(-1, -2));
        return tv;
    }

    private static void removeLoader(View loader) {
        ViewParent p = loader.getParent();
        if (p instanceof ViewGroup) ((ViewGroup) p).removeView(loader);
    }

    private View buildDmCard(final DmChannel dm, final String selfUserId) {
        LinearLayout card = new LinearLayout(activity);
        card.setOrientation(LinearLayout.HORIZONTAL);
        card.setGravity(Gravity.CENTER_VERTICAL);
        card.setPadding(dp(14), 0, dp(14), 0);
        
        LinearLayout.LayoutParams cardLp = new LinearLayout.LayoutParams(-1, dp(CARD_HEIGHT));
        cardLp.setMargins(0, 0, 0, dp(8));
        card.setLayoutParams(cardLp);
        
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(CARD_BG);
        bg.setCornerRadius(dp(CARD_RADIUS));
        card.setBackground(bg);
        card.setClipToOutline(true);
        card.setClickable(true);
        card.addView(buildAvatar(dm, selfUserId));

        LinearLayout col = new LinearLayout(activity);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setLayoutParams(new LinearLayout.LayoutParams(0, -2, 1f));

        TextView name = new TextView(activity);
        name.setText(dm.displayName(selfUserId));
        name.setTextColor(TEXT_PRIMARY);
        name.setTextSize(16);
        name.setTypeface(null, Typeface.BOLD);
        name.setSingleLine(true);
        name.setEllipsize(TextUtils.TruncateAt.END);
        col.addView(name);

        String sub = dm.subtitle(selfUserId);
        if (sub != null && !sub.isEmpty()) {
            TextView subtitle = new TextView(activity);
            subtitle.setText(sub);
            subtitle.setTextColor(TEXT_MUTED);
            subtitle.setTextSize(12);
            subtitle.setSingleLine(true);
            subtitle.setEllipsize(TextUtils.TruncateAt.END);
            subtitle.setPadding(0, dp(2), 0, 0);
            col.addView(subtitle);
        }
        card.addView(col);
        
        card.setOnClickListener(v -> {
            if (activity != null) activity.navigate(new MessageListScreen(null, dm.id));
        });
        return card;
    }

    private View buildAvatar(DmChannel dm, String selfUserId) {
        DmChannel.Recipient r = dm.avatarSource(selfUserId);
        int size = dp(AVATAR_SIZE);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(size, size);
        lp.setMargins(0, 0, dp(14), 0);
        
        GradientDrawable bg = new GradientDrawable();
        bg.setShape(GradientDrawable.OVAL);
        bg.setColor(r != null ? fallbackColor(r.id) : FALLBACK_COLORS[0]);

        if (r != null && r.hasAvatar() && !BuildConfig.SKIP_IMAGES) {
            ImageView iv = new ImageView(activity);
            iv.setLayoutParams(lp);
            iv.setScaleType(ImageView.ScaleType.CENTER_CROP);
            iv.setBackground(bg);
            iv.setClipToOutline(true);
            activity.imageLoader.load(AVATAR_URL + r.id + "/" + r.avatar + ".png?size=128", iv);
            return iv;
        }

        FrameLayout wrap = new FrameLayout(activity);
        wrap.setLayoutParams(lp);
        wrap.setBackground(bg);
        wrap.setClipToOutline(true);

        TextView initial = new TextView(activity);
        initial.setText(initialOf(r));
        initial.setTextColor(TEXT_PRIMARY);
        initial.setTextSize(18);
        initial.setTypeface(null, Typeface.BOLD);
        initial.setGravity(Gravity.CENTER);
        initial.setLayoutParams(new FrameLayout.LayoutParams(-1, -1));
        wrap.addView(initial);
        return wrap;
    }

    private static String initialOf(DmChannel.Recipient r) {
        if (r == null) return "?";
        String s = r.display();
        return (s == null || s.isEmpty()) ? "?" : s.substring(0, 1).toUpperCase(Locale.US);
    }

    private static int fallbackColor(String userId) {
        if (userId == null) return FALLBACK_COLORS[0];
        try {
            long id = Long.parseLong(userId);
            return FALLBACK_COLORS[(int) (Math.abs(id) % FALLBACK_COLORS.length)];
        } catch (Exception e) {
            return FALLBACK_COLORS[0];
        }
    }

    private int dp(int v) { return (int) (v * density); }

    @Override public View getView() { return view; }
    @Override public void onActivityResult(int requestCode, int resultCode, Intent data) {}
    @Override public void onResume() {}
    @Override public void onPause() {}
    
    @Override
    public void onDestroy() {
        destroyed = true;
        activity = null;
        listContainer = null;
    }
}