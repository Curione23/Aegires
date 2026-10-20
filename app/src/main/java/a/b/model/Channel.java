package a.b.model;

public class Channel {
    public String id, name, parent_id;
    public int type;
    public int position;

    public Channel(String id, String name, int type, String parent_id) {
        this.id = id; 
        this.name = name;
        this.type = type;
        this.parent_id = parent_id;
    }
}
