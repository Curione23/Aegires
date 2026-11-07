package a.b.screen;

import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import a.b.model.Message;
import a.b.util.Ui;

/** Input pill, reply bar, attach bar, and E2EE toggle pill. */
final class MessageComposerView {
    private static final int MATCH = -1, WRAP = -2;
    private static final int HINT = Ui.MUTED, TEXT = Ui.TEXT;
    private static final String HINT_MESSAGE = "Message...";
    private static final String HINT_EDIT = "Editing...";
    private static final String HINT_ENCRYPTED = "Encrypted message...";
    private static final int REPLY_PREVIEW_LEN = 60;

    private final MessageListScreen host;

    private EditText input;
    private ImageView attachIcon, sendIcon;
    private TextView replyText, e2eePill, attachText;
    private LinearLayout attachBar, replyBar;

    private String editingId, replyToId;
    private android.net.Uri pendingFileUri;
    private String pendingFileName, pendingMimeType;
    private int pendingImageWidth, pendingImageHeight;

    MessageComposerView(MessageListScreen host) {
        this.host = host;
    }

    View buildPill() {
        attachIcon = new ImageView(host.activity);
        attachIcon.setPadding(dp(10), dp(10), dp(10), dp(10));
        attachIcon.setLayoutParams(new LinearLayout.LayoutParams(dp(44), dp(44)));
        attachIcon.setBackground(Ui.ripple(null, -1));
        attachIcon.setClickable(true);
        setAttachCancel(false);
        attachIcon.setOnClickListener(v -> {
            if (editingId != null) cancelEdit();
            else host.picker().pickImage();
        });

        input = new EditText(host.activity);
        input.setHint(host.encOn() ? HINT_ENCRYPTED : HINT_MESSAGE);
        input.setHintTextColor(HINT);
        input.setTextColor(TEXT);
        input.setTextSize(16);
        input.setBackgroundColor(Color.TRANSPARENT);
        input.setPadding(dp(4), dp(10), dp(4), dp(10));
        input.setMaxLines(6);
        input.setInputType(android.text.InputType.TYPE_CLASS_TEXT
            | android.text.InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
            | android.text.InputType.TYPE_TEXT_FLAG_MULTI_LINE);
        input.addTextChangedListener(new android.text.TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int a, int b, int c) {}
            @Override public void onTextChanged(CharSequence s, int a, int b, int c) {}
            @Override public void afterTextChanged(android.text.Editable e) { updateSendState(); }
        });

        sendIcon = new ImageView(host.activity);
        sendIcon.setPadding(dp(10), dp(10), dp(10), dp(10));
        sendIcon.setLayoutParams(new LinearLayout.LayoutParams(dp(44), dp(44)));
        sendIcon.setBackground(Ui.ripple(null, -1));
        sendIcon.setClickable(true);
        sendIcon.setOnClickListener(v -> host.sendMessage());
        updateSendState();

        LinearLayout pill = new LinearLayout(host.activity);
        pill.setGravity(Gravity.CENTER_VERTICAL);
        pill.setPadding(dp(4), 0, dp(4), 0);
        pill.setBackground(rounded(Ui.BG_INPUT, 22));
        pill.addView(attachIcon);
        pill.addView(input, new LinearLayout.LayoutParams(0, WRAP, 1f));
        pill.addView(sendIcon);

        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(MATCH, WRAP);
        lp.setMargins(dp(10), dp(6), dp(10), dp(10));
        pill.setLayoutParams(lp);
        return pill;
    }

    View buildReplyBar() {
        replyText = new TextView(host.activity);
        replyText.setTextColor(HINT);
        replyText.setTextSize(13);
        replyText.setSingleLine();
        replyText.setEllipsize(TextUtils.TruncateAt.END);

        FrameLayout cancel = Ui.iconButton(host.activity, Ui.I_CLOSE, HINT);
        cancel.setLayoutParams(new LinearLayout.LayoutParams(dp(36), dp(36)));
        cancel.setOnClickListener(v -> cancelReply());

        replyBar = new LinearLayout(host.activity);
        replyBar.setGravity(Gravity.CENTER_VERTICAL);
        replyBar.setPadding(dp(16), dp(2), dp(8), dp(2));
        replyBar.setBackgroundColor(Ui.BG_SECONDARY);
        replyBar.setVisibility(View.GONE);
        replyBar.addView(replyText, new LinearLayout.LayoutParams(0, WRAP, 1f));
        replyBar.addView(cancel);
        replyBar.setLayoutParams(new LinearLayout.LayoutParams(MATCH, WRAP));
        return replyBar;
    }

    View buildAttachBar() {
        attachText = new TextView(host.activity);
        attachText.setTextColor(TEXT);
        attachText.setTextSize(13);
        attachText.setSingleLine();
        attachText.setEllipsize(TextUtils.TruncateAt.MIDDLE);

        FrameLayout cancel = Ui.iconButton(host.activity, Ui.I_CLOSE, HINT);
        cancel.setLayoutParams(new LinearLayout.LayoutParams(dp(36), dp(36)));
        cancel.setOnClickListener(v -> clearAttachment());

        attachBar = new LinearLayout(host.activity);
        attachBar.setGravity(Gravity.CENTER_VERTICAL);
        attachBar.setPadding(dp(16), dp(2), dp(8), dp(2));
        attachBar.setBackgroundColor(Ui.BG_SECONDARY);
        attachBar.setVisibility(View.GONE);
        ImageView clip = Ui.iconView(host.activity, Ui.I_ATTACH, HINT, 16);
        LinearLayout.LayoutParams clipLp = new LinearLayout.LayoutParams(dp(16), dp(16));
        clipLp.setMarginEnd(dp(8));
        attachBar.addView(clip, clipLp);
        attachBar.addView(attachText, new LinearLayout.LayoutParams(0, WRAP, 1f));
        attachBar.addView(cancel);
        attachBar.setLayoutParams(new LinearLayout.LayoutParams(MATCH, WRAP));
        return attachBar;
    }

    View buildE2eePill() {
        e2eePill = new TextView(host.activity);
        e2eePill.setText("");
        e2eePill.setTextColor(Color.WHITE);
        e2eePill.setTextSize(12);
        e2eePill.setTypeface(null, Typeface.BOLD);
        e2eePill.setGravity(Gravity.CENTER_VERTICAL);
        e2eePill.setPadding(dp(10), dp(5), dp(10), dp(5));
        e2eePill.setCompoundDrawablePadding(dp(4));
        e2eePill.setOnClickListener(v -> toggleEncryption());
        LinearLayout.LayoutParams pillLp = new LinearLayout.LayoutParams(WRAP, WRAP);
        pillLp.setMarginEnd(dp(8));
        e2eePill.setLayoutParams(pillLp);
        updateE2eePillState();
        return e2eePill;
    }

    // ── state queries ───────────────────────────────────────────────────────

    String text() { return input == null ? "" : input.getText().toString(); }
    String getEditingId() { return editingId; }
    String getReplyToId() { return replyToId; }
    android.net.Uri getPendingFileUri() { return pendingFileUri; }
    String getPendingFileName() { return pendingFileName; }
    String getPendingMimeType() { return pendingMimeType; }
    int getPendingImageWidth() { return pendingImageWidth; }
    int getPendingImageHeight() { return pendingImageHeight; }

    void clearText() { if (input != null) input.setText(""); }
    void restoreText(String text) {
        if (input == null) return;
        input.setText(text);
        input.setSelection(text.length());
    }

    void requestInputFocus() { if (input != null) input.requestFocus(); }

    void setAttachment(android.net.Uri uri, String name, String mime, int w, int h) {
        pendingFileUri = uri;
        pendingFileName = name;
        pendingMimeType = mime;
        pendingImageWidth = w;
        pendingImageHeight = h;
        showAttachment();
    }

    void clearAttachment() {
        pendingFileUri = null;
        pendingFileName = null;
        pendingMimeType = null;
        pendingImageWidth = 0;
        pendingImageHeight = 0;
        showAttachment();
    }

    void beginReply(Message msg) {
        if (editingId != null) cancelEdit();
        replyToId = msg.id;
        String preview = msg.content == null ? "" : msg.content.replace('\n', ' ');
        if (preview.length() > REPLY_PREVIEW_LEN) preview = preview.substring(0, REPLY_PREVIEW_LEN) + "\u2026";
        replyText.setText("Replying to " + host.actions().nameOf(msg) + (preview.isEmpty() ? "" : ": " + preview));
        replyBar.setVisibility(View.VISIBLE);
        input.requestFocus();
    }

    void cancelReply() {
        replyToId = null;
        replyBar.setVisibility(View.GONE);
    }

    void beginEdit(Message msg) {
        cancelReply();
        editingId = msg.id;
        String content = msg.content == null ? "" : msg.content;
        input.setText(content);
        input.setSelection(content.length());
        input.setHint(HINT_EDIT);
        input.requestFocus();
        setAttachCancel(true);
        android.view.inputmethod.InputMethodManager imm =
            (android.view.inputmethod.InputMethodManager) host.activity.getSystemService(android.content.Context.INPUT_METHOD_SERVICE);
        imm.showSoftInput(input, android.view.inputmethod.InputMethodManager.SHOW_IMPLICIT);
        host.scrollToMessage(msg.id);
    }

    void cancelEdit() {
        editingId = null;
        input.setText("");
        input.setHint(host.encOn() ? HINT_ENCRYPTED : HINT_MESSAGE);
        setAttachCancel(false);
    }

    private void toggleEncryption() {
        if (host.forced || host.activity == null || host.activity.e2eeKey == null) return;
        host.activity.e2eeEnabled = !host.activity.e2eeEnabled;
        updateE2eePillState();
        if (input != null) {
            input.setHint(host.activity.e2eeEnabled ? HINT_ENCRYPTED : HINT_MESSAGE);
        }
        host.activity.toast(host.activity.e2eeEnabled ? "Encryption enabled" : "Encryption disabled");
    }

    void updateE2eePillState() {
        if (host.activity == null || e2eePill == null) return;
        if (host.forced || host.activity.e2eeKey == null) {
            e2eePill.setVisibility(View.GONE);
            return;
        }
        e2eePill.setVisibility(View.VISIBLE);
        boolean active = host.activity.e2eeEnabled;
        GradientDrawable pillBg = rounded(active ? 0x335865F2 : Ui.BG_SECONDARY, 12);
        pillBg.setStroke(dp(1), active ? Ui.BLURPLE : Ui.DIVIDER);
        int color = active ? Color.WHITE : HINT;
        Ui.Icon lock = new Ui.Icon(Ui.I_LOCK, color);
        lock.setBounds(0, 0, dp(12), dp(12));
        e2eePill.setCompoundDrawables(lock, null, null, null);
        e2eePill.setText(active ? "E2EE" : "OFF");
        e2eePill.setTextColor(color);
        e2eePill.setBackground(pillBg);
    }

    private void showAttachment() {
        if (attachBar == null) return;
        boolean has = pendingFileUri != null;
        attachBar.setVisibility(has ? View.VISIBLE : View.GONE);
        if (has) attachText.setText(pendingFileName != null ? pendingFileName : "attachment");
        updateSendState();
    }

    private void updateSendState() {
        if (sendIcon == null || input == null) return;
        boolean can = input.getText().toString().trim().length() > 0
            || (editingId == null && pendingFileUri != null);
        sendIcon.setImageDrawable(new Ui.Icon(Ui.I_SEND, can ? Ui.BLURPLE : HINT));
    }

    private void setAttachCancel(boolean cancel) {
        if (attachIcon == null) return;
        attachIcon.setImageDrawable(new Ui.Icon(cancel ? Ui.I_CLOSE : Ui.I_PLUS, HINT));
    }

    private GradientDrawable rounded(int color, int radiusDp) {
        GradientDrawable g = new GradientDrawable();
        g.setColor(color);
        g.setCornerRadius(dp(radiusDp));
        return g;
    }

    void release() {
        editingId = null;
        replyToId = null;
    }

    private int dp(int v) { return (int) (v * host.density); }
}