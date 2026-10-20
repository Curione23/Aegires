package a.b.model;

public class EmbedField {
    public String name;
    public String value;
    public boolean inline;

    public EmbedField(String name, String value, boolean inline) {
        this.name = name;
        this.value = value;
        this.inline = inline;
    }
}