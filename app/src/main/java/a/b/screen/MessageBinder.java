package a.b.screen;

import android.graphics.Color;
import android.graphics.Typeface;
import android.text.SpannableStringBuilder;
import android.text.Spanned;
import android.text.TextUtils;
import android.text.style.StyleSpan;
import android.widget.ImageView;
import android.view.View;
import android.view.ViewGroup;
import a.b.model.Message;
import a.b.model.Role;
import a.b.util.Crypto;
import a.b.util.TimeUtils;
import a.b.util.Ui;

/** Binds the header (avatar, name, timestamp) and small presentation helpers. */
final class MessageBinder {
    private final MessageAdapter adapter;

    MessageBinder(MessageAdapter adapter) {
        this.adapter = adapter;
    }

    void bindHeader(MessageViewHolder h, Message msg, boolean show) {
        h.header.setVisibility(show ? View.VISIBLE : View.GONE);
        h.avatar.setVisibility(show ? View.VISIBLE : View.INVISIBLE);
        ViewGroup.LayoutParams alp = h.avatar.getLayoutParams();
        if (alp instanceof ViewGroup.MarginLayoutParams) {
            ViewGroup.MarginLayoutParams mlp = (ViewGroup.MarginLayoutParams) alp;
            int wantH = show ? adapter.avatarSize : 0;
            int wantTop = show ? adapter.dp(2) : 0;
            if (mlp.height != wantH || mlp.topMargin != wantTop) {
                mlp.height = wantH;
                mlp.topMargin = wantTop;
                h.avatar.setLayoutParams(mlp);
            }
        }
        h.root.setPadding(adapter.dp(12), show ? adapter.dp(10) : 0, adapter.dp(12), adapter.dp(2));
        if (!show) return;
        h.name.setText(displayName(msg));
        h.name.setTextColor(getAuthorColor(msg));

        String timeStr = TimeUtils.messageTime(msg.timestamp);
        h.time.setText(msg.encrypted ? "\uD83D\uDD12 " + timeStr : timeStr);

        String uid = msg.author != null ? msg.author.id : null;
        h.avatar.setBackground(Ui.oval(Ui.avatarColor(uid)));
        h.avatar.setClipToOutline(true);
        boolean hasAvatar = adapter.activity != null && adapter.imageLoader != null
            && msg.author != null && msg.author.id != null && isReal(msg.author.avatar);
            
        if (adapter.imageLoader != null) {
            if (hasAvatar) {
                adapter.imageLoader.load(MessageAdapter.CDN + "avatars/" + msg.author.id + "/" + msg.author.avatar + ".png?size=128", h.avatar);
            } else {
                h.avatar.setImageDrawable(null); 
            }
        }
        
        View.OnClickListener open = v -> openProfile(uid);
        h.avatar.setOnClickListener(open);
        h.name.setOnClickListener(open);
    }

    boolean mentionsSelf(Message msg) {
        String self = adapter.activity != null ? adapter.activity.selfId() : null;
        if (self == null || msg == null) return false;
        if (msg.author != null && self.equals(msg.author.id)) return false;
        return msg.mentionedUserIds != null && msg.mentionedUserIds.contains(self);
    }

    int getAuthorColor(Message msg) {
        if (adapter.activity == null) return Color.WHITE;
        java.util.List<String> roles = msg.roleIds;
        if ((roles == null || roles.isEmpty()) && msg.author != null && msg.author.id != null && adapter.activity.userRoleCache != null) {
            roles = adapter.activity.userRoleCache.get(msg.author.id);
        }
        if (roles == null || roles.isEmpty() || adapter.activity.roleCache == null) return Color.WHITE;
        Role highest = null;
        for (String roleId : roles) {
            if (roleId == null) continue;
            Role r = adapter.activity.roleCache.get(roleId);
            if (r != null && (highest == null || r.position > highest.position)) highest = r;
        }
        return highest != null && highest.color != 0 ? highest.color | 0xFF000000 : Color.WHITE;
    }

    String displayName(Message message) {
        String nick = null;
        if (adapter.activity != null && message.author != null && message.author.id != null) {
            String cacheKey = adapter.guildId != null ? adapter.guildId + "_" + message.author.id : message.author.id;
            nick = adapter.activity.userNickCache != null ? adapter.activity.userNickCache.get(cacheKey) : null;
            if (nick == null && adapter.guildId != null) {
                nick = adapter.activity.userNickCache.get(message.author.id);
            }
        }
        String name = isReal(message.memberNick) ? message.memberNick :
                      isReal(nick) ? nick :
                      message.author == null ? "System" :
                      isReal(message.author.globalName) ? message.author.globalName :
                      isReal(message.author.username) ? message.author.username :
                      "Unknown";
        if (adapter.activity != null && message.author != null && message.author.id != null && adapter.activity.userNameCache != null) {
            String global = isReal(message.author.globalName) ? message.author.globalName : message.author.username;
            if (isReal(global)) adapter.activity.userNameCache.put(message.author.id, global);
        }
        return name;
    }

    CharSequence replyPreview(Message m) {
        if (m == null) return "";
        SpannableStringBuilder sb = new SpannableStringBuilder("\u21B3 ");
        String authorName = m.replyToAuthorName;
        if (adapter.activity != null && m.replyToAuthorId != null) {
            String cacheKey = adapter.guildId != null ? adapter.guildId + "_" + m.replyToAuthorId : m.replyToAuthorId;
            String cachedNick = adapter.activity.userNickCache != null ? adapter.activity.userNickCache.get(cacheKey) : null;
            if (cachedNick == null && adapter.guildId != null) {
                cachedNick = adapter.activity.userNickCache.get(m.replyToAuthorId);
            }
            if (isReal(cachedNick)) authorName = cachedNick;
        }
        sb.append(authorName != null ? authorName : "unknown");
        sb.setSpan(new StyleSpan(Typeface.BOLD), 2, sb.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        sb.append("  ");
        if (m.replyToMissing) {
            sb.append("Original message was deleted");
        } else if (m.replyToContent != null && Crypto.looksEncrypted(m.replyToContent)) {
            sb.append("\uD83D\uDD12 [encrypted]");
        } else {
            String t = m.replyToContent == null ? "" : m.replyToContent.replace('\n', ' ');
            if (t.length() > 80) t = t.substring(0, 80) + "\u2026";
            sb.append(t.trim().isEmpty() ? "(no text)" : t);
        }
        return sb;
    }

    void openProfile(String uid) {
        if (uid != null && adapter.activity != null) adapter.activity.navigate(new ProfileScreen(uid, adapter.guildId));
    }

    static boolean isReal(String s) { return s != null && !s.isEmpty() && !"null".equals(s); }
}