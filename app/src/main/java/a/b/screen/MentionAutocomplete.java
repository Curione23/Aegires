package a.b.screen;

import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Handler;
import android.os.Looper;
import android.text.Editable;
import android.text.TextUtils;
import android.text.TextWatcher;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import a.b.api.ApiClient;
import a.b.model.Author;
import a.b.model.Channel;
import a.b.model.Message;
import a.b.model.Role;
import a.b.util.MemberListStore;
import a.b.util.Ui;

import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Discord-style @user / @role / #channel autocomplete shown above the composer. Local caches show
 * results instantly, while guild member searches fill in people not yet cached.
 */
final class MentionAutocomplete {
    private static final int KIND_USER = 0, KIND_ROLE = 1, KIND_CHANNEL = 2, KIND_SPECIAL = 3;
    private static final int MAX_RESULTS = 8;
    private static final int MAX_VISIBLE_ROWS = 7;
    private static final int ROW_HEIGHT_DP = 48;
    private static final int REMOTE_LIMIT = 1000;
    private static final long REMOTE_DELAY_MS = 180;
    private static final long REMOTE_CACHE_TTL_MS = 5 * 60_000L;
    private static final long EMPTY_CACHE_TTL_MS = 15_000L;

    private static final int DEEP_PAGE_SIZE = 1000;
    private static final int DEEP_MAX_PAGES = 30;
    private static final long DEEP_PAGE_DELAY_MS = 250;
    private static final long DEEP_RETRY_MS = 10_000L;
    private static final int DEEP_MIN_QUERY = 2;

    private static final class Suggestion {
        final int kind;
        final String id, name;
        final String nameLower;
        String username, avatar, search, searchLower;
        int color, position, remoteRank;
        boolean fromRemote;

        Suggestion(int kind, String id, String name) {
            this.kind = kind;
            this.id = id;
            this.name = name;
            this.nameLower = name.toLowerCase(Locale.ROOT);
            setSearch(name);
        }

        void setSearch(String value) {
            search = value;
            searchLower = value == null ? null : value.toLowerCase(Locale.ROOT);
        }

        Suggestion copy() {
            Suggestion s = new Suggestion(kind, id, name);
            s.username = username;
            s.avatar = avatar;
            s.setSearch(search);
            s.color = color;
            s.position = position;
            s.remoteRank = remoteRank;
            s.fromRemote = fromRemote;
            return s;
        }
    }

    private final MessageListScreen host;
    private final Handler ui = new Handler(Looper.getMainLooper());
    private final Map<String, List<Suggestion>> remoteCache = new HashMap<>();
    private final Map<String, Long> remoteFetchedAt = new HashMap<>();
    private final Set<String> pendingSearches = new HashSet<>();
    private final Map<String, LinkedHashMap<String, Suggestion>> deepMembers = new HashMap<>();
    private final Set<String> deepLoading = new HashSet<>();
    private final Set<String> deepComplete = new HashSet<>();
    private final Map<String, String> deepAfter = new HashMap<>();
    private final Map<String, Integer> deepPages = new HashMap<>();
    private final Map<String, Long> deepFailedUntil = new HashMap<>();
    private final Map<String, Integer> deepFailures = new HashMap<>();
    private final Runnable searchRunnable = this::runRemoteSearch;
    private final Runnable deepPageRunnable = this::maybeLoadDeepMembers;
    private final Runnable blurHide =
            () -> {
                if (this.input == null || !this.input.hasFocus()) hide();
            };

    private ScrollView panel;
    private LinearLayout rows;
    private EditText input;
    private char sigil;
    private int tokenStart = -1;
    private String query = "";
    private List<Suggestion> remote = new ArrayList<>();
    private boolean updating;
    private boolean active;

    MentionAutocomplete(MessageListScreen host) {
        this.host = host;
    }

    View build() {
        rows = new LinearLayout(host.activity);
        rows.setOrientation(LinearLayout.VERTICAL);
        rows.setPadding(0, dp(4), 0, dp(4));

        panel = new ScrollView(host.activity);
        panel.setFillViewport(false);
        panel.setVerticalScrollBarEnabled(false);
        panel.setVisibility(View.GONE);
        panel.addView(rows, new ViewGroup.LayoutParams(-1, -2));

        GradientDrawable bg = new GradientDrawable();
        bg.setColor(Ui.BG_TERTIARY);
        bg.setCornerRadius(dp(8));
        bg.setStroke(dp(1), Ui.DIVIDER);
        panel.setBackground(bg);
        panel.setElevation(dp(8));

        LinearLayout.LayoutParams lp =
                new LinearLayout.LayoutParams(-1, dp(ROW_HEIGHT_DP + 8));
        lp.setMargins(dp(8), 0, dp(8), dp(4));
        panel.setLayoutParams(lp);
        return panel;
    }

    void attach(EditText editor) {
        input = editor;
        editor.addTextChangedListener(
                new TextWatcher() {
                    @Override
                    public void beforeTextChanged(CharSequence s, int a, int b, int c) {}

                    @Override
                    public void onTextChanged(CharSequence s, int a, int b, int c) {}

                    @Override
                    public void afterTextChanged(Editable e) {
                        onEditorChanged();
                    }
                });
        editor.setOnClickListener(v -> onEditorChanged());
        editor.setOnFocusChangeListener(
                (v, hasFocus) -> {
                    ui.removeCallbacks(blurHide);
                    if (hasFocus) onEditorChanged();
                    else ui.postDelayed(blurHide, 150);
                });
    }

    void release() {
        ui.removeCallbacksAndMessages(null);
        input = null;
        panel = null;
        rows = null;
        remoteCache.clear();
        remoteFetchedAt.clear();
        pendingSearches.clear();
        deepMembers.clear();
        deepLoading.clear();
        deepComplete.clear();
        deepAfter.clear();
        deepPages.clear();
        deepFailedUntil.clear();
        deepFailures.clear();
        active = false;
    }

    void hide() {
        ui.removeCallbacks(searchRunnable);
        ui.removeCallbacks(blurHide);
        if (panel != null) panel.setVisibility(View.GONE);
        active = false;
        tokenStart = -1;
    }

    private void onEditorChanged() {
        if (input == null || updating) return;
        if (!input.hasFocus()) {
            hide();
            return;
        }
        Editable text = input.getText();
        int cursor = input.getSelectionEnd();
        if (cursor < 0 || cursor > text.length()) {
            hide();
            return;
        }

        // Allow spaces inside a query so names like "@Moderator Team" keep matching. Only a
        // newline ends the candidate region, and the nearest valid sigil wins.
        int start = cursor;
        while (start > 0 && text.charAt(start - 1) != '\n') start--;

        int sig = -1;
        char found = 0;
        for (int i = cursor - 1; i >= start; i--) {
            char c = text.charAt(i);
            if (c != '@' && c != '#') continue;
            if (i > 0) {
                char prev = text.charAt(i - 1);
                if (prev == '<') continue;
                if (Character.isLetterOrDigit(prev)
                        || prev == '_'
                        || prev == '@'
                        || prev == '#') continue;
            }
            sig = i;
            found = c;
            break;
        }
        if (sig < 0) {
            hide();
            return;
        }

        String q = text.subSequence(sig + 1, cursor).toString();
        if (q.indexOf('<') >= 0 || q.indexOf('>') >= 0 || q.indexOf('\n') >= 0) {
            hide();
            return;
        }
        // Leading/trailing spaces are common after typing "@", and repeated spaces should not
        // prevent a member search from matching.
        q = q.trim().replaceAll("\\s+", " ");

        sigil = found;
        tokenStart = sig;
        query = q;
        active = true;

        String key = currentKey();
        List<Suggestion> cached = remoteCache.get(key);
        remote = cached == null ? new ArrayList<>() : cached;
        render();
        scheduleRemote(key);
        maybeLoadDeepMembers();
    }

    private String currentKey() {
        return (host.guildId == null ? "" : host.guildId) + "|" + sigil + "|" + query.toLowerCase(Locale.ROOT);
    }

    private void scheduleRemote(String key) {
        ui.removeCallbacks(searchRunnable);
        if (!active
                || sigil != '@'
                || host.guildId == null
                || query.isEmpty()
                || host.activity == null
                || host.activity.apiClient == null
                || host.activity.apiClient.isBotAccount())
            return;
        if (pendingSearches.contains(key) || isRemoteFresh(key)) return;
        ui.postDelayed(searchRunnable, REMOTE_DELAY_MS);
    }

    private boolean isRemoteFresh(String key) {
        Long fetchedAt = remoteFetchedAt.get(key);
        if (fetchedAt == null) return false;
        List<Suggestion> cached = remoteCache.get(key);
        long ttl =
                cached == null || cached.isEmpty() ? EMPTY_CACHE_TTL_MS : REMOTE_CACHE_TTL_MS;
        if (System.currentTimeMillis() - fetchedAt < ttl) return true;
        remoteCache.remove(key);
        remoteFetchedAt.remove(key);
        return false;
    }

    private void runRemoteSearch() {
        if (!active
                || input == null
                || sigil != '@'
                || host.guildId == null
                || query.isEmpty()
                || host.activity == null
                || host.activity.apiClient == null)
            return;
        final String key = currentKey();
        if (pendingSearches.contains(key)) return;
        final String q = query;
        pendingSearches.add(key);
        host.activity.apiClient.searchGuildMembers(
                host.guildId,
                q,
                REMOTE_LIMIT,
                new ApiClient.Callback<List<JSONObject>>() {
                    @Override
                    public void onSuccess(List<JSONObject> result) {
                        pendingSearches.remove(key);
                        List<Suggestion> parsed = new ArrayList<>();
                        if (result != null) {
                            for (JSONObject member : result) {
                                Suggestion s = memberSuggestion(member);
                                if (s == null) continue;
                                s.remoteRank = parsed.size();
                                parsed.add(s);
                            }
                        }
                        remoteCache.put(key, parsed);
                        remoteFetchedAt.put(key, System.currentTimeMillis());
                        if (remoteCache.size() > 20) {
                            remoteCache.clear();
                            remoteFetchedAt.clear();
                            remoteFetchedAt.put(key, System.currentTimeMillis());
                            remoteCache.put(key, parsed);
                        }
                        if (active && key.equals(currentKey())) {
                            remote = parsed;
                            render();
                        }
                    }

                    @Override
                    public void onError(String message) {
                        pendingSearches.remove(key);
                        remoteCache.remove(key);
                        remoteFetchedAt.remove(key);
                    }
                });
    }

    /**
     * Discord's member-search endpoint only covers a limited result window. Keep paging through
     * the guild member list in the background so a member can still be found by a later part of
     * their name in a large server.
     */
    private void maybeLoadDeepMembers() {
        if (!active
                || sigil != '@'
                || host.guildId == null
                || query.length() < DEEP_MIN_QUERY
                || host.activity == null
                || host.activity.apiClient == null
                || host.activity.apiClient.isBotAccount())
            return;
        final String guild = host.guildId;
        if (deepComplete.contains(guild) || deepLoading.contains(guild)) return;
        Integer failures = deepFailures.get(guild);
        if (failures != null && failures >= 3) {
            deepComplete.add(guild);
            return;
        }
        Long failedAt = deepFailedUntil.get(guild);
        if (failedAt != null && System.currentTimeMillis() < failedAt) return;
        int pages = deepPages.getOrDefault(guild, 0);
        if (pages >= DEEP_MAX_PAGES) {
            deepComplete.add(guild);
            return;
        }
        deepLoading.add(guild);
        loadDeepMembersPage(guild);
    }

    private void loadDeepMembersPage(final String guild) {
        if (!active) {
            deepLoading.remove(guild);
            return;
        }
        final String after = deepAfter.get(guild);
        host.activity.apiClient.fetchGuildMembersPage(
                guild,
                after,
                DEEP_PAGE_SIZE,
                new ApiClient.Callback<List<JSONObject>>() {
                    @Override
                    public void onSuccess(List<JSONObject> result) {
                        deepLoading.remove(guild);
                        if (!active || host.activity == null) return;
                        deepFailedUntil.remove(guild);
                        deepFailures.remove(guild);
                        LinkedHashMap<String, Suggestion> index = deepMembers.get(guild);
                        if (index == null) {
                            index = new LinkedHashMap<>();
                            deepMembers.put(guild, index);
                        }
                        int added = 0;
                        String lastId = null;
                        if (result != null) {
                            for (JSONObject member : result) {
                                Suggestion s = memberSuggestion(member);
                                if (s == null) continue;
                                s.fromRemote = false;
                                s.remoteRank = 0;
                                lastId = s.id;
                                Suggestion existing = index.get(s.id);
                                if (existing == null) {
                                    index.put(s.id, s);
                                    added++;
                                } else {
                                    if (!Ui.isReal(existing.avatar) && Ui.isReal(s.avatar))
                                        existing.avatar = s.avatar;
                                    if (!Ui.isReal(existing.username) && Ui.isReal(s.username)) {
                                        existing.username = s.username;
                                        existing.setSearch(existing.name + " " + s.username);
                                    }
                                }
                            }
                        }
                        int pages = deepPages.getOrDefault(guild, 0) + 1;
                        deepPages.put(guild, pages);
                        boolean empty = result == null || result.isEmpty();
                        boolean noProgress = lastId == null || (after != null && after.equals(lastId));
                        if (!empty && !noProgress) deepAfter.put(guild, lastId);
                        if (empty || noProgress) deepComplete.add(guild);
                        if (pages >= DEEP_MAX_PAGES) deepComplete.add(guild);
                        if (added > 0) render();
                        if (!deepComplete.contains(guild) && active) {
                            ui.removeCallbacks(deepPageRunnable);
                            ui.postDelayed(deepPageRunnable, DEEP_PAGE_DELAY_MS);
                        }
                    }

                    @Override
                    public void onError(String message) {
                        deepLoading.remove(guild);
                        int failures = deepFailures.getOrDefault(guild, 0) + 1;
                        deepFailures.put(guild, failures);
                        deepFailedUntil.put(
                                guild, System.currentTimeMillis() + DEEP_RETRY_MS);
                        if (active && failures < 3) {
                            ui.removeCallbacks(deepPageRunnable);
                            ui.postDelayed(deepPageRunnable, DEEP_RETRY_MS);
                        }
                    }
                });
    }

    private void render() {
        if (panel == null || rows == null || host.activity == null) return;
        List<Suggestion> results = collectResults();
        if (results.isEmpty()) {
            panel.setVisibility(View.GONE);
            return;
        }

        rows.removeAllViews();
        for (Suggestion s : results) rows.addView(buildRow(s));
        int shown = Math.min(results.size(), MAX_VISIBLE_ROWS);
        ViewGroup.LayoutParams lp = panel.getLayoutParams();
        if (lp != null) {
            lp.height = dp(shown * ROW_HEIGHT_DP + 8);
            panel.setLayoutParams(lp);
        }
        panel.setVisibility(View.VISIBLE);
        panel.scrollTo(0, 0);
    }

    private List<Suggestion> collectResults() {
        String q = query.toLowerCase(Locale.ROOT);
        if (sigil == '#') {
            List<Suggestion> channels = new ArrayList<>();
            List<Channel> cached =
                    host.activity == null ? null : host.activity.channelCache.get(host.guildId);
            if (cached != null) {
                for (Channel ch : cached) {
                    if (ch == null || ch.id == null || ch.name == null) continue;
                    if (ch.type == 4) continue;
                    Suggestion s = new Suggestion(KIND_CHANNEL, ch.id, ch.name);
                    s.color = Ui.BG_INPUT;
                    s.setSearch(ch.name);
                    if (matches(s, q)) channels.add(s);
                }
            }
            Collections.sort(channels, suggestionOrder(q));
            return limit(channels);
        }

        LinkedHashMap<String, Suggestion> users = new LinkedHashMap<>();
        collectLocalUsers(users);
        if (host.guildId != null) {
            Map<String, Suggestion> indexed = deepMembers.get(host.guildId);
            if (indexed != null) {
                for (Suggestion s : indexed.values()) {
                    if (!matches(s, q)) continue;
                    Suggestion candidate = s.copy();
                    Suggestion existing = users.get(s.id);
                    if (existing == null) {
                        users.put(s.id, candidate);
                    } else {
                        if (!Ui.isReal(existing.avatar) && Ui.isReal(candidate.avatar))
                            existing.avatar = candidate.avatar;
                        if (!Ui.isReal(existing.username) && Ui.isReal(candidate.username)) {
                            existing.username = candidate.username;
                            existing.setSearch(existing.name + " " + candidate.username);
                        }
                    }
                }
            }
        }
        for (Suggestion s : remote) {
            Suggestion existing = users.get(s.id);
            if (existing == null) {
                users.put(s.id, s);
                continue;
            }
            existing.fromRemote = true;
            existing.remoteRank = s.remoteRank;
            if (!Ui.isReal(existing.avatar) && Ui.isReal(s.avatar)) existing.avatar = s.avatar;
            if (!Ui.isReal(existing.username) && Ui.isReal(s.username)) {
                existing.username = s.username;
                existing.setSearch(existing.name + " " + s.username);
            }
        }
        List<Suggestion> userList = new ArrayList<>(users.values());
        // Remote results already came from Discord's member search, whose matching rules can be
        // broader than our local "contains" check. Do not throw those matches away.
        userList.removeIf(s -> !s.fromRemote && !matches(s, q));
        Collections.sort(userList, suggestionOrder(q));

        List<Suggestion> roles = new ArrayList<>();
        if (host.guildId != null) {
            Map<String, Role> roleCache =
                    host.activity == null ? null : host.activity.roleCache;
            if (roleCache != null) {
                for (Role role : roleCache.values()) {
                    if (role == null || role.id == null || !Ui.isReal(role.name)) continue;
                    if (role.id.equals(host.guildId)) continue;
                    Suggestion s = new Suggestion(KIND_ROLE, role.id, role.name);
                    s.color = role.color != 0 ? role.color | 0xFF000000 : Ui.BLURPLE;
                    s.position = role.position;
                    s.setSearch(role.name);
                    if (matches(s, q)) roles.add(s);
                }
            }
            addSpecial(roles, q, "everyone", "Notify everyone in this channel", "everyone");
            addSpecial(roles, q, "here", "Notify everyone currently online", "here");
            Collections.sort(roles, roleOrder(q));
        }

        List<Suggestion> out = new ArrayList<>();
        out.addAll(userList);
        out.addAll(roles);
        return limit(out);
    }

    private void collectLocalUsers(Map<String, Suggestion> out) {
        if (host.activity == null) return;
        MemberListStore store = host.activity.memberList;
        if (host.guildId != null && store != null && host.guildId.equals(store.guildId())) {
            for (int i = 0; i < store.loadedCount(); i++) {
                MemberListStore.Entry e = store.get(i);
                if (e == null || e.group) continue;
                addUser(out, e.userId, e.displayName(), e.username, e.avatar);
            }
        }

        int from = Math.max(0, host.messages.size() - 200);
        for (int i = from; i < host.messages.size(); i++) {
            Message m = host.messages.get(i);
            if (m == null || m.author == null) continue;
            Author a = m.author;
            String name =
                    Ui.isReal(m.memberNick)
                            ? m.memberNick
                            : Ui.isReal(a.globalName) ? a.globalName : a.username;
            addUser(out, a.id, name, a.username, a.avatar);
        }

        if (host.guildId != null) {
            String prefix = host.guildId + "_";
            for (Map.Entry<String, String> e : host.activity.userNickCache.entrySet()) {
                if (e.getKey() == null || !e.getKey().startsWith(prefix)) continue;
                addUser(out, e.getKey().substring(prefix.length()), e.getValue(), null, null);
            }
        }

        for (Map.Entry<String, Suggestion> e : out.entrySet()) {
            Suggestion s = e.getValue();
            if (s.username == null) {
                s.username = host.activity.userNameCache.get(s.id);
                if (s.username != null) s.setSearch(s.name + " " + s.username);
            }
        }
    }

    private void addUser(
            Map<String, Suggestion> out, String id, String name, String username, String avatar) {
        if (!Ui.isReal(id) || !Ui.isReal(name)) return;
        Suggestion existing = out.get(id);
        if (existing == null) {
            Suggestion s = new Suggestion(KIND_USER, id, name);
            s.username = username;
            s.avatar = avatar;
            s.setSearch(name + (Ui.isReal(username) ? " " + username : ""));
            out.put(id, s);
            return;
        }
        if (!Ui.isReal(existing.avatar) && Ui.isReal(avatar)) existing.avatar = avatar;
        if (!Ui.isReal(existing.username) && Ui.isReal(username)) {
            existing.username = username;
            existing.setSearch(existing.name + " " + username);
        }
    }

    private static void addSpecial(
            List<Suggestion> out, String q, String id, String subtitle, String search) {
        Suggestion s = new Suggestion(KIND_SPECIAL, id, id);
        s.color = Ui.YELLOW;
        s.setSearch(search);
        s.username = subtitle;
        if (matches(s, q)) out.add(s);
    }

    private Suggestion memberSuggestion(JSONObject o) {
        if (o == null) return null;
        JSONObject u = o.optJSONObject("user");
        if (u == null) return null;
        String id = u.optString("id", null);
        String username = u.optString("username", null);
        String global = u.isNull("global_name") ? null : u.optString("global_name", null);
        String nick = o.isNull("nick") ? null : o.optString("nick", null);
        String name = Ui.isReal(nick) ? nick : Ui.isReal(global) ? global : username;
        if (!Ui.isReal(id) || !Ui.isReal(name)) return null;
        Suggestion s = new Suggestion(KIND_USER, id, name);
        s.username = username;
        s.avatar = u.isNull("avatar") ? null : u.optString("avatar", null);
        s.setSearch(name + (Ui.isReal(username) ? " " + username : ""));
        s.fromRemote = true;
        return s;
    }

    private static boolean matches(Suggestion s, String q) {
        if (q.isEmpty()) return true;
        if (s.nameLower.contains(q)) return true;
        return s.searchLower != null && s.searchLower.contains(q);
    }

    private static List<Suggestion> limit(List<Suggestion> list) {
        if (list.size() <= MAX_RESULTS) return list;
        return new ArrayList<>(list.subList(0, MAX_RESULTS));
    }

    private static Comparator<Suggestion> suggestionOrder(String q) {
        return (a, b) -> {
            int score = Integer.compare(matchScore(a, q), matchScore(b, q));
            if (score != 0) return score;
            if (a.fromRemote && b.fromRemote) {
                int rank = Integer.compare(a.remoteRank, b.remoteRank);
                if (rank != 0) return rank;
            }
            return a.name.compareToIgnoreCase(b.name);
        };
    }

    private static Comparator<Suggestion> roleOrder(String q) {
        return (a, b) -> {
            int score = Integer.compare(matchScore(a, q), matchScore(b, q));
            if (score != 0) return score;
            if (a.kind != b.kind) return a.kind == KIND_SPECIAL ? -1 : 1;
            return Integer.compare(b.position, a.position);
        };
    }

    private static int matchScore(Suggestion s, String q) {
        if (q.isEmpty()) return 0;
        if (s.nameLower.equals(q)) return -1;
        if (s.nameLower.startsWith(q)) return 0;
        if (s.nameLower.contains(q)) return 1;
        String search = s.searchLower == null ? "" : s.searchLower;
        if (search.equals(q)) return -1;
        return search.contains(q) ? 2 : 3;
    }

    private View buildRow(final Suggestion s) {
        LinearLayout row = new LinearLayout(host.activity);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(dp(12), 0, dp(12), 0);
        row.setLayoutParams(new LinearLayout.LayoutParams(-1, dp(ROW_HEIGHT_DP)));
        row.setClickable(true);
        row.setBackground(Ui.ripple(null, 0));
        row.setOnClickListener(v -> choose(s));

        row.addView(iconFor(s), new LinearLayout.LayoutParams(dp(32), dp(32)));

        LinearLayout col = new LinearLayout(host.activity);
        col.setOrientation(LinearLayout.VERTICAL);
        LinearLayout.LayoutParams clp = new LinearLayout.LayoutParams(0, -2, 1f);
        clp.setMarginStart(dp(12));
        row.addView(col, clp);

        TextView title = Ui.text(host.activity, s.name, Ui.TEXT_HEADER, 15);
        title.setTypeface(null, Typeface.BOLD);
        title.setSingleLine(true);
        title.setEllipsize(TextUtils.TruncateAt.END);
        if (s.kind == KIND_ROLE || s.kind == KIND_SPECIAL) title.setTextColor(s.color);
        col.addView(title);

        String subtitle = subtitle(s);
        if (Ui.isReal(subtitle)) {
            TextView sub = Ui.text(host.activity, subtitle, Ui.MUTED, 12);
            sub.setSingleLine(true);
            sub.setEllipsize(TextUtils.TruncateAt.END);
            col.addView(sub);
        }
        return row;
    }

    private String subtitle(Suggestion s) {
        if (s.kind == KIND_SPECIAL) return s.username;
        if (s.kind == KIND_ROLE) return "Role";
        if (s.kind == KIND_CHANNEL) return "Text channel";
        if (Ui.isReal(s.username)) return "@" + s.username;
        return "Member";
    }

    private View iconFor(Suggestion s) {
        int size = dp(32);
        if (s.kind == KIND_USER) {
            FrameLayout wrap = new FrameLayout(host.activity);
            wrap.setBackground(Ui.oval(Ui.avatarColor(s.id)));
            wrap.setClipToOutline(true);
            if (Ui.isReal(s.avatar)) {
                ImageView avatar = new ImageView(host.activity);
                avatar.setScaleType(ImageView.ScaleType.CENTER_CROP);
                if (host.activity.imageLoader != null) {
                    host.activity.imageLoader.load(
                            "https://cdn.discordapp.com/avatars/"
                                    + s.id
                                    + "/"
                                    + s.avatar
                                    + ".png?size=64",
                            avatar);
                }
                wrap.addView(avatar, new FrameLayout.LayoutParams(-1, -1));
            } else {
                ImageView icon = Ui.iconView(host.activity, Ui.I_USER, Color.WHITE, 20);
                icon.setScaleType(ImageView.ScaleType.CENTER_INSIDE);
                wrap.addView(
                        icon,
                        new FrameLayout.LayoutParams(
                                dp(20), dp(20), Gravity.CENTER));
            }
            return wrap;
        }

        TextView badge = Ui.text(host.activity, s.kind == KIND_CHANNEL ? "#" : "@", Color.WHITE, 16);
        badge.setTypeface(null, Typeface.BOLD);
        badge.setGravity(Gravity.CENTER);
        badge.setLayoutParams(new LinearLayout.LayoutParams(size, size));
        badge.setBackground(Ui.oval(s.kind == KIND_SPECIAL ? Ui.BLURPLE : s.color));
        return badge;
    }

    private void choose(Suggestion s) {
        if (input == null || tokenStart < 0) return;
        Editable text = input.getText();
        int start = Math.min(tokenStart, text.length());
        int end = input.getSelectionEnd();
        if (end < start) end = start;

        String insert;
        if (s.kind == KIND_USER) insert = "<@" + s.id + ">";
        else if (s.kind == KIND_ROLE) insert = "<@&" + s.id + ">";
        else if (s.kind == KIND_CHANNEL) insert = "<#" + s.id + ">";
        else insert = "@" + s.id;
        insert += " ";

        updating = true;
        text.replace(start, end, insert);
        input.setSelection(Math.min(text.length(), start + insert.length()));
        updating = false;
        hide();
        input.requestFocus();
    }

    private int dp(int value) {
        return Ui.dp(host.activity, value);
    }
}
