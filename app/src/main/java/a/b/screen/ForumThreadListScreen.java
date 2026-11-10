package a.b.screen;

import android.app.AlertDialog;
import android.content.DialogInterface;
import android.content.Intent;
import android.graphics.Typeface;
import android.text.Editable;
import android.text.InputType;
import android.text.TextWatcher;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.EditText;
import android.widget.HorizontalScrollView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import a.b.MainActivity;
import a.b.api.ApiClient;
import a.b.model.Channel;
import a.b.util.PermissionUtil;
import a.b.util.Ui;

/** Forum channel browser with search, latest-activity ordering and thread creation. */
public class ForumThreadListScreen implements Screen {
    private final String guildId;
    private final Channel forum;

    private MainActivity activity;
    private View view;
    private Ui.AppBar bar;
    private LinearLayout listContainer;
    private ScrollView scrollView;
    private EditText search;
    private ForumThreadRowView rowView;
    private Map<String, Channel.ForumTag> tagsById;
    private List<Channel> threads;
    private boolean loading;
    private boolean loadingMore;
    private boolean hasMoreThreads;
    private int nextOffset;
    private int totalResults = -1;
    private int loadGeneration;
    private String error;
    private String query = "";
    private float density;
    private boolean destroyed;

    private final Runnable searchRunnable = this::reloadThreads;

    public ForumThreadListScreen(String guildId, Channel forum) {
        this.guildId = guildId;
        this.forum = forum;
    }

    @Override
    public View onCreateView(MainActivity activity, ViewGroup parent) {
        this.activity = activity;
        this.destroyed = false;
        density = activity.getResources().getDisplayMetrics().density;
        tagsById = buildTagMap();
        rowView = new ForumThreadRowView(activity, guildId, tagsById);

        LinearLayout root = new LinearLayout(activity);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(Ui.BG_SECONDARY);

        String title = forum != null && forum.name != null && !forum.name.isEmpty() ? forum.name : "Forum";
        bar = new Ui.AppBar(activity, title, Ui.BG_SECONDARY, activity::goBack);
        bar.setSubtitle(threadListLabel());
        if (forum != null && forum.canCreateThreads) {
            bar.addAction(Ui.I_PLUS, v -> showNewThreadDialog());
        }
        bar.addAction(Ui.I_REFRESH, v -> loadThreads());
        ensureCreatePermission();
        root.addView(bar.view, new LinearLayout.LayoutParams(-1, -2));

        LinearLayout searchWrap = new LinearLayout(activity);
        searchWrap.setPadding(dp(12), dp(8), dp(12), dp(8));
        search = new EditText(activity);
        search.setSingleLine(true);
        search.setHint("Search threads");
        search.setTextSize(14);
        search.setTextColor(Ui.TEXT);
        search.setHintTextColor(Ui.MUTED);
        search.setBackground(Ui.rounded(Ui.BG_INPUT, dp(8)));
        search.setPadding(dp(12), dp(8), dp(12), dp(8));
        search.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int start, int count, int after) {}
            @Override public void onTextChanged(CharSequence s, int start, int before, int count) {}
            @Override public void afterTextChanged(Editable s) {
                query = s == null ? "" : s.toString().trim();
                if (activity == null || destroyed) return;
                activity.handler.removeCallbacks(searchRunnable);
                activity.handler.postDelayed(searchRunnable, 350);
            }
        });
        searchWrap.addView(search, new LinearLayout.LayoutParams(-1, -2));
        root.addView(searchWrap, new LinearLayout.LayoutParams(-1, -2));

        scrollView = new ScrollView(activity);
        scrollView.setFillViewport(true);
        listContainer = new LinearLayout(activity);
        listContainer.setOrientation(LinearLayout.VERTICAL);
        listContainer.setPadding(0, dp(4), 0, dp(16));
        scrollView.addView(listContainer, new ViewGroup.LayoutParams(-1, -2));
        scrollView.setOnScrollChangeListener((v, scrollX, scrollY, oldScrollX, oldScrollY) -> {
            View content = scrollView.getChildAt(0);
            if (content != null && scrollY + scrollView.getHeight() >= content.getMeasuredHeight() - dp(240)) {
                loadMoreThreads();
            }
        });
        root.addView(scrollView, new LinearLayout.LayoutParams(-1, 0, 1f));

        render();
        return view = root;
    }

    private Map<String, Channel.ForumTag> buildTagMap() {
        Map<String, Channel.ForumTag> out = new HashMap<>();
        if (forum != null) {
            for (Channel.ForumTag tag : forum.availableTags) {
                if (tag != null && tag.id != null) out.put(tag.id, tag);
            }
        }
        return out;
    }

    private void ensureCreatePermission() {
        if (activity == null || forum == null || forum.id == null || forum.canCreateThreads) return;
        if (forum.type != 15 && forum.type != 16) return;
        activity.apiClient.fetchMyChannelPermissions(forum.id, new ApiClient.Callback<Long>() {
            @Override public void onSuccess(Long permissions) {
                if (activity == null || destroyed || permissions == null) return;
                if ((permissions & PermissionUtil.SEND_MESSAGES) == 0) return;
                forum.canCreateThreads = true;
                if (bar != null) bar.addAction(Ui.I_PLUS, v -> showNewThreadDialog());
                render();
            }

            @Override public void onError(String message) {}
        });
    }

    private void reloadThreads() {
        if (activity == null || destroyed || forum == null || forum.id == null) return;
        loading = false;
        loadingMore = false;
        threads = null;
        hasMoreThreads = false;
        nextOffset = 0;
        totalResults = -1;
        error = null;
        render();
        loadThreads();
    }

    private void loadThreads() {
        if (activity == null || destroyed || forum == null || forum.id == null || loading || loadingMore) return;
        final int generation = ++loadGeneration;
        loading = true;
        error = null;
        updateSubtitle();
        if (threads == null) render();
        activity.apiClient.fetchForumThreads(forum.id, 0, query, new ApiClient.Callback<ApiClient.ThreadPage>() {
            @Override public void onSuccess(ApiClient.ThreadPage page) {
                if (activity == null || destroyed || generation != loadGeneration) return;
                loading = false;
                threads = page == null || page.threads == null ? new ArrayList<>() : page.threads;
                hasMoreThreads = page != null && page.hasMore;
                nextOffset = page == null ? 0 : page.nextOffset;
                totalResults = page == null ? -1 : page.totalResults;
                cacheThreadNames(threads);
                error = null;
                render();
                if (query.isEmpty() && hasMoreThreads && !containsPinned(threads)) {
                    loadPinnedThread(generation);
                }
            }

            @Override public void onError(String message) {
                if (activity == null || destroyed || generation != loadGeneration) return;
                loading = false;
                error = message;
                render();
            }
        });
    }

    private void loadPinnedThread(final int generation) {
        if (activity == null || forum == null || forum.id == null) return;
        activity.apiClient.fetchPinnedForumThread(guildId, forum.id, new ApiClient.Callback<Channel>() {
            @Override public void onSuccess(Channel pinned) {
                if (activity == null || destroyed || generation != loadGeneration) return;
                if (mergePinnedThread(pinned)) render();
            }

            @Override public void onError(String message) {}
        });
    }

    private boolean containsPinned(List<Channel> list) {
        if (list == null) return false;
        for (Channel thread : list) {
            if (thread != null && thread.pinned) return true;
        }
        return false;
    }

    private boolean mergePinnedThread(Channel pinned) {
        if (pinned == null || pinned.id == null || threads == null) return false;
        for (Channel thread : threads) {
            if (thread != null && pinned.id.equals(thread.id)) {
                if (!thread.pinned) {
                    thread.pinned = true;
                    return true;
                }
                return false;
            }
        }
        threads.add(0, pinned);
        if (activity != null && pinned.name != null) activity.channelNameCache.put(pinned.id, pinned.name);
        return true;
    }

    private void loadMoreThreads() {
        if (activity == null || destroyed || loading || loadingMore || !hasMoreThreads
                || forum == null || forum.id == null) return;
        final int generation = loadGeneration;
        final int offset = nextOffset;
        final int scrollY = scrollView != null ? scrollView.getScrollY() : 0;
        loadingMore = true;
        render();
        restoreScroll(scrollY);
        activity.apiClient.fetchForumThreads(forum.id, offset, query, new ApiClient.Callback<ApiClient.ThreadPage>() {
            @Override public void onSuccess(ApiClient.ThreadPage page) {
                if (activity == null || destroyed || generation != loadGeneration) return;
                loadingMore = false;
                if (page != null) {
                    appendThreads(page.threads);
                    hasMoreThreads = page.hasMore;
                    nextOffset = page.nextOffset;
                    if (page.totalResults >= 0) totalResults = page.totalResults;
                } else {
                    hasMoreThreads = false;
                }
                render();
                restoreScroll(scrollY);
            }

            @Override public void onError(String message) {
                if (activity == null || destroyed || generation != loadGeneration) return;
                loadingMore = false;
                activity.toast("Couldn't load more threads: " + message);
                render();
                restoreScroll(scrollY);
            }
        });
    }

    private void appendThreads(List<Channel> extra) {
        if (extra == null || extra.isEmpty()) return;
        if (threads == null) threads = new ArrayList<>();
        Set<String> ids = new HashSet<>();
        for (Channel thread : threads) {
            if (thread != null && thread.id != null) ids.add(thread.id);
        }
        for (Channel thread : extra) {
            if (thread == null || thread.id == null || !ids.add(thread.id)) continue;
            threads.add(thread);
        }
        cacheThreadNames(extra);
    }

    private void cacheThreadNames(List<Channel> list) {
        if (activity == null || list == null) return;
        for (Channel thread : list) {
            if (thread != null && thread.id != null && thread.name != null) {
                activity.channelNameCache.put(thread.id, thread.name);
            }
        }
    }

    private void restoreScroll(int y) {
        if (scrollView != null) scrollView.post(() -> scrollView.scrollTo(0, y));
    }

    private void render() {
        if (listContainer == null || activity == null || destroyed) return;
        listContainer.removeAllViews();
        updateSubtitle();

        if (error != null && threads == null) {
            TextView retry = Ui.emptyState(activity, "Couldn't load threads.\nTap to retry.");
            retry.setOnClickListener(v -> { error = null; render(); loadThreads(); });
            listContainer.addView(retry);
            return;
        }
        if (threads == null) {
            listContainer.addView(Ui.emptyState(activity, "Loading threads\u2026"));
            return;
        }
        if (threads.isEmpty()) {
            String empty;
            if (!query.isEmpty()) empty = "No threads match your search.";
            else if (forum != null && forum.canCreateThreads) empty = "No threads here yet.\nTap + to start the first one.";
            else empty = "No threads here yet.";
            listContainer.addView(Ui.emptyState(activity, empty));
            return;
        }

        List<Channel> ordered = new ArrayList<>();
        for (Channel thread : threads) {
            if (thread != null) ordered.add(thread);
        }
        sortThreads(ordered);
        if (error != null) {
            TextView retry = Ui.text(activity, "Couldn't refresh \u00B7 Tap to retry", Ui.YELLOW, 12);
            retry.setGravity(Gravity.CENTER);
            retry.setPadding(dp(16), dp(10), dp(16), dp(10));
            retry.setOnClickListener(v -> { error = null; render(); loadThreads(); });
            listContainer.addView(retry);
        }
        addSection(query.isEmpty() ? "Threads" : "Results", ordered);
        addLoadMoreFooter();
    }

    private void addLoadMoreFooter() {
        if (!hasMoreThreads) return;
        TextView more = Ui.text(activity,
            loadingMore ? "Loading more threads\u2026" : "Load more threads",
            loadingMore ? Ui.MUTED : Ui.LINK, 13);
        more.setGravity(Gravity.CENTER);
        more.setPadding(dp(16), dp(16), dp(16), dp(16));
        if (!loadingMore) more.setOnClickListener(v -> loadMoreThreads());
        listContainer.addView(more);
    }

    private void addSection(String label, List<Channel> section) {
        if (section.isEmpty()) return;
        TextView header = Ui.sectionLabel(activity, label + " \u00B7 " + section.size());
        header.setPadding(dp(16), dp(12), dp(16), dp(4));
        listContainer.addView(header);
        for (Channel thread : section) listContainer.addView(rowView.build(thread));
    }

    private void sortThreads(List<Channel> list) {
        // The search endpoint already orders threads by last message time. Only
        // re-sort locally when every row has that timestamp; otherwise keep the
        // server order instead of falling back to archive times. Pinned threads
        // still move to the top in both cases.
        boolean allTimed = true;
        for (Channel thread : list) {
            if (thread.lastActivity <= 0) {
                allTimed = false;
                break;
            }
        }
        if (!allTimed) {
            List<Channel> pinned = new ArrayList<>();
            List<Channel> rest = new ArrayList<>();
            for (Channel thread : list) {
                if (thread.pinned) pinned.add(thread);
                else rest.add(thread);
            }
            list.clear();
            list.addAll(pinned);
            list.addAll(rest);
            return;
        }
        Collections.sort(list, (a, b) -> {
            if (a.pinned != b.pinned) return a.pinned ? -1 : 1;
            if (a.lastActivity != b.lastActivity) return Long.compare(b.lastActivity, a.lastActivity);
            String ai = a.id == null ? "" : a.id;
            String bi = b.id == null ? "" : b.id;
            return bi.compareTo(ai);
        });
    }

    private String threadListLabel() {
        return forum != null && forum.type == 16 ? "Media threads" : "Forum threads";
    }

    private void updateSubtitle() {
        if (bar == null) return;
        if (loading) {
            bar.setSubtitle(threads == null ? "Loading threads\u2026" : "Refreshing\u2026");
            return;
        }
        if (threads == null) {
            bar.setSubtitle(threadListLabel());
            return;
        }
        int loaded = threads.size();
        String text;
        if (totalResults > loaded) text = loaded + " of " + totalResults + " threads";
        else text = loaded + (loaded == 1 ? " thread" : " threads");
        if (!query.isEmpty()) text = "Search: " + text;
        bar.setSubtitle(text);
    }

    private void showNewThreadDialog() {
        if (activity == null || destroyed || forum == null || forum.id == null) return;

        LinearLayout box = new LinearLayout(activity);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(dp(20), dp(8), dp(20), 0);

        final EditText titleInput = dialogInput("Thread title", 1);
        final EditText bodyInput = dialogInput("Start the conversation", 4);
        box.addView(titleInput, new LinearLayout.LayoutParams(-1, -2));
        LinearLayout.LayoutParams bodyLp = new LinearLayout.LayoutParams(-1, -2);
        bodyLp.topMargin = dp(10);
        box.addView(bodyInput, bodyLp);

        final Set<String> selectedTags = new HashSet<>();
        if (!forum.availableTags.isEmpty()) {
            TextView tagLabel = Ui.text(activity,
                forum.requireTag ? "Tags (required)" : "Tags (optional)", Ui.MUTED, 12);
            LinearLayout.LayoutParams labelLp = new LinearLayout.LayoutParams(-1, -2);
            labelLp.topMargin = dp(12);
            box.addView(tagLabel, labelLp);

            HorizontalScrollView tagScroll = new HorizontalScrollView(activity);
            tagScroll.setHorizontalScrollBarEnabled(false);
            LinearLayout tagRow = new LinearLayout(activity);
            tagRow.setOrientation(LinearLayout.HORIZONTAL);
            tagRow.setPadding(0, dp(6), 0, 0);
            for (Channel.ForumTag tag : forum.availableTags) {
                if (tag == null || tag.id == null) continue;
                final Channel.ForumTag forumTag = tag;
                final String tagId = tag.id;
                final TextView chip = Ui.text(activity, tag.name, Ui.TEXT, 12);
                chip.setSingleLine(true);
                chip.setPadding(dp(10), dp(6), dp(10), dp(6));
                LinearLayout.LayoutParams chipLp = new LinearLayout.LayoutParams(-2, -2);
                chipLp.setMarginEnd(dp(8));
                chip.setLayoutParams(chipLp);
                styleTagChip(chip, tag, false);
                chip.setOnClickListener(v -> {
                    boolean selected = selectedTags.contains(tagId);
                    if (selected) {
                        selectedTags.remove(tagId);
                    } else {
                        if (selectedTags.size() >= 5) {
                            activity.toast("You can apply up to 5 tags");
                            return;
                        }
                        selectedTags.add(tagId);
                    }
                    styleTagChip(chip, forumTag, !selected);
                });
                tagRow.addView(chip);
            }
            tagScroll.addView(tagRow);
            box.addView(tagScroll, new LinearLayout.LayoutParams(-1, -2));
        }

        final AlertDialog dialog = new AlertDialog.Builder(activity, android.R.style.Theme_DeviceDefault_Dialog_Alert)
            .setTitle("New thread")
            .setView(box)
            .setNegativeButton("Cancel", null)
            .setPositiveButton("Create", null)
            .create();

        dialog.setOnShowListener(ignored -> {
            final Button create = dialog.getButton(DialogInterface.BUTTON_POSITIVE);
            create.setOnClickListener(v -> {
                String name = titleInput.getText().toString().trim();
                String content = bodyInput.getText().toString().trim();
                if (name.isEmpty()) { titleInput.setError("Title is required"); return; }
                if (content.isEmpty()) { bodyInput.setError("Message is required"); return; }
                if (forum.requireTag && !forum.availableTags.isEmpty() && selectedTags.isEmpty()) {
                    activity.toast("Choose at least one tag");
                    return;
                }
                create.setEnabled(false);
                create.setText("Creating\u2026");
                activity.apiClient.createForumThread(forum.id, name, content,
                    new ArrayList<>(selectedTags), new ApiClient.Callback<Channel>() {
                    @Override public void onSuccess(Channel thread) {
                        if (activity == null || destroyed) return;
                        dialog.dismiss();
                        if (thread == null || thread.id == null) {
                            loadThreads();
                            return;
                        }
                        if (thread.name != null) activity.channelNameCache.put(thread.id, thread.name);
                        if (threads != null) {
                            threads.add(0, thread);
                            if (totalResults >= 0) totalResults++;
                            render();
                        }
                        activity.navigate(new MessageListScreen(guildId, thread.id));
                    }

                    @Override public void onError(String message) {
                        if (activity == null || destroyed) return;
                        create.setEnabled(true);
                        create.setText("Create");
                        activity.toast("Couldn't create thread: " + message);
                    }
                });
            });
        });
        dialog.show();
    }

    private EditText dialogInput(String hint, int lines) {
        EditText input = new EditText(activity);
        input.setHint(hint);
        input.setTextColor(Ui.TEXT);
        input.setHintTextColor(Ui.MUTED);
        input.setTextSize(15);
        input.setBackground(Ui.rounded(Ui.BG_INPUT, dp(8)));
        input.setPadding(dp(12), dp(10), dp(12), dp(10));
        if (lines > 1) {
            input.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_MULTI_LINE | InputType.TYPE_TEXT_FLAG_CAP_SENTENCES);
            input.setMinLines(lines);
            input.setMaxLines(6);
            input.setGravity(Gravity.TOP | Gravity.START);
        } else {
            input.setSingleLine(true);
            input.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_CAP_SENTENCES);
        }
        return input;
    }

    private void styleTagChip(TextView chip, Channel.ForumTag tag, boolean selected) {
        int color = tag.color == 0 ? Ui.BLURPLE : (tag.color & 0xFFFFFF);
        int bg;
        if (selected) {
            bg = 0x66000000 | color;
        } else {
            bg = tag.color == 0 ? Ui.BG_TERTIARY : (0x33000000 | color);
        }
        chip.setBackground(Ui.rounded(bg, dp(7)));
        chip.setTextColor(selected ? Ui.TEXT_HEADER : Ui.TEXT);
        chip.setTypeface(null, selected ? Typeface.BOLD : Typeface.NORMAL);
    }

    private int dp(int v) { return (int) (v * density); }

    @Override public View getView() { return view; }

    @Override public void onResume() {
        if (activity != null && !destroyed && threads == null) loadThreads();
    }

    @Override public void onPause() {}

    @Override public void onDestroy() {
        destroyed = true;
        if (activity != null) activity.handler.removeCallbacks(searchRunnable);
        activity = null;
        view = null;
        bar = null;
        listContainer = null;
        scrollView = null;
        search = null;
        rowView = null;
        threads = null;
    }

    @Override public void onActivityResult(int requestCode, int resultCode, Intent data) {}
}
