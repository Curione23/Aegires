package a.b.util;

import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.Canvas;
import android.graphics.ColorFilter;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.graphics.Typeface;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.RippleDrawable;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import androidx.core.graphics.PathParser;

/** Shared design tokens and tiny view helpers so every screen looks the same. */
public final class Ui {
    private Ui() {}

    public static final int BG = 0xFF313338;
    public static final int BG_SECONDARY = 0xFF2B2D31;
    public static final int BG_TERTIARY = 0xFF1E1F22;
    public static final int BG_INPUT = 0xFF383A40;
    public static final int DIVIDER = 0xFF3F4147;
    public static final int TEXT = 0xFFDBDEE1;
    public static final int TEXT_HEADER = 0xFFF2F3F5;
    public static final int MUTED = 0xFF949BA4;
    public static final int BLURPLE = 0xFF5865F2;
    public static final int RED = 0xFFF23F43;
    public static final int GREEN = 0xFF23A55A;
    public static final int YELLOW = 0xFFF0B232;
    public static final int LINK = 0xFF00A8FC;

    public static final int[] AVATAR_COLORS = {
        0xFF5865F2, 0xFF3BA55D, 0xFFFAA81A, 0xFFED4245,
        0xFFEB459E, 0xFF9B59B6, 0xFF1ABC9C, 0xFFE67E22,
    };

    public static final String I_BACK = "M20,11H7.83l5.59,-5.59L12,4l-8,8 8,8 1.41,-1.41L7.83,13H20v-2z";
    public static final String I_CLOSE = "M19,6.41L17.59,5 12,10.59 6.41,5 5,6.41 10.59,12 5,17.59 6.41,19 12,13.41 17.59,19 19,17.59 13.41,12z";
    public static final String I_SEND = "M2.01,21L23,12 2.01,3 2,10l15,2 -15,2z";
    public static final String I_PLUS = "M19,13h-6v6h-2v-6H5v-2h6V5h2v6h6v2z";
    public static final String I_BELL = "M12,22c1.1,0 2,-0.9 2,-2h-4c0,1.1 0.89,2 2,2zM18,16v-5c0,-3.07 -1.64,-5.64 -4.5,-6.32V4c0,-0.83 -0.67,-1.5 -1.5,-1.5s-1.5,0.67 -1.5,1.5v0.68C7.63,5.36 6,7.92 6,11v5l-2,2v1h16v-1l-2,-2z";
    public static final String I_CHAT = "M20,2H4c-1.1,0 -1.99,0.9 -1.99,2L2,22l4,-4h14c1.1,0 2,-0.9 2,-2V4c0,-1.1 -0.9,-2 -2,-2z";
    public static final String I_GEAR = "M19.14,12.94c0.04,-0.3 0.06,-0.61 0.06,-0.94c0,-0.32 -0.02,-0.64 -0.07,-0.94l2.03,-1.58c0.18,-0.14 0.23,-0.41 0.12,-0.61l-1.92,-3.32c-0.12,-0.22 -0.37,-0.29 -0.59,-0.22l-2.39,0.96c-0.5,-0.38 -1.03,-0.7 -1.62,-0.94L14.4,2.81c-0.04,-0.24 -0.24,-0.41 -0.48,-0.41h-3.84c-0.24,0 -0.43,0.17 -0.47,0.41L9.25,5.35C8.66,5.59 8.12,5.92 7.63,6.29L5.24,5.33c-0.22,-0.08 -0.47,0 -0.59,0.22L2.74,8.87C2.62,9.08 2.66,9.34 2.86,9.48l2.03,1.58C4.84,11.36 4.8,11.69 4.8,12s0.02,0.64 0.07,0.94l-2.03,1.58c-0.18,0.14 -0.23,0.41 -0.12,0.61l1.92,3.32c0.12,0.22 0.37,0.29 0.59,0.22l2.39,-0.96c0.5,0.38 1.03,0.7 1.62,0.94l0.36,2.54c0.05,0.24 0.24,0.41 0.48,0.41h3.84c0.24,0 0.44,-0.17 0.47,-0.41l0.36,-2.54c0.59,-0.24 1.13,-0.56 1.62,-0.94l2.39,0.96c0.22,0.08 0.47,0 0.59,-0.22l1.92,-3.32c0.12,-0.22 0.07,-0.47 -0.12,-0.61L19.14,12.94zM12,15.6c-1.98,0 -3.6,-1.62 -3.6,-3.6s1.62,-3.6 3.6,-3.6s3.6,1.62 3.6,3.6S13.98,15.6 12,15.6z";
    public static final String I_LOCK = "M18,8h-1V6c0,-2.76 -2.24,-5 -5,-5S7,3.24 7,6v2H6c-1.1,0 -2,0.9 -2,2v10c0,1.1 0.9,2 2,2h12c1.1,0 2,-0.9 2,-2V10c0,-1.1 -0.9,-2 -2,-2zM12,17c-1.1,0 -2,-0.9 -2,-2s0.9,-2 2,-2 2,0.9 2,2 -0.9,2 -2,2zM9,8V6c0,-1.66 1.34,-3 3,-3s3,1.34 3,3v2H9z";
    public static final String I_CHEVRON_DOWN = "M7.41,8.59L12,13.17l4.59,-4.58L18,10l-6,6 -6,-6z";
    public static final String I_CHEVRON_RIGHT = "M8.59,16.59L13.17,12 8.59,7.41 10,6l6,6 -6,6z";
    public static final String I_DONE_ALL = "M18,7l-1.41,-1.41 -6.34,6.34 1.41,1.41L18,7zM22.24,5.59L11.66,16.17 7.48,12l-1.41,1.41L11.66,19l12,-12 -1.42,-1.41zM0.41,13.41L6,19l1.41,-1.41L1.83,12 0.41,13.41z";
    public static final String I_COPY = "M16,1H4c-1.1,0 -2,0.9 -2,2v14h2V3h12V1zM19,5H8c-1.1,0 -2,0.9 -2,2v14c0,1.1 0.9,2 2,2h11c1.1,0 2,-0.9 2,-2V7c0,-1.1 -0.9,-2 -2,-2zM19,21H8V7h11v14z";
    public static final String I_PEOPLE = "M16,11c1.66,0 2.99,-1.34 2.99,-3S17.66,5 16,5c-1.66,0 -3,1.34 -3,3s1.34,3 3,3zM8,11c1.66,0 2.99,-1.34 2.99,-3S9.66,5 8,5C6.34,5 5,6.34 5,8s1.34,3 3,3zM8,13c-2.33,0 -7,1.17 -7,3.5V19h14v-2.5c0,-2.33 -4.67,-3.5 -7,-3.5zM16,13c-0.29,0 -0.62,0.02 -0.97,0.05 1.16,0.84 1.97,1.97 1.97,3.45V19h6v-2.5c0,-2.33 -4.67,-3.5 -7,-3.5z";
    public static final String I_ATTACH = "M16.5,6v11.5c0,2.21 -1.79,4 -4,4s-4,-1.79 -4,-4V5c0,-1.38 1.12,-2.5 2.5,-2.5s2.5,1.12 2.5,2.5v10.5c0,0.55 -0.45,1 -1,1s-1,-0.45 -1,-1V6H10v9.5c0,1.38 1.12,2.5 2.5,2.5s2.5,-1.12 2.5,-2.5V5c0,-2.21 -1.79,-4 -4,-4S7,2.79 7,5v12.5c0,3.04 2.46,5.5 5.5,5.5s5.5,-2.46 5.5,-5.5V6h-1.5z";

    public static int dp(Context c, float v) {
        return Math.round(v * c.getResources().getDisplayMetrics().density);
    }

    public static GradientDrawable rounded(int color, float radiusPx) {
        GradientDrawable g = new GradientDrawable();
        g.setColor(color);
        g.setCornerRadius(radiusPx);
        return g;
    }

    public static GradientDrawable oval(int color) {
        GradientDrawable g = new GradientDrawable();
        g.setShape(GradientDrawable.OVAL);
        g.setColor(color);
        return g;
    }

    public static Drawable ripple(Drawable content, float cornerPx) {
        GradientDrawable mask = new GradientDrawable();
        if (cornerPx < 0) mask.setShape(GradientDrawable.OVAL);
        else mask.setCornerRadius(cornerPx);
        mask.setColor(0xFFFFFFFF);
        return new RippleDrawable(ColorStateList.valueOf(0x33FFFFFF), content, mask);
    }

    public static int avatarColor(String id) {
        if (id == null) return AVATAR_COLORS[0];
        try {
            return AVATAR_COLORS[(int) (Math.abs(Long.parseLong(id) >> 22) % AVATAR_COLORS.length)];
        } catch (Exception e) {
            return AVATAR_COLORS[Math.abs(id.hashCode()) % AVATAR_COLORS.length];
        }
    }

    public static boolean isReal(String s) {
        return s != null && !s.isEmpty() && !"null".equals(s);
    }


    public static final class Icon extends Drawable {
        private final Path src;
        private final Path dst = new Path();
        private final Matrix m = new Matrix();
        private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);

        public Icon(String pathData, int color) {
            Path parsed = null;
            try { parsed = PathParser.createPathFromPathData(pathData); } catch (Throwable ignored) {}
            src = parsed != null ? parsed : new Path();
            p.setColor(color);
        }

        @Override public void draw(Canvas canvas) {
            Rect b = getBounds();
            if (b.isEmpty()) return;
            m.setScale(b.width() / 24f, b.height() / 24f);
            m.postTranslate(b.left, b.top);
            src.transform(m, dst);
            canvas.drawPath(dst, p);
        }
        @Override public void setAlpha(int alpha) { p.setAlpha(alpha); invalidateSelf(); }
        @Override public void setColorFilter(ColorFilter cf) { p.setColorFilter(cf); invalidateSelf(); }
        @Override public int getOpacity() { return PixelFormat.TRANSLUCENT; }
    }

    public static ImageView iconView(Context c, String path, int color, int sizeDp) {
        ImageView iv = new ImageView(c);
        iv.setImageDrawable(new Icon(path, color));
        iv.setLayoutParams(new LinearLayout.LayoutParams(dp(c, sizeDp), dp(c, sizeDp)));
        return iv;
    }


    public static TextView text(Context c, CharSequence s, int color, float sp) {
        TextView t = new TextView(c);
        t.setText(s);
        t.setTextColor(color);
        t.setTextSize(sp);
        return t;
    }

    public static TextView badge(Context c) {
        TextView b = new TextView(c);
        b.setTextColor(0xFFFFFFFF);
        b.setTextSize(10);
        b.setTypeface(null, Typeface.BOLD);
        b.setGravity(Gravity.CENTER);
        b.setSingleLine(true);
        b.setMinWidth(dp(c, 16));
        b.setMinHeight(dp(c, 16));
        b.setPadding(dp(c, 4), 0, dp(c, 4), 0);
        b.setBackground(rounded(RED, dp(c, 8)));
        b.setVisibility(View.GONE);
        return b;
    }

    public static void setBadge(TextView badge, int count) {
        if (badge == null) return;
        if (count <= 0) { badge.setVisibility(View.GONE); return; }
        badge.setText(count > 99 ? "99+" : String.valueOf(count));
        badge.setVisibility(View.VISIBLE);
    }

    public static TextView sectionLabel(Context c, String s) {
        TextView t = text(c, s.toUpperCase(java.util.Locale.US), MUTED, 12);
        t.setTypeface(null, Typeface.BOLD);
        t.setLetterSpacing(0.04f);
        return t;
    }

    public static TextView emptyState(Context c, String s) {
        TextView t = text(c, s, MUTED, 14);
        t.setGravity(Gravity.CENTER);
        t.setPadding(dp(c, 32), dp(c, 48), dp(c, 32), dp(c, 48));
        t.setLayoutParams(new LinearLayout.LayoutParams(-1, -2));
        return t;
    }

    public static TextView primaryButton(Context c, String label) {
        TextView b = text(c, label, 0xFFFFFFFF, 15);
        b.setTypeface(null, Typeface.BOLD);
        b.setGravity(Gravity.CENTER);
        b.setClickable(true);
        b.setBackground(ripple(rounded(BLURPLE, dp(c, 8)), dp(c, 8)));
        return b;
    }

    public static final class AppBar {
        public final LinearLayout view;
        public final LinearLayout bar;
        public final LinearLayout titleCol;
        public final TextView title, subtitle;
        public final LinearLayout actions;
        private final Context c;

        public AppBar(Context c, String titleText, int bgColor, final Runnable onBack) {
            this.c = c;
            view = new LinearLayout(c);
            view.setOrientation(LinearLayout.VERTICAL);

            bar = new LinearLayout(c);
            bar.setOrientation(LinearLayout.HORIZONTAL);
            bar.setGravity(Gravity.CENTER_VERTICAL);
            bar.setBackgroundColor(bgColor);
            bar.setPadding(dp(c, onBack != null ? 4 : 16), 0, dp(c, 4), 0);

            if (onBack != null) {
                FrameLayout back = iconButton(c, I_BACK, TEXT_HEADER);
                back.setOnClickListener(v -> onBack.run());
                bar.addView(back);
            }

            titleCol = new LinearLayout(c);
            titleCol.setOrientation(LinearLayout.VERTICAL);
            titleCol.setGravity(Gravity.CENTER_VERTICAL);
            title = text(c, titleText, TEXT_HEADER, 17);
            title.setTypeface(null, Typeface.BOLD);
            title.setSingleLine(true);
            title.setEllipsize(TextUtils.TruncateAt.END);
            subtitle = text(c, "", MUTED, 12);
            subtitle.setSingleLine(true);
            subtitle.setEllipsize(TextUtils.TruncateAt.END);
            subtitle.setVisibility(View.GONE);
            titleCol.addView(title);
            titleCol.addView(subtitle);
            bar.addView(titleCol, new LinearLayout.LayoutParams(0, -2, 1f));

            actions = new LinearLayout(c);
            actions.setOrientation(LinearLayout.HORIZONTAL);
            actions.setGravity(Gravity.CENTER_VERTICAL);
            bar.addView(actions);

            view.addView(bar, new LinearLayout.LayoutParams(-1, dp(c, 52)));
            View div = new View(c);
            div.setBackgroundColor(0x33000000);
            view.addView(div, new LinearLayout.LayoutParams(-1, Math.max(1, dp(c, 1) / 2)));
        }

        public void setSubtitle(String s) {
            if (s == null || s.isEmpty()) { subtitle.setVisibility(View.GONE); return; }
            subtitle.setText(s);
            subtitle.setVisibility(View.VISIBLE);
        }

        public ActionButton addAction(String iconPath, View.OnClickListener l) {
            ActionButton a = new ActionButton(c, iconPath);
            a.view.setOnClickListener(l);
            actions.addView(a.view);
            return a;
        }
    }

    public static final class ActionButton {
        public final FrameLayout view;
        public final ImageView icon;
        private final TextView badge;

        ActionButton(Context c, String path) {
            view = iconButton(c, path, 0xFFB5BAC1);
            icon = (ImageView) view.getChildAt(0);
            badge = badge(c);
            FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(-2, -2, Gravity.TOP | Gravity.END);
            lp.topMargin = dp(c, 6);
            lp.setMarginEnd(dp(c, 4));
            view.addView(badge, lp);
        }

        public void setCount(int n) { setBadge(badge, n); }
        public void setIcon(String path, int color) { icon.setImageDrawable(new Icon(path, color)); }
        public void setEnabledLook(boolean on) { view.setAlpha(on ? 1f : 0.4f); view.setEnabled(on); }
    }

    public static FrameLayout iconButton(Context c, String path, int color) {
        FrameLayout f = new FrameLayout(c);
        f.setLayoutParams(new LinearLayout.LayoutParams(dp(c, 44), dp(c, 44)));
        f.setClickable(true);
        f.setBackground(ripple(null, -1));
        ImageView iv = new ImageView(c);
        iv.setImageDrawable(new Icon(path, color));
        f.addView(iv, new FrameLayout.LayoutParams(dp(c, 24), dp(c, 24), Gravity.CENTER));
        return f;
    }

    public static ViewGroup.LayoutParams matchWrap() { return new ViewGroup.LayoutParams(-1, -2); }
}
