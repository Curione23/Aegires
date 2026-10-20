package a.b.util;

import android.os.Build;
import android.util.Base64;
import org.json.JSONObject;
import java.util.UUID;

public final class SuperProperties {
    private static final String LAUNCH_ID = UUID.randomUUID().toString();
    private static String heartbeatId = UUID.randomUUID().toString();
    private static long lastHeartbeat = System.currentTimeMillis();
    
    public static final String CLIENT_VERSION = "347.12";
    public static final int CLIENT_BUILD = 347012;
    public static final String USER_AGENT = "Discord-Android/" + CLIENT_BUILD;

    private SuperProperties() {}

    public static String getBase64() {
        if (System.currentTimeMillis() - lastHeartbeat > 30 * 60 * 1000) {
            heartbeatId = UUID.randomUUID().toString();
            lastHeartbeat = System.currentTimeMillis();
        }

        try {
            JSONObject p = new JSONObject();
            p.put("os", "Android");
            p.put("browser", "Discord Android");
            p.put("device", Build.MANUFACTURER + " " + Build.MODEL);
            p.put("system_locale", "en-US");
            p.put("client_version", CLIENT_VERSION);
            p.put("release_channel", "google-play");
            p.put("client_build_number", CLIENT_BUILD);
            p.put("design_id", 0);

            p.put("client_launch_id", LAUNCH_ID);
            p.put("client_heartbeat_session_id", heartbeatId);
            p.put("launch_signature", "00000000-0000-0000-0000-000000000000"); 

            p.put("os_version", Build.VERSION.RELEASE);
            p.put("os_sdk_version", Build.VERSION.SDK_INT);
            p.put("client_performance_cpu", 11);
            p.put("client_performance_memory", 246220);
            p.put("accessibility_support_enabled", false);

            return Base64.encodeToString(p.toString().getBytes("UTF-8"), Base64.NO_WRAP);
        } catch (Exception e) {
            return "";
        }
    }
}