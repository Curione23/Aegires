package a.b.screen;

import android.content.Intent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.LinearLayout;

import a.b.MainActivity;
import a.b.model.Guild;

import java.util.ArrayList;
import java.util.List;

public class HomeScreen implements Screen {
    private MainActivity activity;
    private LinearLayout root;
    private GuildRailView railView;
    private FrameLayout channelContainer;
    private ChannelListScreen channelScreen;
    private DmListScreen dmScreen;
    private final List<Guild> guilds = new ArrayList<>();
    private String selectedGuildId;

    public HomeScreen(MainActivity activity) {
        this.activity = activity;
    }

    @Override
    public View onCreateView(MainActivity activity, ViewGroup parent) {
        this.activity = activity;
        root = new LinearLayout(activity);
        root.setOrientation(LinearLayout.HORIZONTAL);

        railView =
                new GuildRailView(
                        activity,
                        guilds,
                        g -> {
                            if (!g.id.equals(selectedGuildId)) {
                                selectedGuildId = g.id;
                                railView.setSelectedGuild(g.id);
                                openChannelList(g);
                            }
                        },
                        v -> {
                            selectedGuildId = null;
                            railView.setSelectedGuild(null);
                            openDms();
                        });
        root.addView(railView);

        channelContainer = new FrameLayout(activity);
        root.addView(channelContainer, new LinearLayout.LayoutParams(0, -1, 1f));

        loadGuilds();
        return root;
    }

    private void loadGuilds() {
        List<Guild> cached = activity.guildCache.get("me");
        if (cached != null) {
            guilds.addAll(cached);
            restoreOrSelectFirst();
        } else {
            activity.apiClient.fetchGuilds(
                    new a.b.api.ApiClient.Callback<List<Guild>>() {
                        @Override
                        public void onSuccess(List<Guild> result) {
                            if (activity == null) return;
                            activity.guildCache.put("me", result);
                            guilds.clear();
                            guilds.addAll(result);
                            restoreOrSelectFirst();
                        }

                        @Override
                        public void onError(String message) {}
                    });
        }
    }

    private void restoreOrSelectFirst() {
        String lastGuild = activity.messageCache.getLastGuildId();
        String lastChannel = activity.messageCache.getLastChannelId();

        if (lastChannel != null) {
            if (lastGuild == null) {
                selectedGuildId = null;
                railView.setSelectedGuild(null);
                openDms();
                activity.navigate(new MessageListScreen(null, lastChannel));
                return;
            } else {
                for (Guild g : guilds) {
                    if (g.id.equals(lastGuild)) {
                        selectedGuildId = g.id;
                        railView.setSelectedGuild(g.id);
                        openChannelList(g);
                        activity.navigate(new MessageListScreen(lastGuild, lastChannel));
                        return;
                    }
                }
            }
        }

        if (!guilds.isEmpty() && channelScreen == null && dmScreen == null) {
            selectedGuildId = guilds.get(0).id;
            railView.setSelectedGuild(selectedGuildId);
            openChannelList(guilds.get(0));
        } else if (guilds.isEmpty() && dmScreen == null) {
            openDms();
        }
    }

    private void openChannelList(Guild guild) {
        cleanupMiddlePane();
        channelScreen = new ChannelListScreen(guild, guild.owner);
        channelContainer.addView(channelScreen.onCreateView(activity, channelContainer));
        channelScreen.onResume();
    }

    private void openDms() {
        cleanupMiddlePane();
        dmScreen = new DmListScreen();
        channelContainer.addView(dmScreen.onCreateView(activity, channelContainer));
        dmScreen.onResume();
    }

    private void cleanupMiddlePane() {
        if (channelScreen != null) {
            channelScreen.onPause();
            channelScreen.onDestroy();
            channelScreen = null;
        }
        if (dmScreen != null) {
            dmScreen.onPause();
            dmScreen.onDestroy();
            dmScreen = null;
        }
        channelContainer.removeAllViews();
    }

    @Override
    public View getView() {
        return root;
    }

    @Override
    public void onResume() {
        if (railView != null) railView.onResume();
        if (channelScreen != null) channelScreen.onResume();
        if (dmScreen != null) dmScreen.onResume();
    }

    @Override
    public void onPause() {
        if (railView != null) railView.onPause();
        if (channelScreen != null) channelScreen.onPause();
        if (dmScreen != null) dmScreen.onPause();
    }

    @Override
    public void onDestroy() {
        if (railView != null) railView.onPause();
        cleanupMiddlePane();
        activity = null;
    }

    @Override
    public void onActivityResult(int requestCode, int resultCode, Intent data) {}
}
