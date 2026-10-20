package a.b.util;

import android.graphics.Typeface;
import android.text.Spannable;
import android.text.SpannableStringBuilder;
import android.text.style.BackgroundColorSpan;
import android.text.style.ForegroundColorSpan;
import android.text.style.StrikethroughSpan;
import android.text.style.StyleSpan;
import android.text.style.TypefaceSpan;
import android.text.style.URLSpan;
import android.text.style.UnderlineSpan;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class SimpleMarkdown {
    private static final Pattern BOLD = Pattern.compile("\\*\\*(.+?)\\*\\*");
    private static final Pattern UNDERLINE = Pattern.compile("__(.+?)__");
    private static final Pattern STRIKE = Pattern.compile("~~(.+?)~~");
    private static final Pattern ITALIC_STAR = Pattern.compile("\\*(.+?)\\*");
    private static final Pattern ITALIC_UND = Pattern.compile("_(.+?)_");
    private static final Pattern LINK = Pattern.compile("\\[([^\\]]+)\\]\\(([^)]+)\\)");
    private static final int CODE_BG = 0xFF2B2D31;
    private static final int CODE_FG = 0xFFDBDEE1;
    private static final String CODE_FAMILY = "monospace";

    private SimpleMarkdown() {}
    private interface SpanFactory { Object create(); }

    public static CharSequence render(String raw) {
        if (raw == null || raw.isEmpty()) return "";
        SpannableStringBuilder sb = new SpannableStringBuilder();
        int i = 0, len = raw.length();
        while (i < len) {
            if (matches(raw, i, "```")) {
                i = appendCodeBlock(sb, raw, i);
            } else if (raw.charAt(i) == '`') {
                i = appendInlineCode(sb, raw, i);
            } else {
                int nextTick = raw.indexOf('`', i);
                if (nextTick == -1) nextTick = len;
                sb.append(applyInline(raw.substring(i, nextTick)));
                i = nextTick;
            }
        }
        return sb;
    }

    private static int appendCodeBlock(SpannableStringBuilder sb, String raw, int i) {
        int len = raw.length(), contentStart = i + 3;
        while (contentStart < len && raw.charAt(contentStart) != '\n' && raw.charAt(contentStart) != '`') contentStart++;
        while (contentStart < len && isSpace(raw.charAt(contentStart))) contentStart++;
        int contentEnd = raw.indexOf("```", contentStart);
        if (contentEnd == -1) contentEnd = len;
        int spanStart = sb.length();
        sb.append(raw, contentStart, contentEnd);
        applyCodeSpan(sb, spanStart, sb.length());
        return Math.min(contentEnd + 3, len);
    }

    private static int appendInlineCode(SpannableStringBuilder sb, String raw, int i) {
        int end = raw.indexOf('`', i + 1);
        if (end == -1) {
            sb.append('`');
            return i + 1;
        }
        int spanStart = sb.length();
        sb.append(raw, i + 1, end);
        applyCodeSpan(sb, spanStart, sb.length());
        return end + 1;
    }

    private static boolean matches(String s, int offset, String prefix) {
        if (offset + prefix.length() > s.length()) return false;
        for (int k = 0; k < prefix.length(); k++) {
            if (s.charAt(offset + k) != prefix.charAt(k)) return false;
        }
        return true;
    }

    private static boolean isSpace(char c) { return c == '\n' || c == ' ' || c == '\t'; }

    private static void applyCodeSpan(SpannableStringBuilder sb, int start, int end) {
        if (start >= end) return;
        sb.setSpan(new BackgroundColorSpan(CODE_BG), start, end, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE);
        sb.setSpan(new ForegroundColorSpan(CODE_FG), start, end, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE);
        sb.setSpan(new TypefaceSpan(CODE_FAMILY), start, end, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE);
    }

    private static CharSequence applyInline(String text) {
        if (text.isEmpty()) return "";
        SpannableStringBuilder sb = new SpannableStringBuilder(text);
        applyPattern(sb, BOLD, () -> new StyleSpan(Typeface.BOLD));
        applyPattern(sb, UNDERLINE, UnderlineSpan::new);
        applyPattern(sb, STRIKE, StrikethroughSpan::new);
        applyPattern(sb, ITALIC_STAR, () -> new StyleSpan(Typeface.ITALIC));
        applyPattern(sb, ITALIC_UND, () -> new StyleSpan(Typeface.ITALIC));
        applyLinks(sb);
        return sb;
    }

    private static void applyPattern(SpannableStringBuilder sb, Pattern p, SpanFactory factory) {
        Matcher m = p.matcher(sb.toString());
        List<int[]> ranges = new ArrayList<>();
        List<String> contents = new ArrayList<>();
        while (m.find()) {
            ranges.add(new int[]{m.start(), m.end()});
            contents.add(m.group(1));
        }
        for (int i = ranges.size() - 1; i >= 0; i--) {
            int s = ranges.get(i)[0], e = ranges.get(i)[1];
            String content = contents.get(i);
            sb.replace(s, e, content);
            sb.setSpan(factory.create(), s, s + content.length(), Spannable.SPAN_EXCLUSIVE_EXCLUSIVE);
        }
    }

    private static void applyLinks(SpannableStringBuilder sb) {
        Matcher m = LINK.matcher(sb.toString());
        List<int[]> ranges = new ArrayList<>();
        List<String> labels = new ArrayList<>(), urls = new ArrayList<>();
        while (m.find()) {
            ranges.add(new int[]{m.start(), m.end()});
            labels.add(m.group(1));
            urls.add(m.group(2));
        }
        for (int i = ranges.size() - 1; i >= 0; i--) {
            int s = ranges.get(i)[0], e = ranges.get(i)[1];
            String label = labels.get(i);
            sb.replace(s, e, label);
            sb.setSpan(new URLSpan(urls.get(i)), s, s + label.length(), Spannable.SPAN_EXCLUSIVE_EXCLUSIVE);
        }
    }
}