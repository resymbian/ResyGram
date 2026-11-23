package tg.ui;

import javax.microedition.lcdui.Canvas;
import javax.microedition.lcdui.Font;
import javax.microedition.lcdui.Graphics;

/**
 * A wait with a spinner instead of a paragraph.
 *
 * Every slow step - connecting, requesting a code, sending - used to be a Form
 * holding one line of text. A Form does not animate, so on a handset where
 * generating an auth_key takes ten seconds the screen was indistinguishable from
 * a crash, and the first thing a user reaches for is the soft key that kills the
 * MIDlet. The dots move here, which is the whole difference.
 *
 * The animation is bounded: it repaints for at most a few seconds and then
 * stops on its own. These screens are replaced by whatever the work produced,
 * often without telling the screen, and an unbounded timer would keep repainting
 * a Canvas nobody is showing for the rest of the session.
 */
public final class BusyScreen extends Canvas
{
    private static final int DOTS = 8;
    private static final int FRAME_MS = 120;
    private static final int MAX_FRAMES = 40;

    private final Theme theme;
    private final String title;
    private final String text;
    private final Font titleFont;
    private final Font textFont;
    private final Metrics metrics = new Metrics();

    private int frame;
    private int framesLeft = MAX_FRAMES;

    public BusyScreen(Theme theme, String title, String text)
    {
        this.theme = theme == null ? Theme.byId(Theme.LIGHT) : theme;
        this.title = title == null ? "" : title;
        this.text = text == null ? "" : text;
        titleFont = Font.getFont(Font.FACE_PROPORTIONAL, Font.STYLE_BOLD,
                                 Font.SIZE_MEDIUM);
        textFont = Font.getFont(Font.FACE_PROPORTIONAL, Font.STYLE_PLAIN,
                                Font.SIZE_SMALL);
        start();
    }

    private void start()
    {
        Thread t = new Thread(new Runnable()
        {
            public void run()
            {
                while (framesLeft-- > 0)
                {
                    frame = (frame + 1) % DOTS;
                    repaint();
                    try
                    {
                        Thread.sleep(FRAME_MS);
                    }
                    catch (InterruptedException e)
                    {
                        return;
                    }
                }
            }
        });
        t.start();
    }

    protected void paint(Graphics g)
    {
        metrics.update(getWidth(), getHeight(), textFont, textFont);
        UiChrome.background(g, theme, metrics);

        int w = getWidth();
        int cx = w / 2;
        int y = metrics.bodyTop + 8;

        g.setFont(titleFont);
        g.setColor(theme.text);
        g.drawString(title, cx, y, Graphics.HCENTER | Graphics.TOP);
        y += titleFont.getHeight() + 6;

        drawSpinner(g, cx, y + 8);
        y += 26;

        g.setFont(textFont);
        g.setColor(theme.secondaryText);
        int lineHeight = textFont.getHeight() + 1;
        String[] lines = wrap(text, metrics.width - metrics.padding * 2);
        for (int i = 0; i < lines.length && y < metrics.bodyBottom - lineHeight; i++)
        {
            g.drawString(lines[i], cx, y, Graphics.HCENTER | Graphics.TOP);
            y += lineHeight;
        }
    }

    /** Split the message on newlines, then wrap anything wider than the panel. */
    private String[] wrap(String value, int width)
    {
        if (value == null || value.length() == 0) { return new String[0]; }
        String[] paragraphs = split(value);
        String[] out = new String[0];
        for (int p = 0; p < paragraphs.length; p++)
        {
            String rest = paragraphs[p];
            do
            {
                int fit = EmojiText.fitEnd(rest, 0, rest.length(), width, textFont);
                if (fit <= 0) { fit = Math.min(1, rest.length()); }
                out = append(out, rest.substring(0, fit));
                rest = rest.substring(fit);
                while (rest.length() > 0 && rest.charAt(0) == ' ')
                {
                    rest = rest.substring(1);
                }
            } while (rest.length() > 0);
        }
        return out;
    }

    private static String[] split(String value)
    {
        int count = 1;
        for (int i = 0; i < value.length(); i++)
        {
            if (value.charAt(i) == '\n') { count++; }
        }
        String[] parts = new String[count];
        int at = 0;
        int start = 0;
        for (int i = 0; i < value.length(); i++)
        {
            if (value.charAt(i) == '\n')
            {
                parts[at++] = value.substring(start, i);
                start = i + 1;
            }
        }
        parts[at] = value.substring(start);
        return parts;
    }

    private static String[] append(String[] array, String value)
    {
        String[] out = new String[array.length + 1];
        System.arraycopy(array, 0, out, 0, array.length);
        out[array.length] = value;
        return out;
    }

    private void drawSpinner(Graphics g, int cx, int cy)
    {
        int radius = 9;
        for (int i = 0; i < DOTS; i++)
        {
            int x = cx + (COS[i] * radius) / 256;
            int y = cy + (SIN[i] * radius) / 256;
            g.setColor(i == frame ? theme.accent : theme.secondaryText);
            g.fillArc(x - 2, y - 2, 4, 4, 0, 360);
        }
    }

    // Eight fixed angles; CLDC 1.1 trig would be a double conversion per dot per
    // frame for a shape that never changes.
    private static final int[] COS = { 256, 181, 0, -181, -256, -181, 0, 181 };
    private static final int[] SIN = { 0, 181, 256, 181, 0, -181, -256, -181 };
}
