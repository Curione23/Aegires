package a.b.util;

import android.content.res.Resources;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Typeface;
import android.text.Layout;
import android.text.Spannable;
import android.text.SpannableStringBuilder;
import android.text.TextPaint;
import android.text.style.ClickableSpan;
import android.text.style.ForegroundColorSpan;
import android.text.style.LeadingMarginSpan;
import android.text.style.LineBackgroundSpan;
import android.text.style.BackgroundColorSpan;
import android.text.style.RelativeSizeSpan;
import android.text.style.StrikethroughSpan;
import android.text.style.StyleSpan;
import android.text.style.TypefaceSpan;
import android.text.style.URLSpan;
import android.text.style.UnderlineSpan;
import android.view.View;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** simple-markdown rules rendered into spans. */
public final class DiscordMarkdown {
    private static final int CODE_BG = 0xFF1E1F22;
    private static final int CODE_FG = 0xFFDBDEE1;
    private static final int MUTED = 0xFF949BA4;
    private static final int QUOTE_BAR = 0xFF4E5058;
    private static final int SPOILER_HIDDEN = 0xFF1E1F22;
    private static final int SPOILER_SHOWN_BG = 0x33000000;

    private static final Pattern LANG = Pattern.compile("^[A-Za-z0-9_+\\-.#]+\\n");
    private static final Pattern HEADING = Pattern.compile("(#{1,3}) +(\\S.*)");
    private static final Pattern LIST_ITEM = Pattern.compile("( *)([-*]|\\d{1,9}\\.) +(\\S.*)");
    private static final Pattern AUTO_LINK = Pattern.compile("<(https?://[^\\s>]+)>", Pattern.CASE_INSENSITIVE);
    private static final Pattern URL_START = Pattern.compile("https?://", Pattern.CASE_INSENSITIVE);
    private static final Pattern TOKEN = Pattern.compile(
        "<(?:@!?\\d+|@&\\d+|#\\d+|a?:\\w+:\\d+|t:-?\\d+(?::[tTdDfFR])?|/[\\w-]+(?: [\\w-]+){0,2}:\\d+|id:[\\w-]+)>");

    private static final int FLAGS = Spannable.SPAN_EXCLUSIVE_EXCLUSIVE;

    private interface SpanMaker { Object make(); }

    private DiscordMarkdown() {}

    private static int dp(float v) {
        return Math.round(v * Resources.getSystem().getDisplayMetrics().density);
    }

    public static SpannableStringBuilder parse(String raw) {
        SpannableStringBuilder out = new SpannableStringBuilder();
        if (raw == null || raw.isEmpty()) return out;
        blocks(out, raw, true);
        return out;
    }

    private static void blocks(SpannableStringBuilder out, String s, boolean allowQuote) {
        int n = s.length(), i = 0;
        while (i < n) {
            boolean lineStart = i == 0 || s.charAt(i - 1) == '\n';
            if (lineStart) {
                int next = block(out, s, i, allowQuote);
                if (next >= 0) { i = next; continue; }
            }
            int end = paragraphEnd(s, i, allowQuote);
            int next = inline(out, s, i, end, true, false);
            i = Math.max(next, i + 1);
        }
    }

    private static int lineEnd(String s, int from) {
        int e = s.indexOf('\n', from);
        return e < 0 ? s.length() : e;
    }

    private static int paragraphEnd(String s, int from, boolean allowQuote) {
        int nl = s.indexOf('\n', from);
        while (nl >= 0) {
            int ls = nl + 1;
            if (ls >= s.length()) return s.length();
            if (isBlockStart(s, ls, allowQuote)) return ls;
            nl = s.indexOf('\n', ls);
        }
        return s.length();
    }

    private static boolean isBlockStart(String s, int ls, boolean allowQuote) {
        if (allowQuote && (s.startsWith("> ", ls) || s.startsWith(">>> ", ls))) return true;
        if (s.startsWith("-# ", ls)) return true;
        int e = lineEnd(s, ls);
        if (HEADING.matcher(s).region(ls, e).matches()) return true;
        return LIST_ITEM.matcher(s).region(ls, e).matches();
    }

    private static int block(SpannableStringBuilder out, String s, int i, boolean allowQuote) {
        int n = s.length();
        int e = lineEnd(s, i);

        if (allowQuote && s.startsWith(">>> ", i)) {
            SpannableStringBuilder inner = new SpannableStringBuilder();
            blocks(inner, s.substring(i + 4), false);
            appendQuote(out, inner);
            return n;
        }
        if (allowQuote && s.startsWith("> ", i)) {
            SpannableStringBuilder inner = new SpannableStringBuilder();
            blocks(inner, s.substring(i + 2, e), false);
            if (e < n) inner.append('\n');
            appendQuote(out, inner);
            return Math.min(n, e + 1);
        }

        Matcher hm = HEADING.matcher(s).region(i, e);
        if (hm.matches()) {
            int level = hm.group(1).length();
            int st = out.length();
            inline(out, s, hm.start(2), hm.end(2), false, false);
            float size = level == 1 ? 1.5f : level == 2 ? 1.25f : 1.1f;
            out.setSpan(new RelativeSizeSpan(size), st, out.length(), FLAGS);
            out.setSpan(new StyleSpan(Typeface.BOLD), st, out.length(), FLAGS);
            if (e < n) out.append('\n');
            return Math.min(n, e + 1);
        }

        if (s.startsWith("-# ", i) && e > i + 3) {
            int st = out.length();
            inline(out, s, i + 3, e, false, false);
            out.setSpan(new RelativeSizeSpan(0.8f), st, out.length(), FLAGS);
            out.setSpan(new ForegroundColorSpan(MUTED), st, out.length(), FLAGS);
            if (e < n) out.append('\n');
            return Math.min(n, e + 1);
        }

        Matcher lm = LIST_ITEM.matcher(s).region(i, e);
        if (lm.matches()) return list(out, s, i);
        return -1;
    }

    private static void appendQuote(SpannableStringBuilder out, SpannableStringBuilder inner) {
        int st = out.length();
        out.append(inner);
        if (out.length() > st) out.setSpan(new QuoteBarSpan(), st, out.length(), FLAGS);
    }

    private static int list(SpannableStringBuilder out, String s, int from) {
        int n = s.length(), pos = from;
        int[] counters = new int[6];
        Matcher lm = LIST_ITEM.matcher(s);
        while (pos < n) {
            int le = lineEnd(s, pos);
            lm.region(pos, le);
            if (!lm.matches()) break;
            int depth = Math.min(4, lm.group(1).length() / 2);
            String marker = lm.group(2);
            String label;
            if (Character.isDigit(marker.charAt(0))) {
                int num = Integer.parseInt(marker.substring(0, marker.length() - 1));
                if (counters[depth] > 0) num = counters[depth];
                counters[depth] = num + 1;
                label = num + ".";
            } else {
                counters[depth] = 0;
                label = depth == 0 ? "\u2022" : depth == 1 ? "\u25E6" : "\u25AA";
            }
            for (int d = depth + 1; d < counters.length; d++) counters[d] = 0;

            int st = out.length();
            inline(out, s, lm.start(3), lm.end(3), false, false);
            if (le < n) out.append('\n');
            out.setSpan(new ListSpan(label, depth), st, out.length(), FLAGS);
            pos = Math.min(n, le + 1);
        }
        return pos;
    }

    private static boolean isWord(char c) { return Character.isLetterOrDigit(c) || c == '_'; }

    private static boolean isEscapable(char c) {
        boolean alnum = (c >= '0' && c <= '9') || (c >= 'A' && c <= 'Z') || (c >= 'a' && c <= 'z');
        return !alnum && !Character.isWhitespace(c);
    }

    private static int inline(SpannableStringBuilder out, String s, int from, int to, boolean top, boolean inLink) {
        int limit = top ? s.length() : to;
        int i = from;
        while (i < to) {
            char c = s.charAt(i);
            int r = -1;
            switch (c) {
                case '\\':
                    if (i + 1 < to && isEscapable(s.charAt(i + 1))) {
                        out.append(s.charAt(i + 1));
                        i += 2;
                        continue;
                    }
                    break;
                case '`':
                    r = backtick(out, s, i, to, limit);
                    if (r < 0) {
                        int j = i;
                        while (j < to && s.charAt(j) == '`') j++;
                        out.append(s, i, j);
                        r = j;
                    }
                    break;
                case '*': r = star(out, s, i, to, inLink); break;
                case '_': r = underscore(out, s, i, to, inLink); break;
                case '~': r = pair(out, s, i, to, "~~", inLink, StrikethroughSpan::new); break;
                case '|': r = pair(out, s, i, to, "||", inLink, SpoilerSpan::new); break;
                case '[': r = maskedLink(out, s, i, to, inLink); break;
                case '<': r = angle(out, s, i, to); break;
                case 'h':
                case 'H':
                    if (!inLink && (i == 0 || !isWord(s.charAt(i - 1)))) r = bareUrl(out, s, i, to);
                    break;
                default:
            }
            if (r >= 0) {
                i = r;
            } else {
                out.append(c);
                i++;
            }
        }
        return i;
    }

    private static int delim(SpannableStringBuilder out, String s, int contentFrom, int contentTo,
                             int endIdx, boolean inLink, SpanMaker maker) {
        int st = out.length();
        inline(out, s, contentFrom, contentTo, false, inLink);
        if (out.length() > st) out.setSpan(maker.make(), st, out.length(), FLAGS);
        return endIdx;
    }

    private static int pair(SpannableStringBuilder out, String s, int i, int to, String d,
                            boolean inLink, SpanMaker maker) {
        if (!s.startsWith(d, i) || i + 2 >= to) return -1;
        int close = findClose(s, i + 2, to, d, false);
        if (close < 0) return -1;
        return delim(out, s, i + 2, close, close + 2, inLink, maker);
    }

    private static int star(SpannableStringBuilder out, String s, int i, int to, boolean inLink) {
        if (s.startsWith("**", i) && i + 2 < to) {
            int close = findClose(s, i + 2, to, "**", true);
            if (close >= 0) return delim(out, s, i + 2, close, close + 2, inLink, () -> new StyleSpan(Typeface.BOLD));
        }
        if (i + 1 < to) {
            char nx = s.charAt(i + 1);
            if (!Character.isWhitespace(nx) && nx != '*') {
                int close = findStarEmClose(s, i + 1, to);
                if (close >= 0) return delim(out, s, i + 1, close, close + 1, inLink, () -> new StyleSpan(Typeface.ITALIC));
            }
        }
        return -1;
    }

    private static int underscore(SpannableStringBuilder out, String s, int i, int to, boolean inLink) {
        if (s.startsWith("__", i) && i + 2 < to) {
            int close = findClose(s, i + 2, to, "__", true);
            if (close >= 0) return delim(out, s, i + 2, close, close + 2, inLink, UnderlineSpan::new);
        }
        if (i > 0 && isWord(s.charAt(i - 1))) return -1;
        if (i + 1 < to) {
            char nx = s.charAt(i + 1);
            if (!Character.isWhitespace(nx) && nx != '_') {
                int close = findUnderEmClose(s, i + 1, to);
                if (close >= 0) return delim(out, s, i + 1, close, close + 1, inLink, () -> new StyleSpan(Typeface.ITALIC));
            }
        }
        return -1;
    }

    private static int findClose(String s, int from, int to, String d, boolean notFollowedBySame) {
        char dc = d.charAt(0);
        int k = from;
        while (k < to) {
            char ch = s.charAt(k);
            if (ch == '\\') { k += 2; continue; }
            if (ch == '`') {
                int skip = skipCode(s, k, to);
                if (skip > 0) { k = skip; continue; }
            }
            if (k > from && s.startsWith(d, k)) {
                int after = k + d.length();
                if (!(notFollowedBySame && after < to && s.charAt(after) == dc)) return k;
            }
            k++;
        }
        return -1;
    }

    private static int findStarEmClose(String s, int from, int to) {
        int k = from;
        while (k < to) {
            char ch = s.charAt(k);
            if (ch == '\\') { k += 2; continue; }
            if (ch == '`') {
                int skip = skipCode(s, k, to);
                if (skip > 0) { k = skip; continue; }
            }
            if (ch == '*') {
                if (k + 1 < to && s.charAt(k + 1) == '*') { k += 2; continue; }
                if (k > from && !Character.isWhitespace(s.charAt(k - 1))) return k;
            }
            k++;
        }
        return -1;
    }

    private static int findUnderEmClose(String s, int from, int to) {
        int k = from;
        while (k < to) {
            char ch = s.charAt(k);
            if (ch == '\\') { k += 2; continue; }
            if (ch == '`') {
                int skip = skipCode(s, k, to);
                if (skip > 0) { k = skip; continue; }
            }
            if (ch == '_') {
                if (k + 1 < to && s.charAt(k + 1) == '_') { k += 2; continue; }
                boolean wordAfter = k + 1 < to && isWord(s.charAt(k + 1));
                if (k > from && !Character.isWhitespace(s.charAt(k - 1)) && !wordAfter) return k;
            }
            k++;
        }
        return -1;
    }

    private static int skipCode(String s, int k, int to) {
        int n = 0;
        while (k + n < to && s.charAt(k + n) == '`') n++;
        int j = k + n;
        while (j < to) {
            int c = s.indexOf('`', j);
            if (c < 0 || c >= to) return -1;
            int m = 0;
            while (c + m < to && s.charAt(c + m) == '`') m++;
            if (m == n) return c + m;
            j = c + m;
        }
        return -1;
    }

    private static int backtick(SpannableStringBuilder out, String s, int i, int to, int limit) {
        if (s.startsWith("```", i)) {
            int r = fence(out, s, i, limit);
            if (r >= 0) return r;
        }
        int n = 0;
        while (i + n < to && s.charAt(i + n) == '`') n++;
        int j = i + n;
        while (j < to) {
            int k = s.indexOf('`', j);
            if (k < 0 || k >= to) return -1;
            int m = 0;
            while (k + m < to && s.charAt(k + m) == '`') m++;
            if (m == n) {
                String content = s.substring(i + n, k);
                if (content.isEmpty()) return -1;
                if (content.length() > 2 && content.startsWith(" ") && content.endsWith(" ") && !content.trim().isEmpty()) {
                    content = content.substring(1, content.length() - 1);
                }
                int st = out.length();
                out.append(content);
                out.setSpan(new BackgroundColorSpan(CODE_BG), st, out.length(), FLAGS);
                out.setSpan(new ForegroundColorSpan(CODE_FG), st, out.length(), FLAGS);
                out.setSpan(new TypefaceSpan("monospace"), st, out.length(), FLAGS);
                return k + m;
            }
            j = k + m;
        }
        return -1;
    }

    private static int fence(SpannableStringBuilder out, String s, int i, int limit) {
        int close = s.indexOf("```", i + 3);
        if (close < 0 || close + 3 > limit) return -1;
        String body = s.substring(i + 3, close);
        Matcher lang = LANG.matcher(body);
        if (lang.find()) body = body.substring(lang.end());
        int a = 0, b = body.length();
        while (a < b && body.charAt(a) == '\n') a++;
        while (b > a && body.charAt(b - 1) == '\n') b--;
        if (a >= b) return -1;
        body = body.substring(a, b);

        if (out.length() > 0 && out.charAt(out.length() - 1) != '\n') out.append('\n');
        int st = out.length();
        out.append(body);
        int endIdx = close + 3;
        if (endIdx < s.length()) {
            out.append('\n');
            if (s.charAt(endIdx) == '\n') endIdx++;
        }
        out.setSpan(new CodeBlockSpan(), st, out.length(), FLAGS);
        out.setSpan(new ForegroundColorSpan(CODE_FG), st, out.length(), FLAGS);
        out.setSpan(new TypefaceSpan("monospace"), st, out.length(), FLAGS);
        return endIdx;
    }

    private static boolean isHttp(String url) {
        String l = url.toLowerCase(java.util.Locale.ROOT);
        return (l.startsWith("http://") && l.length() > 7) || (l.startsWith("https://") && l.length() > 8);
    }

    private static int maskedLink(SpannableStringBuilder out, String s, int i, int to, boolean inLink) {
        if (inLink) return -1;
        int depth = 0, close = -1;
        for (int k = i; k < to; k++) {
            char ch = s.charAt(k);
            if (ch == '\\') { k++; continue; }
            if (ch == '[') depth++;
            else if (ch == ']') {
                depth--;
                if (depth == 0) { close = k; break; }
            }
        }
        if (close < 0 || close == i + 1) return -1;
        if (close + 1 >= to || s.charAt(close + 1) != '(') return -1;

        int u = close + 2;
        while (u < to && s.charAt(u) == ' ') u++;
        String url;
        int p;
        if (u < to && s.charAt(u) == '<') {
            int gt = s.indexOf('>', u + 1);
            if (gt < 0 || gt >= to) return -1;
            url = s.substring(u + 1, gt);
            p = gt + 1;
        } else {
            int par = 0;
            p = u;
            while (p < to) {
                char ch = s.charAt(p);
                if (Character.isWhitespace(ch)) break;
                if (ch == '(') par++;
                else if (ch == ')') {
                    if (par == 0) break;
                    par--;
                }
                p++;
            }
            url = s.substring(u, p);
        }
        while (p < to && s.charAt(p) == ' ') p++;
        if (p < to && (s.charAt(p) == '"' || s.charAt(p) == '\'')) {
            int qe = s.indexOf(s.charAt(p), p + 1);
            if (qe < 0 || qe >= to) return -1;
            p = qe + 1;
            while (p < to && s.charAt(p) == ' ') p++;
        }
        if (p >= to || s.charAt(p) != ')') return -1;
        if (!isHttp(url)) return -1;

        int st = out.length();
        inline(out, s, i + 1, close, false, true);
        if (out.length() == st) return -1;
        out.setSpan(new URLSpan(url), st, out.length(), FLAGS);
        return p + 1;
    }

    private static int angle(SpannableStringBuilder out, String s, int i, int to) {
        Matcher m = AUTO_LINK.matcher(s).region(i, to);
        if (m.lookingAt()) {
            String url = m.group(1);
            int st = out.length();
            out.append(url);
            out.setSpan(new URLSpan(url), st, out.length(), FLAGS);
            return m.end();
        }
        m = TOKEN.matcher(s).region(i, to);
        if (m.lookingAt()) {
            out.append(s, i, m.end());
            return m.end();
        }
        return -1;
    }

    private static int bareUrl(SpannableStringBuilder out, String s, int i, int to) {
        Matcher m = URL_START.matcher(s).region(i, to);
        if (!m.lookingAt()) return -1;
        int minEnd = m.end();
        int e = minEnd;
        while (e < to) {
            char ch = s.charAt(e);
            if (Character.isWhitespace(ch) || ch == '<' || ch == '>') break;
            e++;
        }
        while (e > minEnd) {
            char last = s.charAt(e - 1);
            if (".,:;\"'!?".indexOf(last) >= 0) { e--; continue; }
            if (last == ')' && count(s, i, e, '(') < count(s, i, e, ')')) { e--; continue; }
            if (last == ']' && count(s, i, e, '[') < count(s, i, e, ']')) { e--; continue; }
            break;
        }
        if (e <= minEnd) return -1;
        String url = s.substring(i, e);
        int st = out.length();
        out.append(url);
        out.setSpan(new URLSpan(url), st, out.length(), FLAGS);
        return e;
    }

    private static int count(String s, int from, int to, char c) {
        int n = 0;
        for (int k = from; k < to; k++) if (s.charAt(k) == c) n++;
        return n;
    }

    public static final class SpoilerSpan extends ClickableSpan {
        private boolean revealed;

        @Override public void onClick(View v) {
            revealed = !revealed;
            v.invalidate();
        }

        @Override public void updateDrawState(TextPaint ds) {
            ds.bgColor = revealed ? SPOILER_SHOWN_BG : SPOILER_HIDDEN;
            ds.setColor(revealed ? CODE_FG : SPOILER_HIDDEN);
        }
    }

    private static final class CodeBlockSpan implements LeadingMarginSpan, LineBackgroundSpan {
        @Override public int getLeadingMargin(boolean first) { return dp(8); }

        @Override public void drawLeadingMargin(Canvas c, Paint p, int x, int dir, int top, int baseline,
                                                int bottom, CharSequence text, int start, int end,
                                                boolean first, Layout layout) {}

        @Override public void drawBackground(Canvas c, Paint p, int left, int right, int top, int baseline,
                                             int bottom, CharSequence text, int start, int end, int lnum) {
            int old = p.getColor();
            p.setColor(CODE_BG);
            c.drawRect(left, top, right, bottom, p);
            p.setColor(old);
        }
    }

    private static final class QuoteBarSpan implements LeadingMarginSpan {
        @Override public int getLeadingMargin(boolean first) { return dp(4) + dp(10); }

        @Override public void drawLeadingMargin(Canvas c, Paint p, int x, int dir, int top, int baseline,
                                                int bottom, CharSequence text, int start, int end,
                                                boolean first, Layout layout) {
            int old = p.getColor();
            Paint.Style style = p.getStyle();
            p.setColor(QUOTE_BAR);
            p.setStyle(Paint.Style.FILL);
            int w = dp(4);
            c.drawRect(Math.min(x, x + dir * w), top, Math.max(x, x + dir * w), bottom, p);
            p.setColor(old);
            p.setStyle(style);
        }
    }

    private static final class ListSpan implements LeadingMarginSpan {
        private final String label;
        private final int margin;

        ListSpan(String label, int depth) {
            this.label = label;
            this.margin = dp(16) * (depth + 1) + dp(8);
        }

        @Override public int getLeadingMargin(boolean first) { return margin; }

        @Override public void drawLeadingMargin(Canvas c, Paint p, int x, int dir, int top, int baseline,
                                                int bottom, CharSequence text, int start, int end,
                                                boolean first, Layout layout) {
            if (!first) return;
            float w = p.measureText(label);
            float tx = dir > 0 ? x + margin - dp(6) - w : x - margin + dp(6);
            c.drawText(label, tx, baseline, p);
        }
    }
}
