package a.b.screen;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.ActivityNotFoundException;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.ContentResolver;
import android.content.Context;
import android.content.Intent;
import android.database.Cursor;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.provider.OpenableColumns;
import android.text.TextUtils;
import android.view.*;
import android.view.inputmethod.InputMethodManager;
import android.widget.*;
import androidx.recyclerview.widget.ConcatAdapter;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;
import org.json.JSONArray;
import org.json.JSONObject;
import java.io.InputStream;
import java.util.*;
import a.b.MainActivity;
import a.b.api.ApiClient;
import a.b.model.Message;
import a.b.model.Reaction;
import a.b.util.Crypto;

public class MessageListScreen implements Screen {
    private static final int DARK = 0xFF101010, REPLY_BAR_BG = 0xFF1E1F22;
    private static final int HINT = 0xFF949BA4, TEXT = 0xFFDBDEE1;
    private static final int TYPING_BG = 0xFF000000;
    private static final int PICK_IMAGE_REQUEST = 1001;
    private static final int MAX_MESSAGES = 1000;
    private static final int PAGE_SIZE = 30;
    private static final int REPLY_PREVIEW_LEN = 60;
    private static final int MATCH = -1, WRAP = -2;
    private static final int MENU_REPLY = 1, MENU_COPY = 2, MENU_EDIT = 3, MENU_DELETE = 4;
    private static final String HINT_MESSAGE = "Message...";
    private static final String HINT_EDIT = "Editing...";
    private static final String HINT_ENCRYPTED = "Encrypted message...";
    private static final String ATTACH_GLYPH = "\uD83D\uDCCE";
    private static final String CANCEL_GLYPH = "\u2715";
    private static final String SEND_GLYPH = "\u27A4";
    private static final String HASH_GLYPH = "#";
    private static final long TYPING_EXPIRY_MS = 10000;

    private static final Comparator<Message> BY_ID = (a, b) ->
        a.id.length() != b.id.length() ? a.id.length() - b.id.length() : a.id.compareTo(b.id);

    MainActivity activity;
    final String guildId;
    final String channelId;
    public MessageAdapter adapter;
    public List<Message> messages = new ArrayList<>();
    private final Set<String> messageIds = new HashSet<>();
    private float density;
    private View view;
    private RecyclerView messageList;
    private LinearLayoutManager layoutManager;
    private EditText input;
    private TextView attachIcon, replyText, channelNameTv, typingText;
    private LinearLayout replyBar;
    private LoadingAdapter loadingAdapter;
    private boolean loadingNew, loadingOld, allLoaded, sending;
    private Uri pendingFileUri;
    private String pendingFileName, pendingMimeType;
    private String editingId, replyToId;
    private final Map<String, String> typingUsers = new HashMap<>();
    private final Handler typingHandler = new Handler(Looper.getMainLooper());
    private final Runnable clearTypingRunnable = () -> {
        typingUsers.clear();
        updateTypingIndicator();
    };
    private int pendingImageWidth = 0;
    private int pendingImageHeight = 0;

    public MessageListScreen(String guildId, String channelId) {
        this.guildId = guildId;
        this.channelId = channelId;
    }

    @Override
    public View onCreateView(MainActivity activity, ViewGroup parent) {
        this.activity = activity;
        density = activity.getResources().getDisplayMetrics().density;
        LinearLayout root = new LinearLayout(activity);
        root.setOrientation(LinearLayout.VERTICAL);
        root.addView(buildHeader());
        root.addView(buildList(), new LinearLayout.LayoutParams(MATCH, 0, 1f));
        root.addView(buildTypingIndicator());
        root.addView(buildReplyBar());
        root.addView(buildInputPill());
        
        androidx.core.view.ViewCompat.setOnApplyWindowInsetsListener(root, (v, windowInsets) -> {
            androidx.core.graphics.Insets ime = windowInsets.getInsets(androidx.core.view.WindowInsetsCompat.Type.ime());
            if (ime.bottom > 0 && adapter != null && adapter.getItemCount() > 0) {
                messageList.postDelayed(this::scrollToBottom, 100);
            }
            return windowInsets;
        });
        
        subscribeAndLoad();
        return view = root;
    }

    private View buildHeader() {
        LinearLayout header = new LinearLayout(activity);
        header.setGravity(Gravity.CENTER_VERTICAL);
        header.setPadding(dp(16), dp(12), dp(16), dp(12));
        header.setBackgroundColor(DARK);
        
        TextView icon = text(HASH_GLYPH, HINT, 20);
        icon.setTypeface(null, Typeface.BOLD);
        icon.setPadding(0, 0, dp(8), 0);
        header.addView(icon);
        
        channelNameTv = text("Loading...", Color.WHITE, 16);
        channelNameTv.setTypeface(null, Typeface.BOLD);
        channelNameTv.setSingleLine(true);
        channelNameTv.setEllipsize(TextUtils.TruncateAt.END);
        header.addView(channelNameTv, new LinearLayout.LayoutParams(0, WRAP, 1f));
        
        TextView search = text("\uD83D\uDD0D", HINT, 18);
        search.setPadding(dp(12), 0, dp(4), 0);
        search.setOnClickListener(v -> activity.toast("Search coming soon!"));
        header.addView(search);
        
        TextView members = text("\uD83D\uDC65", HINT, 18);
        members.setPadding(dp(4), 0, 0, 0);
        members.setOnClickListener(v -> activity.toast("Member list coming soon!"));
        header.addView(members);
        return header;
    }

    private View buildTypingIndicator() {
        typingText = text("", HINT, 12);
        typingText.setPadding(dp(16), dp(4), dp(16), dp(4));
        typingText.setBackgroundColor(TYPING_BG);
        typingText.setLayoutParams(new LinearLayout.LayoutParams(MATCH, dp(24)));
        typingText.setVisibility(View.INVISIBLE);
        return typingText;
    }

    private View buildList() {
        layoutManager = new LinearLayoutManager(activity);
        messageList = new RecyclerView(activity);
        messageList.setLayoutManager(layoutManager);
        messageList.setBackgroundColor(Color.BLACK);
        messageList.setItemAnimator(null);
        adapter = new MessageAdapter(activity, messages, activity.imageLoader, guildId);
        adapter.setLongPressListener(this::onMessageLongPress);
        
        ProgressBar spinner = new ProgressBar(activity);
        spinner.setIndeterminate(true);
        loadingAdapter = new LoadingAdapter(spinner, dp(48));
        ConcatAdapter concat = new ConcatAdapter(loadingAdapter, adapter);
        messageList.setAdapter(concat);
        
        messageList.addOnScrollListener(new RecyclerView.OnScrollListener() {
            @Override public void onScrolled(RecyclerView rv, int dx, int dy) {
                if (layoutManager.findFirstVisibleItemPosition() <= 1) loadOlderMessages();
            }
        });
        return messageList;
    }

    private View buildReplyBar() {
        replyText = text("", HINT, 12);
        replyText.setSingleLine();
        replyText.setEllipsize(TextUtils.TruncateAt.END);
        
        TextView cancel = text(CANCEL_GLYPH, HINT, 16);
        cancel.setPadding(dp(12), 0, dp(4), 0);
        cancel.setOnClickListener(v -> cancelReply());
        
        replyBar = new LinearLayout(activity);
        replyBar.setGravity(Gravity.CENTER_VERTICAL);
        replyBar.setPadding(dp(14), dp(8), dp(10), dp(8));
        replyBar.setBackground(rounded(REPLY_BAR_BG, 10));
        replyBar.setVisibility(View.GONE);
        replyBar.addView(replyText, new LinearLayout.LayoutParams(0, WRAP, 1f));
        replyBar.addView(cancel);
        
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(MATCH, WRAP);
        lp.setMargins(dp(16), dp(8), dp(16), 0);
        replyBar.setLayoutParams(lp);
        return replyBar;
    }

    private View buildInputPill() {
        attachIcon = text(ATTACH_GLYPH, HINT, 18);
        attachIcon.setPadding(dp(12), dp(8), dp(8), dp(8));
        attachIcon.setOnClickListener(v -> {
            if (editingId != null) cancelEdit();
            else pickImage();
        });
        
        input = new EditText(activity);
        input.setHint(activity.e2eeEnabled ? HINT_ENCRYPTED : HINT_MESSAGE);
        input.setHintTextColor(HINT);
        input.setTextColor(TEXT);
        input.setTextSize(15);
        input.setBackgroundColor(Color.TRANSPARENT);
        input.setPadding(0, dp(12), 0, dp(12));
        
        TextView send = text(SEND_GLYPH, HINT, 18);
        send.setPadding(dp(12), dp(8), dp(12), dp(8));
        send.setOnClickListener(v -> sendMessage());
        
        LinearLayout pill = new LinearLayout(activity);
        pill.setGravity(Gravity.CENTER_VERTICAL);
        pill.setPadding(dp(16), dp(2), dp(8), dp(2));
        pill.setBackground(rounded(DARK, 12));
        pill.addView(attachIcon);
        pill.addView(input, new LinearLayout.LayoutParams(0, WRAP, 1f));
        pill.addView(send);
        
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(MATCH, WRAP);
        lp.setMargins(dp(16), dp(8), dp(16), dp(16));
        pill.setLayoutParams(lp);
        return pill;
    }

    private int dp(int v) { return (int) (v * density); }

    private TextView text(CharSequence s, int color, float sp) {
        TextView t = new TextView(activity);
        t.setText(s);
        t.setTextColor(color);
        t.setTextSize(sp);
        return t;
    }

    private GradientDrawable rounded(int color, int radiusDp) {
        GradientDrawable g = new GradientDrawable();
        g.setColor(color);
        g.setCornerRadius(dp(radiusDp));
        return g;
    }

    private void subscribeAndLoad() {
        subscribe();
        if (activity.apiClient == null) return;
        String cachedName = activity.channelNameCache.get(channelId);
        if (cachedName != null) {
            channelNameTv.setText(cachedName);
        } else {
            activity.apiClient.fetchChannelName(channelId, new ApiClient.Callback<String>() {
                @Override public void onSuccess(String name) {
                    if (activity == null) return;
                    if (name != null) {
                        activity.channelNameCache.put(channelId, name);
                        channelNameTv.setText(name);
                    } else {
                        channelNameTv.setText("unknown-channel");
                    }
                }
                @Override public void onError(String err) {
                    if (channelNameTv != null) channelNameTv.setText("unknown-channel");
                }
            });
        }
        if (guildId != null) {
            activity.apiClient.fetchRoles(guildId, new ApiClient.Callback<Map<String, a.b.model.Role>>() {
                @Override public void onSuccess(Map<String, a.b.model.Role> roles) {
                    if (activity != null && roles != null) activity.roleCache.putAll(roles);
                    loadNewMessages();
                }
                @Override public void onError(String err) { loadNewMessages(); }
            });
        } else {
            loadNewMessages();
        }
    }

    public void onGatewayReady() {
        if (activity == null) return;
        subscribe();
        loadNewMessages();
    }

    private void subscribe() {
        if (activity.gateway != null) activity.gateway.subscribeToGuild(guildId, channelId);
    }

    private void loadNewMessages() {
        if (loadingNew || activity == null || activity.apiClient == null) return;
        loadingNew = true;
        String after = messages.isEmpty() ? null : messages.get(messages.size() - 1).id;
        activity.apiClient.fetchMessages(channelId, null, after, PAGE_SIZE, new ApiClient.Callback<List<Message>>() {
            @Override public void onSuccess(List<Message> batch) {
                loadingNew = false;
                if (activity == null) return;
                boolean isInitialLoad = messages.isEmpty();
                boolean stick = isInitialLoad || atBottom();
                boolean changed = merge(batch, false);
                if (changed) {
                    adapter.submitList(new ArrayList<>(messages));
                    if (stick) scrollToBottom();
                    fetchMissingRoles();
                }
                if (changed && after != null && batch.size() >= PAGE_SIZE) loadNewMessages();
                updateLoadMoreButton();
            }
            @Override public void onError(String err) {
                loadingNew = false;
                updateLoadMoreButton();
            }
        });
    }

    private void loadOlderMessages() {
        if (loadingOld || allLoaded || messages.isEmpty() || activity == null || activity.apiClient == null) return;
        loadingOld = true;
        updateLoadMoreButton();
        activity.apiClient.fetchMessages(channelId, messages.get(0).id, null, PAGE_SIZE, new ApiClient.Callback<List<Message>>() {
            @Override public void onSuccess(List<Message> batch) {
                if (activity == null) { loadingOld = false; return; }
                if (batch.isEmpty()) {
                    loadingOld = false;
                    allLoaded = true;
                    updateLoadMoreButton();
                } else {
                    prepend(batch);
                    fetchMissingRoles();
                }
            }
            @Override public void onError(String err) {
                loadingOld = false;
                if (activity != null) activity.toast("Error: " + err);
                updateLoadMoreButton();
            }
        });
    }

    private void fetchMissingRoles() {
        if (guildId == null || activity == null || activity.apiClient == null) return;
        Set<String> fetched = new HashSet<>();
        for (Message msg : messages) {
            if (msg.author == null || msg.author.id == null) continue;
            String userId = msg.author.id;
            if (activity.userRoleCache.containsKey(userId)) continue;
            if (!fetched.add(userId)) continue;
            activity.apiClient.fetchMember(guildId, userId, new ApiClient.Callback<JSONObject>() {
                @Override public void onSuccess(JSONObject member) {
                    if (activity == null) return;
                    JSONArray rolesArr = member.optJSONArray("roles");
                    if (rolesArr != null && rolesArr.length() > 0) {
                        List<String> roleIds = new ArrayList<>();
                        for (int i = 0; i < rolesArr.length(); i++) roleIds.add(rolesArr.optString(i));
                        activity.userRoleCache.put(userId, roleIds);
                        adapter.refreshUser(userId);
                    }
                }
                @Override public void onError(String err) {}
            });
        }
    }

    private void prepend(List<Message> batch) {
        final int spinnerCount = loadingAdapter.getItemCount();
        String anchorId = null;
        int offset = 0;
        for (int i = 0; i < messageList.getChildCount(); i++) {
            View child = messageList.getChildAt(i);
            if (child == null) continue;
            int pos = messageList.getChildAdapterPosition(child);
            if (pos == RecyclerView.NO_POSITION) continue;
            int msgIdx = pos - spinnerCount;
            if (msgIdx >= 0 && msgIdx < messages.size()) {
                anchorId = messages.get(msgIdx).id;
                offset = child.getTop();
                break;
            }
        }
        loadingOld = false;
        updateLoadMoreButton();
        if (!merge(batch, true)) return;
        adapter.submitList(new ArrayList<>(messages));
        if (anchorId == null) return;
        final int newMsgIdx = indexOf(anchorId);
        if (newMsgIdx < 0) return;
        final int finalOffset = offset;
        messageList.post(() -> {
            int spinnerAfter = loadingAdapter.getItemCount();
            int concatPos = spinnerAfter + newMsgIdx;
            if (layoutManager.getItemCount() > concatPos) {
                layoutManager.scrollToPositionWithOffset(concatPos, finalOffset);
            }
        });
    }

    private void updateLoadMoreButton() {
        if (activity == null) return;
        loadingAdapter.setVisible(loadingOld);
    }

    private boolean merge(List<Message> incoming, boolean older) {
        boolean changed = false;
        for (Message m : incoming) {
            decryptMessageInPlace(m);
            if (m.id != null && messageIds.add(m.id)) {
                messages.add(m);
                changed = true;
            }
        }
        if (!changed) return false;
        Collections.sort(messages, BY_ID);
        while (messages.size() > MAX_MESSAGES) {
            Message gone = messages.remove(older ? messages.size() - 1 : 0);
            messageIds.remove(gone.id);
            if (adapter != null) adapter.parsedContentCache.remove(gone.id);
        }
        return true;
    }

    private int indexOf(String id) {
        for (int i = messages.size() - 1; i >= 0; i--) {
            if (id.equals(messages.get(i).id)) return i;
        }
        return -1;
    }

    private boolean atBottom() {
        RecyclerView.Adapter<?> a = messageList.getAdapter();
        if (a == null || a.getItemCount() == 0) return true;
        return layoutManager.findLastVisibleItemPosition() >= a.getItemCount() - 2;
    }

    private void scrollToBottom() {
        RecyclerView.Adapter<?> a = messageList.getAdapter();
        if (a == null || a.getItemCount() == 0) return;
        final int last = a.getItemCount() - 1;
        messageList.scrollToPosition(last);
        messageList.post(() -> {
            RecyclerView.Adapter<?> aa = messageList.getAdapter();
            if (aa == null) return;
            int lastAfter = aa.getItemCount() - 1;
            if (lastAfter < 0) return;
            messageList.scrollToPosition(lastAfter);
            if (messageList.canScrollVertically(1)) messageList.scrollBy(0, 1_000_000);
        });
    }

    private String prepareOutgoingText(String plain) {
        if (!activity.e2eeEnabled) return plain;
        if (activity.e2eeKey == null) {
            activity.toast("Encryption key missing");
            return null;
        }
        if (plain.length() > Crypto.MAX_PLAINTEXT_LEN) {
            activity.toast("Too long for encryption (max " + Crypto.MAX_PLAINTEXT_LEN + ")");
            return null;
        }
        String out = Crypto.encrypt(plain, activity.e2eeKey);
        if (out == null) activity.toast("Encryption failed");
        return out;
    }

    private static final java.util.regex.Pattern SIZE_TAG_PATTERN = java.util.regex.Pattern.compile("^\\[sz:(\\d+),(\\d+)\\]");

    private void decryptMessageInPlace(Message m) {
        if (m == null || activity == null || activity.e2eeKey == null) return;
        if (Crypto.looksEncrypted(m.content)) {
            String plain = Crypto.decrypt(m.content, activity.e2eeKey);
            if (plain != null) {
                m.content = plain;
                extractAndCleanDimensions(m);
            }
        }
        if (Crypto.looksEncrypted(m.replyToContent)) {
            String plain = Crypto.decrypt(m.replyToContent, activity.e2eeKey);
            if (plain != null) m.replyToContent = plain;
        }
    }

    private static void extractAndCleanDimensions(Message m) {
        if (m == null || m.content == null) return;
        java.util.regex.Matcher matcher = SIZE_TAG_PATTERN.matcher(m.content);
        if (matcher.find()) {
            try {
                int w = Integer.parseInt(matcher.group(1));
                int h = Integer.parseInt(matcher.group(2));
                if (m.id != null) MessageAdapter.messageDimensionCache.put(m.id, new int[]{w, h});
                m.content = m.content.substring(matcher.end());
            } catch (Exception ignored) {}
        }
    }

    private void sendMessage() {
        if (sending || activity == null || activity.apiClient == null) return;
        String text = input.getText().toString().trim();
        boolean hasFile = editingId == null && pendingFileUri != null;
        if (text.isEmpty() && !hasFile) return;
        sending = true;
        input.setText("");
        ApiClient api = activity.apiClient;
        if (editingId != null) {
            String outText = prepareOutgoingText(text);
            if (outText == null) { sending = false; return; }
            String id = editingId;
            api.editMessage(channelId, id, outText, done("Edit failed: ", text, () -> {
                int i = indexOf(id);
                if (i >= 0) {
                    messages.get(i).content = text;
                    adapter.invalidateMessage(id);
                }
                cancelEdit();
            }));
        } else if (hasFile) {
            Runnable clearAttachment = () -> {
                pendingFileUri = null;
                pendingFileName = null;
                pendingMimeType = null;
                pendingImageWidth = 0;
                pendingImageHeight = 0;
            };
            String payloadText = text;
            if (pendingImageWidth > 0 && pendingImageHeight > 0) {
                payloadText = "[sz:" + pendingImageWidth + "," + pendingImageHeight + "]" + text;
            }
            String outText = prepareOutgoingText(payloadText);
            if (outText == null) { sending = false; return; }
            if (activity.e2eeEnabled && activity.e2eeKey != null) {
                String encName = encryptedFileName(pendingFileName);
                api.sendEncryptedFile(activity, channelId, outText, pendingFileUri, encName, activity.e2eeKey, done("Upload failed: ", text, clearAttachment));
            } else {
                api.sendFile(activity, channelId, outText, pendingFileUri, pendingMimeType, pendingFileName, done("Upload failed: ", text, clearAttachment));
            }
        } else {
            String outText = prepareOutgoingText(text);
            if (outText == null) { sending = false; return; }
            if (replyToId != null) {
                String replyId = replyToId;
                api.sendMessage(channelId, outText, replyId, guildId, done("Send failed: ", text, () -> {
                    if (replyId.equals(replyToId)) cancelReply();
                }));
            } else {
                api.sendMessage(channelId, outText, done("Send failed: ", text, null));
            }
        }
    }

    private static String encryptedFileName(String original) {
        if (original == null || original.isEmpty()) return "file.enc";
        int dot = original.lastIndexOf('.');
        if (dot > 0 && dot < original.length() - 1) return original.substring(0, dot) + ".enc" + original.substring(dot);
        return original + ".enc";
    }

    private ApiClient.Callback<Void> done(String failPrefix, String text, Runnable onSuccess) {
        return new ApiClient.Callback<Void>() {
            @Override public void onSuccess(Void v) {
                sending = false;
                if (onSuccess != null) onSuccess.run();
            }
            @Override public void onError(String err) {
                sending = false;
                pendingFileUri = null;
                pendingFileName = null;
                pendingMimeType = null;
                if (activity == null) return;
                activity.toast(failPrefix + err);
                if (input.getText().length() == 0) {
                    input.setText(text);
                    input.setSelection(text.length());
                }
            }
        };
    }

    private void pickImage() {
        Intent intent = new Intent(Intent.ACTION_GET_CONTENT).setType("image/*").addCategory(Intent.CATEGORY_OPENABLE);
        try {
            activity.startActivityForResult(intent, PICK_IMAGE_REQUEST);
        } catch (ActivityNotFoundException e) {
            activity.toast("No file picker available");
        }
    }

    @Override
    public void onActivityResult(int requestCode, int resultCode, Intent data) {
        if (requestCode != PICK_IMAGE_REQUEST || resultCode != Activity.RESULT_OK || data == null || activity == null) return;
        Uri uri = data.getData();
        if (uri == null) return;
        ContentResolver cr = activity.getContentResolver();
        String mime = cr.getType(uri);
        pendingFileUri = uri;
        pendingFileName = fileName(cr, uri);
        pendingMimeType = mime != null ? mime : "image/*";
        try (InputStream is = cr.openInputStream(uri)) {
            android.graphics.BitmapFactory.Options bounds = new android.graphics.BitmapFactory.Options();
            bounds.inJustDecodeBounds = true;
            android.graphics.BitmapFactory.decodeStream(is, null, bounds);
            pendingImageWidth = bounds.outWidth;
            pendingImageHeight = bounds.outHeight;
        } catch (Exception e) {
            pendingImageWidth = 0;
            pendingImageHeight = 0;
        }
        activity.toast("File attached: " + pendingFileName);
    }

    private static String fileName(ContentResolver cr, Uri uri) {
        try (Cursor c = cr.query(uri, null, null, null, null)) {
            if (c != null && c.moveToFirst()) {
                int col = c.getColumnIndex(OpenableColumns.DISPLAY_NAME);
                if (col >= 0 && !c.isNull(col)) return c.getString(col);
            }
        }
        return "image.png";
    }

    private void beginReply(Message msg) {
        if (editingId != null) cancelEdit();
        replyToId = msg.id;
        String preview = msg.content == null ? "" : msg.content.replace('\n', ' ');
        if (preview.length() > REPLY_PREVIEW_LEN) preview = preview.substring(0, REPLY_PREVIEW_LEN) + "\u2026";
        replyText.setText("Replying to " + nameOf(msg) + (preview.isEmpty() ? "" : ": " + preview));
        replyBar.setVisibility(View.VISIBLE);
        input.requestFocus();
    }

    private void cancelReply() {
        replyToId = null;
        replyBar.setVisibility(View.GONE);
    }

    private void beginEdit(Message msg) {
        cancelReply();
        editingId = msg.id;
        String content = msg.content == null ? "" : msg.content;
        input.setText(content);
        input.setSelection(content.length());
        input.setHint(HINT_EDIT);
        input.requestFocus();
        attachIcon.setText(CANCEL_GLYPH);
        InputMethodManager imm = (InputMethodManager) activity.getSystemService(Context.INPUT_METHOD_SERVICE);
        imm.showSoftInput(input, InputMethodManager.SHOW_IMPLICIT);
        int i = indexOf(msg.id);
        if (i >= 0) messageList.scrollToPosition(loadingAdapter.getItemCount() + i);
    }

    private void cancelEdit() {
        editingId = null;
        input.setText("");
        input.setHint(activity.e2eeEnabled ? HINT_ENCRYPTED : HINT_MESSAGE);
        attachIcon.setText(ATTACH_GLYPH);
    }

    private static String nameOf(Message m) {
        if (isReal(m.memberNick)) return m.memberNick;
        if (m.author == null) return "unknown";
        return isReal(m.author.globalName) ? m.author.globalName : m.author.username;
    }

    private static boolean isReal(String s) { return s != null && !s.isEmpty() && !"null".equals(s); }

    private void onMessageLongPress(Message msg, View anchor) {
        if (activity == null || msg == null) return;
        boolean mine = activity.selfUserId != null && msg.author != null && activity.selfUserId.equals(msg.author.id);
        boolean canManage = activity.apiClient != null && activity.apiClient.canManageMessages(channelId);
        PopupMenu menu = new PopupMenu(activity, anchor);
        menu.getMenu().add(0, MENU_REPLY, 0, "Reply");
        menu.getMenu().add(0, MENU_COPY, 1, "Copy text");
        if (mine) menu.getMenu().add(0, MENU_EDIT, 2, "Edit");
        if (mine || canManage) menu.getMenu().add(0, MENU_DELETE, 3, "Delete");
        menu.setOnMenuItemClickListener(item -> {
            switch (item.getItemId()) {
                case MENU_REPLY: beginReply(msg); return true;
                case MENU_COPY: copyMessage(msg); return true;
                case MENU_EDIT: beginEdit(msg); return true;
                case MENU_DELETE: confirmDelete(msg); return true;
                default: return false;
            }
        });
        menu.show();
    }

    private void copyMessage(Message msg) {
        ClipboardManager cm = (ClipboardManager) activity.getSystemService(Context.CLIPBOARD_SERVICE);
        cm.setPrimaryClip(ClipData.newPlainText("message", msg.content == null ? "" : msg.content));
        activity.toast("Copied");
    }

    private void confirmDelete(Message msg) {
        new AlertDialog.Builder(activity)
            .setTitle("Delete message")
            .setMessage("This cannot be undone.")
            .setPositiveButton("Delete", (dialog, which) -> {
                if (activity == null || activity.apiClient == null) return;
                activity.apiClient.deleteMessage(channelId, msg.id, new ApiClient.Callback<Void>() {
                    @Override public void onSuccess(Void v) { gatewayDelete(msg.id); }
                    @Override public void onError(String err) {
                        if (activity != null) activity.toast("Delete failed: " + err);
                    }
                });
            })
            .setNegativeButton("Cancel", null)
            .show();
    }

    private void handleTypingStart(JSONObject d) {
        String userId = d.optString("user_id", null);
        if (userId == null || userId.equals(activity.selfUserId)) return;
        String name = null;
        JSONObject member = d.optJSONObject("member");
        if (member != null) {
            JSONObject user = member.optJSONObject("user");
            if (user != null) name = user.optString("global_name", user.optString("username", null));
            if (name == null && member.has("nick")) name = member.optString("nick");
        }
        if (name == null) name = activity.userNameCache.get(userId);
        if (name == null) name = "Someone";
        typingUsers.put(userId, name);
        updateTypingIndicator();
        typingHandler.removeCallbacks(clearTypingRunnable);
        typingHandler.postDelayed(clearTypingRunnable, TYPING_EXPIRY_MS);
    }

    private void updateTypingIndicator() {
        if (activity == null || typingText == null) return;
        if (typingUsers.isEmpty()) {
            typingText.setVisibility(View.INVISIBLE);
            return;
        }
        List<String> names = new ArrayList<>(typingUsers.values());
        StringBuilder sb = new StringBuilder();
        if (names.size() == 1) sb.append(names.get(0)).append(" is typing...");
        else if (names.size() == 2) sb.append(names.get(0)).append(" and ").append(names.get(1)).append(" are typing...");
        else sb.append("Several people are typing...");
        typingText.setText(sb.toString());
        typingText.setVisibility(View.VISIBLE);
        if (atBottom()) scrollToBottom();
    }

    public void onGatewayEvent(String type, JSONObject d) {
        if (type == null || d == null || activity == null) return;
        if (!channelId.equals(d.optString("channel_id", null))) return;
        try {
            switch (type) {
                case "MESSAGE_CREATE": gatewayCreate(Message.fromJson(d)); break;
                case "MESSAGE_UPDATE": gatewayUpdate(Message.fromJson(d)); break;
                case "MESSAGE_DELETE": gatewayDelete(d.optString("id", null)); break;
                case "MESSAGE_DELETE_BULK":
                    JSONArray ids = d.optJSONArray("ids");
                    if (ids != null) {
                        for (int i = 0; i < ids.length(); i++) gatewayDelete(ids.optString(i));
                    }
                    break;
                case "MESSAGE_REACTION_ADD": gatewayReaction(true, d); break;
                case "MESSAGE_REACTION_REMOVE": gatewayReaction(false, d); break;
                case "TYPING_START": handleTypingStart(d); break;
            }
        } catch (Exception ignored) {}
    }

    private void gatewayCreate(Message m) {
        boolean stick = atBottom();
        typingUsers.clear();
        updateTypingIndicator();
        if (merge(Collections.singletonList(m), false)) {
            adapter.submitList(new ArrayList<>(messages));
            if (stick) scrollToBottom();
        }
    }

    private void gatewayUpdate(Message incoming) {
        if (incoming == null) return;
        decryptMessageInPlace(incoming);
        int i = incoming.id == null ? -1 : indexOf(incoming.id);
        if (i < 0) return;
        messages.get(i).mergeFrom(incoming);
        adapter.invalidateMessage(incoming.id);
    }

    private void gatewayDelete(String id) {
        if (id == null || !messageIds.remove(id)) return;
        int i = indexOf(id);
        if (i >= 0) messages.remove(i);
        adapter.parsedContentCache.remove(id);
        adapter.submitList(new ArrayList<>(messages));
    }

    private void gatewayReaction(boolean add, JSONObject d) throws Exception {
        int i = indexOf(d.getString("message_id"));
        if (i < 0) return;
        Message m = messages.get(i);
        String key = reactionKey(d.optJSONObject("emoji"));
        boolean self = activity.selfUserId != null && activity.selfUserId.equals(d.optString("user_id", null));
        if (m.reactions == null) m.reactions = new ArrayList<>();
        Reaction r = null;
        for (Reaction x : m.reactions) {
            if (key.equals(x.emoji)) { r = x; break; }
        }
        if (add) {
            if (r == null) m.reactions.add(new Reaction(key, 1, self));
            else { r.count++; if (self) r.me = true; }
        } else {
            if (r == null) return;
            if (self) r.me = false;
            if (--r.count <= 0) m.reactions.remove(r);
        }
        adapter.invalidateMessage(m.id);
    }

    private static String reactionKey(JSONObject emoji) {
        return emoji == null ? "" : emoji.optString("name", emoji.optString("id", ""));
    }

    @Override public void onResume() {}
    @Override public void onPause() {}

    @Override
    public void onDestroy() {
        editingId = null;
        replyToId = null;
        typingHandler.removeCallbacksAndMessages(null);
        activity = null;
        if (adapter != null) {
            adapter.activity = null;
            adapter.emojiExecutor.shutdownNow();
        }
    }

    @Override public View getView() { return view; }

    private static final class LoadingAdapter extends RecyclerView.Adapter<LoadingAdapter.Holder> {
        private final ProgressBar spinner;
        private final int height;
        private boolean visible;

        LoadingAdapter(ProgressBar spinner, int height) {
            this.spinner = spinner;
            this.height = height;
            spinner.setIndeterminate(true);
            spinner.getIndeterminateDrawable().setColorFilter(Color.WHITE, android.graphics.PorterDuff.Mode.SRC_IN);
        }

        void setVisible(boolean visible) {
            if (this.visible != visible) {
                this.visible = visible;
                if (visible) notifyItemInserted(0);
                else notifyItemRemoved(0);
            }
        }

        @Override public Holder onCreateViewHolder(ViewGroup parent, int viewType) {
            if (spinner.getParent() != null) ((ViewGroup) spinner.getParent()).removeView(spinner);
            FrameLayout container = new FrameLayout(parent.getContext());
            container.setLayoutParams(new RecyclerView.LayoutParams(MATCH, height));
            int size = height / 2;
            FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(size, size, Gravity.CENTER);
            container.addView(spinner, lp);
            return new Holder(container);
        }

        @Override public void onBindViewHolder(Holder h, int pos) {}
        @Override public int getItemCount() { return visible ? 1 : 0; }

        static final class Holder extends RecyclerView.ViewHolder {
            Holder(View v) { super(v); }
        }
    }
}