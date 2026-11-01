package a.b.util;

public final class SimpleMarkdown {
    private SimpleMarkdown() {}

    public static CharSequence render(String raw) {
        return DiscordMarkdown.parse(raw);
    }
}
