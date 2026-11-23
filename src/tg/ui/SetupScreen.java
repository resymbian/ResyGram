package tg.ui;

import javax.microedition.lcdui.Font;
import javax.microedition.lcdui.Graphics;

/**
 * The application's own setup screen: a heading, some explanation and one
 * editable field, drawn rather than built from a Form.
 *
 * The Form version could not be full screen and handed its primary action to
 * whichever command the AMS preferred - on Series 60 that was often the BACK
 * command ("Change number"), not the one that moves the flow on. Here the right
 * key is always the step's primary command and everything else lives under
 * Options, which is also where this client's other screens put it.
 */
public class SetupScreen extends SoftKeyCanvas
{
    private final Font titleFont;
    private final Font font;
    private final Font metaFont;
    private final Metrics metrics = new Metrics();
    private Theme theme;

    private String title;
    private String[] intro = new String[0];
    private String prompt = "";
    private String value = "";
    private int maxLength;
    private boolean numeric;
    private boolean secret;
    private boolean hasField;
    private String error = "";

    public SetupScreen(Theme theme, String title)
    {
        this.theme = theme == null ? Theme.byId(Theme.LIGHT) : theme;
        this.title = title == null ? "" : title;
        titleFont = Font.getFont(Font.FACE_PROPORTIONAL, Font.STYLE_BOLD,
                                 Font.SIZE_MEDIUM);
        font = Font.getFont(Font.FACE_PROPORTIONAL, Font.STYLE_PLAIN,
                            Font.SIZE_SMALL);
        metaFont = Font.getFont(Font.FACE_PROPORTIONAL, Font.STYLE_PLAIN,
                                Font.SIZE_SMALL);
    }

    public void setTheme(Theme value)
    {
        theme = value == null ? Theme.byId(Theme.LIGHT) : value;
        repaint();
    }

    public void setTitle(String value)
    {
        String next = value == null ? "" : value;
        if (next.equals(title)) { return; }
        title = next;
        repaint();
    }

    public void setIntro(String[] lines)
    {
        intro = lines == null ? new String[0] : lines;
        repaint();
    }

    public void setField(String label, String initial, int max,
                         boolean numericOnly, boolean hidden)
    {
        hasField = true;
        prompt = label == null ? "" : label;
        value = initial == null ? "" : initial;
        maxLength = max;
        numeric = numericOnly;
        secret = hidden;
        repaint();
    }

    public void noField()
    {
        hasField = false;
        repaint();
    }

    public String value() { return value; }

    public void setValue(String next)
    {
        value = next == null ? "" : next;
        error = "";
        repaint();
    }

    public void setError(String message)
    {
        error = message == null ? "" : message;
        repaint();
    }

    protected void paint(Graphics g)
    {
        updateMetrics();
        UiChrome.background(g, theme, metrics);
        UiChrome.header(g, theme, metrics, titleFont, title, "");

        int y = metrics.bodyTop + metrics.padding;
        g.setFont(font);
        g.setColor(theme.secondaryText);
        for (int i = 0; i < intro.length; i++)
        {
            String[] lines = wrap(intro[i], metrics.contentWidth);
            for (int line = 0; line < lines.length; line++)
            {
                if (y + font.getHeight() > metrics.bodyBottom) { break; }
                g.drawString(lines[line], metrics.padding, y,
                        Graphics.TOP | Graphics.LEFT);
                y += font.getHeight() + 1;
            }
            y += metrics.padding / 2;
        }

        if (!hasField) { y = paintFieldlessFooter(g, y); }
        else
        {
            y += metrics.padding;
            g.setFont(metaFont);
            g.setColor(theme.secondaryText);
            if (prompt.length() > 0 && y + metaFont.getHeight()
                    < metrics.bodyBottom)
            {
                g.drawString(UiChrome.clip(prompt, metaFont,
                        metrics.contentWidth), metrics.padding, y,
                        Graphics.TOP | Graphics.LEFT);
                y += metaFont.getHeight() + metrics.padding;
            }

            int boxHeight = font.getHeight() + metrics.padding * 2;
            if (y + boxHeight > metrics.bodyBottom)
            {
                boxHeight = Math.max(font.getHeight(), metrics.bodyBottom - y);
            }
            g.setColor(theme.surface);
            g.fillRect(metrics.padding, y,
                    metrics.contentWidth, boxHeight);
            g.setColor(theme.accent);
            g.drawRect(metrics.padding, y,
                    metrics.contentWidth - 1, boxHeight - 1);

            String shown = secret ? mask(value) : value;
            int textY = y + (boxHeight - font.getHeight()) / 2;
            g.setFont(font);
            g.setColor(theme.text);
            g.drawString(UiChrome.clip(shown, font,
                    metrics.contentWidth - metrics.padding * 2),
                    metrics.padding * 2, textY,
                    Graphics.TOP | Graphics.LEFT);

            // A caret at the end is all a phone keypad entry needs; a
            // mid-string caret would imply editing this screen cannot do.
            int caretX = metrics.padding * 2
                    + EmojiText.substringWidth(shown, 0, shown.length(), font);
            if (caretX < metrics.padding + metrics.contentWidth - 2)
            {
                g.setColor(theme.accent);
                g.fillRect(caretX + 1, textY, 1, font.getHeight());
            }
            y += boxHeight + metrics.padding;
        }

        if (error.length() > 0 && y + metaFont.getHeight() < metrics.bodyBottom)
        {
            g.setFont(metaFont);
            g.setColor(theme.badge);
            String[] lines = wrap(error, metrics.contentWidth);
            for (int i = 0; i < lines.length
                    && y + metaFont.getHeight() <= metrics.bodyBottom; i++)
            {
                g.drawString(lines[i], metrics.padding, y,
                        Graphics.TOP | Graphics.LEFT);
                y += metaFont.getHeight() + 1;
            }
        }
        paintSoftKeys(g, theme, metrics, titleFont, font);
    }

    private int paintFieldlessFooter(Graphics g, int y)
    {
        return y;
    }

    protected void keyPressed(int keyCode)
    {
        int action = 0;
        try { action = getGameAction(keyCode); }
        catch (Throwable ignored) { }

        if (handleSoftKeys(keyCode, action)) { return; }
        if (action == FIRE || keyCode == KEY_NUM5
                || keyCode == 10 || keyCode == 13)
        {
            runPrimary();
            return;
        }
        if (!hasField) { return; }

        if (keyCode == 8 || keyCode == -8 || isClearKey(keyCode))
        {
            if (value.length() > 0)
            {
                value = value.substring(0, value.length() - 1);
                error = "";
                repaint();
            }
            return;
        }
        String typed = typedText(keyCode);
        if (typed.length() == 0) { return; }
        if (value.length() + typed.length() > maxLength) { return; }
        value += typed;
        error = "";
        repaint();
    }

    protected void keyRepeated(int keyCode)
    {
        // Holding a digit is a quick way to correct a long number; holding a
        // soft key must not repeat the primary action.
        if (keyCode != KEY_SOFT_LEFT && keyCode != KEY_SOFT_RIGHT)
        {
            keyPressed(keyCode);
        }
    }

    private void runPrimary()
    {
        javax.microedition.lcdui.Command command = softRight();
        javax.microedition.lcdui.CommandListener listener = listener();
        if (command == null || listener == null) { return; }
        listener.commandAction(command, this);
    }

    private boolean isClearKey(int keyCode)
    {
        try
        {
            String name = getKeyName(keyCode);
            if (name == null) { return false; }
            return name.equalsIgnoreCase("clear")
                    || name.equalsIgnoreCase("backspace")
                    || name.indexOf("Clear") >= 0;
        }
        catch (Throwable ignored) { return false; }
    }

    private String typedText(int keyCode)
    {
        if (numeric)
        {
            if (keyCode >= '0' && keyCode <= '9') { return String.valueOf((char) keyCode); }
            if (keyCode == '+') { return "+"; }
            return "";
        }
        if (keyCode >= 32 && keyCode < 0xfffe
                && !(keyCode >= 0xd800 && keyCode <= 0xdfff)
                && keyCode != 127)
        {
            return String.valueOf((char) keyCode);
        }
        return "";
    }

    private String mask(String source)
    {
        StringBuffer out = new StringBuffer(source.length());
        for (int i = 0; i < source.length(); i++) { out.append('*'); }
        return out.toString();
    }

    private String[] wrap(String text, int width)
    {
        if (text == null || text.length() == 0) { return new String[] { "" }; }
        String[] out = new String[0];
        int start = 0;
        while (start < text.length())
        {
            int end = EmojiText.fitEnd(text, start, text.length(), width, font);
            if (end <= start) { end = Math.min(text.length(), start + 1); }
            out = append(out, text.substring(start, end));
            start = end;
            while (start < text.length() && text.charAt(start) == ' ')
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

    private void updateMetrics()
    {
        softKeyMetrics(metrics, titleFont, metaFont);
        metrics.update(getWidth(), getHeight(), font, metaFont);
    }
}
