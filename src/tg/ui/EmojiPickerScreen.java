package tg.ui;

import javax.microedition.lcdui.Canvas;
import javax.microedition.lcdui.Command;
import javax.microedition.lcdui.CommandListener;
import javax.microedition.lcdui.Displayable;
import javax.microedition.lcdui.Font;
import javax.microedition.lcdui.Graphics;

/**
 * Pick an emoji to type.
 *
 * Reactions already offered the atlas as a grid, but the composer did not: the
 * handset's keypad cannot enter a supplementary-plane character, so a phone
 * without a system emoji keyboard could receive emoji and never send one.
 *
 * The grid is the same {@link EmojiData} the message list draws from, so an
 * emoji typed here is rendered by the same sprite sheet the reader sees - no
 * second copy of the atlas and no glyph that renders in the composer but not in
 * the conversation.
 *
 * Nothing is allocated per paint: the glyph strings are built once, when the
 * screen is constructed.
 */
public final class EmojiPickerScreen extends Canvas
{
    public interface PickListener
    {
        void onEmojiPicked(String emoji);
    }

    private final Theme theme;
    private final Font font;
    private final Metrics metrics = new Metrics();

    /** One string per codepoint, in atlas order. */
    private final String[] glyphs;
    private final PickListener listener;

    private int selected;
    private int top;
    private int columns = 1;
    private int cell;

    public EmojiPickerScreen(Theme theme, PickListener listener)
    {
        this.theme = theme == null ? Theme.byId(Theme.LIGHT) : theme;
        this.listener = listener;
        font = Font.getFont(Font.FACE_PROPORTIONAL, Font.STYLE_PLAIN,
                            Font.SIZE_SMALL);
        glyphs = new String[EmojiData.CODEPOINTS.length];
        for (int i = 0; i < glyphs.length; i++)
        {
            glyphs[i] = String.valueOf((char) EmojiData.CODEPOINTS[i]);
            int cp = EmojiData.CODEPOINTS[i];
            if (cp > 0xffff)
            {
                // Surrogate pair, built by hand: CLDC 1.1 has no
                // Character.toChars, and Appending to a StringBuffer twice is
                // the only way to get a supplementary character into a String.
                char high = (char) (0xd800 + ((cp - 0x10000) >> 10));
                char low = (char) (0xdc00 + ((cp - 0x10000) & 0x3ff));
                glyphs[i] = new String(new char[] { high, low });
            }
        }
        addCommand(new Command("Back", Command.BACK, 1));
        setCommandListener(new CommandListener()
        {
            public void commandAction(Command c, Displayable d)
            {
                if (EmojiPickerScreen.this.listener != null)
                {
                    EmojiPickerScreen.this.listener.onEmojiPicked(null);
                }
            }
        });
    }

    protected void sizeChanged(int w, int h)
    {
        metrics.update(w, h, font, font);
        cell = EmojiText.GLYPH + metrics.padding * 2;
        columns = Math.max(1, (w - metrics.padding) / cell);
        repaint();
    }

    protected void paint(Graphics g)
    {
        metrics.update(getWidth(), getHeight(), font, font);
        UiChrome.background(g, theme, metrics);
        UiChrome.header(g, theme, metrics, font, "Emoji",
                        "Select to insert");

        // A Canvas is not required to have been sized before its first paint.
        if (cell <= 0) { sizeChanged(getWidth(), getHeight()); }

        int rowsVisible = Math.max(1, metrics.bodyHeight / cell);
        ensureVisible(rowsVisible);

        int clipX = g.getClipX();
        int clipY = g.getClipY();
        int clipWidth = g.getClipWidth();
        int clipHeight = g.getClipHeight();
        g.clipRect(0, metrics.bodyTop, metrics.width, metrics.bodyHeight);
        for (int row = 0; row < rowsVisible && (top + row) * columns < glyphs.length;
                row++)
        {
            int y = metrics.bodyTop + row * cell;
            for (int col = 0; col < columns; col++)
            {
                int index = (top + row) * columns + col;
                if (index >= glyphs.length) { break; }
                int x = metrics.padding + col * cell;
                if (index == selected)
                {
                    g.setColor(theme.selection);
                    g.fillRect(x, y, cell, cell);
                }
                EmojiText.drawString(g, glyphs[index], x + metrics.padding,
                        y + metrics.padding, font);
            }
        }
        g.setClip(clipX, clipY, clipWidth, clipHeight);
    }

    /** Keep the selection inside the visible window, one screen at a time. */
    private void ensureVisible(int rowsVisible)
    {
        int row = selected / columns;
        if (row < top) { top = row; }
        if (row >= top + rowsVisible) { top = row - rowsVisible + 1; }
        if (top < 0) { top = 0; }
    }

    protected void keyPressed(int keyCode)
    {
        int action = 0;
        try { action = getGameAction(keyCode); }
        catch (Throwable ignored) { }

        if (action == Canvas.UP || keyCode == Canvas.KEY_NUM2)
        {
            selected -= columns;
        }
        else if (action == Canvas.DOWN || keyCode == Canvas.KEY_NUM8)
        {
            selected += columns;
        }
        else if (action == Canvas.LEFT || keyCode == Canvas.KEY_NUM4)
        {
            selected--;
        }
        else if (action == Canvas.RIGHT || keyCode == Canvas.KEY_NUM6)
        {
            selected++;
        }
        else if (action == Canvas.FIRE || keyCode == Canvas.KEY_NUM5)
        {
            if (selected >= 0 && selected < glyphs.length && listener != null)
            {
                listener.onEmojiPicked(glyphs[selected]);
            }
            return;
        }
        else
        {
            return;
        }

        if (selected < 0) { selected = 0; }
        if (selected >= glyphs.length) { selected = glyphs.length - 1; }
        repaint();
    }
}
