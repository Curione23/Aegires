package a.b.screen;

import java.util.Locale;
import a.b.MainActivity;
import a.b.model.Role;
import a.b.util.MemberListStore.Entry;
import a.b.util.Ui;

/** Stateless presentation helpers for member rows. */
final class MemberPresentation {
    private MemberPresentation() {}

    static int nameColor(MainActivity activity, Entry e) {
        Role best = null;
        for (String id : e.roles) {
            Role r = activity.roleCache.get(id);
            if (r != null && r.color != 0 && (best == null || r.position > best.position)) best = r;
        }
        return best != null ? best.color | 0xFF000000 : Ui.TEXT_HEADER;
    }

    static String groupTitle(MainActivity activity, Entry e) {
        String name;
        if ("online".equals(e.groupId)) name = "Online";
        else if ("offline".equals(e.groupId)) name = "Offline";
        else {
            Role r = activity.roleCache.get(e.groupId);
            name = r != null ? r.name : "Role";
        }
        int count = activity.memberList.groupCount(e.groupId, e.count);
        return name.toUpperCase(Locale.US) + " \u2014 " + count;
    }

    static int statusColor(String status) {
        if ("online".equals(status)) return Ui.GREEN;
        if ("idle".equals(status)) return Ui.YELLOW;
        if ("dnd".equals(status)) return Ui.RED;
        return 0xFF80848E;
    }
}