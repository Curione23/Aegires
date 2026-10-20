package a.b.screen;

import android.content.Context;
import android.graphics.*;
import android.graphics.drawable.*;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.text.*;
import android.text.format.DateUtils;
import android.text.method.LinkMovementMethod;
import android.text.style.*;
import android.util.LruCache;
import android.view.*;
import android.view.inputmethod.InputMethodManager;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.widget.*;
import androidx.recyclerview.widget.DiffUtil;
import androidx.recyclerview.widget.RecyclerView;
import java.io.InputStream;
import java.io.File;
import java.net.HttpURLConnection;
import java.net.URL;
import java.text.DateFormat;
import java.text.SimpleDateFormat;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.regex.*;
import a.b.BuildConfig;
import a.b.MainActivity;
import a.b.api.ApiClient;
import a.b.model.*;
import a.b.util.*;
import static android.widget.LinearLayout.HORIZONTAL;
import static android.widget.LinearLayout.VERTICAL;

public class MessageAdapter extends RecyclerView.Adapter<MessageAdapter.MessageViewHolder> {
    private static final int GROUP_GAP_MS = 7 * 60 * 1000;
    private static final int CACHE_MAX = 300;
    private static final int MATCH = -1, WRAP = -2;
    private static final String USER_AGENT = "Discord-Android/347012";
    private static final String CDN = "https://cdn.discordapp.com/";
    private static final int MENTION_FG = 0xFF5865F2, MENTION_BG = 0x4D5865F2;
    private static final int EVERYONE_FG = 0xFFFEE75C, EVERYONE_BG = 0x4DFEE75C;
    private static final int PANEL = 0xFF2B2D31, EMBED_BG = 0xFF101010;
    private static final int TEXT = 0xFFDBDEE1, MUTED = 0xFF949BA4, LINK_FG = 0xFF00A8FC;

    private static final Pattern CUSTOM_EMOJI = Pattern.compile("<a?:\\w+:(\\d+)>");
    private static final Pattern USER_MENTION = Pattern.compile("<@!?(\\d+)>");
    private static final Pattern ROLE_MENTION = Pattern.compile("<@&(\\d+)>");
    private static final Pattern CHANNEL_MENTION = Pattern.compile("<#(\\d+)>");
    private static final Pattern EVERYONE_HERE = Pattern.compile("(?<![\\w@])@(everyone|here)\\b");
    private static final Pattern TIMESTAMP = Pattern.compile("<t:(-?\\d{1,12})(?::([a-zA-Z]))?>");
    private static final Pattern ATTACHMENT_ID = Pattern.compile("/attachments/[^/]+/([^/]+)/");
    private static final Pattern FENCE_LANG = Pattern.compile("[\\w+#.-]*\\n");
    private static final Pattern HEADING = Pattern.compile("^(#{1,3}) (.+)$", Pattern.MULTILINE);
    private static final Pattern LINK = Pattern.compile("\\[([^\\]]+)\\]\\(([^)]+)\\)");
    private static final Pattern BOLD = Pattern.compile("\\*\\*(.+?)\\*\\*");
    private static final Pattern UNDERLINE = Pattern.compile("__(.+?)__");
    private static final Pattern ITALIC_STAR = Pattern.compile("\\*(.+?)\\*");
    private static final Pattern ITALIC_UNDERSCORE = Pattern.compile("(?<!\\w)_(.+?)_(?!\\w)");
    private static final Pattern STRIKE = Pattern.compile("~~(.+?)~~");
    private static final Pattern SPOILER = Pattern.compile("\\|\\|(.+?)\\|\\|");
    private static final Pattern QUOTE = Pattern.compile("^> .*$", Pattern.MULTILINE);
    private static final Pattern BARE_URL = Pattern.compile("https?://[^\\s<>]*[^\\s<>.,;:!?'\")\\]]");

    private static final String STYLES = "tTdDfF";
    private static final String[] DATE_PATTERNS = {
        "HH:mm", "HH:mm:ss", "dd/MM/yyyy", "dd MMMM yyyy",
        "dd MMMM yyyy HH:mm", "EEEE, dd MMMM yyyy HH:mm"
    };

    private interface Text { CharSequence of(MatchResult m); }
    private interface Styler { void apply(SpannableStringBuilder sb, int start, int end, MatchResult m); }

    public interface MessageLongPressListener {
        void onMessageLongPress(Message msg, View anchor);
    }

    MainActivity activity;
    List<Message> messages;
    private List<Message> displayList = new ArrayList<>();
    ImageLoader imageLoader;
    final String guildId;
    final float density;
    final int emojiSize, bodyIndent, avatarSize;

    public final Map<String, CharSequence> parsedContentCache = Collections.synchronizedMap(
        new LinkedHashMap<String, CharSequence>(64, 0.75f, true) {
            @Override protected boolean removeEldestEntry(Map.Entry<String, CharSequence> eldest) {
                return size() > CACHE_MAX;
            }
        });

    public final LruCache<String, Bitmap> emojiCache = new LruCache<>(100);
    final ConcurrentHashMap<String, Set<String>> emojiMessageMap = new ConcurrentHashMap<>();
    
    public static final LruCache<String, Bitmap> encryptedBitmapCache = new LruCache<String, Bitmap>(computeSafeCacheBytes()) {
        @Override protected int sizeOf(String key, Bitmap value) { return value.getByteCount(); }
    };
    
    public static final LruCache<String, int[]> mediaSizeCache = new LruCache<>(200);
    public static final ConcurrentHashMap<String, int[]> messageDimensionCache = new ConcurrentHashMap<>();

    private static int computeSafeCacheBytes() {
        long target = Runtime.getRuntime().maxMemory() / 4;
        long min = 4 * 1024 * 1024;
        long max = 32 * 1024 * 1024;
        return (int) Math.max(min, Math.min(max, target));
    }

    final Set<String> loadingEmojis = syncSet();
    final Set<String> pendingUserFetches = syncSet();
    final Set<String> failedUserFetches = syncSet();
    final Set<String> pendingChannelFetches = syncSet();
    final Set<String> failedChannelFetches = syncSet();
    final ExecutorService emojiExecutor = Executors.newFixedThreadPool(4);
    private final Handler ui = new Handler(Looper.getMainLooper());
    private MessageLongPressListener longPressListener;

    public MessageAdapter(MainActivity activity, List<Message> messages, ImageLoader loader, String guildId) {
        this.activity = activity;
        this.messages = messages == null ? new ArrayList<>() : new ArrayList<>(messages);
        this.displayList = new ArrayList<>(this.messages);
        this.imageLoader = loader;
        this.guildId = guildId;
        density = activity.getResources().getDisplayMetrics().density;
        avatarSize = dp(40);
        emojiSize = dp(22);
        bodyIndent = dp(52);
        if (activity != null) {
            final Context ctx = activity.getApplicationContext();
            emojiExecutor.execute(() -> cleanDiskCache(ctx));
        }
    }

    public void setLongPressListener(MessageLongPressListener l) { longPressListener = l; }

    @Override public int getItemCount() { return displayList.size(); }
    @Override public MessageViewHolder onCreateViewHolder(ViewGroup parent, int viewType) { return new MessageViewHolder(); }

    @Override
    public void onBindViewHolder(MessageViewHolder h, int pos) {
        if (activity == null || pos < 0 || pos >= displayList.size()) return;
        Message msg = displayList.get(pos);
        if (msg == null) return;
        h.msg = msg;
        if (msg.author != null && msg.author.id != null && msg.roleIds != null && !msg.roleIds.isEmpty() && activity.userRoleCache != null) {
            activity.userRoleCache.put(msg.author.id, msg.roleIds);
        }
        h.bind(msg, pos);
    }

    public void submitList(List<Message> newList) {
        final List<Message> oldList = displayList;
        final List<Message> snapshot = newList == null ? new ArrayList<>() : new ArrayList<>(newList);
        parsedContentCache.clear();
        DiffUtil.DiffResult result = DiffUtil.calculateDiff(new DiffUtil.Callback() {
            @Override public int getOldListSize() { return oldList.size(); }
            @Override public int getNewListSize() { return snapshot.size(); }
            @Override public boolean areItemsTheSame(int oldPosition, int newPosition) {
                Message oldMessage = oldList.get(oldPosition);
                Message newMessage = snapshot.get(newPosition);
                return oldMessage.id != null && oldMessage.id.equals(newMessage.id);
            }
            @Override public boolean areContentsTheSame(int oldPosition, int newPosition) { return false; }
        });
        displayList = snapshot;
        messages = new ArrayList<>(snapshot);
        result.dispatchUpdatesTo(this);
    }

    public void invalidateMessage(String id) {
        if (id == null) return;
        parsedContentCache.remove(id);
        for (int i = 0; i < displayList.size(); i++) {
            Message message = displayList.get(i);
            if (message != null && id.equals(message.id)) notifyItemChanged(i);
        }
    }

    public void refreshUser(String userId) {
        if (userId == null) return;
        for (int i = 0; i < displayList.size(); i++) {
            Message message = displayList.get(i);
            if (message != null && message.author != null && userId.equals(message.author.id)) {
                if (message.id != null) parsedContentCache.remove(message.id);
                notifyItemChanged(i);
            }
        }
    }

    public void notifyChangedCompat() { submitList(new ArrayList<>(messages)); }

    private void bindHeader(MessageViewHolder h, Message msg, boolean show) {
        h.header.setVisibility(show ? View.VISIBLE : View.GONE);
        if (!show) return;
        h.name.setText(displayName(msg));
        h.name.setTextColor(getAuthorColor(msg));
        h.time.setText(TimeUtils.relativeTime(msg.timestamp));
        String uid = msg.author != null ? msg.author.id : null;
        h.avatar.setImageDrawable(null);
        if (activity != null && imageLoader != null && !BuildConfig.SKIP_IMAGES && msg.author != null && isReal(msg.author.avatar)) {
            imageLoader.load(CDN + "avatars/" + uid + "/" + msg.author.avatar + ".png?size=128", h.avatar);
        }
        View.OnClickListener open = v -> openProfile(uid);
        h.avatar.setOnClickListener(open);
        h.name.setOnClickListener(open);
    }

    private int getAuthorColor(Message msg) {
        if (activity == null) return Color.WHITE;
        List<String> roles = msg.roleIds;
        if ((roles == null || roles.isEmpty()) && msg.author != null && msg.author.id != null && activity.userRoleCache != null) {
            roles = activity.userRoleCache.get(msg.author.id);
        }
        if (roles == null || roles.isEmpty() || activity.roleCache == null) return Color.WHITE;
        Role highest = null;
        for (String roleId : roles) {
            Role r = activity.roleCache.get(roleId);
            if (r != null && (highest == null || r.position > highest.position)) highest = r;
        }
        return highest != null && highest.color != 0 ? highest.color | 0xFF000000 : Color.WHITE;
    }

    private void bindReactions(MessageViewHolder h, Message msg, ViewGroup parent) {
        h.reactions.removeAllViews();
        boolean any = msg.reactions != null && !msg.reactions.isEmpty();
        h.reactions.setVisibility(any ? View.VISIBLE : View.GONE);
        if (!any) return;
        int maxWidth = parent.getWidth() - dp(20) - bodyIndent;
        LinearLayout row = null;
        int used = 0;
        for (Reaction r : msg.reactions) {
            if (r == null) continue;
            View chip = reactionChip(r);
            chip.measure(View.MeasureSpec.UNSPECIFIED, View.MeasureSpec.UNSPECIFIED);
            int w = chip.getMeasuredWidth() + dp(4);
            if (row == null || (maxWidth > 0 && used + w > maxWidth)) {
                row = linear(HORIZONTAL);
                row.setLayoutParams(lp(WRAP, WRAP, 0, 0, 0, dp(4)));
                h.reactions.addView(row);
                used = 0;
            }
            row.addView(chip);
            used += w;
        }
    }

    private View reactionChip(Reaction r) {
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(r.me ? 0x335865F2 : PANEL);
        if (r.me) bg.setStroke(dp(1), MENTION_FG);
        bg.setCornerRadius(dp(6));
        LinearLayout chip = linear(HORIZONTAL);
        chip.setPadding(dp(6), dp(4), dp(6), dp(4));
        chip.setLayoutParams(lp(WRAP, WRAP, 0, 0, dp(4), 0));
        chip.setBackground(bg);
        chip.addView(tv(r.emoji, Color.WHITE, 14));
        chip.addView(tv(" " + r.count, MUTED, 14));
        return chip;
    }

    private static boolean sameGroup(Message prev, Message cur) {
        if (prev.author == null || cur.author == null || cur.replyToMessageId != null) return false;
        long difference = cur.timestamp - prev.timestamp;
        return Objects.equals(prev.author.id, cur.author.id) && difference >= 0 && difference <= GROUP_GAP_MS;
    }

    private String displayName(Message message) {
        String name = isReal(message.memberNick) ? message.memberNick :
                      message.author == null ? "System" :
                      isReal(message.author.globalName) ? message.author.globalName : message.author.username;
        if (activity != null && message.author != null && message.author.id != null && name != null && activity.userNameCache != null) {
            activity.userNameCache.put(message.author.id, name);
        }
        return name;
    }

    private static CharSequence replyPreview(Message m) {
        SpannableStringBuilder sb = new SpannableStringBuilder("\u21B3 ");
        sb.append(m.replyToAuthorName != null ? m.replyToAuthorName : "unknown");
        sb.setSpan(new StyleSpan(Typeface.BOLD), 2, sb.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        sb.append("  ");
        if (m.replyToMissing) {
            sb.append("Original message was deleted");
        } else {
            String t = m.replyToContent == null ? "" : m.replyToContent.replace('\n', ' ');
            if (t.length() > 80) t = t.substring(0, 80) + "\u2026";
            sb.append(t.trim().isEmpty() ? "(no text)" : t);
        }
        return sb;
    }

    private void openProfile(String uid) {
        if (uid != null && activity != null) activity.navigate(new ProfileScreen(uid, guildId));
    }

    private CharSequence render(String raw, Message msg) {
        if (raw == null || raw.isEmpty()) return "";
        SpannableStringBuilder sb = parseMarkdown(raw);
        if (msg.id != null) emojis(sb, msg.id);
        mentions(sb, msg);
        timestamps(sb);
        return sb;
    }

    private static SpannableStringBuilder parseMarkdown(String raw) {
        SpannableStringBuilder out = new SpannableStringBuilder();
        int len = raw.length(), i = 0;
        while (i < len) {
            int tick = raw.indexOf('`', i);
            if (tick != i) {
                int end = tick < 0 ? len : tick;
                out.append(inline(raw.substring(i, end)));
                i = end;
            } else if (raw.startsWith("```", i)) {
                int start = i + 3;
                int end = raw.indexOf("```", start);
                if (end < 0) end = len;
                Matcher lang = FENCE_LANG.matcher(raw);
                lang.region(start, end);
                if (lang.lookingAt()) start = lang.end();
                if (out.length() > 0 && out.charAt(out.length() - 1) != '\n') out.append('\n');
                code(out, raw.substring(start, end));
                i = Math.min(len, end + 3);
                if (i < len && raw.charAt(i) != '\n') out.append('\n');
            } else {
                int end = raw.indexOf('`', i + 1);
                if (end < 0) {
                    out.append('`');
                    i++;
                } else {
                    code(out, raw.substring(i + 1, end));
                    i = end + 1;
                }
            }
        }
        return out;
    }

    private static void code(SpannableStringBuilder out, String code) {
        if (code.isEmpty()) return;
        int s = out.length();
        out.append(code);
        span(out, s, out.length(), new BackgroundColorSpan(PANEL), new TypefaceSpan("monospace"), new ForegroundColorSpan(TEXT));
    }

    private static SpannableStringBuilder inline(String text) {
        SpannableStringBuilder sb = new SpannableStringBuilder(text);
        wrap(sb, HEADING, 2, (b, s, e, m) -> span(b, s, e, new RelativeSizeSpan(m.group(1).length() == 1 ? 1.6f : 1.3f), new StyleSpan(Typeface.BOLD)));
        wrap(sb, LINK, 1, (b, s, e, m) -> span(b, s, e, new URLSpan(m.group(2))));
        wrap(sb, BOLD, 1, (b, s, e, m) -> span(b, s, e, new StyleSpan(Typeface.BOLD)));
        wrap(sb, UNDERLINE, 1, (b, s, e, m) -> span(b, s, e, new UnderlineSpan()));
        wrap(sb, ITALIC_STAR, 1, (b, s, e, m) -> span(b, s, e, new StyleSpan(Typeface.ITALIC)));
        wrap(sb, ITALIC_UNDERSCORE, 1, (b, s, e, m) -> span(b, s, e, new StyleSpan(Typeface.ITALIC)));
        wrap(sb, STRIKE, 1, (b, s, e, m) -> span(b, s, e, new StrikethroughSpan()));
        wrap(sb, SPOILER, 1, (b, s, e, m) -> span(b, s, e, new SpoilerSpan()));
        wrap(sb, QUOTE, 0, (b, s, e, m) -> span(b, s, e, new ForegroundColorSpan(MUTED)));
        wrap(sb, BARE_URL, 0, (b, s, e, m) -> {
            if (b.getSpans(s, e, URLSpan.class).length == 0) span(b, s, e, new URLSpan(m.group()));
        });
        return sb;
    }

    private void emojis(SpannableStringBuilder sb, String msgId) {
        sub(sb, CUSTOM_EMOJI, m -> " ", (b, s, e, m) -> {
            String id = m.group(1);
            Bitmap bmp = emojiCache.get(id);
            Drawable d;
            if (bmp != null) {
                d = new BitmapDrawable(activity.getResources(), bmp);
            } else {
                d = new ColorDrawable(Color.TRANSPARENT);
                Set<String> waiting = emojiMessageMap.get(id);
                if (waiting == null) {
                    Set<String> fresh = syncSet();
                    waiting = emojiMessageMap.putIfAbsent(id, fresh);
                    if (waiting == null) waiting = fresh;
                }
                waiting.add(msgId);
                boolean animated = m.group().charAt(1) == 'a';
                loadEmoji(id, CDN + "emojis/" + id + (animated ? ".gif" : ".png") + "?size=128");
            }
            d.setBounds(0, 0, emojiSize, emojiSize);
            span(b, s, e, new ImageSpan(d, ImageSpan.ALIGN_BOTTOM));
        });
    }

    private void mentions(SpannableStringBuilder sb, Message msg) {
        Styler chip = (b, s, e, m) -> span(b, s, e, new ForegroundColorSpan(MENTION_FG), new BackgroundColorSpan(MENTION_BG));
        sub(sb, CHANNEL_MENTION, m -> "#" + resolveChannel(m.group(1)), chip);
        sub(sb, ROLE_MENTION, m -> {
            Role role = activity != null && activity.roleCache != null ? activity.roleCache.get(m.group(1)) : null;
            return "@" + (role != null && isReal(role.name) ? role.name : "role");
        }, chip);
        sub(sb, USER_MENTION, m -> "@" + resolveUser(m.group(1), msg), (b, s, e, m) -> {
            chip.apply(b, s, e, m);
            String id = m.group(1);
            span(b, s, e, new Click(() -> openProfile(id)));
        });
        wrap(sb, EVERYONE_HERE, 0, (b, s, e, m) -> span(b, s, e, new ForegroundColorSpan(EVERYONE_FG), new BackgroundColorSpan(EVERYONE_BG), new StyleSpan(Typeface.BOLD)));
    }

    private String resolveUser(String id, Message msg) {
        String name = msg.mentionedUsers != null ? msg.mentionedUsers.get(id) : null;
        if (!isReal(name) && activity != null && activity.userNameCache != null) name = activity.userNameCache.get(id);
        if (isReal(name)) return name;
        requestUserName(id);
        return "unknown";
    }

    private String resolveChannel(String id) {
        String name = activity != null && activity.channelNameCache != null ? activity.channelNameCache.get(id) : null;
        if (isReal(name)) return name;
        requestChannelName(id);
        return "unknown-channel";
    }

    private void requestUserName(String id) {
        if (!claim(id, pendingUserFetches, failedUserFetches)) return;
        activity.apiClient.fetchUserProfile(id, new ApiClient.Callback<DiscordUser>() {
            @Override public void onSuccess(DiscordUser u) {
                String name = u == null ? null : isReal(u.globalName) ? u.globalName : u.username;
                if (isReal(name) && activity != null && activity.userNameCache != null) activity.userNameCache.put(id, name);
                settle(id, pendingUserFetches, failedUserFetches, isReal(name), true);
            }
            @Override public void onError(String err) { settle(id, pendingUserFetches, failedUserFetches, false, true); }
        });
    }

    private void requestChannelName(String id) {
        if (!claim(id, pendingChannelFetches, failedChannelFetches)) return;
        activity.apiClient.fetchChannelName(id, new ApiClient.Callback<String>() {
            @Override public void onSuccess(String name) {
                if (isReal(name) && activity != null && activity.channelNameCache != null) activity.channelNameCache.put(id, name);
                settle(id, pendingChannelFetches, failedChannelFetches, isReal(name), false);
            }
            @Override public void onError(String err) { settle(id, pendingChannelFetches, failedChannelFetches, false, false); }
        });
    }

    private boolean claim(String id, Set<String> pending, Set<String> failed) {
        return id != null && activity != null && activity.apiClient != null && !failed.contains(id) && pending.add(id);
    }

    private void settle(String id, Set<String> pending, Set<String> failed, boolean ok, boolean isUser) {
        pending.remove(id);
        if (!ok) {
            failed.add(id);
            return;
        }
        ui.post(() -> invalidateMentions(id, isUser));
    }

    private void invalidateMentions(String id, boolean isUser) {
        String prefix = isUser ? "<@" : "<#";
        for (int i = 0; i < displayList.size(); i++) {
            Message m = displayList.get(i);
            if (m == null || m.id == null) continue;
            boolean affected = referencesId(m.content, id, prefix, isUser);
            if (!affected && m.embeds != null) {
                for (Embed e : m.embeds) {
                    if (e == null) continue;
                    if (referencesId(e.title, id, prefix, isUser) || referencesId(e.description, id, prefix, isUser)) {
                        affected = true;
                        break;
                    }
                    if (e.fields != null) {
                        for (EmbedField f : e.fields) {
                            if (f != null && referencesId(f.value, id, prefix, isUser)) {
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
    }

    private static boolean referencesId(String text, String id, String prefix, boolean isUser) {
        if (text == null) return false;
        return text.contains(prefix + id) || (isUser && text.contains("<@!" + id));
    }

    private void loadEmoji(String id, String url) {
        if (!loadingEmojis.add(id)) return;
        emojiExecutor.execute(() -> {
            HttpURLConnection conn = null;
            try {
                conn = (HttpURLConnection) new URL(url).openConnection();
                conn.setConnectTimeout(8000);
                conn.setReadTimeout(8000);
                conn.setRequestProperty("User-Agent", USER_AGENT);
                if (conn.getResponseCode() != 200) return;
                try (InputStream in = conn.getInputStream()) {
                    Bitmap raw = BitmapFactory.decodeStream(in);
                    if (raw == null) return;
                    emojiCache.put(id, Bitmap.createScaledBitmap(raw, emojiSize, emojiSize, true));
                }
                Set<String> affected = emojiMessageMap.remove(id);
                if (affected != null) {
                    List<String> ids;
                    synchronized (affected) {
                        ids = affected.isEmpty() ? Collections.emptyList() : new ArrayList<>(affected);
                    }
                    if (!ids.isEmpty()) ui.post(() -> invalidateEmoji(ids));
                }
            } catch (Exception ignored) {
            } finally {
                if (conn != null) conn.disconnect();
                loadingEmojis.remove(id);
            }
        });
    }

    private void invalidateEmoji(List<String> msgIds) {
        for (String mid : msgIds) {
            parsedContentCache.remove(mid);
            for (int i = 0; i < displayList.size(); i++) {
                Message m = displayList.get(i);
                if (m != null && mid.equals(m.id)) notifyItemChanged(i);
            }
        }
    }

    private View attachmentView(Message.Attachment a, Message msg, ViewGroup parent) {
        if (isEncryptedAttachment(a)) {
            if (activity == null || activity.e2eeKey == null) {
                TextView t = tv("\uD83D\uDD12 " + a.filename + " (encrypted - key missing)", MUTED, 14);
                t.setPadding(dp(4), dp(4), dp(4), dp(4));
                return t;
            }
            return encryptedAttachmentView(a, msg, parent);
        }
        boolean image = a.type != null && a.type.startsWith("image");
        boolean video = a.type != null && a.type.startsWith("video");
        if (!image && !video) {
            TextView t = tv("\uD83D\uDCCE " + a.filename, LINK_FG, 14);
            t.setPadding(dp(4), dp(4), dp(4), dp(4));
            return t;
        }
        return media(image ? a.url : null, false, a.url, video, mediaSize(parent, msg, a.width, a.height, false));
    }

    private View encryptedAttachmentView(Message.Attachment a, Message msg, ViewGroup parent) {
        if (!isImageFilename(a.filename)) {
            TextView t = tv("\uD83D\uDD12 " + a.filename, MUTED, 14);
            t.setPadding(dp(4), dp(4), dp(4), dp(4));
            return t;
        }
        int w = a.width, h = a.height;
        if (msg != null && msg.id != null && messageDimensionCache.containsKey(msg.id)) {
            int[] dim = messageDimensionCache.get(msg.id);
            if (dim != null) { w = dim[0]; h = dim[1]; }
        }
        int[] initialSize = mediaSize(parent, msg, w, h, false);
        FrameLayout frame = new FrameLayout(activity);
        frame.setLayoutParams(lp(initialSize[0], initialSize[1], 0, dp(4), 0, dp(4)));
        frame.setBackgroundColor(EMBED_BG);
        ImageView iv = new ImageView(activity);
        iv.setScaleType(ImageView.ScaleType.FIT_CENTER);
        frame.addView(iv, new FrameLayout.LayoutParams(MATCH, MATCH));
        TextView status = tv("\uD83D\uDD12 Decrypting\u2026", MUTED, 12);
        status.setGravity(Gravity.CENTER);
        frame.addView(status, new FrameLayout.LayoutParams(MATCH, MATCH, Gravity.CENTER));
        final Bitmap[] decoded = new Bitmap[1];
        loadEncryptedBitmap(a.url, parent, (bmp, realW, realH) -> {
            if (bmp == null) {
                status.setText("\u26A0 Decrypt failed");
                return;
            }
            decoded[0] = bmp;
            iv.setImageBitmap(bmp);
            status.setVisibility(View.GONE);
        });
        frame.setOnClickListener(v -> {
            if (decoded[0] != null) showEncryptedFullscreen(decoded[0]);
        });
        return frame;
    }

    private static String getCacheKey(String url) {
        if (url == null) return "";
        int queryIndex = url.indexOf('?');
        return queryIndex > 0 ? url.substring(0, queryIndex) : url;
    }

    private File getDiskCacheFile(Context context, String url) {
        if (context == null || url == null) return null;
        String safeName = Math.abs(getCacheKey(url).hashCode()) + ".enc_cache";
        return new File(context.getCacheDir(), safeName);
    }

    private static void cleanDiskCache(Context context) {
        if (context == null) return;
        try {
            File cacheDir = context.getCacheDir();
            File[] files = cacheDir.listFiles((dir, name) -> name.endsWith(".enc_cache"));
            if (files == null || files.length == 0) return;
            long now = System.currentTimeMillis();
            long maxAgeMillis = 1L * 24 * 60 * 60 * 1000;
            long maxTotalBytes = 100 * 1024 * 1024;
            long totalBytes = 0;
            Arrays.sort(files, Comparator.comparingLong(File::lastModified));
            for (File file : files) {
                if (now - file.lastModified() > maxAgeMillis) file.delete();
                else totalBytes += file.length();
            }
            if (totalBytes > maxTotalBytes) {
                for (File file : files) {
                    if (!file.exists()) continue;
                    long len = file.length();
                    if (file.delete()) {
                        totalBytes -= len;
                        if (totalBytes <= maxTotalBytes) break;
                    }
                }
            }
        } catch (Exception ignored) {}
    }

    private interface EncryptedBitmapCallback {
        void onResult(Bitmap bitmap, int realWidth, int realHeight);
    }

    private void loadEncryptedBitmap(String url, ViewGroup parent, EncryptedBitmapCallback onDone) {
        if (url == null || activity == null || activity.e2eeKey == null) {
            onDone.onResult(null, 0, 0);
            return;
        }
        String cacheKey = getCacheKey(url);
        Bitmap cached = encryptedBitmapCache.get(cacheKey);
        if (cached != null) {
            onDone.onResult(cached, cached.getWidth(), cached.getHeight());
            return;
        }
        int pw = parent != null ? parent.getWidth() : 0;
        if (pw <= 0 && activity != null) pw = activity.getResources().getDisplayMetrics().widthPixels;
        final int targetW = Math.max(pw > 0 ? pw - dp(112) : dp(248), dp(120));
        final int targetH = dp(400);
        final Context ctx = activity.getApplicationContext();
        final javax.crypto.SecretKey key = activity.e2eeKey;
        
        emojiExecutor.execute(() -> {
            Bitmap result = null;
            int imgW = 0, imgH = 0;
            byte[] plain = null;
            File diskFile = getDiskCacheFile(ctx, url);
            if (diskFile != null && diskFile.exists()) {
                try { plain = java.nio.file.Files.readAllBytes(diskFile.toPath()); } catch (Exception ignored) {}
            }
            if (plain == null) {
                HttpURLConnection conn = null;
                try {
                    conn = (HttpURLConnection) new URL(url).openConnection();
                    conn.setConnectTimeout(8000);
                    conn.setReadTimeout(15000);
                    conn.setRequestProperty("User-Agent", USER_AGENT);
                    if (conn.getResponseCode() == 200) {
                        byte[] raw;
                        try (InputStream in = conn.getInputStream()) {
                            java.io.ByteArrayOutputStream buf = new java.io.ByteArrayOutputStream(16384);
                            byte[] b = new byte[8192];
                            int n;
                            while ((n = in.read(b)) > 0) buf.write(b, 0, n);
                            raw = buf.toByteArray();
                        }
                        plain = Crypto.decryptBytes(raw, key);
                        if (plain != null && diskFile != null) {
                            try { java.nio.file.Files.write(diskFile.toPath(), plain); } catch (Exception ignored) {}
                        }
                    }
                } catch (Exception ignored) {
                } finally {
                    if (conn != null) conn.disconnect();
                }
            }
            if (plain != null) {
                try {
                    BitmapFactory.Options bounds = new BitmapFactory.Options();
                    bounds.inJustDecodeBounds = true;
                    BitmapFactory.decodeByteArray(plain, 0, plain.length, bounds);
                    imgW = bounds.outWidth;
                    imgH = bounds.outHeight;
                    BitmapFactory.Options opts = new BitmapFactory.Options();
                    opts.inSampleSize = computeSampleSize(imgW, imgH, targetW, targetH);
                    result = BitmapFactory.decodeByteArray(plain, 0, plain.length, opts);
                    if (result != null) encryptedBitmapCache.put(cacheKey, result);
                } catch (Exception ignored) {}
            }
            final Bitmap r = result;
            final int w = imgW, h = imgH;
            ui.post(() -> onDone.onResult(r, w, h));
        });
    }

    private static int computeSampleSize(int inW, int inH, int reqW, int reqH) {
        if (inW <= 0 || inH <= 0 || reqW <= 0 || reqH <= 0) return 1;
        if (inW <= reqW && inH <= reqH) return 1;
        int sample = 1;
        int halfW = inW / 2;
        int halfH = inH / 2;
        while (halfW / sample >= reqW && halfH / sample >= reqH) sample *= 2;
        return sample;
    }

    private void showEncryptedFullscreen(Bitmap bmp) {
        if (activity == null) return;
        hideKeyboard();
        java.io.ByteArrayOutputStream bos = new java.io.ByteArrayOutputStream();
        bmp.compress(Bitmap.CompressFormat.PNG, 100, bos);
        String b64 = android.util.Base64.encodeToString(bos.toByteArray(), android.util.Base64.NO_WRAP);
        WebView web = new WebView(activity);
        WebSettings s = web.getSettings();
        s.setJavaScriptEnabled(false);
        s.setAllowFileAccess(false);
        s.setAllowContentAccess(false);
        s.setMediaPlaybackRequiresUserGesture(false);
        web.loadDataWithBaseURL(null, "<html><body style='margin:0;height:100vh;background:#000;display:flex;" +
                "justify-content:center;align-items:center'><img src='data:image/png;base64," + b64 + "' style='max-width:100%'></body></html>", "text/html", "UTF-8", null);
        activity.showFullscreen(web);
    }

    private View embedView(Embed e, Message msg, ViewGroup parent) {
        boolean title = isReal(e.title), desc = isReal(e.description);
        boolean fields = e.fields != null && !e.fields.isEmpty();
        boolean footer = isReal(e.footer);
        if (!title && !desc && !fields && !footer) return embedMedia(e, parent);
        LinearLayout box = linear(VERTICAL);
        box.setPadding(dp(12), dp(8), dp(12), dp(8));
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(EMBED_BG);
        bg.setCornerRadius(dp(4));
        box.setBackground(bg);
        box.setClipToOutline(true);
        if (title) {
            TextView t = tv(render(e.title, msg), LINK_FG, 14);
            t.setTypeface(null, Typeface.BOLD);
            box.addView(t);
        }
        if (desc) {
            TextView d = tv(render(e.description, msg), TEXT, 14);
            if (title) d.setPadding(0, dp(4), 0, 0);
            box.addView(d);
        }
        if (fields) {
            for (EmbedField f : e.fields) {
                if (f == null) continue;
                TextView name = tv(f.name, 0xFFB9BBBE, 13);
                name.setTypeface(null, Typeface.BOLD);
                name.setPadding(0, dp(8), 0, 0);
                box.addView(name);
                box.addView(tv(render(f.value, msg), TEXT, 13));
            }
        }
        View media = embedMedia(e, parent);
        if (media != null) box.addView(media);
        if (footer) {
            TextView f = tv(e.footer, MUTED, 12);
            f.setPadding(0, dp(6), 0, 0);
            box.addView(f);
        }
        View bar = new View(activity);
        bar.setBackgroundColor(e.color | 0xFF000000);
        LinearLayout wrapper = linear(HORIZONTAL);
        wrapper.setPadding(0, dp(4), 0, dp(4));
        wrapper.addView(bar, lp(dp(4), MATCH, 0, 0, dp(12), 0));
        wrapper.addView(box, new LinearLayout.LayoutParams(0, WRAP, 1f));
        return wrapper;
    }

    private View embedMedia(Embed e, ViewGroup parent) {
        boolean video = isReal(e.videoUrl);
        String preview;
        int w, h;
        if (video) {
            boolean own = e.videoWidth > 0 && e.videoHeight > 0;
            w = own ? e.videoWidth : e.thumbnailWidth;
            h = own ? e.videoHeight : e.thumbnailHeight;
            preview = isReal(e.thumbnailUrl) ? e.thumbnailUrl : isReal(e.imageUrl) ? e.imageUrl : null;
        } else if (isReal(e.imageUrl)) {
            preview = e.imageUrl;
            w = e.imageWidth;
            h = e.imageHeight;
        } else if (isReal(e.thumbnailUrl)) {
            preview = e.thumbnailUrl;
            w = e.thumbnailWidth;
            h = e.thumbnailHeight;
        } else {
            return null;
        }
        return media(preview, video, video ? e.videoUrl : preview, video, mediaSize(parent, null, w, h, video));
    }

    private static boolean isEncryptedAttachment(Message.Attachment a) {
        return a != null && a.filename != null && (a.filename.contains(".enc.") || a.filename.endsWith(".enc"));
    }

    private static boolean isImageFilename(String name) {
        if (name == null) return false;
        String n = name.toLowerCase();
        return n.endsWith(".png") || n.endsWith(".jpg") || n.endsWith(".jpeg") || n.endsWith(".gif") || n.endsWith(".webp") || n.endsWith(".bmp");
    }

    private View media(String preview, boolean crop, String openUrl, boolean video, int[] size) {
        boolean show = preview != null && imageLoader != null && !BuildConfig.SKIP_IMAGES;
        FrameLayout frame = new FrameLayout(activity);
        frame.setLayoutParams(lp(size[0], size[1], 0, dp(4), 0, dp(4)));
        frame.setBackgroundColor(show ? Color.BLACK : EMBED_BG);
        if (show) {
            ImageView iv = new ImageView(activity);
            iv.setScaleType(crop ? ImageView.ScaleType.CENTER_CROP : ImageView.ScaleType.FIT_CENTER);
            imageLoader.load(preview, iv);
            frame.addView(iv, new FrameLayout.LayoutParams(MATCH, MATCH));
        } else if (preview != null) {
            frame.addView(tv("\uD83D\uDDBC", 0xAAFFFFFF, 14), new FrameLayout.LayoutParams(WRAP, WRAP, Gravity.CENTER));
        }
        if (video) {
            frame.addView(tv("\u25B6", Color.WHITE, 30), new FrameLayout.LayoutParams(WRAP, WRAP, Gravity.CENTER));
        }
        frame.setOnClickListener(v -> showFullscreen(openUrl, video));
        return frame;
    }

    private int[] mediaSize(ViewGroup parent, Message msg, int w, int h, boolean fill) {
        if (msg != null && msg.id != null && (w <= 0 || h <= 0)) {
            int[] dim = messageDimensionCache.get(msg.id);
            if (dim != null) { w = dim[0]; h = dim[1]; }
        }
        int pw = parent != null ? parent.getWidth() : 0;
        if (pw <= 0 && activity != null) pw = activity.getResources().getDisplayMetrics().widthPixels;
        
        int avail = Math.max(pw > 0 ? pw - dp(112) : dp(248), dp(120));
        int maxH = dp(400);
        boolean known = w > 0 && h > 0;
        String cacheKey = w + "_" + h + "_" + fill + "_" + avail;
        int[] cached = mediaSizeCache.get(cacheKey);
        if (cached != null) return cached;
        
        int[] result;
        if (fill) {
            if (!known) { w = 16; h = 9; }
            result = new int[]{avail, Math.max(dp(80), Math.min(maxH, (int) ((long) avail * h / w)))};
        } else if (!known) {
            result = new int[]{avail, Math.min(avail * 9 / 16, maxH)};
        } else {
            float scale = Math.min((float) avail / w, (float) maxH / h);
            if (scale > 2f) scale = 2f;
            result = new int[]{Math.max(1, Math.round(w * scale)), Math.max(1, Math.round(h * scale))};
        }
        mediaSizeCache.put(cacheKey, result);
        return result;
    }

    private void hideKeyboard() {
        if (activity == null) return;
        try {
            InputMethodManager imm = (InputMethodManager) activity.getSystemService(Context.INPUT_METHOD_SERVICE);
            if (imm == null) return;
            View focus = activity.getCurrentFocus();
            IBinder token = focus != null ? focus.getWindowToken() : null;
            if (token == null && activity.getWindow() != null && activity.getWindow().getDecorView() != null) {
                token = activity.getWindow().getDecorView().getWindowToken();
            }
            if (token != null) imm.hideSoftInputFromWindow(token, 0);
            if (focus != null) focus.clearFocus();
        } catch (Exception ignored) {}
    }

    private void showFullscreen(String url, boolean video) {
        if (activity == null || !isHttpsUrl(url)) return;
        hideKeyboard();
        WebView web = new WebView(activity);
        WebSettings settings = web.getSettings();
        settings.setJavaScriptEnabled(false);
        settings.setAllowFileAccess(false);
        settings.setAllowContentAccess(false);
        settings.setMediaPlaybackRequiresUserGesture(false);
        String src = escapeHtmlAttribute(url);
        String media = video ? "<video controls autoplay playsinline style='width:100%'><source src=\"" + src + "\" type=\"video/mp4\"></video>"
                             : "<img src=\"" + src + "\" style='max-width:100%'>";
        web.loadDataWithBaseURL(null, "<html><body style='margin:0;height:100vh;background:#000;display:flex;" +
                "justify-content:center;align-items:center'>" + media + "</body></html>", "text/html", "UTF-8", null);
        activity.showFullscreen(web);
    }

    private static boolean isHttpsUrl(String value) {
        if (!isReal(value)) return false;
        try {
            URL url = new URL(value);
            return "https".equalsIgnoreCase(url.getProtocol()) && url.getHost() != null && !url.getHost().isEmpty();
        } catch (Exception ignored) {
            return false;
        }
    }

    private static String escapeHtmlAttribute(String value) {
        return value.replace("&", "&amp;").replace("\"", "&quot;").replace("<", "&lt;").replace(">", "&gt;").replace("'", "&#39;");
    }

    private static boolean duplicatesAttachment(Embed e, List<Message.Attachment> atts) {
        if (atts == null || atts.isEmpty()) return false;
        Set<String> ids = new HashSet<>();
        for (Message.Attachment a : atts) {
            if (a == null) continue;
            ids.add(attachmentId(a.url));
            ids.add(attachmentId(a.proxyUrl));
        }
        return refs(e.thumbnailUrl, e.thumbnailProxyUrl, ids) || refs(e.imageUrl, e.imageProxyUrl, ids) || refs(e.videoUrl, e.videoProxyUrl, ids);
    }

    private static boolean refs(String url, String proxyUrl, Set<String> ids) {
        if (url != null && url.startsWith("attachment://")) return true;
        String a = attachmentId(url), b = attachmentId(proxyUrl);
        return (a != null && ids.contains(a)) || (b != null && ids.contains(b));
    }

    private static String attachmentId(String url) {
        if (url == null) return null;
        Matcher m = ATTACHMENT_ID.matcher(url);
        return m.find() ? m.group(1) : null;
    }

    private int dp(int v) { return (int) (v * density); }
    private static boolean isReal(String s) { return s != null && !s.isEmpty() && !"null".equals(s); }
    private static Set<String> syncSet() { return Collections.synchronizedSet(new HashSet<>()); }

    private static LinearLayout.LayoutParams lp(int w, int h, int l, int t, int r, int b) {
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(w, h);
        p.setMargins(l, t, r, b);
        return p;
    }

    private LinearLayout linear(int orientation) {
        LinearLayout l = new LinearLayout(activity);
        l.setOrientation(orientation);
        return l;
    }

    private TextView tv(CharSequence text, int color, float sp) {
        TextView view = new TextView(activity);
        view.setText(text);
        view.setTextColor(color);
        view.setTextSize(sp);
        view.setLinkTextColor(LINK_FG);
        view.setMovementMethod(LinkMovementMethod.getInstance());
        return view;
    }

    public final class MessageViewHolder extends RecyclerView.ViewHolder implements View.OnLongClickListener {
        final LinearLayout root, header, body, attachments, embeds, reactions;
        final ImageView avatar;
        final TextView name, time, reply, content;
        Message msg;

        MessageViewHolder() {
            super(new LinearLayout(activity));
            root = (LinearLayout) itemView;
            root.setLayoutParams(new RecyclerView.LayoutParams(RecyclerView.LayoutParams.MATCH_PARENT, RecyclerView.LayoutParams.WRAP_CONTENT));
            root.setOrientation(VERTICAL);
            root.setBackgroundColor(Color.BLACK);
            root.setPadding(dp(10), dp(2), dp(10), dp(2));
            root.setOnLongClickListener(this);

            header = new LinearLayout(activity);
            header.setOrientation(HORIZONTAL);
            header.setGravity(Gravity.CENTER_VERTICAL);
            header.setPadding(0, dp(12), 0, dp(2));
            
            avatar = new ImageView(activity);
            GradientDrawable round = new GradientDrawable();
            round.setCornerRadius(avatarSize / 2f);
            avatar.setScaleType(ImageView.ScaleType.CENTER_CROP);
            avatar.setBackground(round);
            avatar.setClipToOutline(true);

            name = new TextView(activity);
            name.setTextColor(Color.WHITE);
            name.setTypeface(null, Typeface.BOLD);
            name.setTextSize(15);
            name.setSingleLine();
            name.setEllipsize(TextUtils.TruncateAt.END);
            name.setGravity(Gravity.START);
            int screenWidth = activity.getResources().getDisplayMetrics().widthPixels;
            name.setMaxWidth((int) (screenWidth * 0.7f));

            time = new TextView(activity);
            time.setTextColor(MUTED);
            time.setTextSize(12);
            time.setGravity(Gravity.START);

            View spacer = new View(activity);
            spacer.setLayoutParams(new LinearLayout.LayoutParams(0, 1, 1f));
            LinearLayout.LayoutParams nameLp = new LinearLayout.LayoutParams(WRAP, WRAP);
            nameLp.setMarginEnd(dp(8));
            name.setLayoutParams(nameLp);

            header.addView(avatar, lp(avatarSize, avatarSize, 0, 0, dp(12), 0));
            header.addView(name);
            header.addView(time);
            header.addView(spacer);

            reply = new TextView(activity);
            reply.setTextColor(MUTED);
            reply.setTextSize(12);
            reply.setSingleLine();
            reply.setEllipsize(TextUtils.TruncateAt.END);
            reply.setPadding(0, dp(2), 0, dp(2));
            reply.setVisibility(View.GONE);

            content = new TextView(activity);
            content.setTextColor(TEXT);
            content.setTextSize(15);
            content.setLinkTextColor(LINK_FG);
            content.setMovementMethod(LinkMovementMethod.getInstance());
            content.setLineSpacing(0, 1.2f);
            content.setOnLongClickListener(this);

            attachments = new LinearLayout(activity);
            attachments.setOrientation(VERTICAL);
            embeds = new LinearLayout(activity);
            embeds.setOrientation(VERTICAL);
            reactions = new LinearLayout(activity);
            reactions.setOrientation(VERTICAL);
            reactions.setPadding(0, dp(4), 0, 0);

            body = new LinearLayout(activity);
            body.setOrientation(VERTICAL);
            body.setPaddingRelative(bodyIndent, 0, 0, 0);
            body.addView(reply);
            body.addView(content);
            body.addView(attachments);
            body.addView(embeds);
            body.addView(reactions);

            root.addView(header);
            root.addView(body);
        }

        void bind(Message msg, int pos) {
            boolean showHeader = pos == 0 || !sameGroup(displayList.get(pos - 1), msg);
            bindHeader(this, msg, showHeader);
            CharSequence text;
            if (msg.id == null) {
                text = render(msg.content, msg);
            } else {
                text = parsedContentCache.get(msg.id);
                if (text == null) {
                    text = render(msg.content, msg);
                    parsedContentCache.put(msg.id, text);
                }
            }
            content.setText(text);
            content.setVisibility(text.length() > 0 ? View.VISIBLE : View.GONE);
            boolean isReply = msg.replyToMessageId != null;
            reply.setVisibility(isReply ? View.VISIBLE : View.GONE);
            if (isReply) reply.setText(replyPreview(msg));
            
            attachments.removeAllViews();
            if (msg.attachments != null) {
                for (Message.Attachment a : msg.attachments) {
                    if (a != null) add(attachments, attachmentView(a, msg, root));
                }
            }
            
            embeds.removeAllViews();
            if (msg.embeds != null) {
                for (Embed e : msg.embeds) {
                    if (e != null && !duplicatesAttachment(e, msg.attachments)) add(embeds, embedView(e, msg, root));
                }
            }
            bindReactions(this, msg, root);
        }

        private void add(LinearLayout box, View child) {
            if (child == null) return;
            child.setOnLongClickListener(this);
            box.addView(child);
        }

        @Override public boolean onLongClick(View v) {
            if (longPressListener == null || msg == null) return false;
            longPressListener.onMessageLongPress(msg, v);
            return true;
        }
    }

    private static final class Click extends ClickableSpan {
        private final Runnable action;
        Click(Runnable action) { this.action = action; }
        @Override public void onClick(View v) { action.run(); }
        @Override public void updateDrawState(TextPaint ds) { ds.setUnderlineText(false); }
    }

    private static final class SpoilerSpan extends ClickableSpan {
        private boolean revealed;
        @Override public void onClick(View v) {
            revealed = !revealed;
            v.invalidate();
        }
        @Override public void updateDrawState(TextPaint ds) {
            ds.bgColor = revealed ? 0x33000000 : Color.BLACK;
            ds.setColor(revealed ? TEXT : Color.BLACK);
        }
    }

    private static Long timestampMillis(String seconds) {
        try { return Math.multiplyExact(Long.parseLong(seconds), 1000L); }
        catch (NumberFormatException | ArithmeticException ex) { return null; }
    }

    private void timestamps(SpannableStringBuilder sb) {
        sub(sb, TIMESTAMP, match -> {
            Long millis = timestampMillis(match.group(1));
            return millis == null ? match.group() : formatTimestamp(millis, match.group(2));
        }, (b, s, e, match) -> {
            Long millis = timestampMillis(match.group(1));
            if (millis == null) return;
            span(b, s, e, new ForegroundColorSpan(LINK_FG), new Click(() -> {
                if (activity != null) {
                    activity.toast(DateFormat.getDateTimeInstance(DateFormat.LONG, DateFormat.LONG).format(new Date(millis)));
                }
            }));
        });
    }

    private static String formatTimestamp(long millis, String style) {
        if ("R".equals(style)) {
            return DateUtils.getRelativeTimeSpanString(millis, System.currentTimeMillis(), DateUtils.SECOND_IN_MILLIS).toString();
        }
        int index = style == null ? -1 : STYLES.indexOf(style);
        return new SimpleDateFormat(DATE_PATTERNS[index < 0 ? 4 : index], Locale.US).format(new Date(millis));
    }

    private static void span(Spannable sb, int start, int end, Object... spans) {
        for (Object o : spans) sb.setSpan(o, start, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
    }

    private static List<MatchResult> matches(SpannableStringBuilder sb, Pattern p) {
        LinkedList<MatchResult> out = new LinkedList<>();
        Matcher m = p.matcher(sb.toString());
        while (m.find()) {
            if (!isCode(sb, m.start(), m.end())) out.addFirst(m.toMatchResult());
        }
        return out;
    }

    private static boolean isCode(Spanned sb, int start, int end) {
        for (TypefaceSpan t : sb.getSpans(start, end, TypefaceSpan.class)) {
            if ("monospace".equals(t.getFamily())) return true;
        }
        return false;
    }

    private static void wrap(SpannableStringBuilder sb, Pattern p, int g, Styler styler) {
        for (MatchResult m : matches(sb, p)) {
            int len = m.end(g) - m.start(g);
            sb.delete(m.end(g), m.end());
            sb.delete(m.start(), m.start(g));
            styler.apply(sb, m.start(), m.start() + len, m);
        }
    }

    private static void sub(SpannableStringBuilder sb, Pattern p, Text text, Styler styler) {
        for (MatchResult m : matches(sb, p)) {
            CharSequence r = text.of(m);
            int start = m.start(), end = m.end();
            Object[] spans = sb.getSpans(start, end, Object.class);
            int[] flags = new int[spans.length];
            for (int i = 0; i < spans.length; i++) {
                boolean inside = sb.getSpanStart(spans[i]) >= start && sb.getSpanEnd(spans[i]) <= end;
                flags[i] = inside ? sb.getSpanFlags(spans[i]) : -1;
            }
            sb.replace(start, end, r);
            for (int i = 0; i < spans.length; i++) {
                if (flags[i] >= 0) sb.setSpan(spans[i], start, start + r.length(), flags[i]);
            }
            styler.apply(sb, start, start + r.length(), m);
        }
    }
}