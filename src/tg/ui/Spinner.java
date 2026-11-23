package tg.ui;

import javax.microedition.lcdui.Graphics;

/**
 * The "something is happening" indicator: a bar with a sweeping segment.
 *
 * The first version of this was eight dots on a circle. On a 320x240 panel seen
 * from arm's length that reads as a decoration, not as progress, and a bar is
 * the one loading idiom every phone of the era already uses. The segment travels
 * left to right with a short tail, and since the real duration is unknown the
 * bar never claims to be a percentage - it only has to prove motion.
 *
 * Drawn from integers and frame counters: no timer of its own, no allocation,
 * and no trig. Whoever owns the screen decides how fast the frame advances.
 */
public final class Spinner
{
    private Spinner() { }

    /** Suggested frame interval for callers driving their own repaint loop. */
    public static final int FRAME_MS = 90;

    public static int height(Theme theme, int width)
    {
        int bar = Math.max(3, width / 60);
        return bar;
    }

    /**
     * @param frame  monotonically increasing tick; only its value modulo the
     *               travel distance matters, so callers can pass anything
     */
    public static void draw(Graphics g, Theme theme, int x, int y, int width,
                            int frame)
    {
        int bar = height(theme, width);
        if (width < bar * 4) { return; }
        g.setColor(theme.border);
        g.fillRect(x, y, width, bar);

        // The moving segment is a third of the track, so the bar never looks
        // either empty or full - an indeterminate bar that touches both ends
        // reads as finished.
        int segment = Math.max(bar * 4, width / 3);
        int travel = width + segment;
        int position = ((frame * Math.max(1, width / 24)) % travel);
        int from = x + position - segment;
        int to = x + position;
        if (from < x) { from = x; }
        if (to > x + width) { to = x + width; }
        if (to > from)
        {
            g.setColor(theme.accent);
            g.fillRect(from, y, to - from, bar);
        }

        // A brighter leading edge, one bar tall, so the direction is unambiguous
        // even on a panel with slow refresh.
        int edge = x + position - 1;
        if (edge > x && edge < x + width)
        {
            g.setColor(theme.text);
            g.fillRect(edge, y, 1, bar);
        }
    }
}
