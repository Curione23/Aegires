package a.b.screen;

import android.content.Context;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.util.TypedValue;
import android.view.*;
import android.widget.*;
import android.content.Intent;
import android.app.AlertDialog;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import a.b.MainActivity;
import a.b.model.Guild;
import a.b.BuildConfig;
import a.b.util.Crypto;
import javax.crypto.SecretKey;

public class GuildListScreen implements Screen {
    private static final int BG = 0xFF000000;
    private static final int CARD_BG = 0xFF1E1F22;
    private static final int TEXT_PRIMARY = 0xFFFFFFFF;
    private static final int TEXT_MUTED = 0xFF949BA4;
    private static final int ACCENT = 0xFF5865F2;
    private static final int ROW_HEIGHT_DP = 72;
    private static final int ICON_SIZE_DP = 56;
    private static final int TYPE_HEADER = 0;
    private static final int TYPE_GUILD = 1;

    MainActivity activity;
    View view;
    RecyclerView recyclerView;
    GuildAdapter adapter;
    float density;
    boolean destroyed;
    private List<Guild> guilds = new ArrayList<>();

    @Override
    public View onCreateView(final MainActivity activity, ViewGroup parent) {
        this.activity = activity;
        this.density = activity.getResources().getDisplayMetrics().density;
        
        LinearLayout root = new LinearLayout(activity);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(BG);
        root.setPadding(dp(16), dp(16), dp(16), dp(16));
        
        recyclerView = new RecyclerView(activity);
        recyclerView.setLayoutManager(new LinearLayoutManager(activity));
        recyclerView.setItemAnimator(null);
        adapter = new GuildAdapter();
        recyclerView.setAdapter(adapter);
        root.addView(recyclerView, new LinearLayout.LayoutParams(-1, -1));
        
        loadGuilds();
        return view = root;
    }

    private void loadGuilds() {
        List<Guild> cached = activity.guildCache.get("me");
        if (cached != null) {
            guilds = new ArrayList<>(cached);
            adapter.notifyDataSetChanged();
            return;
        }
        activity.apiClient.fetchGuilds(new a.b.api.ApiClient.Callback<List<Guild>>() {
            @Override public void onSuccess(List<Guild> result) {
                if (destroyed) return;
                activity.guildCache.put("me", result);
                guilds = new ArrayList<>(result);
                adapter.notifyDataSetChanged();
            }
            @Override public void onError(String msg) {
                if (destroyed) return;
                activity.toast("Failed to load servers: " + msg);
            }
        });
    }

    private class GuildAdapter extends RecyclerView.Adapter<RecyclerView.ViewHolder> {
        @Override public int getItemCount() { return guilds.size() + 1; }
        @Override public int getItemViewType(int pos) { return pos == 0 ? TYPE_HEADER : TYPE_GUILD; }

        @Override
        public RecyclerView.ViewHolder onCreateViewHolder(ViewGroup parent, int viewType) {
            return viewType == TYPE_HEADER ? new HeaderViewHolder() : new GuildViewHolder();
        }

        @Override
        public void onBindViewHolder(RecyclerView.ViewHolder holder, int pos) {
            if (holder instanceof GuildViewHolder) ((GuildViewHolder) holder).bind(guilds.get(pos - 1));
        }
    }

    private class HeaderViewHolder extends RecyclerView.ViewHolder {
        HeaderViewHolder() { super(createHeader()); }
    }

    private class GuildViewHolder extends RecyclerView.ViewHolder {
        final ImageView icon;
        final TextView name;
        final ImageView banner;

        GuildViewHolder() {
            super(createGuildRow());
            LinearLayout row = (LinearLayout) itemView;
            icon = (ImageView) row.getChildAt(0);
            FrameLayout pill = (FrameLayout) row.getChildAt(1);
            banner = (ImageView) pill.getChildAt(0);
            name = (TextView) pill.getChildAt(1);
        }

        void bind(Guild g) {
            name.setText(g.name);
            if (!BuildConfig.SKIP_IMAGES && isReal(g.icon)) {
                GradientDrawable bg = new GradientDrawable();
                bg.setColor(CARD_BG);
                bg.setCornerRadius(dp(12));
                icon.setBackground(bg);
                icon.setClipToOutline(true);
                activity.imageLoader.load("https://cdn.discordapp.com/icons/" + g.id + "/" + g.icon + ".png?size=128", icon);
            } else {
                icon.setImageDrawable(null);
                GradientDrawable bg = new GradientDrawable();
                bg.setColor(ACCENT);
                bg.setCornerRadius(dp(12));
                icon.setBackground(bg);
                icon.setClipToOutline(true);
            }

            if (!BuildConfig.SKIP_IMAGES && isReal(g.banner)) {
                activity.imageLoader.load("https://cdn.discordapp.com/banners/" + g.id + "/" + g.banner + ".png?size=256", banner);
                banner.setColorFilter(0x77000000, android.graphics.PorterDuff.Mode.SRC_ATOP);
            } else {
                banner.setImageDrawable(null);
                banner.setBackgroundColor(CARD_BG);
            }

            itemView.setOnClickListener(v -> {
                if (activity != null) activity.navigate(new ChannelListScreen(g, g.owner));
            });
        }
    }

    private View createHeader() {
        LinearLayout header = new LinearLayout(activity);
        header.setOrientation(LinearLayout.HORIZONTAL);
        header.setGravity(Gravity.CENTER_VERTICAL);
        header.setPadding(0, dp(24), 0, dp(12));

        TextView title = new TextView(activity);
        title.setText("YOUR SERVERS");
        title.setTextSize(18);
        title.setTextColor(TEXT_PRIMARY);
        title.setTypeface(null, Typeface.BOLD);
        title.setLetterSpacing(0.05f);
        title.setLayoutParams(new LinearLayout.LayoutParams(-2, -2));

        View spacer = new View(activity);
        spacer.setLayoutParams(new LinearLayout.LayoutParams(0, 1, 1f));

        TextView dmBtn = new TextView(activity);
        dmBtn.setText("\uD83D\uDCAC");
        dmBtn.setTextSize(20);
        dmBtn.setPadding(dp(8), dp(8), dp(8), dp(8));
        applySelectableBackground(dmBtn);
        dmBtn.setOnClickListener(v -> {
            if (activity != null) activity.navigate(new DmListScreen());
        });

        TextView settingsBtn = new TextView(activity);
        settingsBtn.setText("\u2699\uFE0F");
        settingsBtn.setTextSize(20);
        settingsBtn.setPadding(dp(8), dp(8), dp(8), dp(8));
        applySelectableBackground(settingsBtn);
        settingsBtn.setOnClickListener(v -> showSessionMenu());

        header.addView(title);
        header.addView(spacer);
        header.addView(dmBtn);
        header.addView(settingsBtn);
        return header;
    }

    private View createGuildRow() {
        LinearLayout row = new LinearLayout(activity);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setLayoutParams(new RecyclerView.LayoutParams(-1, dp(ROW_HEIGHT_DP)));
        row.setPadding(0, dp(4), 0, dp(4));

        ImageView icon = new ImageView(activity);
        LinearLayout.LayoutParams iconLp = new LinearLayout.LayoutParams(dp(ICON_SIZE_DP), dp(ICON_SIZE_DP));
        iconLp.setMarginEnd(dp(16));
        icon.setLayoutParams(iconLp);
        icon.setScaleType(ImageView.ScaleType.CENTER_CROP);
        GradientDrawable iconBg = new GradientDrawable();
        iconBg.setCornerRadius(dp(12));
        icon.setBackground(iconBg);
        icon.setClipToOutline(true);

        FrameLayout pill = new FrameLayout(activity);
        pill.setLayoutParams(new LinearLayout.LayoutParams(0, -1, 1f));
        GradientDrawable pillBg = new GradientDrawable();
        pillBg.setCornerRadius(dp(12));
        pill.setBackground(pillBg);
        pill.setClipToOutline(true);

        ImageView banner = new ImageView(activity);
        banner.setScaleType(ImageView.ScaleType.CENTER_CROP);
        banner.setLayoutParams(new FrameLayout.LayoutParams(-1, -1));

        TextView name = new TextView(activity);
        name.setTextColor(TEXT_PRIMARY);
        name.setTextSize(16);
        name.setTypeface(null, Typeface.BOLD);
        name.setGravity(Gravity.CENTER);
        name.setShadowLayer(5, 0, 0, Color.BLACK);

        pill.addView(banner);
        pill.addView(name);
        row.addView(icon);
        row.addView(pill);
        return row;
    }

    private void showSessionMenu() {
        if (activity == null) return;
        final boolean hasKey = activity.e2eeKey != null;
        String[] items = hasKey ? new String[]{"Set encryption key", "Clear encryption key", "Log out"}
                                : new String[]{"Set encryption key", "Log out"};
        new AlertDialog.Builder(activity)
            .setTitle("Session")
            .setItems(items, (d, which) -> {
                if (which == 0) showSetKeyDialog();
                else if (hasKey && which == 1) clearKey();
                else confirmLogout();
            })
            .show();
    }

    private void confirmLogout() {
        if (activity == null) return;
        new AlertDialog.Builder(activity)
            .setTitle("Log out")
            .setMessage("Clear the saved token and return to the login screen?")
            .setPositiveButton("Log out", (dialog, which) -> {
                if (activity != null) activity.logout();
            })
            .setNegativeButton("Cancel", null)
            .show();
    }

    private void showSetKeyDialog() {
        if (activity == null) return;
        final EditText input = new EditText(activity);
        input.setHint("Encryption password");
        input.setInputType(android.text.InputType.TYPE_CLASS_TEXT | android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD);
        input.setTextColor(Color.WHITE);
        input.setHintTextColor(TEXT_MUTED);
        input.setPadding(dp(30), dp(30), dp(30), dp(30));
        
        new AlertDialog.Builder(activity)
            .setTitle("Set encryption key")
            .setMessage("Everyone in the chat must use the same password.")
            .setView(input)
            .setPositiveButton("Set", (d, which) -> {
                String pass = input.getText().toString();
                if (pass.isEmpty()) {
                    if (activity != null) activity.toast("Password cannot be empty");
                    return;
                }
                if (pass.length() > 128) {
                    if (activity != null) activity.toast("Password too long (max 128)");
                    return;
                }
                deriveAndSetKey(pass);
            })
            .setNegativeButton("Cancel", null)
            .show();
    }

    private void deriveAndSetKey(final String password) {
        if (activity == null) return;
        activity.toast("Deriving key...");
        new Thread(() -> {
            final SecretKey key = Crypto.deriveKey(password);
            if (activity == null) return;
            activity.runOnUiThread(() -> {
                if (activity == null) return;
                if (key == null) {
                    activity.toast("Key derivation failed");
                    return;
                }
                activity.e2eeKey = key;
                activity.e2eeEnabled = true;
                activity.toast("Encryption enabled for this session");
            });
        }).start();
    }

    private void clearKey() {
        if (activity == null) return;
        activity.e2eeKey = null;
        activity.e2eeEnabled = false;
        activity.toast("Encryption disabled");
    }

    private void applySelectableBackground(View v) {
        TypedValue outValue = new TypedValue();
        if (activity.getTheme().resolveAttribute(android.R.attr.selectableItemBackgroundBorderless, outValue, true)) {
            v.setBackgroundResource(outValue.resourceId);
        }
    }

    private static boolean isReal(String s) { return s != null && !s.isEmpty() && !"null".equals(s); }
    private int dp(int v) { return (int) (v * density); }

    @Override public View getView() { return view; }
    @Override public void onActivityResult(int requestCode, int resultCode, Intent data) {}
    @Override public void onResume() {}
    @Override public void onPause() {}
    @Override public void onDestroy() {
        destroyed = true;
        activity = null;
    }
}