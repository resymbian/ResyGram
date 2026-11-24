package tg.ui;

import javax.microedition.lcdui.Canvas;
import javax.microedition.lcdui.Font;
import javax.microedition.lcdui.Graphics;

/** Allocation-free painter for common Canvas chrome. */
public final class UiChrome
{
    /**
     * Background work, counted by the workers that started it.
     *
     * The indicator is deliberately global: every screen shares the same top
     * bar, and a user does not care which of the three workers is busy. Only
     * the fact that something is still arriving matters, so the count and the
     * animation frame live here rather than in each screen.
     */
    private static volatile int working;
    private static volatile int busyFrame;
    /**
     * Height of the last header painted, so the activity pulse can repaint only
     * the strip that actually contains the spinner. Zero until a screen with a
     * header has been painted.
     */
    private static volatile int headerHeight;

    private static final int[] BUSY_DX = { 0, 2, 3, 3, 3, 2, 0, -1 };
    private static final int[] BUSY_DY = { -1, 0, 1, 2, 3, 3, 3, 2 };

    private UiChrome() { }

    public static void setWorking(boolean busy)
    {
        // No lock: header() asks this on every paint of every screen, and a
        // synchronized read in the display thread is pure overhead there.
        working = busy ? 1 : 0;
    }

    public static boolean isWorking() { return working > 0; }

    /** Advance the tiny spinner one frame. Called by the client's pulse. */
    public static void tick() { busyFrame++; }

    /**
     * A very small eight-dot ring, drawn in the accent bar's own text colour.
     *
     * It is intentionally not another progress bar: the bar belongs to a screen
     * that is waiting for a person, this one only says "the phone is still
     * doing something" while the screen itself remains usable.
     */
    public static void busy(Graphics g, Theme theme, Metrics metrics)
    {
        if (!isWorking()) { return; }
        int size = 9;
        int x = metrics.width - metrics.padding - size;
        int y = (metrics.headerHeight - size) / 2;
        int phase = busyFrame % 8;
        for (int i = 0; i < 8; i++)
        {
            boolean lit = ((i - phase + 8) % 8) < 3;
            g.setColor(lit ? theme.accentText : theme.accent);
            int px = x + 4 + BUSY_DX[i];
            int py = y + 4 + BUSY_DY[i];
            g.fillRect(px, py, lit ? 2 : 1, lit ? 2 : 1);
        }
    }

    /**
     * Repaint just the area the spinner lives in.
     *
     * The pulse used to call {@code canvas.repaint()} five times a second, which
     * on the chat list meant re-measuring every row, wrapping every visible
     * message and clipping every string just to move eight dots in the header.
     * The header records its own height when it paints; until then a full
     * repaint is the only safe answer.
     */
    public static void repaintIndicator(Canvas canvas)
    {
        if (canvas == null) { return; }
        int height = headerHeight;
        int canvasHeight = canvas.getHeight();
        if (height <= 0 || height + 8 >= canvasHeight)
        {
            canvas.repaint();
            return;
        }
        canvas.repaint(0, 0, canvas.getWidth(), height + 8);
    }

    public static void background(Graphics g, Theme theme, Metrics metrics)
    {
        g.setColor(theme.background);
        g.fillRect(0, 0, metrics.width, metrics.height);
    }

    public static void header(Graphics g, Theme theme, Metrics metrics,
                              Font font, String left, String right)
    {
        headerHeight = metrics.headerHeight;
        g.setColor(theme.accent);
        g.fillRect(0, 0, metrics.width, metrics.headerHeight);
        g.setColor(theme.accentText);
        g.setFont(font);
        int y = metrics.padding;
        if (left != null)
        {
            g.drawString(clip(left, font,
                    Math.max(1, metrics.width * 2 / 3)),
                    metrics.padding, y, Graphics.TOP | Graphics.LEFT);
        }
        int rightEdge = metrics.width - metrics.padding;
        if (isWorking()) { rightEdge -= 13; }
        if (right != null && right.length() > 0)
        {
            g.drawString(clip(right, font, Math.max(1, metrics.width / 3)),
                    rightEdge, y, Graphics.TOP | Graphics.RIGHT);
        }
        busy(g, theme, metrics);
    }

    /**
     * The command bar a fullscreen canvas draws for itself.
     *
     * In fullscreen mode the AMS draws no soft key bar at all, so a screen that
     * wants the extra rows has to put the two labels back - otherwise the
     * commands become invisible and the only way out is "Options" that is not
     * there either.
     */
    public static void footer(Graphics g, Theme theme, Metrics metrics,
                              Font font, String left, String right)
    {
        int top = metrics.height - metrics.footerHeight;
        if (metrics.footerHeight <= 0) { return; }
        g.setColor(theme.surface);
        g.fillRect(0, top, metrics.width, metrics.footerHeight);
        g.setColor(theme.border);
        g.fillRect(0, top, metrics.width, 1);
        int y = top + (metrics.footerHeight - font.getHeight()) / 2;
        g.setFont(font);
        g.setColor(theme.secondaryText);
        if (left != null && left.length() > 0)
        {
            g.drawString(left, metrics.padding, y, Graphics.TOP | Graphics.LEFT);
        }
        if (right != null && right.length() > 0)
        {
            g.setColor(theme.text);
            g.drawString(right, metrics.width - metrics.padding, y,
                    Graphics.TOP | Graphics.RIGHT);
        }
    }

    public static String clip(String value, Font font, int width)
    {
        if (value == null || width <= 0) { return ""; }
        if (font.stringWidth(value) <= width) { return value; }
        String suffix = "...";
        int suffixWidth = font.stringWidth(suffix);
        if (suffixWidth > width) { return ""; }
        // substringWidth(0, n) grows monotonically with n, so halve the search
        // instead of measuring every prefix. A 96-character preview used to cost
        // up to 96 measurements per paint, per row; this costs about seven.
        int low = 0;
        int high = value.length();
        while (low < high)
        {
            int mid = low + ((high - low + 1) >> 1);
            if (font.substringWidth(value, 0, mid) + suffixWidth <= width)
            {
                low = mid;
            }
            else
            {
                high = mid - 1;
            }
        }
        return value.substring(0, low) + suffix;
    }
}
