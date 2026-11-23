package tg.ui;

import javax.microedition.lcdui.Canvas;
import javax.microedition.lcdui.Font;
import javax.microedition.lcdui.Graphics;
import javax.microedition.lcdui.Image;

/**
 * What the user sees while the client builds itself.
 *
 * The first launch used to show nothing at all until every store had been
 * opened: the AMS paints only after {@code startApp} returns, so a second or two
 * of RMS reads and object construction was a black screen with a paused-looking
 * application behind it. This canvas is shown before any of that work starts,
 * and the work then runs behind it, so the handset has something to draw
 * immediately.
 *
 * The indicator is a sweeping progress bar (see {@link Spinner}) stepped by a
 * timer thread. That is 90 ms of repaint per frame at the size of a 320x240
 * panel, which is cheap enough that it cannot be mistaken for the reason a slow
 * handset is slow - and it never allocates a new object per frame, which matters
 * because the heap is exactly what is being measured right after.
 */
public final class SplashScreen extends Canvas
{
    private static final int DOTS = 8;
    private static final int FRAME_MS = Spinner.FRAME_MS;

    private final Theme theme;
    private final Font titleFont;
    private final Font statusFont;
    private Image icon;

    private volatile String status = "starting";
    private volatile boolean running;
    private int frame;

    public SplashScreen(Theme theme)
    {
        this.theme = theme == null ? Theme.byId(Theme.LIGHT) : theme;
        titleFont = Font.getFont(Font.FACE_PROPORTIONAL, Font.STYLE_BOLD,
                                 Font.SIZE_LARGE);
        statusFont = Font.getFont(Font.FACE_PROPORTIONAL, Font.STYLE_PLAIN,
                                  Font.SIZE_SMALL);
        try
        {
            icon = Image.createImage("/icon.png");
        }
        catch (Throwable ignored)
        {
            // A build without the resource still has to start; the splash then
            // shows the name alone.
        }
    }

    protected void showNotify()
    {
        try { setFullScreenMode(true); }
        catch (Throwable ignored) { }
    }

    /**
     * Say what is happening now.
     *
     * Called from the thread doing the work, hence the volatile field: the
     * timer thread is the only one that paints, and it is the only reader.
     */
    public void setStatus(String next)
    {
        status = next == null ? "" : next;
    }

    /** Start spinning. Safe to call twice. */
    public void start()
    {
        if (running) { return; }
        running = true;
        Thread t = new Thread(new Runnable()
        {
            public void run()
            {
                while (running)
                {
                    frame++;
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

    /**
     * Stop the timer.
     *
     * Without this the thread outlives the screen and keeps calling repaint()
     * on a Canvas nobody is showing, which on some handsets is what a "MIDlet is
     * still running" prompt after exit turns out to be.
     */
    public void stop()
    {
        running = false;
    }

    protected void paint(Graphics g)
    {
        int w = getWidth();
        int h = getHeight();
        g.setColor(theme.background);
        g.fillRect(0, 0, w, h);

        int centreY = h / 2 - 18;
        if (icon != null)
        {
            g.drawImage(icon, w / 2, centreY - 20,
                        Graphics.HCENTER | Graphics.VCENTER);
        }

        g.setFont(titleFont);
        g.setColor(theme.text);
        g.drawString("ResyGram", w / 2, centreY + 24,
                     Graphics.HCENTER | Graphics.TOP);

        int barWidth = Math.min(w - 24, 220);
        if (barWidth < 40) { barWidth = w - 8; }
        Spinner.draw(g, theme, (w - barWidth) / 2,
                centreY + 24 + titleFont.getHeight() + 14, barWidth, frame);

        g.setFont(statusFont);
        g.setColor(theme.secondaryText);
        g.drawString(status == null ? "" : status, w / 2,
                     centreY + 24 + titleFont.getHeight() + 34,
                     Graphics.HCENTER | Graphics.TOP);
    }

}
