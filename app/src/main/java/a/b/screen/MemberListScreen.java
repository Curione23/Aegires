package a.b.screen;

import android.content.Intent;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;
import a.b.MainActivity;
import a.b.util.MemberListStore;
import a.b.util.Ui;

public class MemberListScreen implements Screen, MemberListStore.Listener {
    private static final int PAGE_BG = Ui.BG_SECONDARY;

    MainActivity activity;
    final String guildId;
    final String channelId;
    View view;
    Ui.AppBar bar;
    RecyclerView list;
    TextView emptyView;
    MemberListAdapter adapter;
    private final Handler ui = new Handler(Looper.getMainLooper());
    private final Runnable refresh = this::refreshNow;
    private MemberRangeLoader rangeLoader;

    public MemberListScreen(String guildId, String channelId) {
        this.guildId = guildId;
        this.channelId = channelId;
    }

    @Override
    public View onCreateView(final MainActivity activity, ViewGroup parent) {
        this.activity = activity;

        LinearLayout root = new LinearLayout(activity);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(PAGE_BG);

        bar = new Ui.AppBar(activity, "Members", PAGE_BG, activity::goBack);
        root.addView(bar.view, new LinearLayout.LayoutParams(-1, -2));

        FrameLayout body = new FrameLayout(activity);
        list = new RecyclerView(activity);
        list.setLayoutManager(new LinearLayoutManager(activity));
        list.setItemAnimator(null);
        adapter = new MemberListAdapter(activity, guildId);
        list.setAdapter(adapter);
        rangeLoader = new MemberRangeLoader(activity, guildId, channelId, list, ui);
        list.addOnScrollListener(new RecyclerView.OnScrollListener() {
            @Override public void onScrolled(RecyclerView rv, int dx, int dy) {
                if (rangeLoader != null) rangeLoader.ensureLoaded();
            }
        });
        body.addView(list, new FrameLayout.LayoutParams(-1, -1));

        emptyView = Ui.emptyState(activity, "Loading members\u2026");
        body.addView(emptyView, new FrameLayout.LayoutParams(-1, -2, Gravity.CENTER));
        root.addView(body, new LinearLayout.LayoutParams(-1, 0, 1f));

        if (activity.apiClient.isBotAccount()) {
            emptyView.setText("The member list isn't available for bot accounts.");
        }
        refreshNow();
        return view = root;
    }

    @Override public void onMemberListChanged() {
        if (activity == null) return;
        ui.removeCallbacks(refresh);
        ui.postDelayed(refresh, 120);
    }

    @Override public void onMemberListRangeChanged(int positionStart, int itemCount) {
        if (adapter != null && itemCount > 0) {
            adapter.notifyItemRangeChanged(positionStart, itemCount);
        }
    }

    private void refreshNow() {
        if (activity == null) return;
        if (emptyView == null || bar == null) return;
        MemberListStore store = activity.memberList;
        
        boolean mine = store != null && guildId.equals(store.guildId());
        int total = mine ? store.total() : 0;
        
        boolean bot = activity.apiClient != null && activity.apiClient.isBotAccount();
        if (!bot) {
            emptyView.setText("Loading members\u2026");
        }
        emptyView.setVisibility(total == 0 ? View.VISIBLE : View.GONE);
        
        if (mine && store.memberCount() > 0) {
            bar.setSubtitle(store.memberCount() + " members \u00B7 " + store.onlineCount() + " online");
        } else {
            bar.setSubtitle(null);
        }
        if (adapter != null) adapter.notifyDataSetChanged();
        
        if (rangeLoader != null) rangeLoader.ensureLoaded(); 
    }

    @Override public View getView() { return view; }
    @Override public void onActivityResult(int requestCode, int resultCode, Intent data) {}

    @Override public void onResume() {
        if (activity == null) return;
        activity.memberList.addListener(this);
        refreshNow();
    }

    @Override public void onPause() {
        if (activity != null) activity.memberList.removeListener(this);
    }

    @Override
    public void onDestroy() {
        ui.removeCallbacksAndMessages(null);
        if (activity != null) activity.memberList.removeListener(this);
        if (adapter != null) adapter.release();
        if (rangeLoader != null) rangeLoader.release();
        activity = null;
        adapter = null;
        rangeLoader = null;
        emptyView = null;
        bar = null;
        list = null;
        view = null;
    }
}