package a.b.screen;

import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TimeZone;
import org.json.JSONArray;
import org.json.JSONObject;
import a.b.MainActivity;
import a.b.api.ApiClient;
import a.b.model.DiscordUser;
import a.b.model.Role;
import a.b.util.Ui;

public class ProfileScreen implements Screen, ApiClient.Callback<DiscordUser> {
    MainActivity activity;
    final String userId;
    final String guildId;
    View view;

    private ProfileHeaderView headerView;
    private ProfileDetailsView detailsView;
    private ProfileRolesView rolesView;

    private DiscordUser user;
    private String nick;
    private long joinedMs;

    public ProfileScreen(String userId, String guildId) {
        this.userId = userId;
        this.guildId = guildId;
    }

    @Override
    public View onCreateView(final MainActivity activity, ViewGroup parent) {
        this.activity = activity;

        FrameLayout frame = new FrameLayout(activity);
        frame.setBackgroundColor(Ui.BG_SECONDARY);

        ScrollView scroll = new ScrollView(activity);
        scroll.setFillViewport(true);
        LinearLayout col = new LinearLayout(activity);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setPadding(0, 0, 0, dp(24));

        headerView = new ProfileHeaderView(activity, userId);
        detailsView = new ProfileDetailsView(activity);
        rolesView = new ProfileRolesView(activity);

        col.addView(headerView.getHeaderView());
        col.addView(headerView.getNameBlock());
        col.addView(detailsView.getDetailsView(), sectionLp());
        col.addView(detailsView.getAboutCard(), sectionLp());
        col.addView(rolesView.getRolesView(), sectionLp());

        scroll.addView(col, new ViewGroup.LayoutParams(-1, -2));
        frame.addView(scroll, new FrameLayout.LayoutParams(-1, -1));

        FrameLayout back = Ui.iconButton(activity, Ui.I_BACK, 0xFFFFFFFF);
        back.setBackground(Ui.ripple(Ui.oval(0x99000000), -1));
        FrameLayout.LayoutParams backLp = new FrameLayout.LayoutParams(dp(40), dp(40));
        backLp.setMargins(dp(8), dp(8), 0, 0);
        back.setOnClickListener(v -> { if (this.activity != null) this.activity.goBack(); });
        frame.addView(back, backLp);

        FrameLayout copy = Ui.iconButton(activity, Ui.I_COPY, 0xFFFFFFFF);
        copy.setBackground(Ui.ripple(Ui.oval(0x99000000), -1));
        FrameLayout.LayoutParams copyLp = new FrameLayout.LayoutParams(dp(40), dp(40), Gravity.TOP | Gravity.END);
        copyLp.setMargins(0, dp(8), dp(8), 0);
        copy.setOnClickListener(v -> copyId());
        frame.addView(copy, copyLp);

        activity.apiClient.fetchUserProfileFull(userId, this);
        loadMember();
        return view = frame;
    }

    private void copyId() {
        if (activity == null) return;
        ClipboardManager cm = (ClipboardManager) activity.getSystemService(Context.CLIPBOARD_SERVICE);
        cm.setPrimaryClip(ClipData.newPlainText("user id", userId));
        activity.toast("Copied user ID");
    }

    private LinearLayout.LayoutParams sectionLp() {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
        lp.setMargins(dp(16), dp(20), dp(16), 0);
        return lp;
    }

    @Override
    public void onSuccess(DiscordUser u) {
        if (u == null || activity == null) return;
        user = u;
        headerView.applyName(user, nick);
        headerView.renderUser(u);
        detailsView.renderDetails(user, guildId, joinedMs);
        detailsView.renderAbout(user);
    }

    @Override
    public void onError(String msg) {
        if (activity != null) activity.toast("Failed to load profile: " + msg);
    }

    private void loadMember() {
        if (guildId == null || activity == null) return;
        activity.apiClient.fetchMember(guildId, userId, new ApiClient.Callback<JSONObject>() {
            @Override public void onSuccess(JSONObject member) {
                if (activity == null) return;
                nick = member.isNull("nick") ? null : member.optString("nick", null);
                joinedMs = parseIso(member.isNull("joined_at") ? null : member.optString("joined_at", null));
                headerView.applyName(user, nick);
                detailsView.renderDetails(user, guildId, joinedMs);

                JSONArray rolesArray = member.optJSONArray("roles");
                if (rolesArray == null || rolesArray.length() == 0) return;
                final List<String> roleIds = new ArrayList<>();
                for (int i = 0; i < rolesArray.length(); i++) roleIds.add(rolesArray.optString(i));
                activity.apiClient.fetchRoles(guildId, new ApiClient.Callback<Map<String, Role>>() {
                    @Override public void onSuccess(Map<String, Role> roles) {
                        if (activity == null) return;
                        activity.roleCache.putAll(roles);
                        rolesView.render(roleIds, roles);
                    }
                    @Override public void onError(String err) {}
                });
            }
            @Override public void onError(String err) {}
        });
    }

    private static long parseIso(String s) {
        if (s == null || s.length() < 19) return 0;
        try {
            SimpleDateFormat f = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", Locale.US);
            f.setTimeZone(TimeZone.getTimeZone("UTC"));
            Date d = f.parse(s.substring(0, 19));
            return d == null ? 0 : d.getTime();
        } catch (Exception e) {
            return 0;
        }
    }

    private int dp(int v) { return Ui.dp(activity, v); }

    @Override public void onActivityResult(int requestCode, int resultCode, Intent data) {}
    @Override public View getView() { return view; }
    @Override public void onResume() {}
    @Override public void onPause() {}
    @Override public void onDestroy() {
        activity = null;
        headerView = null;
        detailsView = null;
        rolesView = null;
    }
}