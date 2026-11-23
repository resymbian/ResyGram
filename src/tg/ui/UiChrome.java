package tg.ui;

import javax.microedition.lcdui.Font;
import javax.microedition.lcdui.Graphics;

/** Allocation-free painter for common Canvas chrome. */
public final class UiChrome
{
    private UiChrome() { }

    public static void background(Graphics g, Theme theme, Metrics metrics)
    {
        g.setColor(theme.background);
        g.fillRect(0, 0, metrics.width, metrics.height);
    }

    public static void header(Graphics g, Theme theme, Metrics metrics,
                              Font font, String left, String right)
    {
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
        if (right != null && right.length() > 0)
        {
            g.drawString(clip(right, font, Math.max(1, metrics.width / 3)),
                    metrics.width - metrics.padding, y,
                    Graphics.TOP | Graphics.RIGHT);
        }
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
        int end = value.length();
        while (end > 0
                && font.substringWidth(value, 0, end) + suffixWidth > width)
        {
            end--;
        }
        return value.substring(0, end) + suffix;
    }
}
