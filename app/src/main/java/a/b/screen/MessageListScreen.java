package a.b.screen;

import android.content.Intent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.recyclerview.widget.ConcatAdapter;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import a.b.MainActivity;
import a.b.api.ApiClient;
import a.b.model.Message;
import a.b.util.Crypto;
import a.b.util.ForcedKeys;
import a.b.util.Ui;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

public class MessageListScreen implements Screen {
    private static final int MATCH = -1;

    MainActivity activity;
    public final String guildId;
    public final String channelId;
    public MessageAdapter adapter;
    public List<Message> messages = new ArrayList<>();
    final Set<String> messageIds = new HashSet<>();
    float density;
    private View view;
    RecyclerView messageList;
    LinearLayoutManager layoutManager;
    MessageListScreen.LoadingAdapter loadingAdapter;
    TextView channelNameTv;
    boolean forced;
    javax.crypto.SecretKey forcedKey;

    private MessageComposerView composer;
    private MessageFeedController feed;
    private TypingIndicator typing;
    private MessageActionsController actions;
    private AttachmentPicker picker;

    private boolean sending;

    public MessageListScreen(String guildId, String channelId) {
        this.guildId = guildId;
        this.channelId = channelId;
    }

    MessageComposerView composer() {
        return composer;
    }

    MessageFeedController feed() {
        return feed;
    }

    TypingIndicator typing() {
        return typing;
    }

    MessageActionsController actions() {
        return actions;
    }

    AttachmentPicker picker() {
        return picker;
    }

    javax.crypto.SecretKey key() {
        if (forced) return forcedKey;
        return activity != null ? activity.e2eeKey : null;
    }

    Crypto.Preset preset() {
        return forced ? Crypto.Preset.AES128 : activity.e2eePreset;
    }

    boolean encOn() {
        return forced || (activity != null && activity.e2eeEnabled);
    }

    int dp(int v) {
        return (int) (v * density);
    }

    @Override
    public View onCreateView(MainActivity activity, ViewGroup parent) {
        this.activity = activity;
        forced = ForcedKeys.isForced(channelId);
        forcedKey = forced ? ForcedKeys.keyFor(channelId) : null;
        density = activity.getResources().getDisplayMetrics().density;

        composer = new MessageComposerView(this);
        typing = new TypingIndicator(this);
        actions = new MessageActionsController(this);
        picker = new AttachmentPicker(this);

        LinearLayout root = new LinearLayout(activity);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(Ui.BG);
        root.addView(buildHeader());
        root.addView(buildList(), new LinearLayout.LayoutParams(MATCH, 0, 1f));
        root.addView(typing.getView());
        root.addView(composer.buildReplyBar());
        root.addView(composer.buildAttachBar());
        root.addView(composer.buildPill());

        androidx.core.view.ViewCompat.setOnApplyWindowInsetsListener(
                root,
                (v, windowInsets) -> {
                    androidx.core.graphics.Insets ime =
                            windowInsets.getInsets(
                                    androidx.core.view.WindowInsetsCompat.Type.ime());
                    if (ime.bottom > 0 && adapter != null && adapter.getItemCount() > 0) {
                        messageList.postDelayed(this::scrollToBottom, 100);
                    }
                    return windowInsets;
                });

        adapter.keyOverride = forcedKey;
        adapter.channelId = channelId;
        activity.mentions.markChannelRead(channelId);
        if (forced && forcedKey == null) {
            ForcedKeys.prepare(
                    channelId,
                    k -> {
                        if (this.activity == null) return;
                        forcedKey = k;
                        adapter.keyOverride = k;
                        if (k == null) this.activity.toast("Couldn't derive this channel's key");
                        subscribeAndLoad();
                    });
        } else {
            subscribeAndLoad();
        }
        return view = root;
    }

    private View buildHeader() {
        Ui.AppBar bar =
                new Ui.AppBar(activity, "Loading\u2026", Ui.BG, activity::goBack, Ui.I_MENU);
        channelNameTv = bar.title;
        channelNameTv.setTextSize(16);
        TextView hash = text(guildId == null ? "@" : "#", Ui.MUTED, 22);
        hash.setTypeface(null, android.graphics.Typeface.BOLD);
        hash.setPadding(dp(4), 0, dp(6), 0);
        bar.bar.addView(hash, 1);

        bar.actions.addView(composer.buildE2eePill());
        composer.updateE2eePillState();
        if (guildId != null) {
            bar.addAction(
                    Ui.I_PEOPLE,
                    v -> {
                        if (activity != null)
                            activity.navigate(new MemberListScreen(guildId, channelId));
                    });
        }
        return bar.view;
    }

    private View buildList() {
        layoutManager = new LinearLayoutManager(activity);
        layoutManager.setStackFromEnd(true);
        messageList = new RecyclerView(activity);
        messageList.setLayoutManager(layoutManager);
        messageList.setBackgroundColor(Ui.BG);
        messageList.setItemAnimator(null);
        adapter = new MessageAdapter(activity, messages, activity.imageLoader, guildId);
        adapter.setLongPressListener(actions::onMessageLongPress);

        android.widget.ProgressBar spinner = new android.widget.ProgressBar(activity);
        spinner.setIndeterminate(true);
        loadingAdapter = new LoadingAdapter(spinner, dp(48));
        feed =
                new MessageFeedController(
                        this, adapter, messageList, layoutManager, messages, messageIds);
        ConcatAdapter concat = new ConcatAdapter(loadingAdapter, adapter);
        messageList.setAdapter(concat);

        messageList.addOnScrollListener(
                new RecyclerView.OnScrollListener() {
                    @Override
                    public void onScrolled(RecyclerView rv, int dx, int dy) {
                        if (layoutManager.findFirstVisibleItemPosition() <= 1)
                            feed.loadOlderMessages();
                    }
                });
        return messageList;
    }

    private TextView text(CharSequence s, int color, float sp) {
        TextView t = new TextView(activity);
        t.setText(s);
        t.setTextColor(color);
        t.setTextSize(sp);
        return t;
    }

    private void subscribeAndLoad() {
        subscribe();
        if (activity.apiClient == null) return;

        List<Message> cached = activity.messageCache.getMessages(channelId);
        if (cached == null || cached.isEmpty()) {
            cached = activity.messageCache.loadLastChannelMessages(channelId);
        }

        boolean hasCache = cached != null && !cached.isEmpty();

        if (hasCache) {
            messages.clear();
            messageIds.clear();
            for (Message m : cached) {
                messages.add(m);
                messageIds.add(m.id);
            }
            adapter.submitList(new ArrayList<>(messages));
            scrollToBottom();
        }

        activity.messageCache.saveLastChannelState(guildId, channelId, messages);

        String cachedName = activity.channelNameCache.get(channelId);
        if (guildId == null) {
            loadDmTitle();
        } else if (cachedName != null) {
            channelNameTv.setText(cachedName);
        } else {
            activity.apiClient.fetchChannelName(
                    channelId,
                    new ApiClient.Callback<String>() {
                        @Override
                        public void onSuccess(String name) {
                            if (activity == null || channelNameTv == null) return;
                            if (name != null) {
                                activity.channelNameCache.put(channelId, name);
                                channelNameTv.setText(name);
                            } else {
                                channelNameTv.setText("unknown-channel");
                            }
                        }

                        @Override
                        public void onError(String err) {
                            if (channelNameTv != null) channelNameTv.setText("unknown-channel");
                        }
                    });
        }

        if (guildId != null) {
            activity.apiClient.fetchRoles(
                    guildId,
                    new ApiClient.Callback<java.util.Map<String, a.b.model.Role>>() {
                        @Override
                        public void onSuccess(java.util.Map<String, a.b.model.Role> roles) {
                            if (activity != null && roles != null) activity.roleCache.putAll(roles);
                            if (feed != null) feed.loadNewMessages();
                        }

                        @Override
                        public void onError(String err) {
                            if (feed != null) feed.loadNewMessages();
                        }
                    });
        } else {
            if (feed != null) feed.loadNewMessages();
        }
    }

    private void loadDmTitle() {
        activity.apiClient.fetchChannelMeta(
                channelId,
                new ApiClient.Callback<JSONObject>() {
                    @Override
                    public void onSuccess(JSONObject meta) {
                        if (activity == null || channelNameTv == null) return;
                        channelNameTv.setText(
                                a.b.model.DmChannel.fromJson(meta).displayName(activity.selfId()));
                    }

                    @Override
                    public void onError(String err) {
                        if (channelNameTv != null) channelNameTv.setText("Direct message");
                    }
                });
    }

    public void onGatewayReady() {
        if (activity == null || feed == null) return;
        subscribe();
        feed.loadNewMessages();
    }

    private void subscribe() {
        if (activity.gateway != null) activity.gateway.subscribeToGuild(guildId, channelId);
    }

    boolean atBottom() {
        if (messageList == null) return true;
        RecyclerView.Adapter<?> a = messageList.getAdapter();
        if (a == null || a.getItemCount() == 0) return true;
        return layoutManager.findLastVisibleItemPosition() >= a.getItemCount() - 2;
    }

    void scrollToBottom() {
        if (messageList == null) return;
        RecyclerView.Adapter<?> a = messageList.getAdapter();
        if (a == null || a.getItemCount() == 0) return;
        final int last = a.getItemCount() - 1;
        messageList.scrollToPosition(last);
        messageList.post(
                () -> {
                    if (messageList == null) return;
                    RecyclerView.Adapter<?> aa = messageList.getAdapter();
                    if (aa == null) return;
                    int lastAfter = aa.getItemCount() - 1;
                    if (lastAfter < 0) return;
                    messageList.scrollToPosition(lastAfter);
                    if (messageList.canScrollVertically(1)) messageList.scrollBy(0, 1_000_000);
                });
    }

    void scrollToMessage(String id) {
        if (id == null || feed == null || messageList == null || loadingAdapter == null) return;
        int i = feed.indexOf(id);
        if (i >= 0) messageList.scrollToPosition(loadingAdapter.getItemCount() + i);
    }

    public void onGatewayEvent(String type, JSONObject d) {
        if (type == null || d == null || activity == null) return;
        if (feed == null || adapter == null || typing == null) return;
        if (channelId == null || !channelId.equals(d.optString("channel_id", null))) return;
        try {
            switch (type) {
                case "MESSAGE_CREATE":
                    gatewayCreate(Message.fromJson(d));
                    break;
                case "MESSAGE_UPDATE":
                    gatewayUpdate(Message.fromJson(d));
                    break;
                case "MESSAGE_DELETE":
                    gatewayDelete(d.optString("id", null));
                    break;
                case "MESSAGE_DELETE_BULK":
                    JSONArray ids = d.optJSONArray("ids");
                    if (ids != null) {
                        for (int i = 0; i < ids.length(); i++) gatewayDelete(ids.optString(i));
                    }
                    break;
                case "TYPING_START":
                    typing.handleTypingStart(d);
                    break;
            }
        } catch (Exception ignored) {
        }
    }

    private void gatewayCreate(Message m) {
        if (messageList == null || adapter == null) return;
        boolean stick = atBottom();
        if (typing != null && m != null && m.author != null && m.author.id != null) {
            typing.removeUser(m.author.id);
        }
        if (feed.merge(java.util.Collections.singletonList(m), false)) {
            if (activity != null && activity.messageCache != null) {
                activity.messageCache.appendMessage(channelId, m);
                activity.messageCache.saveLastChannelState(guildId, channelId, messages);
            }
            messageList.post(
                    () -> {
                        if (messageList == null || adapter == null) return;
                        adapter.submitList(new ArrayList<>(messages));
                        if (stick) scrollToBottom();
                    });
        }
    }

    private void gatewayUpdate(Message incoming) {
        if (incoming == null || messageList == null || adapter == null) return;
        MessageCryptoHelper.decryptMessageInPlace(this, incoming);

        if (activity != null && activity.messageCache != null) {
            activity.messageCache.updateMessage(channelId, incoming);
        }

        int i = incoming.id == null ? -1 : feed.indexOf(incoming.id);
        if (i < 0) return;
        messages.get(i).mergeFrom(incoming);
        messageList.post(
                () -> {
                    if (adapter == null) return;
                    adapter.invalidateMessage(incoming.id);
                });
    }

    void gatewayDelete(String id) {
        if (id == null || messageList == null || adapter == null || feed == null) return;

        if (activity != null && activity.messageCache != null) {
            activity.messageCache.deleteMessage(channelId, id);
        }

        if (!feed.removeMessage(id)) return;
        messageList.post(
                () -> {
                    if (adapter == null) return;
                    adapter.parsedContentCache.remove(id);
                    adapter.submitList(new ArrayList<>(messages));
                });
    }

    void sendMessage() {
        if (sending || activity == null || activity.apiClient == null) return;
        if (forced && forcedKey == null) {
            activity.toast("Encryption key not ready yet");
            return;
        }
        String text = composer.text().trim();
        boolean hasFile = composer.getEditingId() == null && composer.getPendingFileUri() != null;
        if (text.isEmpty() && !hasFile) return;
        sending = true;
        composer.clearText();
        ApiClient api = activity.apiClient;
        if (composer.getEditingId() != null) {
            String outText = MessageCryptoHelper.prepareOutgoingText(this, text);
            if (outText == null) {
                restoreInput(text);
                return;
            }
            String id = composer.getEditingId();
            api.editMessage(
                    channelId,
                    id,
                    outText,
                    done(
                            "Edit failed: ",
                            text,
                            () -> {
                                int i = feed.indexOf(id);
                                if (i >= 0) {
                                    messages.get(i).content = text;
                                    adapter.invalidateMessage(id);
                                }
                                composer.cancelEdit();
                            }));
        } else if (hasFile) {
            Runnable clearAttachment = composer::clearAttachment;
            String payloadText = text;
            if (encOn()
                    && key() != null
                    && composer.getPendingImageWidth() > 0
                    && composer.getPendingImageHeight() > 0) {
                payloadText =
                        "[sz:"
                                + composer.getPendingImageWidth()
                                + ","
                                + composer.getPendingImageHeight()
                                + "]"
                                + text;
            }
            String outText = MessageCryptoHelper.prepareOutgoingText(this, payloadText);
            if (outText == null) {
                restoreInput(text);
                return;
            }
            if (encOn() && key() != null) {
                String encName = encryptedFileName(composer.getPendingFileName());
                api.sendEncryptedFile(
                        activity,
                        channelId,
                        outText,
                        composer.getPendingFileUri(),
                        encName,
                        key(),
                        preset(),
                        activity.selfId(),
                        done("Upload failed: ", text, clearAttachment));
            } else {
                api.sendFile(
                        activity,
                        channelId,
                        outText,
                        composer.getPendingFileUri(),
                        composer.getPendingMimeType(),
                        composer.getPendingFileName(),
                        done("Upload failed: ", text, clearAttachment));
            }
        } else {
            String outText = MessageCryptoHelper.prepareOutgoingText(this, text);
            if (outText == null) {
                restoreInput(text);
                return;
            }
            if (composer.getReplyToId() != null) {
                String replyId = composer.getReplyToId();
                api.sendMessage(
                        channelId,
                        outText,
                        replyId,
                        guildId,
                        done(
                                "Send failed: ",
                                text,
                                () -> {
                                    if (replyId.equals(composer.getReplyToId()))
                                        composer.cancelReply();
                                }));
            } else {
                api.sendMessage(channelId, outText, done("Send failed: ", text, null));
            }
        }
    }

    private void restoreInput(String text) {
        sending = false;
        composer.restoreText(text);
    }

    private static String encryptedFileName(String original) {
        if (original == null || original.isEmpty()) return "file.enc";
        int dot = original.lastIndexOf('.');
        if (dot > 0 && dot < original.length() - 1) {
            return original.substring(0, dot) + ".enc" + original.substring(dot);
        }
        return original + ".enc";
    }

    private ApiClient.Callback<Void> done(String failPrefix, String text, Runnable onSuccess) {
        return new ApiClient.Callback<Void>() {
            @Override
            public void onSuccess(Void v) {
                sending = false;
                if (onSuccess != null) onSuccess.run();
            }

            @Override
            public void onError(String err) {
                sending = false;
                if (activity == null) return;
                activity.toast(failPrefix + err);
                if (composer != null && composer.text().length() == 0) {
                    composer.restoreText(text);
                }
            }
        };
    }

    @Override
    public void onActivityResult(int requestCode, int resultCode, Intent data) {
        if (picker != null) picker.onActivityResult(requestCode, resultCode, data);
    }

    @Override
    public void onResume() {
        if (composer != null) composer.updateE2eePillState();
    }

    @Override
    public void onPause() {}

    @Override
    public void onDestroy() {
        if (typing != null) typing.release();
        if (composer != null) composer.release();
        activity = null;
        if (adapter != null) adapter.activity = null;
        composer = null;
        feed = null;
        typing = null;
        actions = null;
        picker = null;
        loadingAdapter = null;
        layoutManager = null;
        messageList = null;
        channelNameTv = null;
        forcedKey = null;
        view = null;
    }

    public String getChannelId() {
        return channelId;
    }

    @Override
    public View getView() {
        return view;
    }

    static final class LoadingAdapter extends RecyclerView.Adapter<LoadingAdapter.Holder> {
        private final android.widget.ProgressBar spinner;
        private final int height;
        private boolean visible;

        LoadingAdapter(android.widget.ProgressBar spinner, int height) {
            this.spinner = spinner;
            this.height = height;
            spinner.setIndeterminate(true);
            spinner.getIndeterminateDrawable()
                    .setColorFilter(
                            android.graphics.Color.WHITE, android.graphics.PorterDuff.Mode.SRC_IN);
        }

        void setVisible(boolean visible) {
            if (this.visible != visible) {
                this.visible = visible;
                if (visible) notifyItemInserted(0);
                else notifyItemRemoved(0);
            }
        }

        @Override
        public Holder onCreateViewHolder(ViewGroup parent, int viewType) {
            if (spinner.getParent() != null) ((ViewGroup) spinner.getParent()).removeView(spinner);
            FrameLayout container = new FrameLayout(parent.getContext());
            container.setLayoutParams(new RecyclerView.LayoutParams(MATCH, height));
            int size = height / 2;
            FrameLayout.LayoutParams lp =
                    new FrameLayout.LayoutParams(size, size, android.view.Gravity.CENTER);
            container.addView(spinner, lp);
            return new Holder(container);
        }

        @Override
        public void onBindViewHolder(Holder h, int pos) {}

        @Override
        public int getItemCount() {
            return visible ? 1 : 0;
        }

        static final class Holder extends RecyclerView.ViewHolder {
            Holder(View v) {
                super(v);
            }
        }
    }
}
