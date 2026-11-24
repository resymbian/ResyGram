package tg.ui;

import javax.microedition.lcdui.Canvas;
import javax.microedition.lcdui.Command;
import javax.microedition.lcdui.CommandListener;
import javax.microedition.lcdui.Font;
import javax.microedition.lcdui.Graphics;

/**
 * A full-screen canvas that draws its own soft keys.
 *
 * The AMS bar is gone in full screen, so every screen that wants the extra rows
 * has to put the two labels back itself. This base class is the small piece of
 * that which is the same everywhere: the right key runs one command, the left
 * opens a drawn menu when there is more than one, and the menu is a scrolling
 * overlay rather than the handset's own list.
 *
 * It deliberately does not paint a header. Screens already draw their own, with
 * information the base class cannot know; {@link UiChrome#busy} is called by
 * those headers, so the background-work indicator appears on all of them.
 */
public abstract class SoftKeyCanvas extends Canvas
{
    /** Raw Series 60 soft-key codes; MIDP defines none for them. */
    protected static final int KEY_SOFT_LEFT = -6;
    protected static final int KEY_SOFT_RIGHT = -7;

    private Command rightCommand;
    private Command[] menu = new Command[0];
    private CommandListener dispatch;
    private boolean menuOpen;
    private int menuSelection;

    /**
     * Go full screen once the subclass is fully constructed.
     *
     * Doing it in the base constructor is tempting and wrong: a handset that
     * reports the size change synchronously would call the subclass's paint or
     * sizeChanged before its fonts and palette fields exist. showNotify is the
     * first moment both the object and the screen are real.
     */
    protected void showNotify()
    {
        try { setFullScreenMode(true); }
        catch (Throwable ignored)
        {
            // A handset that refuses full screen still has a working canvas;
            // the reserved footer simply buys no extra rows.
        }
    }

    /**
     * Install what the two keys do.
     *
     * @param right the command the right key runs, usually the screen's main
     *              action; null only for a screen with no action at all
     * @param items the commands listed under Options, in menu order
     */
    public void setSoftKeys(Command right, Command[] items)
    {
        rightCommand = right;
        menu = items == null ? new Command[0] : items;
        if (menuSelection >= menu.length) { menuSelection = 0; }
        repaint();
    }

    public void setCommandListener(CommandListener listener)
    {
        // See ChatScreen: in fullscreen, a native listener makes S60 consume the
        // soft keys before keyPressed can run the drawn Back action.
        dispatch = listener;
    }

    // Soft keys are drawn and dispatched here; nothing is registered with AMS.
    public void addCommand(Command command) {}

    public void removeCommand(Command command) {}

    /** The command listener this screen dispatches to, or null. */
    protected CommandListener listener()
    {
        return dispatch;
    }

    protected boolean hasSoftKeys()
    {
        return rightCommand != null || menu.length > 0;
    }

    protected Command softRight()
    {
        return rightCommand;
    }

    /** Reserve the bar before Metrics computes the body, never after. */
    protected void softKeyMetrics(Metrics metrics, Font font, Font metaFont)
    {
        int height = Math.max(font == null ? 1 : font.getHeight(),
                metaFont == null ? 1 : metaFont.getHeight());
        metrics.footerHeight = hasSoftKeys() ? height + 8 : 0;
    }

    /**
     * Handle the keys this bar owns.
     *
     * @return true when the key was consumed and the screen's own navigation
     *         must not also see it
     */
    protected boolean handleSoftKeys(int keyCode, int action)
    {
        if (keyCode == KEY_SOFT_LEFT)
        {
            if (menu.length > 0)
            {
                menuOpen = !menuOpen;
                menuSelection = 0;
                repaint();
            }
            return true;
        }
        if (keyCode == KEY_SOFT_RIGHT)
        {
            if (menuOpen)
            {
                menuOpen = false;
                repaint();
            }
            else
            {
                run(rightCommand);
            }
            return true;
        }
        if (!menuOpen) { return false; }

        if (action == Canvas.UP || keyCode == Canvas.KEY_NUM2)
        {
            if (menuSelection > 0) { menuSelection--; }
        }
        else if (action == Canvas.DOWN || keyCode == Canvas.KEY_NUM8)
        {
            if (menuSelection + 1 < menu.length) { menuSelection++; }
        }
        else if (action == Canvas.FIRE || keyCode == Canvas.KEY_NUM5)
        {
            menuOpen = false;
            run(menu[menuSelection]);
        }
        else if (action == Canvas.LEFT || action == Canvas.RIGHT
                || keyCode == KEY_SOFT_LEFT)
        {
            menuOpen = false;
        }
        repaint();
        return true;
    }

    /** The bar and, when it is open, the menu drawn over the body. */
    protected void paintSoftKeys(Graphics g, Theme theme, Metrics metrics,
                                 Font font, Font metaFont)
    {
        if (!hasSoftKeys()) { return; }
        String left = menuOpen ? "Close"
                : (menu.length > 0 ? "Options" : "");
        String right = rightCommand == null ? "" : rightCommand.getLabel();
        UiChrome.footer(g, theme, metrics, font, left, right);
        if (menuOpen) { paintMenu(g, theme, metrics, font, metaFont); }
    }

    private void paintMenu(Graphics g, Theme theme, Metrics metrics,
                           Font font, Font metaFont)
    {
        if (menu.length == 0) { return; }
        int rowHeight = Math.max(metrics.lineHeight,
                font == null ? 1 : font.getHeight());
        int visible = menu.length < 7 ? menu.length : 7;
        int height = visible * rowHeight + metrics.padding * 2;
        int top = metrics.height - metrics.footerHeight - height;
        if (top < metrics.bodyTop) { top = metrics.bodyTop; }
        g.setColor(theme.surface);
        g.fillRect(0, top, metrics.width, height);
        g.setColor(theme.border);
        g.drawRect(0, top, metrics.width - 1, height - 1);
        int first = 0;
        if (menuSelection >= visible) { first = menuSelection - visible + 1; }
        int y = top + metrics.padding;
        g.setFont(font);
        for (int row = 0; row < visible; row++)
        {
            int index = first + row;
            if (index >= menu.length) { break; }
            boolean selected = index == menuSelection;
            if (selected)
            {
                g.setColor(theme.selection);
                g.fillRect(metrics.padding, y,
                        metrics.width - metrics.padding * 2, rowHeight);
            }
            g.setColor(selected ? theme.selectionText : theme.text);
            g.drawString(menu[index].getLabel(), metrics.padding * 2,
                    y + (rowHeight - font.getHeight()) / 2,
                    Graphics.TOP | Graphics.LEFT);
            y += rowHeight;
        }
    }

    protected boolean isMenuOpen() { return menuOpen; }

    private void run(Command command)
    {
        if (command == null || dispatch == null) { return; }
        dispatch.commandAction(command, this);
    }
}
