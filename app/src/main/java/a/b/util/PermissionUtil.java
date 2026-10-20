package a.b.util;

import android.util.Base64;
import org.json.JSONArray;
import org.json.JSONObject;
import java.util.List;
import java.util.Map;

public final class PermissionUtil {
    public static final long VIEW_CHANNEL = 1L << 10;
    public static final long MANAGE_MESSAGES = 1L << 13;
    public static final long ADMINISTRATOR = 1L << 3;
    private static final int OVERWRITE_ROLE = 0;
    private static final int OVERWRITE_MEMBER = 1;

    private PermissionUtil() {}

    public static String getUserIdFromToken(String token) {
        if (token == null) return null;
        int dot = token.indexOf('.');
        if (dot <= 0) return null;
        try {
            byte[] decoded = Base64.decode(token.substring(0, dot), Base64.DEFAULT);
            if (decoded == null || decoded.length == 0) return null;
            String s = new String(decoded, "UTF-8").trim();
            return isNumeric(s) ? s : null;
        } catch (Exception ignored) {
            return null;
        }
    }

    public static boolean canViewChannel(JSONObject channelJson, String guildId, String userId, List<String> memberRoleIds, Map<String, Long> rolePerms, boolean isOwner) {
        return (computePermissions(channelJson, guildId, userId, memberRoleIds, rolePerms, isOwner) & VIEW_CHANNEL) != 0;
    }

    public static boolean canManageMessages(JSONObject channelJson, String guildId, String userId, List<String> memberRoleIds, Map<String, Long> rolePerms, boolean isOwner) {
        return (computePermissions(channelJson, guildId, userId, memberRoleIds, rolePerms, isOwner) & MANAGE_MESSAGES) != 0;
    }

    private static long computePermissions(JSONObject channelJson, String guildId, String userId, List<String> memberRoleIds, Map<String, Long> rolePerms, boolean isOwner) {
        if (channelJson == null) return 0;
        if (isOwner) return ~0L;
        long permissions = basePermissions(guildId, memberRoleIds, rolePerms);
        if ((permissions & ADMINISTRATOR) != 0) return ~0L;
        JSONArray overwrites = channelJson.optJSONArray("permission_overwrites");
        if (overwrites != null && overwrites.length() > 0) {
            permissions = applyOverwrites(permissions, overwrites, guildId, userId, memberRoleIds);
        }
        return permissions;
    }

    private static long basePermissions(String guildId, List<String> memberRoleIds, Map<String, Long> rolePerms) {
        if (rolePerms == null) return 0;
        long out = 0;
        if (guildId != null) {
            Long everyone = rolePerms.get(guildId);
            if (everyone != null) out |= everyone;
        }
        if (memberRoleIds != null) {
            for (int i = 0; i < memberRoleIds.size(); i++) {
                String id = memberRoleIds.get(i);
                if (id == null || id.equals(guildId)) continue;
                Long p = rolePerms.get(id);
                if (p != null) out |= p;
            }
        }
        return out;
    }

    private static long applyOverwrites(long permissions, JSONArray overwrites, String guildId, String userId, List<String> memberRoleIds) {
        permissions = applyRoleOverwrite(permissions, findOverwrite(overwrites, guildId, OVERWRITE_ROLE));
        long roleAllow = 0, roleDeny = 0;
        boolean anyMatch = false;
        for (int i = 0; i < overwrites.length(); i++) {
            JSONObject ow = overwrites.optJSONObject(i);
            if (ow == null || ow.optInt("type", -1) != OVERWRITE_ROLE) continue;
            String targetId = ow.optString("id", null);
            if (targetId == null || targetId.equals(guildId) || memberRoleIds == null || !memberRoleIds.contains(targetId)) continue;
            roleAllow |= parseBits(ow.optString("allow", "0"));
            roleDeny |= parseBits(ow.optString("deny", "0"));
            anyMatch = true;
        }
        if (anyMatch) permissions = (permissions & ~roleDeny) | roleAllow;
        if (userId != null) permissions = applyRoleOverwrite(permissions, findOverwrite(overwrites, userId, OVERWRITE_MEMBER));
        return permissions;
    }

    private static JSONObject findOverwrite(JSONArray overwrites, String id, int type) {
        if (id == null) return null;
        for (int i = 0; i < overwrites.length(); i++) {
            JSONObject ow = overwrites.optJSONObject(i);
            if (ow != null && ow.optInt("type", -1) == type && id.equals(ow.optString("id", null))) return ow;
        }
        return null;
    }

    private static long applyRoleOverwrite(long permissions, JSONObject overwrite) {
        if (overwrite == null) return permissions;
        long allow = parseBits(overwrite.optString("allow", "0"));
        long deny = parseBits(overwrite.optString("deny", "0"));
        return (permissions & ~deny) | allow;
    }

    private static long parseBits(String s) {
        if (s == null || s.isEmpty()) return 0;
        for (int i = 0; i < s.length(); i++) {
            if (s.charAt(i) < '0' || s.charAt(i) > '9') return 0;
        }
        try { return Long.parseLong(s); } catch (NumberFormatException e) { return 0; }
    }

    private static boolean isNumeric(String s) {
        if (s == null || s.isEmpty()) return false;
        for (int i = 0; i < s.length(); i++) {
            if (!Character.isDigit(s.charAt(i))) return false;
        }
        return true;
    }
}