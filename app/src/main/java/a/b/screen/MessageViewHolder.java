package a.b.screen;

import android.content.Context;
import android.graphics.Color;
import android.graphics.Typeface;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import androidx.recyclerview.widget.RecyclerView;
import java.util.ArrayList;
import java.util.List;
import a.b.model.Embed;
import a.b.model.Message;
import a.b.util.Crypto;
import a.b.util.ImageLoader;
import a.b.util.Ui;

/** A single message row. */
public final class MessageViewHolder extends RecyclerView.ViewHolder implements View.OnLongClickListener {
    private static final int WRAP = -2;
    private static final int HORIZONTAL = LinearLayout.HORIZONTAL;
    private static final int VERTICAL = LinearLayout.VERTICAL;
    private static final int BG = MessageAdapter.BG;
    private static final int TEXT = MessageAdapter.TEXT;
    private static final int MUTED = MessageAdapter.MUTED;
    private static final int LINK_FG = MessageAdapter.LINK_FG;

    private final MessageAdapter adapter;
    final LinearLayout root, header, body, attachments, embeds, linkPreviews;
    final FrameLayout avatarWrap;
    final ImageView avatar, avatarIcon;
    final TextView name, time, reply, content;
    Message msg;

    MessageViewHolder(MessageAdapter adapter, Context context) {
        super(new LinearLayout(context));
        this.adapter = adapter;
        root = (LinearLayout) itemView;
        root.setLayoutParams(new RecyclerView.LayoutParams(RecyclerView.LayoutParams.MATCH_PARENT, RecyclerView.LayoutParams.WRAP_CONTENT));
        root.setOrientation(HORIZONTAL);
        root.setBackgroundColor(BG);
        root.setPadding(adapter.dp(12), adapter.dp(10), adapter.dp(12), 0);
        root.setOnLongClickListener(this);

        avatarWrap = new FrameLayout(context);
        avatarWrap.setBackground(Ui.oval(Ui.AVATAR_COLORS[0]));
        avatarWrap.setClipToOutline(true);
        root.addView(avatarWrap, lp(adapter.avatarSize, adapter.avatarSize, 0, adapter.dp(2), adapter.dp(12), 0));

        avatarIcon = Ui.iconView(context, Ui.I_USER, Color.WHITE, 24);
        avatarIcon.setScaleType(ImageView.ScaleType.CENTER_INSIDE);
        avatarWrap.addView(avatarIcon, new FrameLayout.LayoutParams(-1, -1));

        avatar = new ImageView(context);
        avatar.setScaleType(ImageView.ScaleType.CENTER_CROP);
        avatarWrap.addView(avatar, new FrameLayout.LayoutParams(-1, -1));

        body = new LinearLayout(context);
        body.setOrientation(VERTICAL);

        header = new LinearLayout(context);
        header.setOrientation(HORIZONTAL);
        header.setGravity(Gravity.CENTER_VERTICAL);

        name = new TextView(context);
        name.setTextColor(Color.WHITE);
        name.setTypeface(null, Typeface.BOLD);
        name.setTextSize(16);
        name.setSingleLine();
        name.setEllipsize(TextUtils.TruncateAt.END);
        name.setMaxWidth((int) (context.getResources().getDisplayMetrics().widthPixels * 0.6f));
        LinearLayout.LayoutParams nameLp = new LinearLayout.LayoutParams(WRAP, WRAP);
        nameLp.setMarginEnd(adapter.dp(8));

        time = new TextView(context);
        time.setTextColor(MUTED);
        time.setTextSize(12);
        time.setSingleLine();

        header.addView(name, nameLp);
        header.addView(time);

        reply = new TextView(context);
        reply.setTextColor(MUTED);
        reply.setTextSize(12);
        reply.setSingleLine();
        reply.setEllipsize(TextUtils.TruncateAt.END);
        reply.setPadding(0, 0, 0, adapter.dp(2));
        reply.setVisibility(View.GONE);

        content = new TextView(context);
        content.setTextColor(TEXT);
        content.setTextSize(16);
        content.setLinkTextColor(LINK_FG);
        content.setMovementMethod(android.text.method.LinkMovementMethod.getInstance());
        content.setLineSpacing(0, 1.12f);
        content.setOnLongClickListener(this);

        attachments = new LinearLayout(context);
        attachments.setOrientation(VERTICAL);
        embeds = new LinearLayout(context);
        embeds.setOrientation(VERTICAL);
        linkPreviews = new LinearLayout(context);
        linkPreviews.setOrientation(VERTICAL);

        body.addView(header);
        body.addView(reply);
        body.addView(content);
        body.addView(attachments);
        body.addView(embeds);
        body.addView(linkPreviews);
        root.addView(body, new LinearLayout.LayoutParams(0, WRAP, 1f));
    }

    void bind(Message msg, int pos) {
        if (adapter.activity == null || msg == null) return;
        boolean showHeader = pos == 0 || !MessageAdapter.sameGroup(adapter.displayList.get(pos - 1), msg);
        adapter.bindHeader(this, msg, showHeader);
        root.setBackgroundColor(adapter.mentionsSelf(msg) ? 0xFF3A3A33 : BG);

        CharSequence text;
        if (msg.encrypted && adapter.key() == null) {
            text = "\uD83D\uDD12 Encrypted message (key missing)";
        } else if (msg.encrypted && msg.content != null && Crypto.looksEncrypted(msg.content)) {
            text = "\uD83D\uDD12 Encrypted message (can't decrypt)";
        } else if (msg.id == null) {
            text = adapter.render(msg.content, msg);
        } else {
            text = adapter.parsedContentCache.get(msg.id);
            if (text == null) {
                text = adapter.render(msg.content, msg);
                adapter.parsedContentCache.put(msg.id, text);
            }
        }
        content.setText(text);
        content.setVisibility(text.length() > 0 ? View.VISIBLE : View.GONE);

        boolean isReply = msg.replyToMessageId != null;
        reply.setVisibility(isReply ? View.VISIBLE : View.GONE);
        if (isReply) {
            if (msg.replyToContent != null && Crypto.looksEncrypted(msg.replyToContent)) {
                reply.setText("\u21B3 \uD83D\uDD12 [encrypted reply]");
            } else {
                reply.setText(adapter.replyPreview(msg));
            }
        }

        List<View> oldMedia = new ArrayList<>();
        for (int i = 0; i < attachments.getChildCount(); i++) oldMedia.add(attachments.getChildAt(i));
        for (int i = 0; i < embeds.getChildCount(); i++) oldMedia.add(embeds.getChildAt(i));
        for (int i = 0; i < linkPreviews.getChildCount(); i++) oldMedia.add(linkPreviews.getChildAt(i));
        attachments.removeAllViews();
        if (msg.attachments != null) {
            for (Message.Attachment a : msg.attachments) {
                if (a != null) add(attachments, adapter.attachmentView(a, msg, root));
            }
        }

        embeds.removeAllViews();
        if (msg.embeds != null) {
            for (Embed e : msg.embeds) {
                if (e != null && !EmbedViewFactory.duplicatesAttachment(e, msg.attachments)) add(embeds, adapter.embedView(e, msg, root));
            }
        }

        linkPreviews.removeAllViews();
        for (View preview : adapter.linkPreviewViews(msg, root)) {
            if (preview != null) add(linkPreviews, preview);
        }
        for (View old : oldMedia) ImageLoader.cancelTree(old);
    }

    private void add(LinearLayout box, View child) {
        if (child == null) return;
        child.setOnLongClickListener(this);
        box.addView(child);
    }

    @Override public boolean onLongClick(View v) {
        if (adapter.longPressListener == null || msg == null) return false;
        adapter.longPressListener.onMessageLongPress(msg, v);
        return true;
    }

    private static LinearLayout.LayoutParams lp(int w, int h, int l, int t, int r, int b) {
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(w, h);
        p.setMargins(l, t, r, b);
        return p;
    }
}