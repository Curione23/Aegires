package a.b.screen;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import a.b.model.Channel;

/** Groups a flat channel list into categories + uncategorized, in render order. */
final class ChannelTreeBuilder {
    static final class CategoryGroup {
        final Channel category;
        final List<Channel> children;
        CategoryGroup(Channel category, List<Channel> children) {
            this.category = category;
            this.children = children;
        }
    }

    static final class Result {
        final List<Channel> uncategorized;
        final List<CategoryGroup> categories;
        Result(List<Channel> uncategorized, List<CategoryGroup> categories) {
            this.uncategorized = uncategorized;
            this.categories = categories;
        }
    }

    static final Comparator<Channel> POSITION_COMPARATOR = (a, b) -> Integer.compare(a.position, b.position);
    static final Comparator<Channel> CHANNEL_COMPARATOR = (a, b) -> {
        int ag = isVoiceLike(a.type) ? 1 : 0, bg = isVoiceLike(b.type) ? 1 : 0;
        if (ag != bg) return ag - bg;
        return Integer.compare(a.position, b.position);
    };

    static Result build(List<Channel> all) {
        if (all == null || all.isEmpty()) {
            return new Result(new ArrayList<Channel>(), new ArrayList<CategoryGroup>());
        }
        List<Channel> categories = new ArrayList<>();
        List<Channel> uncategorized = new ArrayList<>();
        Map<String, List<Channel>> children = new HashMap<>();

        for (Channel ch : all) {
            if (ch == null || ch.type != 4 || ch.id == null) continue;
            categories.add(ch);
            children.putIfAbsent(ch.id, new ArrayList<Channel>());
        }
        for (Channel ch : all) {
            if (ch == null || ch.type == 4) continue;
            String parent = ch.parent_id;
            boolean hasParent = parent != null && parent.length() > 0 && !"null".equals(parent) && children.containsKey(parent);
            if (!hasParent) uncategorized.add(ch);
            else children.get(parent).add(ch);
        }

        Collections.sort(uncategorized, CHANNEL_COMPARATOR);
        Collections.sort(categories, POSITION_COMPARATOR);

        List<CategoryGroup> groups = new ArrayList<>();
        for (Channel cat : categories) {
            List<Channel> kids = children.get(cat.id);
            if (kids == null || kids.isEmpty()) continue;
            Collections.sort(kids, CHANNEL_COMPARATOR);
            groups.add(new CategoryGroup(cat, kids));
        }
        return new Result(uncategorized, groups);
    }

    static boolean isVoiceLike(int type) { return type == 2 || type == 13; }
    static boolean isOpenable(int type) { return type == 0 || type == 2 || type == 5 || type == 13; }
}