package a.b.screen;

import android.app.AlertDialog;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.view.View;
import android.widget.PopupMenu;
import a.b.api.ApiClient;
import a.b.model.Message;

/** Long-press context menu, copy, delete, reply/edit entry points. */
final class MessageActionsController {
    private static final int MENU_REPLY = 1, MENU_COPY = 2, MENU_EDIT = 3, MENU_DELETE = 4;

    private final MessageListScreen host;

    MessageActionsController(MessageListScreen host) {
        this.host = host;
    }

    void onMessageLongPress(Message msg, View anchor) {
        if (host.activity == null || msg == null) return;
        boolean mine = host.activity.selfId() != null && msg.author != null && host.activity.selfId().equals(msg.author.id);
        boolean canManage = host.activity.apiClient != null && host.activity.apiClient.canManageMessages(host.channelId);
        PopupMenu menu = new PopupMenu(host.activity, anchor);
        menu.getMenu().add(0, MENU_REPLY, 0, "Reply");
        menu.getMenu().add(0, MENU_COPY, 1, "Copy text");
        if (mine) menu.getMenu().add(0, MENU_EDIT, 2, "Edit");
        if (mine || canManage) menu.getMenu().add(0, MENU_DELETE, 3, "Delete");
        menu.setOnMenuItemClickListener(item -> {
            if (host.activity == null) return true;
            switch (item.getItemId()) {
                case MENU_REPLY:
                    if (host.composer() != null) host.composer().beginReply(msg);
                    return true;
                case MENU_COPY:
                    copyMessage(msg);
                    return true;
                case MENU_EDIT:
                    if (host.composer() != null) host.composer().beginEdit(msg);
                    return true;
                case MENU_DELETE:
                    confirmDelete(msg);
                    return true;
                default:
                    return false;
            }
        });
        menu.show();
    }

    private void copyMessage(Message msg) {
        if (host.activity == null) return;
        ClipboardManager cm = (ClipboardManager) host.activity.getSystemService(Context.CLIPBOARD_SERVICE);
        if (cm == null) { host.activity.toast("Couldn't copy"); return; }
        cm.setPrimaryClip(ClipData.newPlainText("message", msg.content == null ? "" : msg.content));
        host.activity.toast("Copied");
    }

    private void confirmDelete(Message msg) {
        if (host.activity == null || msg == null || msg.id == null || host.activity.apiClient == null) return;
        new AlertDialog.Builder(host.activity)
            .setTitle("Delete message")
            .setMessage("This cannot be undone.")
            .setPositiveButton("Delete", (dialog, which) -> {
                if (host.activity == null || host.activity.apiClient == null) return;
                host.activity.apiClient.deleteMessage(host.channelId, msg.id, new ApiClient.Callback<Void>() {
                    @Override public void onSuccess(Void v) {
                        if (host.activity == null) return;
                        host.gatewayDelete(msg.id);
                    }
                    @Override public void onError(String err) {
                        if (host.activity != null) host.activity.toast("Delete failed: " + err);
                    }
                });
            })
            .setNegativeButton("Cancel", null)
            .show();
    }

    String nameOf(Message m) {
        if (m == null || m.author == null) return "Unknown";
        java.util.Map<String, String> nickCache = host.activity != null ? host.activity.userNickCache : null;
        String nick = null;
        if (m.author.id != null && nickCache != null) {
            String cacheKey = host.guildId != null ? host.guildId + "_" + m.author.id : m.author.id;
            nick = nickCache.get(cacheKey);
            if (nick == null && host.guildId != null) nick = nickCache.get(m.author.id);
        }
        if (isReal(m.memberNick)) return m.memberNick;
        if (isReal(nick)) return nick;
        if (isReal(m.author.globalName)) return m.author.globalName;
        if (isReal(m.author.username)) return m.author.username;
        return "Unknown";
    }

    private static boolean isReal(String s) { return s != null && !s.isEmpty() && !"null".equals(s); }
}