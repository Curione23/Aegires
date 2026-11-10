package a.b.screen;

import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.view.ViewGroup;
import android.webkit.WebView;

import androidx.recyclerview.widget.DiffUtil;
import androidx.recyclerview.widget.RecyclerView;

import a.b.MainActivity;
import a.b.model.Embed;
import a.b.model.Message;
import a.b.util.ImageLoader;
import a.b.util.MediaCache;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

public class MessageAdapter extends RecyclerView.Adapter<MessageViewHolder> {
    static final int MATCH = -1, WRAP = -2;
    static final int MENTION_FG = 0xFFC9CDFB, MENTION_BG = 0x4D5865F2;
    static final int EVERYONE_FG = 0xFFFEE75C, EVERYONE_BG = 0x4DFEE75C;
    static final int EMBED_BG = 0xFF2B2D31;
    static final int TEXT = 0xFFDBDEE1, MUTED = 0xFF949BA4, LINK_FG = 0xFF00A8FC;
    static final int BG = 0xFF313338;
    static final String CDN = "https://cdn.discordapp.com/";

    private static final int CACHE_MAX = 300;
    private static final int GROUP_GAP_MS = 7 * 60 * 1000;

    public interface MessageLongPressListener {
        void onMessageLongPress(Message msg, View anchor);
    }

    public javax.crypto.SecretKey keyOverride;
    public String channelId;
    public final Map<String, CharSequence> parsedContentCache =
            Collections.synchronizedMap(
                    new LinkedHashMap<String, CharSequence>(64, 0.75f, true) {
                        @Override
                        protected boolean removeEldestEntry(
                                Map.Entry<String, CharSequence> eldest) {
                            return size() > CACHE_MAX;
                        }
                    });
    MainActivity activity;
    List<Message> messages;
    List<Message> displayList;
    ImageLoader imageLoader;
    String guildId;
    float density;
    int emojiSize, jumboSize, bodyIndent, avatarSize;
    final Handler ui = new Handler(Looper.getMainLooper());
    MessageLongPressListener longPressListener;

    final EmojiRegistry emojiRegistry;
    final MentionRegistry mentionRegistry;
    final MessageMediaViewFactory mediaFactory;
    final DiscordMessageRenderer renderer;
    final AttachmentViewFactory attachmentFactory;
    final EmbedViewFactory embedFactory;
    final MessageLinkPreviewFactory linkPreviewFactory;
    final MessageBinder binder;

    public MessageAdapter(
            MainActivity activity, List<Message> messages, ImageLoader loader, String guildId) {
        this.activity = activity;
        this.messages = messages == null ? new ArrayList<>() : new ArrayList<>(messages);
        this.displayList = new ArrayList<>(this.messages);
        this.imageLoader = loader;
        this.guildId = guildId;
        density = activity.getResources().getDisplayMetrics().density;
        avatarSize = dp(40);
        emojiSize = dp(22);
        jumboSize = dp(48);
        bodyIndent = dp(52);
        if (activity != null) MediaCache.get(activity.getApplicationContext());

        emojiRegistry = new EmojiRegistry(this);
        mentionRegistry = new MentionRegistry(this);
        mediaFactory = new MessageMediaViewFactory(this);
        renderer = new DiscordMessageRenderer(this);
        attachmentFactory = new AttachmentViewFactory(this);
        embedFactory = new EmbedViewFactory(this);
        binder = new MessageBinder(this);
        linkPreviewFactory = new MessageLinkPreviewFactory(this);
    }

    public void setLongPressListener(MessageLongPressListener l) {
        longPressListener = l;
    }

    @Override
    public int getItemCount() {
        return displayList.size();
    }

    @Override
    public void onViewRecycled(MessageViewHolder holder) {
        ImageLoader.cancelTree(holder.itemView);
        super.onViewRecycled(holder);
    }

    @Override
    public MessageViewHolder onCreateViewHolder(ViewGroup parent, int viewType) {
        return new MessageViewHolder(this, parent.getContext());
    }

    @Override
    public void onBindViewHolder(MessageViewHolder h, int pos) {
        if (activity == null || pos < 0 || pos >= displayList.size()) return;
        Message msg = displayList.get(pos);
        if (msg == null) return;
        h.msg = msg;
        if (msg.author != null
                && msg.author.id != null
                && msg.roleIds != null
                && !msg.roleIds.isEmpty()
                && activity.userRoleCache != null) {
            activity.userRoleCache.put(msg.author.id, msg.roleIds);
        }
        h.bind(msg, pos);
    }

    public void submitList(List<Message> newList) {
        final List<Message> oldList = displayList;
        final List<Message> snapshot =
                newList == null ? new ArrayList<>() : new ArrayList<>(newList);
        DiffUtil.DiffResult result =
                DiffUtil.calculateDiff(
                        new DiffUtil.Callback() {
                            @Override
                            public int getOldListSize() {
                                return oldList.size();
                            }

                            @Override
                            public int getNewListSize() {
                                return snapshot.size();
                            }

                            @Override
                            public boolean areItemsTheSame(int oldPosition, int newPosition) {
                                Message oldMessage = oldList.get(oldPosition);
                                Message newMessage = snapshot.get(newPosition);
                                return oldMessage != null
                                        && newMessage != null
                                        && oldMessage.id != null
                                        && oldMessage.id.equals(newMessage.id);
                            }

                            @Override
                            public boolean areContentsTheSame(int oldPosition, int newPosition) {
                                return oldList.get(oldPosition) == snapshot.get(newPosition);
                            }
                        });
        displayList = snapshot;
        messages = new ArrayList<>(snapshot);
        result.dispatchUpdatesTo(this);
    }

    public void invalidateMessage(String id) {
        if (id == null) return;
        parsedContentCache.remove(id);
        linkPreviewFactory.invalidate(id);
        ui.post(
                () -> {
                    for (int i = 0; i < displayList.size(); i++) {
                        Message message = displayList.get(i);
                        if (message != null && id.equals(message.id)) notifyItemChanged(i);
                    }
                });
    }

    public void refreshUser(String userId) {
        if (userId == null) return;
        ui.post(
                () -> {
                    for (int i = 0; i < displayList.size(); i++) {
                        Message message = displayList.get(i);
                        if (message != null
                                && message.author != null
                                && userId.equals(message.author.id)) {
                            if (message.id != null) parsedContentCache.remove(message.id);
                            notifyItemChanged(i);
                        }
                    }
                });
    }

    public void notifyChangedCompat() {
        submitList(new ArrayList<>(messages));
    }

    int dp(int v) {
        return (int) (v * density);
    }

    public int emojiCacheSize() {
        return emojiRegistry.size();
    }

    javax.crypto.SecretKey key() {
        return keyOverride != null ? keyOverride : (activity != null ? activity.e2eeKey : null);
    }

    CharSequence render(String raw, Message msg) {
        return renderer.render(raw, msg);
    }

    CharSequence replyPreview(Message m) {
        return binder.replyPreview(m);
    }

    void bindHeader(MessageViewHolder h, Message msg, boolean show) {
        binder.bindHeader(h, msg, show);
    }

    boolean mentionsSelf(Message msg) {
        return binder.mentionsSelf(msg);
    }

    View attachmentView(Message.Attachment a, Message msg, ViewGroup parent) {
        return attachmentFactory.build(a, msg, parent);
    }

    View embedView(Embed e, Message msg, ViewGroup parent) {
        return embedFactory.build(e, msg, parent);
    }

    List<View> linkPreviewViews(Message msg, ViewGroup parent) {
        return linkPreviewFactory.build(msg, parent);
    }

    void hideKeyboard() {
        if (activity == null) return;
        try {
            android.view.inputmethod.InputMethodManager imm =
                    (android.view.inputmethod.InputMethodManager)
                            activity.getSystemService(android.content.Context.INPUT_METHOD_SERVICE);
            if (imm == null) return;
            View focus = activity.getCurrentFocus();
            android.os.IBinder token = focus != null ? focus.getWindowToken() : null;
            if (token == null
                    && activity.getWindow() != null
                    && activity.getWindow().getDecorView() != null) {
                token = activity.getWindow().getDecorView().getWindowToken();
            }
            if (token != null) imm.hideSoftInputFromWindow(token, 0);
            if (focus != null) focus.clearFocus();
        } catch (Exception ignored) {
        }
    }

    void showFullscreen(WebView web) {
        if (activity != null) activity.showFullscreen(web);
    }

    void invalidateEmojis(List<String> msgIds) {
        ui.post(
                () -> {
                    for (String mid : msgIds) {
                        parsedContentCache.remove(mid);
                        for (int i = 0; i < displayList.size(); i++) {
                            Message m = displayList.get(i);
                            if (m != null && mid.equals(m.id)) notifyItemChanged(i);
                        }
                    }
                });
    }

    void invalidateMentions(String id, boolean isUser) {
        ui.post(
                () -> {
                    String prefix = isUser ? "<@" : "<#";
                    for (int i = 0; i < displayList.size(); i++) {
                        Message m = displayList.get(i);
                        if (m == null || m.id == null) continue;
                        boolean affected = referencesId(m.content, id, prefix, isUser);
                        if (!affected && m.embeds != null) {
                            for (Embed e : m.embeds) {
                                if (e == null) continue;
                                if (referencesId(e.title, id, prefix, isUser)
                                        || referencesId(e.description, id, prefix, isUser)) {
                                    affected = true;
                                    break;
                                }
                                if (e.fields != null) {
                                    for (a.b.model.EmbedField f : e.fields) {
                                        if (f != null
                                                && referencesId(f.value, id, prefix, isUser)) {
                                            affected = true;
                                            break;
                                        }
                                    }
                                }
                                if (affected) break;
                            }
                        }
                        if (affected) {
                            parsedContentCache.remove(m.id);
                            notifyItemChanged(i);
                        }
                    }
                });
    }

    private static boolean referencesId(String text, String id, String prefix, boolean isUser) {
        if (text == null) return false;
        return text.contains(prefix + id) || (isUser && text.contains("<@!" + id));
    }

    static boolean sameGroup(Message prev, Message cur) {
        if (prev == null
                || cur == null
                || prev.author == null
                || cur.author == null
                || cur.replyToMessageId != null) return false;
        long difference = cur.timestamp - prev.timestamp;
        return Objects.equals(prev.author.id, cur.author.id)
                && difference >= 0
                && difference <= GROUP_GAP_MS;
    }
}
