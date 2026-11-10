package a.b.screen;

import android.content.Context;
import android.content.SharedPreferences;

import a.b.MainActivity;

import java.util.HashSet;
import java.util.Set;

/** Persists which channel categories are collapsed, per-device. */
final class CollapsedCategoryStore {
    private static final String PREFS_NAME = "channel_state";
    private static final String KEY_COLLAPSED = "collapsed_categories";

    private final SharedPreferences prefs;
    private final Set<String> collapsed = new HashSet<>();

    CollapsedCategoryStore(MainActivity activity) {
        if (activity == null) {
            this.prefs = null;
            return;
        }
        this.prefs = activity.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        Set<String> stored = prefs.getStringSet(KEY_COLLAPSED, null);
        if (stored == null) return;
        for (String s : stored) {
            if (s != null) collapsed.add(s);
        }
    }

    boolean isCollapsed(String id) {
        return id != null && collapsed.contains(id);
    }

    void setCollapsed(String id, boolean value) {
        if (id == null) return;
        boolean changed = value ? collapsed.add(id) : collapsed.remove(id);
        if (changed) save();
    }

    private void save() {
        if (prefs == null) return;
        prefs.edit().putStringSet(KEY_COLLAPSED, new HashSet<>(collapsed)).apply();
    }
}
