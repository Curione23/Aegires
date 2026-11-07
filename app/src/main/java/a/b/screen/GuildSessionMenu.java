package a.b.screen;

import android.graphics.Color;
import android.view.View;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.RadioButton;
import android.widget.RadioGroup;
import android.widget.TextView;
import javax.crypto.SecretKey;
import a.b.MainActivity;
import a.b.util.Crypto;
import a.b.util.MediaCache;
import a.b.util.Ui;

/** The gear-icon "Session" menu: encryption preset/key + logout. */
final class GuildSessionMenu {
    private final MainActivity activity;
    private final float density;

    GuildSessionMenu(MainActivity activity, float density) {
        this.activity = activity;
        this.density = density;
    }

    void showSessionMenu() {
        if (activity == null) return;
        final boolean hasKey = activity.e2eeKey != null;
        final String presetItem = "Encryption preset: " + activity.e2eePreset.label;
        String[] items = hasKey
            ? new String[]{"Set encryption key", "Clear encryption key", presetItem, "Log out"}
            : new String[]{"Set encryption key", presetItem, "Log out"};
        new android.app.AlertDialog.Builder(activity, android.R.style.Theme_DeviceDefault_Dialog_Alert)
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

        new android.app.AlertDialog.Builder(host, android.R.style.Theme_DeviceDefault_Dialog_Alert)
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
        new android.app.AlertDialog.Builder(activity, android.R.style.Theme_DeviceDefault_Dialog_Alert)
            .setTitle("Log out")
            .setMessage("Clear the saved token and return to the login screen?")
            .setPositiveButton("Log out", (dialog, which) -> {
                if (activity == null) return;
                MediaCache.get(activity).clearSensitive();
                activity.logout();
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
        input.setHintTextColor(Ui.MUTED);
        input.setSingleLine(true);
        FrameLayout box = new FrameLayout(activity);
        box.setPadding(dp(20), dp(8), dp(20), 0);
        box.addView(input);

        new android.app.AlertDialog.Builder(activity, android.R.style.Theme_DeviceDefault_Dialog_Alert)
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
                MediaCache.get(host).clearFailures();
                host.toast("Encryption enabled for this session");
            });
        }, "key-derivation").start();
    }

    private void clearKey() {
        if (activity == null) return;
        activity.e2eeKey = null;
        activity.e2eeEnabled = false;
        MediaCache.get(activity).clearSensitive();
        activity.toast("Encryption disabled");
    }

    private int dp(int v) { return (int) (v * density); }
}