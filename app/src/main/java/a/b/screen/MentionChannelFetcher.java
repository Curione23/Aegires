package a.b.screen;

import java.util.HashSet;
import java.util.Set;
import java.util.function.Consumer;
import a.b.MainActivity;
import a.b.api.ApiClient;

/**
 * Fetches and caches channel names for mention rows.
 *
 * <p>Keeps a set of channel IDs we have already requested a name for so we
 * don't hammer the API on every re-render. IDs are intentionally never
 * removed from {@code pending}; the fetch is fire-once.
 */
final class MentionChannelFetcher {
    private final MainActivity activity;
    private final Consumer<String> onResolved;
    private final Set<String> pending = new HashSet<>();

    MentionChannelFetcher(MainActivity activity, Consumer<String> onResolved) {
        this.activity = activity;
        this.onResolved = onResolved;
    }

    void request(final String channelId) {
        if (activity == null || !pending.add(channelId)) return;
        activity.apiClient.fetchChannelName(channelId, new ApiClient.Callback<String>() {
            @Override public void onSuccess(String name) {
                if (name != null && activity != null) {
                    activity.channelNameCache.put(channelId, name);
                    if (onResolved != null) onResolved.accept(channelId);
                }
            }
            @Override public void onError(String msg) {}
        });
    }
}