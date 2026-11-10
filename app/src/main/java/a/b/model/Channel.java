package a.b.model;

import java.util.ArrayList;
import java.util.List;

public class Channel {
    public static final class ForumTag {
        public final String id, name;
        public final int color;
        public final boolean moderated;

        public ForumTag(String id, String name, int color, boolean moderated) {
            this.id = id;
            this.name = name;
            this.color = color;
            this.moderated = moderated;
        }
    }

    public String id, name, parent_id;
    public String topic, ownerId, lastMessageId;
    public int type;
    public int position;
    public boolean nsfw;
    public boolean canCreateThreads;
    public boolean requireTag;

    /** Forum channel tags, and the tags applied to a thread. */
    public final List<ForumTag> availableTags = new ArrayList<>();

    public final List<String> appliedTagIds = new ArrayList<>();

    /** Forum thread metadata. */
    public int messageCount, memberCount, totalMessageSent;

    public long lastActivity, archiveTimestamp;
    public boolean archived, locked, pinned;

    public Channel(String id, String name, int type, String parent_id) {
        this.id = id;
        this.name = name;
        this.type = type;
        this.parent_id = parent_id;
    }
}
