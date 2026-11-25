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
 * Two sprite sheets back the renderer. The core sheet holds the fixed reaction
 * palette and the highest-frequency single code points; the extended sheet is
 * decoded only when a message actually uses one of its extra singles or a
 * whole-sequence sprite (flags, keycaps, ZWJ compositions, skin tones). A
 * sequence is measured and drawn as one 16x16 token, so no wrapping boundary
 * can split a family, a flag or a keycap.
 */
public final class EmojiText
{
    public static final int GLYPH = 16;
    private static Image sheet;
    private static Image extSheet;
    private static boolean loadAttempted;
    private static boolean extLoadAttempted;
    private static int loadFailures;
    private static int extLoadFailures;
    private static long nextLoadAt;
    private static long extNextLoadAt;

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
            if (next <= at) { next = at + 1; }
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
            if (next <= at) { next = at + 1; }
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
        // Loading is lazy: a plain text message must not decode either sheet
        // just because the drawing code passed through here.
        int n = text.length();
        int at = 0;
        int runStart = -1;
        while (at < n)
        {
            int next = tokenEnd(text, at, n);
            if (next <= at) { next = at + 1; }
            int cp = codePoint(text, at, n);
            int packed = matchSequence(text, at, n);
            int cell = packed >= 0 ? packed & 0xffff : find(cp);
            if (cell >= 0)
            {
                Image sprites = sheetFor(cell);
                x = flush(g, text, runStart, at, x, y, font);
                runStart = -1;
                if (sprites != null)
                {
                    int local = cell >= EmojiData.EXT_BASE
                            ? cell - EmojiData.EXT_BASE : cell;
                    g.drawRegion(sprites, (local & 15) * GLYPH, (local >> 4) * GLYPH,
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

    /** Boundary after one renderer token; never splits a surrogate or sequence. */
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
        if (at >= end) { return at; }
        int packed = matchSequence(text, at, end);
        if (packed >= 0) { return at + (packed >>> 16); }
        int cp = codePoint(text, at, end);
        int next = baseEnd(text, at, end, cp);
        // A compact client renders a ZWJ sequence as its first useful base, so
        // the whole sequence is one token. Plain text keeps its own boundary -
        // a stray ZWJ between two letters must not glue them together.
        int res = (find(cp) >= 0 || isEmoji(cp)) ? zwjEnd(text, next, end) : next;
        return res > at ? res : at + 1;
    }

    /** Width of the single token at {@code at}. */
    private static int tokenWidth(String text, int at, int end, Font font)
    {
        int packed = matchSequence(text, at, end);
        if (packed >= 0) { return GLYPH; }
        int cp = codePoint(text, at, end);
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

    /** Base character plus its variation selector, tone modifier and keycap. */
    private static int baseEnd(String text, int at, int end, int cp)
    {
        int next = at + (cp > 0xffff ? 2 : 1);
        if (next > end) { next = end; }
        if (next < end && text.charAt(next) == '\ufe0f') { next++; }
        if (next < end)
        {
            int modifier = codePoint(text, next, end);
            if (modifier >= 0x1f3fb && modifier <= 0x1f3ff)
            {
                next += 2;
                if (next > end) { next = end; }
            }
        }
        if (next < end && text.charAt(next) == '\u20e3') { next++; }
        return next;
    }

    private static int zwjEnd(String text, int next, int end)
    {
        int sequenceEnd = next;
        while (sequenceEnd < end && text.charAt(sequenceEnd) == '\u200d')
        {
            sequenceEnd++;
            if (sequenceEnd >= end) { break; }
            int joined = codePoint(text, sequenceEnd, end);
            if (joined < 0) { break; }
            sequenceEnd += joined > 0xffff ? 2 : 1;
            if (sequenceEnd > end) { sequenceEnd = end; break; }
            int modifier = codePoint(text, sequenceEnd, end);
            if (modifier >= 0x1f3fb && modifier <= 0x1f3ff)
            {
                sequenceEnd += 2;
                if (sequenceEnd > end) { sequenceEnd = end; }
            }
            if (sequenceEnd < end && text.charAt(sequenceEnd) == '\ufe0f')
            {
                sequenceEnd++;
            }
        }
        return sequenceEnd;
    }

    /**
     * Longest known whole-sequence match at {@code at}.
     *
     * The packed result is {@code (charLength << 16) | spriteCell}, or -1.
     * Keeping both values in one int avoids an allocation on every wrapped
     * character of every line.
     */
    private static int matchSequence(String text, int at, int end)
    {
        int cp = codePoint(text, at, end);
        int[] first = EmojiData.SEQ_FIRST;
        int lo = lowerBound(first, cp);
        if (lo >= first.length || first[lo] != cp) { return -1; }
        int bestLength = 0;
        int bestCell = -1;
        int i = lo;
        while (i < first.length && first[i] == cp)
        {
            int[] sequence = EmojiData.SEQ_CPS[i];
            int p = at;
            boolean matches = true;
            for (int j = 0; j < sequence.length; j++)
            {
                if (p >= end) { matches = false; break; }
                int got = codePoint(text, p, end);
                if (got != sequence[j]) { matches = false; break; }
                p += got > 0xffff ? 2 : 1;
                if (p > end) { p = end; }
            }
            if (matches && p - at > bestLength)
            {
                bestLength = p - at;
                bestCell = EmojiData.SEQ_CELLS[i];
            }
            i++;
        }
        return bestCell < 0 ? -1 : ((bestLength << 16) | (bestCell & 0xffff));
    }

    private static int lowerBound(int[] values, int key)
    {
        int lo = 0;
        int hi = values.length;
        while (lo < hi)
        {
            int mid = (lo + hi) >>> 1;
            if (values[mid] < key) { lo = mid + 1; }
            else { hi = mid; }
        }
        return lo;
    }

    private static int codePoint(String text, int at, int end)
    {
        if (at >= end || text == null) { return -1; }
        char high = text.charAt(at);
        if (high >= 0xd800 && high <= 0xdbff && at + 1 < end)
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
        int cell = search(EmojiData.CORE_CODEPOINTS, EmojiData.CORE_CELLS, cp);
        if (cell >= 0) { return cell; }
        return search(EmojiData.EXT_CODEPOINTS, EmojiData.EXT_CELLS, cp);
    }

    private static int search(int[] codePoints, short[] cells, int cp)
    {
        int lo = 0;
        int hi = codePoints.length - 1;
        while (lo <= hi)
        {
            int mid = (lo + hi) >>> 1;
            int value = codePoints[mid];
            if (value == cp) { return cells[mid]; }
            if (value < cp) { lo = mid + 1; }
            else { hi = mid - 1; }
        }
        return -1;
    }

    private static boolean isEmoji(int cp)
    {
        if (cp < 0) { return false; }
        if (cp >= 0x1f300 && cp <= 0x1faff) { return true; }
        if (cp >= 0x1f1e6 && cp <= 0x1f1ff) { return true; }
        if (cp >= 0x2600 && cp <= 0x27bf) { return true; }
        return cp == 0x00a9 || cp == 0x00ae || cp == 0x203c
                || cp == 0x2049 || cp == 0x2122 || cp == 0x2139
                || cp == 0x3030 || cp == 0x303d || cp == 0x3297
                || cp == 0x3299;
    }

    private static String fallback(int cp)
    {
        switch (cp)
        {
            case 0x1f600: case 0x1f603: case 0x1f604: case 0x1f60a: case 0x1f60c: return ":-)";
            case 0x1f601: return ":D";
            case 0x1f602: case 0x1f923: return ":'D";
            case 0x1f605: return ":')";
            case 0x1f606: return "XD";
            case 0x1f607: return "O:-)";
            case 0x1f608: case 0x1f47f: return ">:-)";
            case 0x1f609: return ";-)";
            case 0x1f60b: case 0x1f61b: return ":-P";
            case 0x1f61c: return ";-P";
            case 0x1f61d: return "X-P";
            case 0x1f60d: return ":*";
            case 0x1f60e: return "B-)";
            case 0x1f60f: return ":-}";
            case 0x1f610: case 0x1f636: return ":-|";
            case 0x1f611: return "-__-";
            case 0x1f612: case 0x1f615: case 0x1f928: return ":-/";
            case 0x1f613: case 0x1f622: case 0x1f625: case 0x1f62d: case 0x1f97a: return ":'-(";
            case 0x1f614: case 0x1f61e: return ":-(";
            case 0x1f616: case 0x1f623: return ">_<";
            case 0x1f617: case 0x1f618: case 0x1f619: case 0x1f61a: return ":-*";
            case 0x1f620: case 0x1f621: return ">:(";
            case 0x1f624: return ":-t";
            case 0x1f628: case 0x1f62f: case 0x1f632: return ":-O";
            case 0x1f629: case 0x1f62b: return ":'O";
            case 0x1f62a: case 0x1f634: case 0x1f4a4: return "zzz";
            case 0x1f62e: return ":-o";
            case 0x1f630: case 0x1f631: return ":-O";
            case 0x1f633: return ":-!";
            case 0x1f635: return "X_X";
            case 0x1f637: return "[mask]";
            case 0x1f910: return "[zipper]";
            case 0x1f911: return "$$$";
            case 0x1f912: return "[sick]";
            case 0x1f913: return "[nerd]";
            case 0x1f914: return ":-?";
            case 0x1f915: return "[injured]";
            case 0x1f917: return "[hug]";
            case 0x1f920: return "[cowboy]";
            case 0x1f921: return "[clown]";
            case 0x1f922: return "[nauseated]";
            case 0x1f924: return "[drool]";
            case 0x1f925: return "[liar]";
            case 0x1f929: return "*-*";
            case 0x1f92a: return "[crazy]";
            case 0x1f92b: return "[shh]";
            case 0x1f92c: return "[censored]";
            case 0x1f92d: return "[giggle]";
            case 0x1f92e: return "[vomit]";
            case 0x1f92f: return "[mindblown]";
            case 0x1f970: return "<3";
            case 0x1f971: return "[yawn]";
            case 0x1f973: return "[party]";
            case 0x1f974: return "[woozy]";
            case 0x1f975: return "[hot]";
            case 0x1f976: return "[cold]";
            case 0x2764: case 0x1f49b: case 0x1f49a:
            case 0x1f499: case 0x1f49c: case 0x1f49d:
            case 0x1f49e: case 0x1f49f: return "<3";
            case 0x1f494: return "</3";
            case 0x1f44b: return "[wave]";
            case 0x1f44c: return "[ok]";
            case 0x1f44d: return "[+]";
            case 0x1f44e: return "[-]";
            case 0x1f44f: return "[clap]";
            case 0x1f450: return "[open]";
            case 0x1f64f: return "[pray]";
            case 0x1f91d: return "[handshake]";
            case 0x1f4aa: return "[muscle]";
            case 0x1f446: return "[^]";
            case 0x1f447: return "[v]";
            case 0x1f448: return "[<]";
            case 0x1f449: return "[>]";
            case 0x270a: return "[fist]";
            case 0x270b: return "[hand]";
            case 0x270c: return "[v]";
            case 0x1f918: return "[rock]";
            case 0x1f4af: return "[100]";
            case 0x1f4a9: return "[poop]";
            case 0x1f4a1: return "[bulb]";
            case 0x1f4a2: return "[anger]";
            case 0x1f4a3: return "[bomb]";
            case 0x1f4a5: return "[boom]";
            case 0x1f4a6: return "[sweat]";
            case 0x2705: return "[OK]";
            case 0x274c: return "[X]";
            case 0x26a0: return "[!]";
            case 0x2b50: case 0x1f31f: return "[star]";
            case 0x1f381: return "[gift]";
            case 0x1f382: return "[cake]";
            case 0x1f389: return "[party]";
            case 0x1f4f1: return "[phone]";
            case 0x1f4f7: return "[camera]";
            case 0x1f525: return "[fire]";
            case 0x1f680: return "[rocket]";
            case 0x1f514: return "[bell]";
            case 0x1f515: return "[no bell]";
            case 0x1f50d: case 0x1f50e: return "[search]";
            case 0x1f512: return "[lock]";
            case 0x1f513: return "[unlock]";
            case 0x1f4b0: return "[money]";
            case 0x2615: return "[coffee]";
            case 0x1f37b: return "[beer]";
            case 0x1f355: return "[pizza]";
            case 0x1f697: return "[car]";
            case 0x2708: return "[plane]";
            default:
                if (cp >= 0x1f1e6 && cp <= 0x1f1ff) return "[flag]";
                if (cp >= 0x1f3fb && cp <= 0x1f3ff) return "[tone]";
                if (cp == 0x1f3f3) return "[flag]";
                return "[emoji]";
        }
    }

    /**
     * Drop both decoded sprite sheets so they can be collected, and allow them
     * to be loaded again on the next paint.
     *
     * Every field has to be reset. The attempted flags latch true only after a
     * successful load, a missing asset or three failed decodes; nulling a sheet
     * alone would leave that half of emoji disabled for the rest of the session.
     * The short cooldowns stop a shed/reload/shed cycle from decoding again on
     * the very next paint, which was measurable as handset heat.
     *
     * The sheets are the largest things on the pressure ladder: on a 32bpp
     * handset the 256x176 core is roughly 180 KB and the 256x224 extended
     * sheet another 230 KB of live heap.
     */
    public static synchronized void release()
    {
        sheet = null;
        extSheet = null;
        loadAttempted = false;
        extLoadAttempted = false;
        loadFailures = 0;
        extLoadFailures = 0;
        nextLoadAt = System.currentTimeMillis() + 1000L;
        extNextLoadAt = nextLoadAt;
    }

    private static Image sheetFor(int cell)
    {
        return cell >= EmojiData.EXT_BASE ? extSprites() : sprites();
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

    private static synchronized Image extSprites()
    {
        if (extSheet != null) { return extSheet; }
        if (extLoadAttempted) { return null; }

        long now = System.currentTimeMillis();
        if (now < extNextLoadAt) { return null; }
        extNextLoadAt = now + 1000L;

        InputStream in = null;
        try
        {
            in = EmojiText.class.getResourceAsStream("/emoji_ext.png");
            if (in == null)
            {
                extLoadAttempted = true;
                return null;
            }
            extSheet = Image.createImage(in);
            extLoadAttempted = extSheet != null;
        }
        catch (Throwable ignored)
        {
            extSheet = null;
            extLoadFailures++;
            if (extLoadFailures >= 3) { extLoadAttempted = true; }
        }
        finally
        {
            if (in != null) { try { in.close(); } catch (IOException ignored) { } }
        }
        return extSheet;
    }
}
