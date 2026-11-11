package a.b.screen;

import android.content.Intent;
import android.graphics.Typeface;
import android.text.InputType;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.inputmethod.EditorInfo;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import a.b.MainActivity;
import a.b.util.TokenStore;
import a.b.util.Ui;

public class LoginScreen implements Screen {
    MainActivity activity;
    View view;

    @Override
    public View onCreateView(final MainActivity activity, ViewGroup parent) {
        this.activity = activity;

        FrameLayout root = new FrameLayout(activity);
        root.setBackgroundColor(Ui.BG);

        ScrollView scroll = new ScrollView(activity);
        scroll.setFillViewport(true);
        root.addView(scroll, new FrameLayout.LayoutParams(-1, -1));

        LinearLayout form = new LinearLayout(activity);
        form.setOrientation(LinearLayout.VERTICAL);
        form.setGravity(Gravity.CENTER);
        FrameLayout.LayoutParams formLp = new FrameLayout.LayoutParams(-1, -2);
        formLp.leftMargin = dp(24);
        formLp.rightMargin = dp(24);

        TextView header = Ui.text(activity, "Aegires", Ui.TEXT_HEADER, 28);
        header.setTypeface(null, Typeface.BOLD);
        header.setGravity(Gravity.CENTER);
        form.addView(header);

        TextView sub =
                Ui.text(
                        activity,
                        "Paste your token, or use Discord's web login",
                        Ui.MUTED,
                        14);
        sub.setGravity(Gravity.CENTER);
        sub.setPadding(0, dp(6), 0, dp(28));
        form.addView(sub);

        TextView label = Ui.sectionLabel(activity, "Token");
        label.setPadding(dp(2), 0, 0, dp(6));
        form.addView(label, new LinearLayout.LayoutParams(-1, -2));

        final EditText tokenInput = new EditText(activity);
        tokenInput.setHint("Token");
        tokenInput.setHintTextColor(Ui.MUTED);
        tokenInput.setTextColor(Ui.TEXT);
        tokenInput.setTextSize(15);
        tokenInput.setSingleLine(true);
        tokenInput.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
        tokenInput.setImeOptions(EditorInfo.IME_ACTION_DONE);
        tokenInput.setPadding(dp(14), dp(14), dp(14), dp(14));
        tokenInput.setBackground(Ui.rounded(Ui.BG_TERTIARY, dp(8)));
        LinearLayout.LayoutParams inputLp = new LinearLayout.LayoutParams(-1, -2);
        inputLp.bottomMargin = dp(16);
        form.addView(tokenInput, inputLp);

        final TextView connect = Ui.primaryButton(activity, "Log in");
        form.addView(connect, new LinearLayout.LayoutParams(-1, dp(48)));

        TextView or = Ui.text(activity, "or", Ui.MUTED, 12);
        or.setGravity(Gravity.CENTER);
        or.setPadding(0, dp(14), 0, dp(14));
        form.addView(or);

        TextView discordLogin = Ui.text(activity, "Log in with Discord", Ui.TEXT_HEADER, 15);
        discordLogin.setTypeface(null, Typeface.BOLD);
        discordLogin.setGravity(Gravity.CENTER);
        discordLogin.setClickable(true);
        discordLogin.setBackground(Ui.ripple(Ui.rounded(Ui.BG_INPUT, dp(8)), dp(8)));
        form.addView(discordLogin, new LinearLayout.LayoutParams(-1, dp(48)));
        discordLogin.setOnClickListener(v -> activity.navigate(new WebLoginScreen()));

        TextView webNote =
                Ui.text(
                        activity,
                        "Opens Discord's web login. The token stays on this device.",
                        Ui.MUTED,
                        12);
        webNote.setGravity(Gravity.CENTER);
        webNote.setPadding(dp(8), dp(10), dp(8), 0);
        form.addView(webNote);

        final Runnable submit =
                () -> {
                    if (activity == null) return;
                    String token = tokenInput.getText().toString().trim();
                    if (token.length() > 1 && (token.startsWith("\"") && token.endsWith("\""))) {
                        token = token.substring(1, token.length() - 1).trim();
                    }
                    if (token.isEmpty()) {
                        tokenInput.setError("Enter a token");
                        return;
                    }
                    TokenStore.save(activity, token);
                    activity.setToken(token);
                    activity.resetTo(new HomeScreen(activity));
                };
        connect.setOnClickListener(v -> submit.run());
        tokenInput.setOnEditorActionListener(
                (v, actionId, e) -> {
                    if (actionId == EditorInfo.IME_ACTION_DONE) {
                        submit.run();
                        return true;
                    }
                    return false;
                });
        scroll.addView(form, formLp);
        return view = root;
    }

    private int dp(int v) {
        return Ui.dp(activity, v);
    }

    @Override
    public void onActivityResult(int requestCode, int resultCode, Intent data) {}

    @Override
    public View getView() {
        return view;
    }

    @Override
    public void onResume() {}

    @Override
    public void onPause() {}

    @Override
    public void onDestroy() {
        activity = null;
        view = null;
    }
}
