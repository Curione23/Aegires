package a.b.util;

import java.text.DateFormat;
import java.text.SimpleDateFormat;
import java.util.Calendar;
import java.util.Date;
import java.util.Locale;
import java.util.concurrent.TimeUnit;

public class TimeUtils {
    public static String relativeTime(long epochMs) {
        long diff = System.currentTimeMillis() - epochMs;
        if (diff < TimeUnit.MINUTES.toMillis(1)) return "now";
        if (diff < TimeUnit.HOURS.toMillis(1)) return (diff / 60000) + "m";
        if (diff < TimeUnit.DAYS.toMillis(1)) return (diff / 3600000) + "h";
        if (diff < TimeUnit.DAYS.toMillis(7)) return (diff / 86400000) + "d";
        return new SimpleDateFormat("MMM d", Locale.US).format(new Date(epochMs));
    }

    public static String messageTime(long epochMs) {
        Calendar now = Calendar.getInstance();
        Calendar then = Calendar.getInstance();
        then.setTimeInMillis(epochMs);
        String clock = DateFormat.getTimeInstance(DateFormat.SHORT).format(new Date(epochMs));
        long dayDiff =
                Math.round(
                        (startOfDayMillis(now) - startOfDayMillis(then))
                                / (double) TimeUnit.DAYS.toMillis(1));
        if (dayDiff == 0) return "Today at " + clock;
        if (dayDiff == 1) return "Yesterday at " + clock;
        return DateFormat.getDateInstance(DateFormat.SHORT).format(new Date(epochMs));
    }

    private static long startOfDayMillis(Calendar c) {
        Calendar copy = (Calendar) c.clone();
        copy.set(Calendar.HOUR_OF_DAY, 0);
        copy.set(Calendar.MINUTE, 0);
        copy.set(Calendar.SECOND, 0);
        copy.set(Calendar.MILLISECOND, 0);
        return copy.getTimeInMillis();
    }
}
