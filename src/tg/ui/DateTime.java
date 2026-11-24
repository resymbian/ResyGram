package tg.ui;

import java.util.Calendar;
import java.util.Date;

/**
 * Small local-time formatter; avoids heavyweight locale/date-format classes.
 *
 * The dialog list and the transcript ask for the same handful of timestamps on
 * every paint (and twice while laying out a chat). {@link Calendar} is expensive
 * on CLDC, so the last few results are kept here instead of being rebuilt for
 * every row of every frame. The cache is intentionally tiny and allocation-free
 * after the first fills: a handful of strings is cheaper than one Calendar.
 */
public final class DateTime
{
    private static final int SLOTS = 24;
    private static final int[] stamps = new int[SLOTS];
    private static final String[] compacts = new String[SLOTS];
    private static final String[] times = new String[SLOTS];
    private static final String[] dates = new String[SLOTS];
    private static final int[] days = new int[SLOTS];
    private static int nextSlot;

    /** "Today" changes at midnight, not on every paint; recheck every 30 s. */
    private static long todayCheckedAt = -1L;
    private static int todayKey;

    private DateTime() { }

    public static String time(int unixSeconds)
    {
        if (unixSeconds <= 0) { return ""; }
        int at = slot(unixSeconds);
        if (times[at] != null) { return times[at]; }
        Calendar c = calendar(unixSeconds);
        String out = two(c.get(Calendar.HOUR_OF_DAY)) + ":"
                + two(c.get(Calendar.MINUTE));
        times[at] = out;
        return out;
    }

    public static String date(int unixSeconds)
    {
        if (unixSeconds <= 0) { return ""; }
        int at = slot(unixSeconds);
        if (dates[at] != null) { return dates[at]; }
        Calendar c = calendar(unixSeconds);
        String out = c.get(Calendar.YEAR) + "-"
                + two(c.get(Calendar.MONTH) + 1) + "-"
                + two(c.get(Calendar.DAY_OF_MONTH));
        dates[at] = out;
        return out;
    }

    /** Compact dialog-list timestamp: local time today, otherwise YYYY-MM-DD. */
    public static String compact(int unixSeconds)
    {
        if (unixSeconds <= 0) { return ""; }
        int at = slot(unixSeconds);
        if (compacts[at] != null) { return compacts[at]; }
        long now = System.currentTimeMillis();
        if (todayCheckedAt < 0L || now - todayCheckedAt >= 30000L)
        {
            int fresh = rawDayKey((int) (now / 1000L));
            if (fresh != todayKey)
            {
                // A timestamp formatted as a time yesterday must become a date
                // today even though its slot has not been recycled.
                for (int i = 0; i < SLOTS; i++) { compacts[i] = null; }
            }
            todayKey = fresh;
            todayCheckedAt = now;
        }
        String out = dayKey(unixSeconds) == todayKey
                ? time(unixSeconds) : date(unixSeconds);
        compacts[at] = out;
        return out;
    }

    /** Stable day identity in the handset's local timezone. */
    public static int dayKey(int unixSeconds)
    {
        if (unixSeconds <= 0) { return 0; }
        int at = slot(unixSeconds);
        if (days[at] != 0) { return days[at]; }
        int out = rawDayKey(unixSeconds);
        days[at] = out;
        return out;
    }

    private static int rawDayKey(int unixSeconds)
    {
        Calendar c = calendar(unixSeconds);
        return c.get(Calendar.YEAR) * 10000
                + (c.get(Calendar.MONTH) + 1) * 100
                + c.get(Calendar.DAY_OF_MONTH);
    }

    /**
     * Existing slot for a timestamp, else one recycled in round-robin order.
     * Zero is never a real Unix timestamp in this client, so it doubles as the
     * "unused" marker.
     */
    private static int slot(int unixSeconds)
    {
        for (int i = 0; i < SLOTS; i++)
        {
            if (stamps[i] == unixSeconds) { return i; }
        }
        int at = nextSlot;
        nextSlot = (nextSlot + 1) % SLOTS;
        stamps[at] = unixSeconds;
        compacts[at] = null;
        times[at] = null;
        dates[at] = null;
        days[at] = 0;
        return at;
    }

    private static Calendar calendar(int unixSeconds)
    {
        Calendar c = Calendar.getInstance();
        c.setTime(new Date((long) unixSeconds * 1000L));
        return c;
    }

    private static String two(int value)
    {
        return value < 10 ? ("0" + value) : String.valueOf(value);
    }
}
