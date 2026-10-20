package a.b.screen;

import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.*;
import android.content.Intent;
import a.b.MainActivity;
import a.b.util.TokenStore;

public class LoginScreen implements Screen {
    MainActivity activity;
    View view;
    float density;

    @Override
    public View onCreateView(final MainActivity activity, ViewGroup parent) {
        this.activity = activity;
        this.density = activity.getResources().getDisplayMetrics().density;

        FrameLayout root = new FrameLayout(activity);
        root.setBackgroundColor(0xFF000000);

        LinearLayout form = new LinearLayout(activity);
        form.setOrientation(LinearLayout.VERTICAL);
        form.setGravity(Gravity.CENTER_HORIZONTAL);
        
        FrameLayout.LayoutParams formLp = new FrameLayout.LayoutParams(dp(320), ViewGroup.LayoutParams.WRAP_CONTENT);
        formLp.gravity = Gravity.CENTER;
        formLp.leftMargin = dp(24);
        formLp.rightMargin = dp(24);
        form.setLayoutParams(formLp);

        TextView header = new TextView(activity);
        header.setText("Aegires");
        header.setTextSize(26);
        header.setTextColor(Color.WHITE);
        header.setTypeface(null, Typeface.BOLD);
        header.setGravity(Gravity.CENTER);
        header.setPadding(0, 0, 0, dp(32));
        form.addView(header);

        final EditText tokenInput = new EditText(activity);
        tokenInput.setHint("Enter Token");
        tokenInput.setHintTextColor(0xFF72767D);
        tokenInput.setTextColor(Color.WHITE);
        tokenInput.setTextSize(15);
        tokenInput.setPadding(dp(16), dp(14), dp(16), dp(14));
        
        GradientDrawable inputBg = new GradientDrawable();
        inputBg.setColor(0xFF1E1F22);
        inputBg.setCornerRadius(dp(8));
        tokenInput.setBackground(inputBg);
        
        LinearLayout.LayoutParams inputLp = new LinearLayout.LayoutParams(-1, -2);
        inputLp.bottomMargin = dp(16);
        tokenInput.setLayoutParams(inputLp);
        form.addView(tokenInput);

        Button connectBtn = new Button(activity);
        connectBtn.setText("CONNECT");
        connectBtn.setTextColor(Color.WHITE);
        connectBtn.setTextSize(14);
        connectBtn.setTypeface(null, Typeface.BOLD);
        connectBtn.setAllCaps(false);
        
        GradientDrawable btnBg = new GradientDrawable();
        btnBg.setColor(0xFF5865F2);
        btnBg.setCornerRadius(dp(8));
        connectBtn.setBackground(btnBg);
        
        LinearLayout.LayoutParams btnLp = new LinearLayout.LayoutParams(-1, dp(48));
        connectBtn.setLayoutParams(btnLp);
        
        connectBtn.setOnClickListener(v -> {
            String token = tokenInput.getText().toString().trim();
            if (!token.isEmpty()) {
                activity.token = token;
                activity.setToken(token);
                TokenStore.save(activity, token);
                activity.resetTo(new GuildListScreen());
            }
        });
        form.addView(connectBtn);
        root.addView(form);

        return view = root;
    }

    private int dp(int v) { return (int) (v * density); }

    @Override public void onActivityResult(int requestCode, int resultCode, Intent data) {}
    @Override public View getView() { return view; }
    @Override public void onResume() {}
    @Override public void onPause() {}
    @Override public void onDestroy() { activity = null; }
}