package a.b.screen;

import android.os.Handler;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;
import a.b.MainActivity;
import a.b.util.MemberListStore;

/**
 * Drives lazy member-range requests as the user scrolls.
 *
 * <p>Lives on the screen's {@link Handler} so {@code removeCallbacksAndMessages(null)}
 * at screen teardown clears any pending retry.
 */
final class MemberRangeLoader {
    private static final int BLOCK = 100;

    private final MainActivity activity;
    private final String guildId;
    private final String channelId;
    private final RecyclerView list;
    private final Handler ui;
    private int lastBlock = -1;
    private long lastRequestAt;

    MemberRangeLoader(MainActivity activity, String guildId, String channelId, RecyclerView list, Handler ui) {
        this.activity = activity;
        this.guildId = guildId;
        this.channelId = channelId;
        this.list = list;
        this.ui = ui;
    }

    void ensureLoaded() {
        if (activity == null || activity.gateway == null) return;
        MemberListStore store = activity.memberList;
        if (!guildId.equals(store.guildId())) return;
        LinearLayoutManager lm = (LinearLayoutManager) list.getLayoutManager();
        int first = lm.findFirstVisibleItemPosition();
        int last = lm.findLastVisibleItemPosition();
        if (first < 0 || last < 0) return;

        int from = Math.max(0, first - 10);
        int to = Math.min(store.total() - 1, last + 25);
        int missing = -1;
        for (int i = from; i <= to; i++) {
            if (store.get(i) == null) { missing = i; break; }
        }
        if (missing < 0) return;

        int block = missing / BLOCK;
        long now = System.currentTimeMillis();
        if (block == lastBlock && now - lastRequestAt < 2000) return;
        lastBlock = block;
        lastRequestAt = now;
        activity.gateway.requestMemberRanges(guildId, channelId, block * BLOCK);
        ui.postDelayed(this::ensureLoaded, 2100);
    }
}