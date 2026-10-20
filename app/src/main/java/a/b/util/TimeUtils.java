package a.b.util;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.concurrent.TimeUnit;

public class TimeUtils {
    public static String relativeTime(long epochMs) {
        long now = System.currentTimeMillis();
        long diff = now - epochMs;

        if (diff < TimeUnit.MINUTES.toMillis(1)) return "Just now";
        if (diff < TimeUnit.HOURS.toMillis(1)) return (diff / 60000) + "m ago";
        if (diff < TimeUnit.DAYS.toMillis(1)) return (diff / 3600000) + "h ago";
        if (diff < TimeUnit.DAYS.toMillis(7)) return (diff / 86400000) + "d ago";
        return new SimpleDateFormat("MMM d", Locale.US).format(new Date(epochMs));
    }
}