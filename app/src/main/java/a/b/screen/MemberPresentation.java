package a.b.screen;

import a.b.MainActivity;
import a.b.model.Role;
import a.b.util.MemberListStore.Entry;
import a.b.util.Ui;

import java.util.Locale;

/** Stateless presentation helpers for member rows. */
final class MemberPresentation {
    private MemberPresentation() {}

    static int nameColor(MainActivity activity, Entry e) {
        if (activity == null || e == null || e.roles == null) return Ui.TEXT_HEADER;
        Role best = null;
        for (String id : e.roles) {
            if (id == null) continue;
            Role r = activity.roleCache != null ? activity.roleCache.get(id) : null;
            if (r != null && r.color != 0 && (best == null || r.position > best.position)) best = r;
        }
        return best != null ? best.color | 0xFF000000 : Ui.TEXT_HEADER;
    }

    static String groupTitle(MainActivity activity, Entry e) {
        if (activity == null || e == null) return "";
        String name;
        if ("online".equals(e.groupId)) name = "Online";
        else if ("offline".equals(e.groupId)) name = "Offline";
        else {
            Role r = activity.roleCache != null ? activity.roleCache.get(e.groupId) : null;
            name = (r != null && Ui.isReal(r.name)) ? r.name : "Role";
        }
        int count =
                activity.memberList != null
                        ? activity.memberList.groupCount(e.groupId, e.count)
                        : e.count;
        return name.toUpperCase(Locale.US) + " \u2014 " + count;
    }

    static int statusColor(String status) {
        if ("online".equals(status)) return Ui.GREEN;
        if ("idle".equals(status)) return Ui.YELLOW;
        if ("dnd".equals(status)) return Ui.RED;
        return 0xFF80848E;
    }
}
