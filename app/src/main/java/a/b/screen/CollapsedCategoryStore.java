package a.b.screen;

import android.content.Context;
import android.content.SharedPreferences;
import java.util.HashSet;
import java.util.Set;
import a.b.MainActivity;

/** Persists which channel categories are collapsed, per-device. */
final class CollapsedCategoryStore {
    private static final String PREFS_NAME = "channel_state";
    private static final String KEY_COLLAPSED = "collapsed_categories";

    private final MainActivity activity;
    private Set<String> collapsed = new HashSet<>();

    CollapsedCategoryStore(MainActivity activity) {
        this.activity = activity;
        if (activity == null) return;
        SharedPreferences prefs = activity.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        collapsed = new HashSet<>(prefs.getStringSet(KEY_COLLAPSED, new HashSet<String>()));
    }

    boolean isCollapsed(String id) {
        return collapsed.contains(id);
    }

    void setCollapsed(String id, boolean value) {
        if (value) collapsed.add(id); else collapsed.remove(id);
        save();
    }

    private void save() {
        if (activity == null) return;
        activity.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit()
            .putStringSet(KEY_COLLAPSED, new HashSet<>(collapsed)).apply();
    }
}