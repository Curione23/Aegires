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
            switch (item.getItemId()) {
                case MENU_REPLY: host.composer().beginReply(msg); return true;
                case MENU_COPY: copyMessage(msg); return true;
                case MENU_EDIT: host.composer().beginEdit(msg); return true;
                case MENU_DELETE: confirmDelete(msg); return true;
                default: return false;
            }
        });
        menu.show();
    }

    private void copyMessage(Message msg) {
        ClipboardManager cm = (ClipboardManager) host.activity.getSystemService(Context.CLIPBOARD_SERVICE);
        cm.setPrimaryClip(ClipData.newPlainText("message", msg.content == null ? "" : msg.content));
        host.activity.toast("Copied");
    }

    private void confirmDelete(Message msg) {
        new AlertDialog.Builder(host.activity)
            .setTitle("Delete message")
            .setMessage("This cannot be undone.")
            .setPositiveButton("Delete", (dialog, which) -> {
                if (host.activity == null || host.activity.apiClient == null) return;
                host.activity.apiClient.deleteMessage(host.channelId, msg.id, new ApiClient.Callback<Void>() {
                    @Override public void onSuccess(Void v) { host.gatewayDelete(msg.id); }
                    @Override public void onError(String err) {
                        if (host.activity != null) host.activity.toast("Delete failed: " + err);
                    }
                });
            })
            .setNegativeButton("Cancel", null)
            .show();
    }

    String nameOf(Message m) {
        String nick = null;
        if (host.activity != null && m.author != null && m.author.id != null) {
            String cacheKey = host.guildId != null ? host.guildId + "_" + m.author.id : m.author.id;
            nick = host.activity.userNickCache != null ? host.activity.userNickCache.get(cacheKey) : null;
            if (nick == null && host.guildId != null) {
                nick = host.activity.userNickCache.get(m.author.id);
            }
        }
        if (isReal(m.memberNick)) return m.memberNick;
        if (isReal(nick)) return nick;
        if (m.author == null) return "unknown";
        return isReal(m.author.globalName) ? m.author.globalName : m.author.username;
    }

    private static boolean isReal(String s) { return s != null && !s.isEmpty() && !"null".equals(s); }
}