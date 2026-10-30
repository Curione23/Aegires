package a.b.screen;

import android.content.Intent;
import android.graphics.Color;
import android.graphics.Typeface;
import android.text.InputType;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.inputmethod.EditorInfo;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
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

        LinearLayout form = new LinearLayout(activity);
        form.setOrientation(LinearLayout.VERTICAL);
        form.setGravity(Gravity.CENTER_HORIZONTAL);
        FrameLayout.LayoutParams formLp = new FrameLayout.LayoutParams(-1, -2, Gravity.CENTER);
        formLp.leftMargin = dp(24);
        formLp.rightMargin = dp(24);
        form.setLayoutParams(formLp);

        TextView header = Ui.text(activity, "Aegires", Ui.TEXT_HEADER, 28);
        header.setTypeface(null, Typeface.BOLD);
        header.setGravity(Gravity.CENTER);
        form.addView(header);

        TextView sub = Ui.text(activity, "Paste your token to continue", Ui.MUTED, 14);
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

        final Runnable submit = () -> {
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
            activity.resetTo(new GuildListScreen());
        };
        connect.setOnClickListener(v -> submit.run());
        tokenInput.setOnEditorActionListener((v, actionId, e) -> {
            if (actionId == EditorInfo.IME_ACTION_DONE) { submit.run(); return true; }
            return false;
        });
        root.addView(form);
        return view = root;
    }

    private int dp(int v) { return Ui.dp(activity, v); }

    @Override public void onActivityResult(int requestCode, int resultCode, Intent data) {}
    @Override public View getView() { return view; }
    @Override public void onResume() {}
    @Override public void onPause() {}
    @Override public void onDestroy() { activity = null; }
}
