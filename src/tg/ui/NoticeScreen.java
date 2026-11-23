package tg.ui;

import javax.microedition.lcdui.Command;
import javax.microedition.lcdui.CommandListener;
import javax.microedition.lcdui.Displayable;
import javax.microedition.lcdui.Font;
import javax.microedition.lcdui.Graphics;

/**
 * A short message with one acknowledgement, drawn in the client's own style.
 *
 * The AMS Alert has its own fonts, its own title bar and its own idea of a
 * timeout, none of which can be themed. Every ordinary "that worked" or "that
 * cannot be done" used to land there, which made the application look like two
 * applications. This is the same message with the same right-key convention as
 * every other screen.
 */
public final class NoticeScreen extends SoftKeyCanvas implements CommandListener
{
    public interface Dismiss
    {
        void onDismiss();
    }

    private final Theme theme;
    private final Font titleFont;
    private final Font font;
    private final Metrics metrics = new Metrics();
    private final String title;
    private final String message;
    private final Dismiss dismiss;
    private final Command ok = new Command("OK", Command.OK, 1);
    private boolean closed;

    public NoticeScreen(Theme theme, String title, String message,
                        Dismiss dismiss)
    {
        this.theme = theme == null ? Theme.byId(Theme.LIGHT) : theme;
        this.title = title == null ? "" : title;
        this.message = message == null ? "" : message;
        this.dismiss = dismiss;
        titleFont = Font.getFont(Font.FACE_PROPORTIONAL, Font.STYLE_BOLD,
                                 Font.SIZE_MEDIUM);
        font = Font.getFont(Font.FACE_PROPORTIONAL, Font.STYLE_PLAIN,
                            Font.SIZE_SMALL);
        setSoftKeys(ok, null);
        setCommandListener(this);
    }

    public void commandAction(Command c, Displayable d)
    {
        close();
    }

    private void close()
    {
        if (closed) { return; }
        closed = true;
        if (dismiss != null) { dismiss.onDismiss(); }
    }

    protected void paint(Graphics g)
    {
        softKeyMetrics(metrics, titleFont, font);
        metrics.update(getWidth(), getHeight(), font, font);
        UiChrome.background(g, theme, metrics);
        UiChrome.header(g, theme, metrics, titleFont, title, "");

        int y = metrics.bodyTop + metrics.padding * 2;
        String[] lines = wrap(message, metrics.contentWidth);
        g.setFont(font);
        g.setColor(theme.text);
        for (int i = 0; i < lines.length
                && y + font.getHeight() <= metrics.bodyBottom; i++)
        {
            g.drawString(lines[i], metrics.padding, y,
                    Graphics.TOP | Graphics.LEFT);
            y += font.getHeight() + 2;
        }
        paintSoftKeys(g, theme, metrics, titleFont, font);
    }

    protected void keyPressed(int keyCode)
    {
        int action = 0;
        try { action = getGameAction(keyCode); }
        catch (Throwable ignored) { }
        if (handleSoftKeys(keyCode, action)) { return; }
        if (action == FIRE || keyCode == KEY_NUM5) { close(); }
    }

    private String[] wrap(String text, int width)
    {
        if (text == null || text.length() == 0) { return new String[] { "" }; }
        StringBuffer buffer = new StringBuffer(text.length());
        for (int i = 0; i < text.length(); i++)
        {
            char c = text.charAt(i);
            buffer.append(c == '\n' ? ' ' : c);
        }
        String flat = buffer.toString();
        String[] out = new String[0];
        int start = 0;
        while (start < flat.length())
        {
            int end = EmojiText.fitEnd(flat, start, flat.length(), width, font);
            if (end <= start) { end = Math.min(flat.length(), start + 1); }
            out = append(out, flat.substring(start, end));
            start = end;
            while (start < flat.length() && flat.charAt(start) == ' ')
            {
                start++;
            }
        }
        return out;
    }

    private static String[] append(String[] values, String value)
    {
        String[] out = new String[values.length + 1];
        System.arraycopy(values, 0, out, 0, values.length);
        out[values.length] = value;
        return out;
    }
}
