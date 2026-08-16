package dev.reinforcedclaims.util;

// "How long ago" strings for timestamps.
public final class RelativeTime {

    private RelativeTime() {
    }

    // Compact form for log lines: "5m ago".
    public static String compact(long time) {
        long seconds = elapsedSeconds(time);
        if (seconds < 60) {
            return "just now";
        }
        long minutes = seconds / 60;
        if (minutes < 60) {
            return minutes + "m ago";
        }
        long hours = minutes / 60;
        return hours < 24 ? hours + "h ago" : (hours / 24) + "d ago";
    }

    // Spelled-out form for menu lore: "5 minutes ago".
    public static String verbose(long time) {
        long minutes = elapsedSeconds(time) / 60;
        if (minutes < 1) {
            return "just now";
        }
        if (minutes < 60) {
            return plural(minutes, "minute");
        }
        long hours = minutes / 60;
        return hours < 24 ? plural(hours, "hour") : plural(hours / 24, "day");
    }

    // Elapsed seconds, clamped to zero.
    private static long elapsedSeconds(long time) {
        return Math.max(0, (System.currentTimeMillis() - time) / 1000);
    }

    private static String plural(long count, String unit) {
        return count + " " + unit + (count == 1 ? "" : "s") + " ago";
    }
}
