package tg.ui;

import java.io.IOException;
import java.io.InputStream;

import javax.microedition.lcdui.Font;
import javax.microedition.lcdui.Graphics;
import javax.microedition.lcdui.Image;

/**
 * Mixed device-font, sprite-emoji and semantic ASCII renderer.
 *
 * <h3>Everything here is measured incrementally, one token at a time</h3>
 * It used to be possible to ask only "how wide is this whole prefix", and word
 * wrapping did exactly that once per character: {@code ChatScreen.lineEnd}
 * measured {@code [start, next)} for every boundary, and each of those calls
 * re-tokenised the prefix from {@code start}. Wrapping a k-character line
 * therefore cost O(k&sup2;) tokenisations and O(k&sup2;) throwaway objects, and a
 * transcript went through it twice. {@link #fitEnd} replaces that with a single
 * accumulating walk.
 *
 * The accumulation assumes a token's width does not depend on what precedes it,
 * which is true of the bitmap fonts MIDP actually ships - there is no kerning to
 * make {@code stringWidth} differ from the sum of its parts. {@code ui/emoji-wrap}
 * pins that assumption rather than leaving it as folklore.
 */
public final class EmojiText
{
    public static final int GLYPH = 16;
    private static Image sheet;
    private static boolean loadAttempted;
    private static int loadFailures;
    private static long nextLoadAt;

    private EmojiText() { }

    public static int stringWidth(String text, Font font)
    {
        return substringWidth(text, 0, text == null ? 0 : text.length(), font);
    }

    public static int substringWidth(String text, int start, int length, Font font)
    {
        if (text == null || length <= 0) { return 0; }
        int end = Math.min(text.length(), start + length);
        int width = 0;
        int at = start;
        while (at < end)
        {
            int next = tokenEnd(text, at, end);
            width += tokenWidth(text, at, end, font);
            at = next;
        }
        return width;
    }

    /**
     * Where a run starting at {@code start} stops fitting in {@code width}.
     *
     * Everything in {@code [start, result)} fits; the token beginning at
     * {@code result} does not, unless {@code result == end}. The caller decides
     * what to do about a first token that is already too wide - this reports
     * {@code start} rather than forcing a zero-length line.
     */
    public static int fitEnd(String text, int start, int end, int width, Font font)
    {
        if (text == null) { return start; }
        if (end > text.length()) { end = text.length(); }
        int used = 0;
        int at = start;
        while (at < end)
        {
            int next = tokenEnd(text, at, end);
            used += tokenWidth(text, at, end, font);
            if (used > width) { return at; }
            at = next;
        }
        return end;
    }

    /**
     * Draw mixed text, batching each run of device-font characters into one
     * {@code drawString}.
     *
     * The run matters: a per-character draw is one native call per character,
     * and a transcript repaint is fifteen lines of them.
     *
     * The x advance comes from {@link #substringWidth} rather than from the
     * graphics context, so what is drawn advances by exactly what the wrap
     * budgeted for it. That also means an emoji whose sprite sheet failed to
     * load still occupies the space reserved for it instead of pulling the rest
     * of the line left.
     */
    public static void drawString(Graphics g, String text, int x, int y, Font font)
    {
        if (text == null) { return; }
        // Loading is lazy: a plain text message must not decode the sheet just
        // because the drawing code passed through here.
        Image sprites = null;
        int n = text.length();
        int at = 0;
        int runStart = -1;
        while (at < n)
        {
            int next = tokenEnd(text, at, n);
            int cp = codePoint(text, at);
            int cell = find(cp);
            if (cell >= 0)
            {
                if (sprites == null) { sprites = sprites(); }
                x = flush(g, text, runStart, at, x, y, font);
                runStart = -1;
                if (sprites != null)
                {
                    g.drawRegion(sprites, (cell & 15) * GLYPH, (cell >> 4) * GLYPH,
                            GLYPH, GLYPH, 0, x, y, Graphics.TOP | Graphics.LEFT);
                }
                x += GLYPH;
            }
            else if (isEmoji(cp))
            {
                x = flush(g, text, runStart, at, x, y, font);
                runStart = -1;
                String replacement = fallback(cp);
                g.setFont(font);
                g.drawString(replacement, x, y, Graphics.TOP | Graphics.LEFT);
                x += font.stringWidth(replacement);
            }
            else if (runStart < 0)
            {
                runStart = at;
            }
            at = next;
        }
        flush(g, text, runStart, n, x, y, font);
    }

    private static int flush(Graphics g, String text, int runStart, int runEnd,
                             int x, int y, Font font)
    {
        if (runStart < 0 || runEnd <= runStart) { return x; }
        g.setFont(font);
        g.drawString(text.substring(runStart, runEnd), x, y,
                Graphics.TOP | Graphics.LEFT);
        return x + substringWidth(text, runStart, runEnd - runStart, font);
    }

    /** Boundary after one renderer token; never splits a surrogate/ZWJ sequence. */
    public static int nextBoundary(String text, int at)
    {
        if (text == null || at >= text.length()) { return at; }
        return tokenEnd(text, at, text.length());
    }

    public static boolean hasSprite(int codePoint)
    {
        return find(codePoint) >= 0;
    }

    public static String fallbackFor(int codePoint)
    {
        return isEmoji(codePoint) ? fallback(codePoint) : null;
    }

    /**
     * Index one past one renderer token.
     *
     * Allocation-free on purpose. This is called once per character of every
     * line the client wraps, and it used to hand back an object each time.
     */
    private static int tokenEnd(String text, int at, int end)
    {
        int cp = codePoint(text, at);
        int next = baseEnd(text, at, end, cp);
        // A compact client renders a ZWJ sequence as its first useful base, so
        // the whole sequence is one token. Plain text keeps its own boundary -
        // a stray ZWJ between two letters must not glue them together.
        return (find(cp) >= 0 || isEmoji(cp)) ? zwjEnd(text, next, end) : next;
    }

    /** Width of the single token at {@code at}. */
    private static int tokenWidth(String text, int at, int end, Font font)
    {
        int cp = codePoint(text, at);
        if (find(cp) >= 0) { return GLYPH; }
        if (isEmoji(cp)) { return font.stringWidth(fallback(cp)); }
        int plainEnd = baseEnd(text, at, end, cp);
        // charWidth for the ordinary single-character case. Every other path
        // has to cut a String out of the line, and at one per character that is
        // the largest single source of garbage in laying a transcript out.
        return plainEnd == at + 1
                ? font.charWidth(text.charAt(at))
                : font.stringWidth(text.substring(at, plainEnd));
    }

    /** Base character plus its variation selector and skin-tone modifier. */
    private static int baseEnd(String text, int at, int end, int cp)
    {
        int next = at + (cp > 0xffff ? 2 : 1);
        if (next < end && text.charAt(next) == '\ufe0f') { next++; }
        if (next < end)
        {
            int modifier = codePoint(text, next);
            if (modifier >= 0x1f3fb && modifier <= 0x1f3ff)
            {
                next += modifier > 0xffff ? 2 : 1;
            }
        }
        return next;
    }

    private static int zwjEnd(String text, int next, int end)
    {
        int sequenceEnd = next;
        while (sequenceEnd < end && text.charAt(sequenceEnd) == '\u200d')
        {
            sequenceEnd++;
            if (sequenceEnd >= end) { break; }
            int joined = codePoint(text, sequenceEnd);
            sequenceEnd += joined > 0xffff ? 2 : 1;
            if (sequenceEnd < end && text.charAt(sequenceEnd) == '\ufe0f')
            {
                sequenceEnd++;
            }
        }
        return sequenceEnd;
    }

    private static int codePoint(String text, int at)
    {
        char high = text.charAt(at);
        if (high >= 0xd800 && high <= 0xdbff && at + 1 < text.length())
        {
            char low = text.charAt(at + 1);
            if (low >= 0xdc00 && low <= 0xdfff)
            {
                return 0x10000 + ((high - 0xd800) << 10) + (low - 0xdc00);
            }
        }
        return high;
    }

    private static int find(int cp)
    {
        int lo = 0;
        int hi = EmojiData.CODEPOINTS.length - 1;
        while (lo <= hi)
        {
            int mid = (lo + hi) >>> 1;
            int value = EmojiData.CODEPOINTS[mid];
            if (value == cp) { return EmojiData.CELLS[mid]; }
            if (value < cp) { lo = mid + 1; }
            else { hi = mid - 1; }
        }
        return -1;
    }

    private static boolean isEmoji(int cp)
    {
        return cp >= 0x1f000 || (cp >= 0x2600 && cp <= 0x27ff)
                || cp == 0x00a9 || cp == 0x00ae || cp == 0x203c
                || cp == 0x2049 || cp == 0x2122 || cp == 0x2139;
    }

    private static String fallback(int cp)
    {
        switch (cp)
        {
            case 0x1f600: case 0x1f603: case 0x1f604: return ":-)";
            case 0x1f622: case 0x1f625: case 0x1f62d: return ":'-(";
            case 0x1f620: case 0x1f621: return ">:(";
            case 0x1f609: return ";-)";
            case 0x1f618: return ":-*";
            case 0x2764: case 0x1f49b: case 0x1f49a:
            case 0x1f499: case 0x1f49c: return "<3";
            case 0x1f4f7: return "[camera]";
            case 0x1f525: return "[fire]";
            case 0x1f389: return "[party]";
            case 0x1f680: return "[rocket]";
            case 0x1f44d: return "[+]";
            case 0x1f44e: return "[-]";
            default:
                // Regional indicators are flags, modifiers are skin tones.
                // Neither has a sprite in a per-codepoint atlas, but showing
                // "[flag]"/"[tone]" is better than a generic "[emoji]".
                if (cp >= 0x1f1e6 && cp <= 0x1f1ff) return "[flag]";
                if (cp >= 0x1f3fb && cp <= 0x1f3ff) return "[tone]";
                if (cp == 0x1f3f3) return "[flag]";
                return "[emoji]";
        }
    }

    /**
     * Drop the decoded sprite sheet so it can be collected, and allow it to be
     * loaded again on the next paint.
     *
     * All four fields have to be reset. {@code loadAttempted} latches true only
     * after a successful load, a missing asset or three failed decodes; nulling
     * the sheet alone would leave emoji disabled for the rest of the session.
     * The short cooldown stops a shed/reload/shed cycle from decoding the sheet
     * again on the very next paint, which was measurable as handset heat.
     *
     * The sheet is the largest thing on the pressure ladder; on a 32bpp handset
     * the 256x176 PNG decodes to roughly 180 KB of live heap.
     */
    public static synchronized void release()
    {
        sheet = null;
        loadAttempted = false;
        loadFailures = 0;
        nextLoadAt = System.currentTimeMillis() + 1000L;
    }

    private static synchronized Image sprites()
    {
        if (sheet != null) { return sheet; }
        if (loadAttempted) { return null; }

        long now = System.currentTimeMillis();
        if (now < nextLoadAt) { return null; }
        // Even if every emoji on a line calls this, only one decode per second
        // may be attempted. Without this the three retries happened inside a
        // single repaint, on the display thread.
        nextLoadAt = now + 1000L;

        InputStream in = null;
        try
        {
            in = EmojiText.class.getResourceAsStream("/emoji.png");
            if (in == null)
            {
                // No asset in the JAR: retrying cannot help.
                loadAttempted = true;
                return null;
            }
            sheet = Image.createImage(in);
            loadAttempted = sheet != null;
        }
        catch (Throwable ignored)
        {
            // A transient decode/OOM failure used to latch forever and blank
            // every emoji for the rest of the session. Retry at most three
            // times, never more often than once a second.
            sheet = null;
            loadFailures++;
            if (loadFailures >= 3) { loadAttempted = true; }
        }
        finally
        {
            if (in != null) { try { in.close(); } catch (IOException ignored) { } }
        }
        return sheet;
    }
}
