package a.b.model;

import org.json.JSONArray;
import org.json.JSONObject;

public class DiscordUser {
    public String id;
    public String username;
    public String globalName;
    public String avatar;
    public String banner;
    public String bio;
    public String pronouns;

    public int accentColor;
    public int publicFlags;
    public int premiumType;

    public boolean bot;
    public boolean verified;
    public boolean system;

    public int mutualGuildsCount;

    // Derived from the snowflake id, always populated.
    public long createdAtMs;

    public DiscordUser(String id, String username, String globalName,
            String avatar, String banner, String bio) {
        this.id = id;
        this.username = username;
        this.globalName = globalName;
        this.avatar = avatar;
        this.banner = banner;
        this.bio = bio;
        this.createdAtMs = snowflakeToMillis(id);
    }

    /**
     * Parses either a flat user object (/users/{id}) or a profile envelope
     * (/users/{id}/profile) — the profile response nests the user under "user".
     */
    public static DiscordUser fromJson(JSONObject o) throws Exception {
        JSONObject user = o.has("user") ? o.getJSONObject("user") : o;
        JSONObject profile = o.optJSONObject("user_profile");

        String id = user.getString("id");
        String username = user.getString("username");
        String globalName = user.isNull("global_name") ? null : user.optString("global_name", null);

        // Banner can live on the user object OR inside user_profile.
        String banner = null;
        if (!user.isNull("banner")) banner = user.optString("banner", null);
        if (banner == null && profile != null && !profile.isNull("banner")) {
            banner = profile.optString("banner", null);
        }

        DiscordUser u = new DiscordUser(id, username, globalName,
                user.isNull("avatar") ? null : user.optString("avatar", null),
                banner,
                null);

        u.bot = user.optBoolean("bot", false);
        u.system = user.optBoolean("system", false);
        u.verified = user.optBoolean("verified", false);
        u.publicFlags = user.optInt("public_flags", 0);
        u.premiumType = user.optInt("premium_type", 0);
        u.accentColor = user.optInt("accent_color", 0);

        if (profile != null) {
            if (u.bio == null && !profile.isNull("bio")) {
                u.bio = profile.optString("bio", null);
            }
            if (profile.has("accent_color") && !profile.isNull("accent_color")) {
                u.accentColor = profile.optInt("accent_color", u.accentColor);
            }
            if (!profile.isNull("pronouns")) {
                u.pronouns = profile.optString("pronouns", null);
            }
        }

        JSONArray mutuals = o.optJSONArray("mutual_guilds");
        if (mutuals != null) u.mutualGuildsCount = mutuals.length();

        return u;
    }

    public static long snowflakeToMillis(String id) {
        try {
            return (Long.parseLong(id) >> 22) + 1420070400000L;
        } catch (Exception e) {
            return 0L;
        }
    }
}