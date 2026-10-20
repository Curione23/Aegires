package a.b.model;

public class Guild {
    public String id;
    public String name;
    public String icon;
    public String banner;
    public boolean owner;

    public Guild(String id, String name, String icon, String banner, boolean owner) {
        this.id = id; this.name = name; this.icon = icon; this.banner = banner;
        this.owner = owner;
    }
}