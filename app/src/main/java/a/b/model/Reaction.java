package a.b.model;

public class Reaction {
    public String emoji;  // could be unicode or custom emoji name
    public int count;
    public boolean me;    // whether current user reacted

    public Reaction(String emoji, int count, boolean me) {
        this.emoji = emoji;
        this.count = count;
        this.me = me;
    }
}