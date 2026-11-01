package a.b.screen;

import android.app.AlertDialog;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.Typeface;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.RadioButton;
import android.widget.RadioGroup;
import android.widget.TextView;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import javax.crypto.SecretKey;
import a.b.MainActivity;
import a.b.api.ApiClient;
import a.b.model.Guild;
import a.b.util.Crypto;
import a.b.util.MentionStore;
import a.b.util.Ui;

public class GuildListScreen implements Screen, MentionStore.Listener {
    private static final int ROW_HEIGHT_DP = 60;
    private static final int ICON_SIZE_DP = 44;

    MainActivity activity;
    View view;
    RecyclerView recyclerView;
    GuildAdapter adapter;
    TextView emptyView;
    Ui.ActionButton mentionsBtn, dmBtn;
    float density;
    boolean destroyed;
    private boolean loading;
    private List<Guild> guilds = new ArrayList<>();

    @Override
    public View onCreateView(final MainActivity activity, ViewGroup parent) {
        this.activity = activity;
        this.density = activity.getResources().getDisplayMetrics().density;

        LinearLayout root = new LinearLayout(activity);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(Ui.BG_SECONDARY);

        Ui.AppBar bar = new Ui.AppBar(activity, "Servers", Ui.BG_SECONDARY, null);
        mentionsBtn = bar.addAction(Ui.I_BELL, v -> activity.navigate(new NotificationsScreen()));
        dmBtn = bar.addAction(Ui.I_CHAT, v -> activity.navigate(new DmListScreen()));
        bar.addAction(Ui.I_GEAR, v -> showSessionMenu());
        root.addView(bar.view, new LinearLayout.LayoutParams(-1, -2));

        FrameLayout body = new FrameLayout(activity);
        recyclerView = new RecyclerView(activity);
        recyclerView.setLayoutManager(new LinearLayoutManager(activity));
        recyclerView.setItemAnimator(null);
        recyclerView.setClipToPadding(false);
        recyclerView.setPadding(0, dp(8), 0, dp(16));
        adapter = new GuildAdapter();
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
            guilds = new ArrayList<>(cached);
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
                guilds = new ArrayList<>(disk);
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
                guilds = new ArrayList<>(result);
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

    private class GuildAdapter extends RecyclerView.Adapter<GuildViewHolder> {
        @Override public int getItemCount() { return guilds.size(); }
        @Override public GuildViewHolder onCreateViewHolder(ViewGroup parent, int viewType) { return new GuildViewHolder(); }
        @Override public void onBindViewHolder(GuildViewHolder holder, int pos) { holder.bind(guilds.get(pos)); }
    }

    private class GuildViewHolder extends RecyclerView.ViewHolder {
        final FrameLayout iconWrap;
        final ImageView icon;
        final TextView initial, name;

        GuildViewHolder() {
            super(new LinearLayout(activity));
            LinearLayout row = (LinearLayout) itemView;
            row.setLayoutParams(new RecyclerView.LayoutParams(-1, dp(ROW_HEIGHT_DP)));
            row.setOrientation(LinearLayout.HORIZONTAL);
            row.setGravity(Gravity.CENTER_VERTICAL);
            row.setPadding(dp(16), 0, dp(16), 0);
            row.setClickable(true);
            row.setBackground(Ui.ripple(null, 0));

            iconWrap = new FrameLayout(activity);
            iconWrap.setClipToOutline(true);
            LinearLayout.LayoutParams iconLp = new LinearLayout.LayoutParams(dp(ICON_SIZE_DP), dp(ICON_SIZE_DP));
            iconLp.setMarginEnd(dp(14));
            icon = new ImageView(activity);
            icon.setScaleType(ImageView.ScaleType.CENTER_CROP);
            initial = Ui.text(activity, "", Color.WHITE, 16);
            initial.setTypeface(null, Typeface.BOLD);
            initial.setGravity(Gravity.CENTER);
            iconWrap.addView(initial, new FrameLayout.LayoutParams(-1, -1));
            iconWrap.addView(icon, new FrameLayout.LayoutParams(-1, -1));
            row.addView(iconWrap, iconLp);

            name = Ui.text(activity, "", Ui.TEXT_HEADER, 16);
            name.setSingleLine(true);
            name.setEllipsize(TextUtils.TruncateAt.END);
            row.addView(name, new LinearLayout.LayoutParams(0, -2, 1f));
        }

        void bind(final Guild g) {
            name.setText(g.name);
            boolean hasIcon = Ui.isReal(g.icon);
            iconWrap.setBackground(Ui.rounded(hasIcon ? Ui.BG_TERTIARY : Ui.avatarColor(g.id), dp(16)));
            iconWrap.setOutlineProvider(android.view.ViewOutlineProvider.BACKGROUND);
            initial.setText(initialOf(g.name));
            initial.setVisibility(hasIcon ? View.GONE : View.VISIBLE);
            activity.imageLoader.load(hasIcon
                ? "https://cdn.discordapp.com/icons/" + g.id + "/" + g.icon + ".png?size=128" : null, icon);
            itemView.setOnClickListener(v -> {
                if (activity != null) activity.navigate(new ChannelListScreen(g, g.owner));
            });
        }
    }

    private static String initialOf(String name) {
        if (name == null || name.trim().isEmpty()) return "?";
        return name.trim().substring(0, name.trim().offsetByCodePoints(0, 1)).toUpperCase(Locale.US);
    }


    private void showSessionMenu() {
        if (activity == null) return;
        final boolean hasKey = activity.e2eeKey != null;
        final String presetItem = "Encryption preset: " + activity.e2eePreset.label;
        String[] items = hasKey
            ? new String[]{"Set encryption key", "Clear encryption key", presetItem, "Log out"}
            : new String[]{"Set encryption key", presetItem, "Log out"};
        new AlertDialog.Builder(activity, android.R.style.Theme_DeviceDefault_Dialog_Alert)
            .setTitle("Session")
            .setItems(items, (d, which) -> {
                String picked = items[which];
                if (picked.startsWith("Set encryption")) showSetKeyDialog();
                else if (picked.startsWith("Clear encryption")) clearKey();
                else if (picked.startsWith("Encryption preset")) showPresetDialog();
                else confirmLogout();
            })
            .show();
    }

    private static String presetBlurb(Crypto.Preset p) {
        String tier = p == Crypto.Preset.AES128 ? "Compact" : p == Crypto.Preset.AES192 ? "Balanced" : "Maximum";
        return tier + " \u00B7 " + p.tagBits() + "-bit tag \u00B7 " + p.padBlock + "-byte padding\nup to "
            + Crypto.maxPlaintextBytes(p) + " bytes per message";
    }

    private void showPresetDialog() {
        if (activity == null) return;
        final MainActivity host = activity;

        LinearLayout box = new LinearLayout(host);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(dp(20), dp(8), dp(20), 0);

        TextView info = Ui.text(host, "Applies to the messages and files you send. You can read everyone else's whatever they chose. "
            + "Preset channels always use AES-128-GCM.", Ui.MUTED, 13);
        info.setPadding(0, 0, 0, dp(10));
        box.addView(info);

        final RadioGroup group = new RadioGroup(host);
        for (Crypto.Preset p : Crypto.Preset.values()) {
            RadioButton rb = new RadioButton(host);
            rb.setId(View.generateViewId());
            rb.setTag(p);
            rb.setText(p.label + "\n" + presetBlurb(p));
            rb.setTextColor(Ui.TEXT_HEADER);
            rb.setTextSize(14);
            rb.setPadding(dp(8), dp(8), 0, dp(8));
            rb.setChecked(p == host.e2eePreset);
            group.addView(rb);
        }
        box.addView(group);

        new AlertDialog.Builder(host, android.R.style.Theme_DeviceDefault_Dialog_Alert)
            .setTitle("Encryption preset")
            .setView(box)
            .setPositiveButton("Save", (d, w) -> {
                View sel = group.findViewById(group.getCheckedRadioButtonId());
                if (sel == null || !(sel.getTag() instanceof Crypto.Preset)) return;
                Crypto.Preset p = (Crypto.Preset) sel.getTag();
                host.setE2eePreset(p);
                host.toast("Using " + p.label + " (max " + Crypto.maxPlaintextBytes(p) + " bytes per message)");
            })
            .setNegativeButton("Cancel", null)
            .show();
    }

    private void confirmLogout() {
        if (activity == null) return;
        new AlertDialog.Builder(activity, android.R.style.Theme_DeviceDefault_Dialog_Alert)
            .setTitle("Log out")
            .setMessage("Clear the saved token and return to the login screen?")
            .setPositiveButton("Log out", (dialog, which) -> { if (activity != null) activity.logout(); })
            .setNegativeButton("Cancel", null)
            .show();
    }

    private void showSetKeyDialog() {
        if (activity == null) return;
        final EditText input = new EditText(activity);
        input.setHint("Encryption password");
        input.setInputType(android.text.InputType.TYPE_CLASS_TEXT | android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD);
        input.setTextColor(Color.WHITE);
        input.setHintTextColor(Ui.MUTED);
        input.setSingleLine(true);
        FrameLayout box = new FrameLayout(activity);
        box.setPadding(dp(20), dp(8), dp(20), 0);
        box.addView(input);

        new AlertDialog.Builder(activity, android.R.style.Theme_DeviceDefault_Dialog_Alert)
            .setTitle("Set encryption key")
            .setMessage("Everyone in the chat must use the same password. Use a long passphrase: the password is the only secret protecting your messages.")
            .setView(box)
            .setPositiveButton("Set", (d, which) -> {
                String pass = input.getText().toString();
                if (pass.length() < 8) { if (activity != null) activity.toast("Use at least 8 characters"); return; }
                if (pass.length() > 256) { if (activity != null) activity.toast("Password too long (max 256)"); return; }
                deriveAndSetKey(pass);
            })
            .setNegativeButton("Cancel", null)
            .show();
    }

    private void deriveAndSetKey(final String password) {
        if (activity == null) return;
        final MainActivity host = activity;
        host.toast("Deriving key, this takes a few seconds\u2026");
        new Thread(() -> {
            final SecretKey key = Crypto.deriveKey(password);
            host.runOnUiThread(() -> {
                if (key == null) { host.toast("Key derivation failed (not enough memory?)"); return; }
                host.e2eeKey = key;
                host.e2eeEnabled = true;
                host.toast("Encryption enabled for this session");
            });
        }, "key-derivation").start();
    }

    private void clearKey() {
        if (activity == null) return;
        activity.e2eeKey = null;
        activity.e2eeEnabled = false;
        activity.toast("Encryption disabled");
    }

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
        activity = null;
    }
}
