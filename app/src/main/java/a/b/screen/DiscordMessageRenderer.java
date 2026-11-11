package a.b.screen;

import android.graphics.Bitmap;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.BitmapDrawable;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.Drawable;
import android.text.Spannable;
import android.text.SpannableStringBuilder;
import android.text.Spanned;
import android.text.TextPaint;
import android.text.format.DateUtils;
import android.text.style.BackgroundColorSpan;
import android.text.style.ClickableSpan;
import android.text.style.ForegroundColorSpan;
import android.text.style.ImageSpan;
import android.text.style.RelativeSizeSpan;
import android.text.style.StyleSpan;
import android.text.style.TypefaceSpan;
import android.text.style.URLSpan;
import android.view.View;

import a.b.model.Message;
import a.b.model.Role;
import a.b.util.DiscordMarkdown;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.LinkedList;
import java.util.List;
import java.util.Locale;
import java.util.regex.MatchResult;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Parses message text into a styled, mention-resolved, emoji-inlined Spannable. */
final class DiscordMessageRenderer {
    private static final Pattern CUSTOM_EMOJI = Pattern.compile("<a?:\\w+:(\\d+)>");
    private static final Pattern USER_MENTION = Pattern.compile("<@!?(\\d+)>");
    private static final Pattern ROLE_MENTION = Pattern.compile("<@&(\\d+)>");
    private static final Pattern CHANNEL_MENTION = Pattern.compile("<#(\\d+)>");
    private static final Pattern EVERYONE_HERE = Pattern.compile("(?<![\\w@])@(everyone|here)\\b");
    private static final Pattern TIMESTAMP = Pattern.compile("<t:(-?\\d{1,12})(?::([tTdDfFR]))?>");
    private static final Pattern SLASH_COMMAND =
            Pattern.compile("</([\\w-]+(?: [\\w-]+){0,2}):\\d+>");
    private static final Pattern GUILD_NAV =
            Pattern.compile("<id:(customize|browse|guide|linked-roles)>");

    private static final String STYLES = "tTdDfF";
    private static final String[] DATE_PATTERNS = {
        "HH:mm",
        "HH:mm:ss",
        "dd/MM/yyyy",
        "dd MMMM yyyy",
        "dd MMMM yyyy HH:mm",
        "EEEE, dd MMMM yyyy HH:mm"
    };

    private interface Text {
        CharSequence of(MatchResult m);
    }

    private interface Styler {
        void apply(SpannableStringBuilder sb, int start, int end, MatchResult m);
    }

    private final MessageAdapter adapter;

    DiscordMessageRenderer(MessageAdapter adapter) {
        this.adapter = adapter;
    }

    CharSequence render(String raw, Message msg) {
        return render(raw, msg, true);
    }

    CharSequence render(String raw, Message msg, boolean markdown) {
        if (raw == null || raw.isEmpty()) return "";
        SpannableStringBuilder sb =
                markdown ? DiscordMarkdown.parse(raw) : new SpannableStringBuilder(raw);
        int jumbo = markdown ? emojiOnly(raw) : 0;
        if (msg.id != null) emojis(sb, msg.id, jumbo != 0);
        mentions(sb, msg);
        timestamps(sb);
        if (jumbo == 2) span(sb, 0, sb.length(), new RelativeSizeSpan(2.4f));
        return sb;
    }

    private static boolean isEmojiCodePoint(int cp) {
        return (cp >= 0x1F000 && cp <= 0x1FAFF)
                || (cp >= 0x2190 && cp <= 0x21FF)
                || (cp >= 0x2300 && cp <= 0x23FF)
                || (cp >= 0x2600 && cp <= 0x27BF)
                || (cp >= 0x2900 && cp <= 0x297F)
                || (cp >= 0x2B00 && cp <= 0x2BFF)
                || cp == 0xA9
                || cp == 0xAE
                || cp == 0x203C
                || cp == 0x2049
                || cp == 0x2122
                || cp == 0x2139
                || cp == 0x24C2
                || cp == 0x3030
                || cp == 0x303D
                || cp == 0x3297
                || cp == 0x3299;
    }

    private static int emojiOnly(String raw) {
        String t = CUSTOM_EMOJI.matcher(raw).replaceAll("\u0001");
        int count = 0;
        boolean unicode = false;
        for (int i = 0; i < t.length(); ) {
            int cp = t.codePointAt(i);
            i += Character.charCount(cp);
            if (Character.isWhitespace(cp)) continue;
            if (cp == 0x1) {
                count++;
                continue;
            }
            if (cp == 0x200D || cp == 0xFE0F || cp == 0x20E3 || (cp >= 0x1F3FB && cp <= 0x1F3FF))
                continue;
            if (!isEmojiCodePoint(cp)) return 0;
            count++;
            unicode = true;
        }
        if (count == 0 || count > 27) return 0;
        return unicode ? 2 : 1;
    }

    private void emojis(SpannableStringBuilder sb, String msgId, boolean jumbo) {
        final int size = jumbo ? adapter.jumboSize : adapter.emojiSize;
        sub(
                sb,
                CUSTOM_EMOJI,
                m -> " ",
                (b, s, e, m) -> {
                    String id = m.group(1);
                    Bitmap bmp = adapter.emojiRegistry.get(id);
                    Drawable d;
                    if (bmp != null) {
                        d = new BitmapDrawable(adapter.activity.getResources(), bmp);
                    } else {
                        d = new ColorDrawable(Color.TRANSPARENT);
                        adapter.emojiRegistry.track(id, msgId);
                        boolean animated = m.group().charAt(1) == 'a';
                        adapter.emojiRegistry.load(
                                id,
                                MessageAdapter.CDN
                                        + "emojis/"
                                        + id
                                        + (animated ? ".gif" : ".png")
                                        + "?size=128");
                    }
                    d.setBounds(0, 0, size, size);
                    span(b, s, e, new ImageSpan(d, ImageSpan.ALIGN_BOTTOM));
                });
    }

    private void mentions(SpannableStringBuilder sb, Message msg) {
        Styler chip =
                (b, s, e, m) ->
                        span(
                                b,
                                s,
                                e,
                                new ForegroundColorSpan(MessageAdapter.MENTION_FG),
                                new BackgroundColorSpan(MessageAdapter.MENTION_BG));
        sub(
                sb,
                CHANNEL_MENTION,
                m -> "#" + adapter.mentionRegistry.resolveChannel(m.group(1)),
                (b, s, e, m) -> {
                    chip.apply(b, s, e, m);
                    span(b, s, e, new Click(() -> openChannel(m.group(1))));
                });
        sub(
                sb,
                ROLE_MENTION,
                m -> {
                    Role role = role(m.group(1));
                    return "@" + (role != null && isReal(role.name) ? role.name : "role");
                },
                (b, s, e, m) -> {
                    Role role = role(m.group(1));
                    int color =
                            role != null && role.color != 0
                                    ? role.color | 0xFF000000
                                    : MessageAdapter.MENTION_FG;
                    span(
                            b,
                            s,
                            e,
                            new ForegroundColorSpan(color),
                            new BackgroundColorSpan(withAlpha(color, 0x33)));
                });
        sub(
                sb,
                USER_MENTION,
                m -> "@" + adapter.mentionRegistry.resolveUser(m.group(1), msg),
                (b, s, e, m) -> {
                    chip.apply(b, s, e, m);
                    String id = m.group(1);
                    span(b, s, e, new Click(() -> openProfile(id)));
                });
        sub(sb, SLASH_COMMAND, m -> "/" + m.group(1), chip);
        sub(
                sb,
                GUILD_NAV,
                m -> {
                    switch (m.group(1)) {
                        case "customize":
                            return "Channels & Roles";
                        case "browse":
                            return "Browse Channels";
                        case "guide":
                            return "Server Guide";
                        default:
                            return "Linked Roles";
                    }
                },
                chip);
        wrap(
                sb,
                EVERYONE_HERE,
                0,
                (b, s, e, m) ->
                        span(
                                b,
                                s,
                                e,
                                new ForegroundColorSpan(MessageAdapter.EVERYONE_FG),
                                new BackgroundColorSpan(MessageAdapter.EVERYONE_BG),
                                new StyleSpan(Typeface.BOLD)));
    }

    private void openProfile(String uid) {
        if (uid != null && adapter.activity != null)
            adapter.activity.navigate(new ProfileScreen(uid, adapter.guildId));
    }

    private void openChannel(String channelId) {
        if (channelId == null || adapter.activity == null) return;
        adapter.activity.navigate(new MessageListScreen(adapter.guildId, channelId));
    }

    private Role role(String id) {
        return adapter.activity != null && adapter.activity.roleCache != null
                ? adapter.activity.roleCache.get(id)
                : null;
    }

    private static int withAlpha(int color, int alpha) {
        return (color & 0x00FFFFFF) | ((alpha & 0xFF) << 24);
    }

    private static Long timestampMillis(String seconds) {
        try {
            return Math.multiplyExact(Long.parseLong(seconds), 1000L);
        } catch (NumberFormatException | ArithmeticException ex) {
            return null;
        }
    }

    private void timestamps(SpannableStringBuilder sb) {
        sub(
                sb,
                TIMESTAMP,
                match -> {
                    Long millis = timestampMillis(match.group(1));
                    return millis == null ? match.group() : formatTimestamp(millis, match.group(2));
                },
                (b, s, e, match) -> {
                    Long millis = timestampMillis(match.group(1));
                    if (millis == null) return;
                    span(
                            b,
                            s,
                            e,
                            new ForegroundColorSpan(MessageAdapter.LINK_FG),
                            new Click(
                                    () -> {
                                        if (adapter.activity != null) {
                                            adapter.activity.toast(
                                                    java.text.DateFormat.getDateTimeInstance(
                                                                    java.text.DateFormat.LONG,
                                                                    java.text.DateFormat.LONG)
                                                            .format(new Date(millis)));
                                        }
                                    }));
                });
    }

    private static String formatTimestamp(long millis, String style) {
        if ("R".equals(style)) {
            return DateUtils.getRelativeTimeSpanString(
                            millis, System.currentTimeMillis(), DateUtils.SECOND_IN_MILLIS)
                    .toString();
        }
        int index = style == null ? -1 : STYLES.indexOf(style);
        return new SimpleDateFormat(DATE_PATTERNS[index < 0 ? 4 : index], Locale.US)
                .format(new Date(millis));
    }

    private static void span(Spannable sb, int start, int end, Object... spans) {
        for (Object o : spans) sb.setSpan(o, start, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
    }

    private static List<MatchResult> matches(SpannableStringBuilder sb, Pattern p) {
        LinkedList<MatchResult> out = new LinkedList<>();
        Matcher m = p.matcher(sb.toString());
        while (m.find()) {
            if (!isProtected(sb, m.start(), m.end())) out.addFirst(m.toMatchResult());
        }
        return out;
    }

    private static boolean isProtected(Spanned sb, int start, int end) {
        for (TypefaceSpan t : sb.getSpans(start, end, TypefaceSpan.class)) {
            if ("monospace".equals(t.getFamily())) return true;
        }
        if (sb.getSpans(start, end, URLSpan.class).length > 0) return true;
        return sb.getSpans(start, end, DiscordMarkdown.LinkSpan.class).length > 0;
    }

    private static void wrap(SpannableStringBuilder sb, Pattern p, int g, Styler styler) {
        for (MatchResult m : matches(sb, p)) {
            int len = m.end(g) - m.start(g);
            sb.delete(m.end(g), m.end());
            sb.delete(m.start(), m.start(g));
            styler.apply(sb, m.start(), m.start() + len, m);
        }
    }

    private static void sub(SpannableStringBuilder sb, Pattern p, Text text, Styler styler) {
        for (MatchResult m : matches(sb, p)) {
            CharSequence r = text.of(m);
            if (r == null) continue;
            int start = m.start(), end = m.end();
            Object[] spans = sb.getSpans(start, end, Object.class);
            int[] flags = new int[spans.length];
            for (int i = 0; i < spans.length; i++) {
                boolean inside =
                        sb.getSpanStart(spans[i]) >= start && sb.getSpanEnd(spans[i]) <= end;
                flags[i] = inside ? sb.getSpanFlags(spans[i]) : -1;
            }
            sb.replace(start, end, r);
            for (int i = 0; i < spans.length; i++) {
                if (flags[i] >= 0) sb.setSpan(spans[i], start, start + r.length(), flags[i]);
            }
            styler.apply(sb, start, start + r.length(), m);
        }
    }

    private static boolean isReal(String s) {
        return s != null && !s.isEmpty() && !"null".equals(s);
    }

    private static final class Click extends ClickableSpan {
        private final Runnable action;

        Click(Runnable action) {
            this.action = action;
        }

        @Override
        public void onClick(View v) {
            action.run();
        }

        @Override
        public void updateDrawState(TextPaint ds) {
            ds.setUnderlineText(false);
        }
    }
}
