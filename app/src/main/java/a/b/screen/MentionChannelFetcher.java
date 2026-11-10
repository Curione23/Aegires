package a.b.screen;

import a.b.MainActivity;
import a.b.api.ApiClient;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;

/* Fetches and caches channel names for mention rows.*/
final class MentionChannelFetcher {
    private static final long FAILURE_COOLDOWN_MS = 10_000;

    private final MainActivity activity;
    private final Consumer<String> onResolved;
    private final Set<String> pending = new HashSet<>();
    private final Set<String> resolved = new HashSet<>();
    private final Map<String, Long> lastFailure = new HashMap<>();

    MentionChannelFetcher(MainActivity activity, Consumer<String> onResolved) {
        this.activity = activity;
        this.onResolved = onResolved;
    }

    void request(final String channelId) {
        if (channelId == null || activity == null || activity.apiClient == null) return;
        if (resolved.contains(channelId) || pending.contains(channelId)) return;
        if (activity.channelNameCache.containsKey(channelId)) {
            resolved.add(channelId);
            return;
        }
        Long failedAt = lastFailure.get(channelId);
        if (failedAt != null && System.currentTimeMillis() - failedAt < FAILURE_COOLDOWN_MS) return;
        pending.add(channelId);
        activity.apiClient.fetchChannelName(
                channelId,
                new ApiClient.Callback<String>() {
                    @Override
                    public void onSuccess(String name) {
                        pending.remove(channelId);
                        if (activity == null) return;
                        if (name != null && !name.isEmpty()) {
                            resolved.add(channelId);
                            lastFailure.remove(channelId);
                            activity.channelNameCache.put(channelId, name);
                            if (onResolved != null) onResolved.accept(channelId);
                        } else {
                            lastFailure.put(channelId, System.currentTimeMillis());
                        }
                    }

                    @Override
                    public void onError(String msg) {
                        pending.remove(channelId);
                        lastFailure.put(channelId, System.currentTimeMillis());
                    }
                });
    }
}
