package a.b.screen;

import android.os.Handler;

import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import a.b.MainActivity;
import a.b.util.MemberListStore;

/* Drives lazy member-range requests as the user scrolls. */
final class MemberRangeLoader {
    private static final int BLOCK = 100;
    private static final long COOLDOWN_MS = 2000;
    private static final long CHECK_INTERVAL_MS = 800;

    private final MainActivity activity;
    private final String guildId;
    private final String channelId;
    private final RecyclerView list;
    private final Handler ui;
    private int lastBlock = -1;
    private long lastRequestAt;
    private boolean released;

    MemberRangeLoader(
            MainActivity activity,
            String guildId,
            String channelId,
            RecyclerView list,
            Handler ui) {
        this.activity = activity;
        this.guildId = guildId;
        this.channelId = channelId;
        this.list = list;
        this.ui = ui;
    }

    void release() {
        released = true;
        ui.removeCallbacksAndMessages(null);
    }

    void ensureLoaded() {
        if (released || activity == null || activity.gateway == null) return;
        ui.removeCallbacks(this::ensureLoaded);
        MemberListStore store = activity.memberList;
        if (store != null && store.guildId() != null && !guildId.equals(store.guildId())) return;
        int total = store != null ? store.total() : 0;
        if (total == 0) {
            long now = System.currentTimeMillis();
            if (now - lastRequestAt >= COOLDOWN_MS) {
                if (activity.gateway.requestMemberRanges(guildId, channelId, 0)) {
                    lastRequestAt = now;
                }
            }
            ui.postDelayed(this::ensureLoaded, CHECK_INTERVAL_MS);
            return;
        }

        RecyclerView.LayoutManager raw = list.getLayoutManager();
        if (!(raw instanceof LinearLayoutManager)) return;
        LinearLayoutManager lm = (LinearLayoutManager) raw;
        int first = lm.findFirstVisibleItemPosition();
        int last = lm.findLastVisibleItemPosition();
        if (first < 0 || last < 0) {
            ui.postDelayed(this::ensureLoaded, 300);
            return;
        }

        int from = Math.max(0, first - 10);
        int to = Math.min(total - 1, last + 25);
        if (to < from) return;

        int missing = -1;
        for (int i = from; i <= to; i++) {
            if (store == null || store.get(i) == null) {
                missing = i;
                break;
            }
        }

        if (missing >= 0) {
            int block = missing / BLOCK;
            long now = System.currentTimeMillis();

            if (now - lastRequestAt >= COOLDOWN_MS) {
                if (activity.gateway.requestMemberRanges(guildId, channelId, block * BLOCK)) {
                    lastBlock = block;
                    lastRequestAt = now;
                }
            }
            ui.postDelayed(this::ensureLoaded, CHECK_INTERVAL_MS);
        } else {
            lastBlock = -1;
        }
    }
}
