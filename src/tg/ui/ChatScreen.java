package tg.ui;

import javax.microedition.lcdui.Canvas;
import javax.microedition.lcdui.Command;
import javax.microedition.lcdui.CommandListener;
import javax.microedition.lcdui.Font;
import javax.microedition.lcdui.Graphics;
import javax.microedition.lcdui.Image;

import tg.api.Media;
import tg.api.Message;
import tg.api.MessageEntity;
import tg.api.Peer;
import tg.api.Poll;
import tg.api.PollOption;
import tg.api.ReactionSummary;
import tg.api.ThreadInfo;
import tg.mem.MemoryBudget;

/**
 * One conversation: messages, newest at the bottom.
 *
 * Drawn rather than built from Form items. A Form allocates an Item per line
 * and reflows the whole thing on every change; on a 320x240 screen only about
 * fifteen lines are visible at a time, so drawing just those keeps both the
 * heap and the repaint cost proportional to the screen rather than to the
 * history.
 *
 * Word wrapping is done once when the message list is set, not on every paint.
 *
 * <h3>Only part of the transcript is laid out</h3>
 * The screen holds every retained {@link Message} - a reply needs to be able to
 * quote a message that is nowhere near the viewport - but wraps only the ones
 * within {@link MemoryBudget#layoutWindowScreens()} screens either side of it.
 * Everything laid out costs five parallel arrays keyed by display line plus a
 * String per line, so before this the cost was proportional to how far back the
 * user had ever read. Now it is proportional to the screen.
 *
 * The window is rebuilt when the viewport comes within one screen of an edge of
 * it, which leaves two screens of scrolling before the next rebuild can be
 * provoked. That gap is deliberate: it is what stops a reader moving back and
 * forth across a boundary from reflowing on every keypress.
 */
public class ChatScreen extends Canvas
{
    public interface ActivationListener
    {
        void onMessageActivated(int messageId);
    }

    /**
     * Told whenever the viewport moves, so somebody else can decide whether
     * more history is needed. Deliberately carries nothing: the listener asks
     * the screen what it wants to know, exactly as
     * {@link DialogListScreen.ViewportListener} does for avatars.
     */
    public interface ViewportListener
    {
        void onChatViewportChanged();
    }

    private final Font font;
    private final Font metaFont;
    private int lineHeight;
    /** Air above the text inside its line cell, so bubbles do not hug text. */
    private int textOffsetY;
    private final RichText richText;
    private final Metrics metrics = new Metrics();
    private Theme theme;
    private int lastLayoutWidth = -1;
    private int lastThumbnailHeight = -1;

    /**
     * Draw each message inside a bubble, Telegram-style.
     *
     * The transcript is still a list of wrapped lines; what changes is that the
     * text is wrapped to a narrower column and each run of lines belonging to one
     * message gets a rounded rectangle behind it, left for incoming and right for
     * outgoing. Keeping the line engine is deliberate: it is what makes the
     * scroll window, the thumbnail rows and the emoji atlas cheap, and a
     * message-by-message layout would have to rediscover all of it.
     */
    private boolean bubbles = true;

    /** Line offset tag for the "who, when" row above a message. */
    private static final int SENDER_ROW = 3000;

    // Raw key codes Series 60 delivers to a Canvas for the two soft keys. MIDP
    // has no constants for them - a Device one-shot made every handset its own
    // case, and these two are the only values the E51 sends.
    private static final int KEY_SOFT_LEFT = -6;
    private static final int KEY_SOFT_RIGHT = -7;

    /**
     * Fullscreen, with the two soft keys drawn here instead of by the AMS.
     *
     * The point is the rows: the native bar plus its padding is most of a text
     * line on a 240-pixel-tall screen. The cost is that the commands the AMS used
     * to list are now this screen's problem, hence {@link #setMenu} - the left
     * key opens a drawn menu over the transcript, the right key runs the
     * composing action.
     */
    private boolean fullscreen;
    private Command softRight;
    private Command[] menu = new Command[0];
    private CommandListener commandDispatch;
    private boolean menuOpen;
    private int menuSelection;

    /** Horizontal room a bubble takes from the transcript column. */
    private int bubbleInset()
    {
        int pad = metrics.padding + 1;
        return pad < 3 ? 3 : pad;
    }

    /** Width text is wrapped to: the column minus the bubble walls. */
    private int wrapWidth()
    {
        int width = metrics.contentWidth - bubbleInset() * 2;
        return width < 8 ? 8 : width;
    }

    private String title = "";
    private String[] lines = new String[0];
    private boolean[] outgoing = new boolean[0];
    private boolean[] meta = new boolean[0];
    private int[] lineMessageIds = new int[0];
    private int[] lineMessageOffsets = new int[0];
    /** UTF-16 offset in Message.text, or -1 for metadata/media rows. */
    private int[] lineTextStarts = new int[0];
    private Message[] currentMessages = new Message[0];
    private Peer peer;
    private ThreadInfo thread;
    private final int thumbnailCapacity;
    private final int[] thumbnailIds;
    private final Image[] thumbnails;
    private int thumbnailCount;

    private final ChatScrollState scroll = new ChatScrollState();
    private String status;
    private int newMessageCount;

    /**
     * The status line, for a driver that has to assert on what it says.
     *
     * The line is how every asynchronous history action reports itself - which
     * page is loading, which one was refused, whether a jump landed - and on a
     * MIDlet there is nowhere else for a test to read that from.
     */
    public String status() { return displayStatus(); }
    private boolean mediaPreviews = true;
    private int focusedMessageId;
    private int[] revealedSpoilerIds = new int[0];
    private int revealedSpoilerCount;
    private ActivationListener activationListener;
    private ViewportListener viewportListener;
    private final TouchGesture touch = new TouchGesture();
    private TouchContextListener touchContextListener;
    /** Uncommitted pixel part of a live pointer scroll. */
    private int touchPaintOffsetY;
    private boolean touchOnMessage;

    /**
     * Inclusive bounds of the laid-out slice of {@link #currentMessages}, which
     * is newest-first: {@code windowFirst} is the newest message wrapped and
     * {@code windowLast} the oldest. An empty transcript leaves the window
     * empty rather than degenerate, hence the -1.
     */
    private int windowFirst;
    private int windowLast = -1;

    /** Message the window is built around - the one at the top of the viewport. */
    private int anchorMessageId;

    private int layoutCount;

    private final int windowScreens;

    public ChatScreen()
    {
        this(Theme.byId(Theme.LIGHT));
    }

    public ChatScreen(Theme theme)
    {
        this(theme, MemoryBudget.thumbnailCacheEntries(),
                MemoryBudget.layoutWindowScreens());
    }

    /**
     * @param thumbnailCapacity decoded inline thumbnails to retain; floored at
     *                          two, because a single slot is evicted by the
     *                          next message that scrolls into view
     * @param windowScreens     screens of wrapped transcript kept either side
     *                          of the viewport; floored at one, which is the
     *                          least that can fill the screen at all
     */
    public ChatScreen(Theme theme, int thumbnailCapacity, int windowScreens)
    {
        richText = new RichText();
        font = richText.plainFont();
        metaFont = Font.getFont(Font.FACE_PROPORTIONAL, Font.STYLE_PLAIN, Font.SIZE_SMALL);
        int base = richText.lineHeight();
        // A bubble needs air above and below its text, and the line height is the
        // only place to put it: the text is drawn at the top of its cell, and the
        // bubble is as tall as its cells, so half the padding on each side is
        // what keeps glyphs off the rounded corners.
        textOffsetY = Math.max(1, base / 8);
        lineHeight = base + textOffsetY * 2;
        this.theme = theme == null ? Theme.byId(Theme.LIGHT) : theme;
        if (thumbnailCapacity < 2) { thumbnailCapacity = 2; }
        this.thumbnailCapacity = thumbnailCapacity;
        this.thumbnailIds = new int[thumbnailCapacity];
        this.thumbnails = new Image[thumbnailCapacity];
        this.windowScreens = windowScreens < 1 ? 1 : windowScreens;
    }

    /** Decoded thumbnails this screen will hold. */
    public int thumbnailCapacity() { return thumbnailCapacity; }

    /** Screens of wrapped transcript this screen keeps either side of the viewport. */
    public int windowScreens() { return windowScreens; }

    /**
     * How many times the transcript has been wrapped.
     *
     * Reported rather than inferred because the number is the only way to see
     * whether the window is doing its job: a reader moving back and forth
     * across an eviction boundary should reflow once, not once per keypress,
     * and nothing else on the screen shows the difference.
     */
    public int layoutCount() { return layoutCount; }

    public void setTheme(Theme value)
    {
        theme = value == null ? Theme.byId(Theme.LIGHT) : value;
        richText.themeChanged();
        repaint();
    }

    public void setTitle(String title)
    {
        this.title = title == null ? "" : title;
        repaint();
    }

    public void setPeer(Peer value) { peer = value; }
    public Peer peer() { return peer; }

    /**
     * The thread this transcript shows - a forum topic or a comment thread -
     * or null for the peer's own history. Held here because the navigation
     * stack can carry two ChatScreens, and {@code restoreScreen} rebinds the
     * open conversation from whichever it lands on.
     */
    public void setThread(ThreadInfo value) { thread = value; }
    public ThreadInfo thread() { return thread; }

    public Message[] messages() { return currentMessages; }

    /** Transient one-line status, e.g. "sending..." */
    public void setStatus(String status)
    {
        this.status = status;
        repaint();
    }

    /** New live messages retained below a reader who is viewing older text. */
    public void setNewMessageCount(int count)
    {
        newMessageCount = count > 0 ? count : 0;
        repaint();
    }

    public int newMessageCount() { return newMessageCount; }

    public void setMediaPreviews(boolean value)
    {
        if (mediaPreviews == value) { return; }
        mediaPreviews = value;
        layoutMessages(currentMessages, true);
        viewportChanged();
    }

    public void setActivationListener(ActivationListener value)
    {
        activationListener = value;
    }

    public void setViewportListener(ViewportListener value)
    {
        viewportListener = value;
    }

    public void setTouchContextListener(TouchContextListener value)
    {
        touchContextListener = value;
    }

    public int focusedMessageId() { return focusedMessageId; }

    public boolean hasConcealedSpoilers(int messageId)
    {
        Message message = messageById(messageId);
        return message != null && MessageEntity.hasSpoiler(message.entities)
                && !isSpoilerRevealed(messageId);
    }

    /** Reveal every spoiler in one message without changing its layout. */
    public void revealSpoilers(int messageId)
    {
        if (!hasConcealedSpoilers(messageId)) { return; }
        if (revealedSpoilerCount == revealedSpoilerIds.length)
        {
            int grown = revealedSpoilerIds.length == 0
                    ? 4 : revealedSpoilerIds.length * 2;
            int[] next = new int[grown];
            System.arraycopy(revealedSpoilerIds, 0, next, 0,
                    revealedSpoilerCount);
            revealedSpoilerIds = next;
        }
        revealedSpoilerIds[revealedSpoilerCount++] = messageId;
        repaint();
    }

    public boolean isSpoilerRevealed(int messageId)
    {
        for (int i = 0; i < revealedSpoilerCount; i++)
        {
            if (revealedSpoilerIds[i] == messageId) { return true; }
        }
        return false;
    }

    /** Display lines currently laid out. Bounded by the window, not the history. */
    public int transcriptLineCount() { return lines.length; }

    /** Retained messages, laid out or not. */
    public int messageCount() { return currentMessages.length; }

    /**
     * At the newest message, rather than merely at the bottom of the window.
     * New content only follows the viewport when both are true.
     */
    public boolean isAtEnd()
    {
        return windowFirst == 0 && scroll.top() >= maxTop();
    }

    /** Id of the message at the top of the viewport, or 0 when there is none. */
    public int topVisibleMessageId()
    {
        int top = scroll.top();
        for (int i = top; i < lineMessageIds.length; i++)
        {
            if (lineMessageIds[i] != 0) { return lineMessageIds[i]; }
        }
        for (int i = Math.min(top, lineMessageIds.length) - 1; i >= 0; i--)
        {
            if (lineMessageIds[i] != 0) { return lineMessageIds[i]; }
        }
        return 0;
    }

    /**
     * Retained messages older than the top of the viewport.
     *
     * This is what decides when another page is worth asking for, and it counts
     * messages rather than lines on purpose: lines beyond the window have not
     * been wrapped and their count is not known without doing the work.
     */
    public int messagesOlderThanViewport()
    {
        if (currentMessages.length == 0) { return 0; }
        int at = messageIndex(topVisibleMessageId());
        if (at < 0) { return currentMessages.length; }
        return currentMessages.length - 1 - at;
    }

    /**
     * Retained messages newer than the top of the viewport.
     *
     * The mirror of {@link #messagesOlderThanViewport}, and it exists for the
     * same reason: a reader who has gone far enough back has pushed the newest
     * messages out of the retained set, and coming forward again has to be able
     * to notice that before running out of transcript.
     */
    public int messagesNewerThanViewport()
    {
        if (currentMessages.length == 0) { return 0; }
        int at = messageIndex(topVisibleMessageId());
        return at < 0 ? currentMessages.length : at;
    }

    /**
     * Messages on or near the screen, newest first.
     *
     * The band is one screen either side of the viewport rather than exactly
     * what is visible, so that whatever is decoded for them - inline thumbnails
     * - is ready by the time it scrolls in.
     */
    public Message[] visibleMessages()
    {
        int visible = visibleLines();
        int from = scroll.top() - visible;
        int to = scroll.top() + visible * 2;
        if (from < 0) { from = 0; }
        if (to > lineMessageIds.length) { to = lineMessageIds.length; }

        Message[] found = new Message[to - from < 0 ? 0 : to - from];
        int count = 0;
        int previousId = 0;
        for (int i = from; i < to; i++)
        {
            int id = lineMessageIds[i];
            if (id == 0 || id == previousId) { continue; }
            previousId = id;
            int at = messageIndex(id);
            if (at >= 0) { found[count++] = currentMessages[at]; }
        }
        Message[] out = new Message[count];
        System.arraycopy(found, 0, out, 0, count);
        return out;
    }

    public void focusMessage(int messageId)
    {
        if (messageIndex(messageId) < 0) { return; }
        focusedMessageId = messageId;
        ensureLaidOut(messageId);
        for (int i = 0; i < lineMessageIds.length; i++)
        {
            if (lineMessageIds[i] == messageId)
            {
                int targetTop = i - visibleLines() / 2;
                scroll.userScroll(targetTop - scroll.top(), maxTop());
                settleFollowState();
                break;
            }
        }
        repaint();
        viewportChanged();
    }

    /**
     * Lay out a history.
     *
     * @param messages newest first, as Telegram returns them; displayed oldest
     *                 first so the conversation reads downwards
     */
    public void setMessages(Message[] messages)
    {
        layoutMessages(messages, true);
        viewportChanged();
    }

    /** Clear or replace the transcript when navigating to a different peer. */
    public void resetMessages(Message[] messages)
    {
        clearThumbnails();
        revealedSpoilerIds = new int[0];
        revealedSpoilerCount = 0;
        anchorMessageId = 0;
        layoutMessages(messages, false);
        viewportChanged();
    }

    private void layoutMessages(Message[] messages, boolean preserveScroll)
    {
        if (messages == null) { messages = new Message[0]; }
        layoutCount++;
        currentMessages = messages;
        int[] oldMessageIds = lineMessageIds;
        int[] oldMessageOffsets = lineMessageOffsets;
        updateMetrics();
        int width = wrapWidth();
        lastLayoutWidth = width;
        lastThumbnailHeight = metrics.thumbnailHeight;
        chooseWindow(messages, width);

        // Two passes: count, then fill. Growing a Vector of Strings and copying
        // it out would double the peak allocation for no benefit. Both are now
        // bounded to the window, so what used to be the whole transcript twice
        // is a few screens twice.
        int count = 0;
        for (int pass = 0; pass < 2; pass++)
        {
            int previousDay = 0;
            if (pass == 1)
            {
                lines = new String[count];
                outgoing = new boolean[count];
                meta = new boolean[count];
                lineMessageIds = new int[count];
                lineMessageOffsets = new int[count];
                lineTextStarts = new int[count];
                for (int line = 0; line < count; line++)
                {
                    lineTextStarts[line] = -1;
                }
                count = 0;
            }
            for (int i = windowLast; i >= windowFirst; i--)
            {
                Message m = messages[i];
                if (m == null)
                {
                    continue;
                }
                int day = DateTime.dayKey(m.date);
                if (day != 0 && day != previousDay)
                {
                    if (pass == 1)
                    {
                        lines[count] = "-- " + DateTime.date(m.date) + " --";
                        outgoing[count] = false;
                        meta[count] = true;
                        lineMessageIds[count] = 0;
                        lineMessageOffsets[count] = 0;
                    }
                    count++;
                    previousDay = day;
                }
                String who = senderLine(m);
                if (who.length() > 0)
                {
                    if (pass == 1)
                    {
                        lines[count] = who;
                        outgoing[count] = m.outgoing;
                        meta[count] = true;
                        lineMessageIds[count] = m.id;
                        // Marked so the bubble pass knows this row is the name
                        // above the message, not the first line of it.
                        lineMessageOffsets[count] = SENDER_ROW;
                    }
                    count++;
                }
                String reply = replyLine(m, messages,
                        thread == null ? 0 : thread.rootId);
                if (reply.length() > 0)
                {
                    int replyStart = count;
                    count = wrap(reply, width, pass, count,
                            m.outgoing, m.id, 150);
                    if (pass == 1)
                    {
                        for (int line = replyStart; line < count; line++)
                        {
                            meta[line] = true;
                        }
                    }
                }
                if (m.forwarded != null && m.forwarded.label.length() > 0)
                {
                    int forwardedStart = count;
                    count = wrap(m.forwarded.label, width, pass, count,
                            m.outgoing, m.id, 100);
                    if (pass == 1)
                    {
                        for (int line = forwardedStart; line < count; line++)
                        {
                            meta[line] = true;
                        }
                    }
                }
                if (m.editDate > 0)
                {
                    count = wrap("edited", width, pass, count, m.outgoing,
                            m.id, 195);
                    if (pass == 1) { meta[count - 1] = true; }
                }
                count = wrapRich(m.text, width, pass, count, m.outgoing,
                        m.id, 200, m.entities);
                if (m.media != null)
                {
                    if (m.media.kind == Media.POLL && m.media.poll != null)
                    {
                        count = layoutPoll(m.media.poll, width, pass, count,
                                m.outgoing, m.id);
                    }
                    else
                    {
                        count = wrap(m.media.label, width, pass, count,
                                m.outgoing, m.id, 1000);
                        int rows = thumbnailRows(m);
                        for (int row = 0; row < rows; row++)
                        {
                            if (pass == 1)
                            {
                                lines[count] = "";
                                outgoing[count] = m.outgoing;
                                meta[count] = false;
                                lineMessageIds[count] = m.id;
                                lineMessageOffsets[count] = 1100 + row;
                            }
                            count++;
                        }
                    }
                }
                String reactionLine = reactionLine(m.reactions);
                if (reactionLine.length() > 0)
                {
                    count = wrap(reactionLine, width, pass, count,
                            m.outgoing, m.id, 2000);
                }
                if (m.hasComments && m.repliesCount > 0)
                {
                    // The entry into a channel post's discussion thread; the
                    // count is display-capped the way the unread badge is.
                    count = wrap((m.repliesCount > 999 ? "999+"
                            : String.valueOf(m.repliesCount)) + " comments",
                            width, pass, count, m.outgoing, m.id, 2100);
                    if (pass == 1) { meta[count - 1] = true; }
                }
            }
        }
        if (messageIndex(focusedMessageId) < 0 && messages.length > 0)
        {
            for (int i = 0; i < messages.length; i++)
            {
                if (messages[i] != null)
                {
                    focusedMessageId = messages[i].id;
                    break;
                }
            }
        }
        if (preserveScroll)
        {
            scroll.replace(oldMessageIds, oldMessageOffsets,
                    lineMessageIds, lineMessageOffsets, maxTop());
        }
        else
        {
            scroll.reset(maxTop());
        }
        settleFollowState();
        anchorMessageId = topVisibleMessageId();
        repaint();
    }

    /**
     * Re-decide whether new content should pull the viewport with it.
     *
     * "At the end" has to mean the end of the conversation rather than the
     * bottom of the window, or paging down in the middle of the history would
     * arm the follow flag and the next rebuild would yank the reader to the
     * newest message. When the window does reach the newest, asking
     * {@code userScroll} for a zero move re-evaluates the flag honestly instead
     * of latching it off.
     */
    private void settleFollowState()
    {
        if (windowFirst > 0) { scroll.stopFollowingEnd(); }
        else { scroll.userScroll(0, maxTop()); }
    }

    /**
     * Decide which slice of the transcript to wrap.
     *
     * Walks outward from the anchor, wrapping each message only far enough to
     * count its lines, and stops on each side once a screen budget is covered.
     * The cost is therefore set by the window, never by how much history is
     * retained - which is the entire point.
     */
    private void chooseWindow(Message[] messages, int width)
    {
        if (messages.length == 0)
        {
            windowFirst = 0;
            windowLast = -1;
            return;
        }
        int anchor = anchorIndex(messages);
        int budget = windowScreens * Math.max(1, visibleLines());

        int first = anchor;
        for (int newer = 0; first > 0 && newer < budget; )
        {
            first--;
            newer += messageLines(messages[first], width);
        }
        int last = anchor;
        for (int older = 0; last + 1 < messages.length && older < budget; )
        {
            last++;
            older += messageLines(messages[last], width);
        }
        windowFirst = first;
        windowLast = last;
    }

    /**
     * Index of the message the window should be built around.
     *
     * Falling back to the newest when the anchor is gone is a safety net rather
     * than a normal path: the retained set is trimmed around the same anchor, so
     * the message being read is the one thing eviction is not allowed to take.
     */
    private int anchorIndex(Message[] messages)
    {
        if (scroll.followsEnd() || anchorMessageId == 0) { return 0; }
        for (int i = 0; i < messages.length; i++)
        {
            if (messages[i] != null && messages[i].id == anchorMessageId)
            {
                return i;
            }
        }
        return 0;
    }

    /**
     * Display lines one message occupies, excluding any date separator.
     *
     * Used only to size the window; the array is sized by the exact counting
     * pass, so a separator this does not know about cannot make the layout
     * disagree with itself.
     */
    private int messageLines(Message m, int width)
    {
        if (m == null) { return 0; }
        int count = 0;
        if (senderLine(m).length() > 0) { count++; }
        String reply = replyLine(m, currentMessages,
                thread == null ? 0 : thread.rootId);
        if (reply.length() > 0) { count += countWrapped(reply, width); }
        if (m.forwarded != null && m.forwarded.label.length() > 0)
        {
            count += countWrapped(m.forwarded.label, width);
        }
        if (m.text != null && m.text.length() > 0)
        {
            count += countWrappedRich(m.text, width, m.entities);
        }
        if (m.media != null)
        {
            if (m.media.kind == Media.POLL && m.media.poll != null)
            {
                count += pollLineCount(m.media.poll, width);
            }
            else
            {
                if (m.media.label != null && m.media.label.length() > 0)
                {
                    count += countWrapped(m.media.label, width);
                }
                count += thumbnailRows(m);
            }
        }
        String reactions = reactionLine(m.reactions);
        if (reactions.length() > 0) { count += countWrapped(reactions, width); }
        return count == 0 ? 1 : count;
    }

    private int thumbnailRows(Message m)
    {
        if (!mediaPreviews || m.media == null || m.media.kind != Media.PHOTO
                || m.media.photo == null || m.media.photo.stripped() == null)
        {
            return 0;
        }
        return (metrics.thumbnailHeight + lineHeight - 1) / lineHeight;
    }

    private String senderLine(Message m)
    {
        String who = m.senderName();
        if (m.outgoing && m.read) { who += " [read]"; }
        String time = DateTime.time(m.date);
        if (time.length() > 0)
        {
            who = who.length() == 0 ? time : (who + "  " + time);
        }
        return who;
    }

    /** Append a message we just sent, before the server echoes it back. */
    public void appendLocal(String text)
    {
        // The appended lines belong after the newest message, so the window has
        // to be there. Somebody who just sent something wants to see it.
        if (windowFirst > 0) { jumpToNewest(); }
        updateMetrics();
        int width = wrapWidth();

        int extra = 1 + countWrapped(text, width);
        String[] newLines = new String[lines.length + extra];
        boolean[] newOut = new boolean[newLines.length];
        boolean[] newMeta = new boolean[newLines.length];
        int[] newMessageIds = new int[newLines.length];
        int[] newMessageOffsets = new int[newLines.length];
        int[] newTextStarts = new int[newLines.length];
        for (int i = 0; i < newTextStarts.length; i++) { newTextStarts[i] = -1; }
        System.arraycopy(lines, 0, newLines, 0, lines.length);
        System.arraycopy(outgoing, 0, newOut, 0, outgoing.length);
        System.arraycopy(meta, 0, newMeta, 0, meta.length);
        System.arraycopy(lineMessageIds, 0, newMessageIds, 0,
                lineMessageIds.length);
        System.arraycopy(lineMessageOffsets, 0, newMessageOffsets, 0,
                lineMessageOffsets.length);
        System.arraycopy(lineTextStarts, 0, newTextStarts, 0,
                lineTextStarts.length);

        int at = lines.length;
        newLines[at] = "You";
        newOut[at] = true;
        newMeta[at] = true;
        newMessageIds[at] = 0;
        newMessageOffsets[at] = 0;
        at++;

        lines = newLines;
        outgoing = newOut;
        meta = newMeta;
        lineMessageIds = newMessageIds;
        lineMessageOffsets = newMessageOffsets;
        lineTextStarts = newTextStarts;
        wrapInto(text, width, at, true, 0, 1);
        scroll.appended(maxTop());
        repaint();
    }

    public void scrollToEnd()
    {
        jumpToNewest();
        viewportChanged();
    }

    /** Put the window back on the newest message and follow it again. */
    private void jumpToNewest()
    {
        anchorMessageId = 0;
        scroll.reset(maxTop());
        layoutMessages(currentMessages, true);
        repaint();
    }

    protected void paint(Graphics g)
    {
        updateMetrics();
        if (lastLayoutWidth != wrapWidth()
                || lastThumbnailHeight != metrics.thumbnailHeight)
        {
            layoutMessages(currentMessages, true);
        }
        String headerStatus = UiChrome.clip(displayStatus(), metaFont,
                metrics.width / 2);
        UiChrome.background(g, theme, metrics);
        UiChrome.header(g, theme, metrics, font, title, headerStatus);
        int visible = visibleLines();

        int first = scroll.top();
        int y = metrics.bodyTop + touchPaintOffsetY;
        if (touchPaintOffsetY > 0 && first > 0)
        {
            first--;
            y -= lineHeight;
        }
        int paintedMessageId = 0;
        Message paintedMessage = null;
        int bodyClipX = g.getClipX();
        int bodyClipY = g.getClipY();
        int bodyClipWidth = g.getClipWidth();
        int bodyClipHeight = g.getClipHeight();
        g.clipRect(0, metrics.bodyTop, metrics.width, metrics.bodyHeight);
        if (bubbles)
        {
            paintBubbles(g, first, visible);
        }
        for (int i = 0; i < visible + 2; i++)
        {
            int idx = first + i;
            if (idx >= lines.length)
            {
                break;
            }
            if (y >= metrics.bodyBottom) { break; }
            if (meta[idx])
            {
                g.setFont(metaFont);
                g.setColor(theme.secondaryText);
            }
            if (lineMessageIds[idx] != 0
                    && lineMessageIds[idx] == focusedMessageId && !bubbles)
            {
                g.setColor(theme.selection);
                g.fillRect(0, y, metrics.width, lineHeight);
                g.setColor(meta[idx] ? theme.secondaryText
                        : (outgoing[idx] ? theme.outgoingText : theme.text));
            }
            else
            {
                g.setFont(font);
                g.setColor(outgoing[idx] ? theme.outgoingText : theme.text);
            }
            String s = lines[idx];
            int messageOffset = lineMessageOffsets[idx];
            if (messageOffset >= 1100 && messageOffset < 1200)
            {
                Image thumbnail = thumbnail(lineMessageIds[idx]);
                if (thumbnail != null)
                {
                    int clipX = g.getClipX();
                    int clipY = g.getClipY();
                    int clipWidth = g.getClipWidth();
                    int clipHeight = g.getClipHeight();
                    g.clipRect(0, y, metrics.width, lineHeight);
                    int thumbX = bubbles
                            ? bubbleLeft(lineMessageIds[idx], idx) + bubbleInset()
                            : metrics.padding;
                    g.drawImage(thumbnail, thumbX,
                            y - (messageOffset - 1100) * lineHeight
                                    + textOffsetY,
                            Graphics.TOP | Graphics.LEFT);
                    g.setClip(clipX, clipY, clipWidth, clipHeight);
                }
            }
            else if (s != null && lineTextStarts[idx] >= 0)
            {
                int id = lineMessageIds[idx];
                if (paintedMessage == null || paintedMessageId != id)
                {
                    paintedMessage = messageById(id);
                    paintedMessageId = id;
                }
                if (paintedMessage != null)
                {
                    boolean focused = id == focusedMessageId;
                    boolean quoted = richText.quotedAt(paintedMessage.entities,
                            lineTextStarts[idx]);
                    int x = bubbles
                            ? bubbleLeft(id, idx) + bubbleInset()
                            : metrics.padding;
                    int textTop = y + textOffsetY;
                    if (quoted)
                    {
                        g.setColor(focused ? theme.selectionText : theme.accent);
                        g.fillRect(x, textTop, 2, lineHeight - textOffsetY * 2);
                        x += 6;
                    }
                    int textColor = outgoing[idx]
                            ? theme.outgoingText : theme.text;
                    richText.drawLine(g, paintedMessage.text,
                            lineTextStarts[idx],
                            lineTextStarts[idx] + s.length(), x, textTop,
                            paintedMessage.entities, isSpoilerRevealed(id),
                            focused, textColor,
                            focused ? theme.selectionText : theme.secondaryText);
                }
            }
            else if (s != null)
            {
                EmojiText.drawString(g, s, metrics.padding, y + textOffsetY,
                        meta[idx] ? metaFont : font);
            }
            y += lineHeight;
        }
        g.setClip(bodyClipX, bodyClipY, bodyClipWidth, bodyClipHeight);
        if (fullscreen)
        {
            UiChrome.footer(g, theme, metrics, font, "Options",
                    softRight == null ? "" : softRight.getLabel());
            if (menuOpen) { paintMenu(g); }
        }
    }

    /** The left key's menu, drawn over the transcript rather than by the AMS. */
    private void paintMenu(Graphics g)
    {
        if (menu.length == 0) { return; }
        int rowHeight = lineHeight;
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
                g.fillRect(metrics.padding, y, metrics.width - metrics.padding * 2,
                        rowHeight);
            }
            g.setColor(selected ? theme.selectionText : theme.text);
            g.drawString(menu[index].getLabel(), metrics.padding * 2,
                    y + textOffsetY, Graphics.TOP | Graphics.LEFT);
            y += rowHeight;
        }
    }

    /**
     * Paint the bubble behind every message group in the visible window.
     *
     * Lines of one message are contiguous by construction, so a run of equal,
     * non-zero ids is a message. The run's own lines give its height and its
     * widest line gives its width - which is what makes a two-word answer a small
     * bubble instead of a full-width band.
     */
    private void paintBubbles(Graphics g, int first, int visible)
    {
        int i = 0;
        while (i <= visible)
        {
            int idx = first + i;
            if (idx >= lines.length) { break; }
            int id = lineMessageIds[idx];
            if (id == 0 || lineMessageOffsets[idx] == SENDER_ROW)
            {
                i++;
                continue;
            }
            int end = bubbleEnd(idx, id);
            // Only a run carrying text or a picture is a bubble. The sender name
            // above a group is its own line and deliberately stays outside.
            boolean drawable = false;
            for (int line = idx; line <= end; line++)
            {
                if (lineTextStarts[line] >= 0 || isPictureRow(line))
                {
                    drawable = true;
                    break;
                }
            }
            if (drawable)
            {
                int width = bubbleWidth(idx, end);
                int x = outgoing[idx]
                        ? metrics.width - metrics.padding - width
                        : metrics.padding;
                int top = metrics.bodyTop + touchPaintOffsetY
                        + (idx - first) * lineHeight;
                int gap = Math.max(1, textOffsetY / 2);
                int height = (end - idx + 1) * lineHeight - gap * 2;
                g.setColor(outgoing[idx] ? theme.selection : theme.surface);
                g.fillRoundRect(x, top + gap, width, height, 8, 8);
                if (id == focusedMessageId)
                {
                    g.setColor(theme.accent);
                    g.drawRoundRect(x, top + gap, width, height, 8, 8);
                }
            }
            i = end - first + 1;
        }
    }

    /** Last line of the message's bubble: the run minus any trailing name row. */
    private int bubbleEnd(int idx, int id)
    {
        int end = idx;
        while (end + 1 < lines.length && lineMessageIds[end + 1] == id)
        {
            end++;
        }
        // The name row and the reaction/comment rows are annotations under the
        // message, not part of its balloon: inside it, they would either be
        // clipped or leave an empty band below the text.
        while (end > idx
                && (lineMessageOffsets[end] == SENDER_ROW
                    || lineMessageOffsets[end] >= 2000))
        {
            end--;
        }
        return end;
    }

    /** A row of thumbnail pixels, as opposed to the media label above them. */
    private boolean isPictureRow(int line)
    {
        return lineMessageOffsets[line] >= 1100
                && lineMessageOffsets[line] < 1200;
    }

    /**
     * Left edge of the bubble the line at {@code idx} belongs to.
     *
     * Recomputed per line instead of cached: a cache would be another array to
     * grow, index and keep in step with the layout, and the scan is bounded by
     * the lines of one message.
     */
    private int bubbleLeft(int id, int idx)
    {
        int start = idx;
        while (start > 0 && lineMessageIds[start - 1] == id
                && lineMessageOffsets[start - 1] != SENDER_ROW)
        {
            start--;
        }
        int width = bubbleWidth(start, bubbleEnd(idx, id));
        return outgoing[idx] ? metrics.width - metrics.padding - width
                             : metrics.padding;
    }

    private int bubbleWidth(int start, int end)
    {
        int inset = bubbleInset() * 2;
        int widest = 0;
        for (int line = start; line <= end; line++)
        {
            if (isPictureRow(line))
            {
                // A picture is as wide as the column inside the bubble.
                return Math.min(metrics.contentWidth, wrapWidth() + inset);
            }
            String text = lines[line];
            if (lineTextStarts[line] < 0 || text == null) { continue; }
            int width = EmojiText.substringWidth(text, 0, text.length(), font);
            if (width > widest) { widest = width; }
        }
        int width = widest + inset;
        int max = wrapWidth() + inset;
        if (width > max) { width = max; }
        return width < inset ? inset : width;
    }

    private String displayStatus()
    {
        String shown = status == null ? "" : status;
        if (newMessageCount > 0)
        {
            shown += (shown.length() == 0 ? "" : " ") + "+"
                    + newMessageCount + " new";
        }
        return shown;
    }

    protected void sizeChanged(int width, int height)
    {
        metrics.update(width, height, font, metaFont);
        if (lastLayoutWidth != wrapWidth()
                || lastThumbnailHeight != metrics.thumbnailHeight)
        {
            layoutMessages(currentMessages, true);
        }
        viewportChanged();
    }

    protected void keyPressed(int keyCode)
    {
        int action = 0;
        try { action = getGameAction(keyCode); }
        catch (Throwable ignored) { }

        if (fullscreen && handleSoftKeys(keyCode, action)) { return; }

        if (action == Canvas.UP || keyCode == Canvas.KEY_NUM2)
        {
            focus(-1);
        }
        else if (action == Canvas.DOWN || keyCode == Canvas.KEY_NUM8)
        {
            focus(1);
        }
        else if (action == Canvas.LEFT || keyCode == Canvas.KEY_NUM4)
        {
            scroll(-visibleLines());
        }
        else if (action == Canvas.RIGHT || keyCode == Canvas.KEY_NUM6)
        {
            scroll(visibleLines());
        }
        else if (action == Canvas.FIRE || keyCode == Canvas.KEY_NUM5)
        {
            activateFocused();
        }
    }

    /**
     * The fullscreen screen's own keys.
     *
     * @return true when the key was consumed here
     */
    private boolean handleSoftKeys(int keyCode, int action)
    {
        if (keyCode == KEY_SOFT_LEFT)
        {
            menuOpen = menu.length > 0 && !menuOpen;
            repaint();
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
                runCommand(softRight);
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
            runCommand(menu[menuSelection]);
        }
        else if (action == Canvas.LEFT || action == Canvas.RIGHT
                || keyCode == KEY_SOFT_LEFT)
        {
            menuOpen = false;
        }
        repaint();
        return true;
    }

    private void runCommand(Command command)
    {
        if (command == null || commandDispatch == null) { return; }
        commandDispatch.commandAction(command, this);
    }

    protected void keyRepeated(int keyCode)
    {
        keyPressed(keyCode);
    }

    protected void pointerPressed(int x, int y)
    {
        touch.press(x, y);
        touchPaintOffsetY = 0;
        touchOnMessage = focusAt(y);
    }

    protected void pointerDragged(int x, int y)
    {
        if (!touch.drag(x, y)) { return; }
        int delta = touch.deltaY();
        if (delta == 0) { return; }
        touchPaintOffsetY += delta;
        int unit = Math.max(1, lineHeight);
        int steps = touchPaintOffsetY / unit;
        if (steps != 0)
        {
            touchPaintOffsetY -= steps * unit;
            scrollTouch(-steps);
        }
        if ((scroll.top() == 0 && touchPaintOffsetY > 0)
                || (scroll.top() == maxTop() && touchPaintOffsetY < 0))
        {
            touchPaintOffsetY = 0;
        }
        touch.repaintFrame(this, 0, metrics.bodyTop,
                metrics.width, metrics.bodyHeight);
    }

    protected void pointerReleased(int x, int y)
    {
        if (touch.isPressed()) { pointerDragged(x, y); }
        boolean dragged = touch.isDragging();
        int result = touch.release(x, y);
        if (dragged)
        {
            settleTouchScroll();
            return;
        }
        if (!touchOnMessage || result == TouchGesture.NONE) { return; }
        if (result == TouchGesture.LONG_PRESS)
        {
            TouchContextListener listener = touchContextListener;
            if (listener != null) { listener.onTouchContextRequested(this); }
        }
        else { activateFocused(); }
    }

    private void settleTouchScroll()
    {
        int unit = Math.max(1, lineHeight);
        if (touchPaintOffsetY <= -unit / 2) { scrollTouch(1); }
        else if (touchPaintOffsetY >= unit / 2) { scrollTouch(-1); }
        touchPaintOffsetY = 0;
        repaint();
        viewportChanged();
    }

    /** Invoke the same focused-message action as FIRE and a short tap. */
    public void activateFocused()
    {
        ActivationListener listener = activationListener;
        if (listener != null && focusedMessageId != 0)
        {
            listener.onMessageActivated(focusedMessageId);
        }
    }

    private boolean focusAt(int y)
    {
        updateMetrics();
        if (y < metrics.bodyTop || y >= metrics.bodyBottom) { return false; }
        int line = scroll.top() + (y - metrics.bodyTop) / lineHeight;
        if (line < 0 || line >= lineMessageIds.length) { return false; }
        int id = lineMessageIds[line];
        if (id == 0) { return false; }
        focusedMessageId = id;
        repaint();
        viewportChanged();
        return true;
    }

    // ------------------------------------------------------------ internal

    private void scroll(int delta)
    {
        scrollTouch(delta);
        repaint();
        viewportChanged();
    }

    /** Pointer path: update all state, defer paint and heavy listeners. */
    private void scrollTouch(int delta)
    {
        scroll.userScroll(delta, maxTop());
        settleFollowState();
        reflowIfNearWindowEdge();
    }

    /**
     * Rebuild the window when the viewport gets within a screen of an edge of
     * it and there is retained history on the far side.
     *
     * The margin is one screen and the window is {@link #windowScreens} screens
     * deep either side, so a rebuild leaves the viewport at least two screens
     * from both edges. Moving back and forth across an eviction boundary
     * therefore cannot provoke a second rebuild without real scrolling in
     * between - which is what keeps eviction from turning into a fetch storm.
     */
    private boolean reflowIfNearWindowEdge()
    {
        if (currentMessages.length == 0) { return false; }
        int margin = visibleLines();
        boolean nearOldest = scroll.top() <= margin
                && windowLast < currentMessages.length - 1;
        boolean nearNewest = scroll.top() >= maxTop() - margin && windowFirst > 0;
        if (!nearOldest && !nearNewest) { return false; }

        int anchor = topVisibleMessageId();
        if (anchor == 0) { return false; }
        anchorMessageId = anchor;
        layoutMessages(currentMessages, true);
        return true;
    }

    /** Bring one message into the laid-out window if it is not already there. */
    private void ensureLaidOut(int messageId)
    {
        if (messageId == 0 || lineIndex(messageId) >= 0) { return; }
        if (messageIndex(messageId) < 0) { return; }
        anchorMessageId = messageId;
        // Jumping to a named message is leaving the end, and the window has to
        // be allowed to build somewhere other than the newest to get there.
        // settleFollowState turns following back on if the jump lands at the
        // end anyway.
        scroll.stopFollowingEnd();
        layoutMessages(currentMessages, true);
    }

    /**
     * Move focus one message.
     *
     * Walks the retained messages rather than the laid-out lines, so reaching
     * the edge of the window steps into the next message instead of stopping at
     * a boundary the reader cannot see.
     */
    private void focus(int direction)
    {
        if (currentMessages.length == 0) { return; }
        // Display order is oldest-first and currentMessages is newest-first, so
        // moving down the screen means moving back through the array.
        int step = -direction;
        int at = messageIndex(focusedMessageId);
        int i = at < 0 ? (step > 0 ? 0 : currentMessages.length - 1) : at + step;
        while (i >= 0 && i < currentMessages.length)
        {
            Message candidate = currentMessages[i];
            if (candidate != null && candidate.id != 0
                    && candidate.id != focusedMessageId)
            {
                focusedMessageId = candidate.id;
                ensureLaidOut(focusedMessageId);
                scrollFocusedIntoView();
                repaint();
                viewportChanged();
                return;
            }
            i += step;
        }
        // There is no next message, but the focused message may have more
        // lines below the viewport (caption/media/thumbnail/reactions).
        if (direction > 0 && scroll.top() < maxTop())
        {
            scroll.userScroll(maxTop() - scroll.top(), maxTop());
            repaint();
            viewportChanged();
        }
    }

    /** Minimal scroll that puts the focused message on screen. */
    private void scrollFocusedIntoView()
    {
        int i = lineIndex(focusedMessageId);
        if (i < 0) { return; }
        int top = scroll.top();
        int visible = visibleLines();
        int first = i;
        while (first > 0 && lineMessageIds[first - 1] == focusedMessageId)
        {
            first--;
        }
        int last = i;
        while (last + 1 < lineMessageIds.length
                && lineMessageIds[last + 1] == focusedMessageId)
        {
            last++;
        }
        if (first < top)
        {
            scroll.userScroll(first - top, maxTop());
        }
        else if (last >= top + visible)
        {
            int messageLines = last - first + 1;
            int target = messageLines <= visible ? last - visible + 1 : first;
            scroll.userScroll(target - top, maxTop());
        }
        settleFollowState();
    }

    /** First laid-out line of a message, or -1 when it is outside the window. */
    private int lineIndex(int id)
    {
        if (id == 0) { return -1; }
        for (int i = 0; i < lineMessageIds.length; i++)
        {
            if (lineMessageIds[i] == id) { return i; }
        }
        return -1;
    }

    /** Index in the retained transcript, laid out or not, or -1. */
    private int messageIndex(int id)
    {
        if (id == 0) { return -1; }
        for (int i = 0; i < currentMessages.length; i++)
        {
            if (currentMessages[i] != null && currentMessages[i].id == id)
            {
                return i;
            }
        }
        return -1;
    }

    private Message messageById(int id)
    {
        int at = messageIndex(id);
        return at < 0 ? null : currentMessages[at];
    }

    private void viewportChanged()
    {
        ViewportListener listener = viewportListener;
        if (listener != null) { listener.onChatViewportChanged(); }
    }

    private int visibleLines()
    {
        updateMetrics();
        return metrics.visibleLines();
    }

    private int maxTop()
    {
        int max = lines.length - visibleLines();
        return max < 0 ? 0 : max;
    }

    /**
     * Greedy word wrap. On pass 0 it only counts lines; on pass 1 it writes
     * them. Splitting an over-long word mid-way is deliberate - a URL with no
     * spaces must not vanish off the right edge.
     */
    private int wrap(String text, int width, int pass, int at,
                     boolean isOutgoing, int messageId, int messageOffset)
    {
        if (text == null || text.length() == 0)
        {
            return at;
        }
        if (pass == 0)
        {
            return at + countWrapped(text, width);
        }
        return wrapInto(text, width, at, isOutgoing, messageId, messageOffset);
    }

    private int countWrapped(String text, int width)
    {
        int count = 0;
        int start = 0;
        while (start < text.length())
        {
            int end = lineEnd(text, start, width);
            count++;
            start = skipSpace(text, end);
        }
        return count == 0 ? 1 : count;
    }

    private int wrapRich(String text, int width, int pass, int at,
                         boolean isOutgoing, int messageId, int messageOffset,
                         MessageEntity[] entities)
    {
        if (text == null || text.length() == 0) { return at; }
        if (pass == 0) { return at + countWrappedRich(text, width, entities); }
        int start = 0;
        while (start < text.length() && at < lines.length)
        {
            int end = lineEndRich(text, start, width, entities);
            lines[at] = text.substring(start, end);
            outgoing[at] = isOutgoing;
            meta[at] = false;
            lineMessageIds[at] = messageId;
            lineMessageOffsets[at] = messageOffset++;
            lineTextStarts[at] = start;
            at++;
            start = skipSpace(text, end);
        }
        return at;
    }

    private int countWrappedRich(String text, int width,
                                 MessageEntity[] entities)
    {
        int count = 0;
        int start = 0;
        while (start < text.length())
        {
            int end = lineEndRich(text, start, width, entities);
            count++;
            start = skipSpace(text, end);
        }
        return count == 0 ? 1 : count;
    }

    private int lineEndRich(String text, int start, int width,
                            MessageEntity[] entities)
    {
        int hardBreak = text.indexOf('\n', start);
        int limit = hardBreak >= 0 ? hardBreak : text.length();
        limit = richText.blockBoundary(start, limit, entities);
        if (limit <= start) { return limit; }

        int available = width - (richText.quotedAt(entities, start) ? 6 : 0);
        if (available < 1) { available = 1; }
        int fit = richText.fitEnd(text, start, limit, available, entities);
        if (fit >= limit) { return limit; }
        for (int i = fit; i > start; i--)
        {
            if (text.charAt(i) == ' ') { return i; }
        }
        return fit > start ? fit
                : Math.min(limit, EmojiText.nextBoundary(text, start));
    }

    private int wrapInto(String text, int width, int at, boolean isOutgoing,
                         int messageId, int messageOffset)
    {
        int start = 0;
        while (start < text.length() && at < lines.length)
        {
            int end = lineEnd(text, start, width);
            lines[at] = text.substring(start, end);
            outgoing[at] = isOutgoing;
            meta[at] = false;
            lineMessageIds[at] = messageId;
            lineMessageOffsets[at] = messageOffset;
            at++;
            messageOffset++;
            start = skipSpace(text, end);
        }
        return at;
    }

    /**
     * Index one past the last character that fits on a line starting at
     * {@code start}.
     *
     * Three passes over the line at most, each linear: find the hard break,
     * measure forward until the width runs out, then look back for a space to
     * break at. It used to be one pass that re-measured the whole prefix per
     * character, which is the same answer at O(k&sup2;) - see {@link EmojiText}.
     */
    private int lineEnd(String text, int start, int width)
    {
        int n = text.length();
        int hardBreak = text.indexOf('\n', start);
        int limit = hardBreak >= 0 ? hardBreak : n;
        if (limit <= start) { return limit; }

        int fit = EmojiText.fitEnd(text, start, limit, width, font);
        if (fit >= limit) { return limit; }

        // Break at the last space if there was one, otherwise mid-word: a URL
        // with no spaces in it must not vanish off the right edge. The space
        // that itself overflowed still counts - the line ends before it either
        // way - but one at the very start does not, or the line would be empty.
        for (int i = fit; i > start; i--)
        {
            if (text.charAt(i) == ' ') { return i; }
        }
        // Nothing fits and nothing to break on - emit one token so wrapping
        // always makes progress.
        return fit > start ? fit
                : Math.min(limit, EmojiText.nextBoundary(text, start));
    }

    private static int skipSpace(String text, int at)
    {
        while (at < text.length() && (text.charAt(at) == ' ' || text.charAt(at) == '\n'))
        {
            at++;
        }
        return at;
    }

    private static String reactionLine(ReactionSummary[] reactions)
    {
        if (reactions == null || reactions.length == 0) { return ""; }
        StringBuffer out = new StringBuffer();
        for (int i = 0; i < reactions.length; i++)
        {
            ReactionSummary reaction = reactions[i];
            if (reaction == null || reaction.emoji == null) { continue; }
            if (out.length() > 0) { out.append(' '); }
            if (reaction.chosen) { out.append('*'); }
            out.append(reaction.emoji);
            out.append(' ');
            out.append(reaction.count);
        }
        return out.toString();
    }

    private int layoutPoll(Poll poll, int width, int pass, int at,
                           boolean isOutgoing, int messageId)
    {
        int offset = 1200;
        at = wrap(pollHeader(poll), width, pass, at, isOutgoing,
                messageId, offset);
        offset += 32;
        for (int i = 0; i < poll.options.length; i++)
        {
            at = wrap(pollOptionLine(poll, poll.options[i]), width, pass, at,
                    isOutgoing, messageId, offset);
            offset += 32;
        }
        int footerStart = at;
        at = wrap(pollFooter(poll), width, pass, at, isOutgoing,
                messageId, 1700);
        if (pass == 1)
        {
            for (int i = footerStart; i < at; i++) { meta[i] = true; }
        }
        if (poll.solution != null && poll.solution.length() > 0)
        {
            int solutionStart = at;
            at = wrap("Solution: " + poll.solution, width, pass, at,
                    isOutgoing, messageId, 1800);
            if (pass == 1)
            {
                for (int i = solutionStart; i < at; i++) { meta[i] = true; }
            }
        }
        return at;
    }

    private int pollLineCount(Poll poll, int width)
    {
        int count = countWrapped(pollHeader(poll), width);
        for (int i = 0; i < poll.options.length; i++)
        {
            count += countWrapped(pollOptionLine(poll, poll.options[i]), width);
        }
        count += countWrapped(pollFooter(poll), width);
        if (poll.solution != null && poll.solution.length() > 0)
        {
            count += countWrapped("Solution: " + poll.solution, width);
        }
        return count;
    }

    private static String pollHeader(Poll poll)
    {
        String label = poll.quiz ? "[quiz]" : "[poll]";
        return poll.question == null || poll.question.length() == 0
                ? label : label + " " + poll.question;
    }

    private static String pollOptionLine(Poll poll, PollOption option)
    {
        if (option == null) { return "[ ]"; }
        StringBuffer out = new StringBuffer();
        out.append(option.chosen ? "[x] " : "[ ] ");
        int percent = poll.percent(option);
        if (percent >= 0)
        {
            out.append(percent);
            out.append("% ");
        }
        out.append(option.text == null ? "" : option.text);
        if (poll.quiz && option.correct)
        {
            out.append(" (correct)");
        }
        else if (poll.quiz && option.chosen && knownCorrect(poll))
        {
            out.append(" (wrong)");
        }
        return out.toString();
    }

    private static boolean knownCorrect(Poll poll)
    {
        for (int i = 0; i < poll.options.length; i++)
        {
            if (poll.options[i] != null && poll.options[i].correct) { return true; }
        }
        return poll.solution != null && poll.solution.length() > 0;
    }

    private static String pollFooter(Poll poll)
    {
        StringBuffer out = new StringBuffer();
        if (poll.totalVoters >= 0)
        {
            out.append(poll.totalVoters);
            out.append(poll.totalVoters == 1 ? " vote" : " votes");
        }
        appendPollFact(out, poll.publicVoters ? "public" : "anonymous");
        if (poll.multipleChoice) { appendPollFact(out, "multiple"); }
        if (poll.closed) { appendPollFact(out, "closed"); }
        else if (poll.revotingDisabled) { appendPollFact(out, "one vote only"); }
        if (poll.hideResultsUntilClose && !poll.closed
                && !poll.hasOptionResults())
        {
            appendPollFact(out, "results hidden");
        }
        if (poll.subscribersOnly) { appendPollFact(out, "members only"); }
        return out.toString();
    }

    private static void appendPollFact(StringBuffer out, String value)
    {
        if (out.length() > 0) { out.append(", "); }
        out.append(value);
    }

    public static String replyLine(Message message, Message[] messages)
    {
        return replyLine(message, messages, 0);
    }

    /**
     * @param threadRootId suppresses the caption a thread's plumbing would
     *                     otherwise paint on every row: inside a topic every
     *                     message "replies to" the topic's root service
     *                     message, and that is membership, not an answer
     */
    public static String replyLine(Message message, Message[] messages,
                                   int threadRootId)
    {
        if (message == null) { return ""; }
        int target = message.visibleReplyTo(threadRootId);
        if (target <= 0) { return ""; }
        for (int i = 0; i < messages.length; i++)
        {
            Message source = messages[i];
            if (source == null || source.id != target)
            {
                continue;
            }
            String author = source.senderName();
            if (author.length() == 0) { author = "Message"; }
            String text = source.summaryText();
            int nl = text.indexOf('\n');
            if (nl >= 0) { text = text.substring(0, nl); }
            if (text.length() > 48) { text = text.substring(0, 45) + "..."; }
            return text.length() == 0 ? ("Reply to " + author)
                    : ("Reply to " + author + ": " + text);
        }
        return "Reply to #" + target;
    }

    public synchronized boolean hasThumbnail(int messageId)
    {
        return thumbnail(messageId) != null;
    }

    public int thumbnailWidth()
    {
        updateMetrics();
        return wrapWidth();
    }

    public int thumbnailHeight()
    {
        updateMetrics();
        return metrics.thumbnailHeight;
    }

    public synchronized void setThumbnail(int messageId, Image image)
    {
        if (messageId == 0 || image == null) { return; }
        for (int i = 0; i < thumbnailCount; i++)
        {
            if (thumbnailIds[i] == messageId)
            {
                thumbnails[i] = image;
                repaint();
                return;
            }
        }
        if (thumbnailCount < thumbnailCapacity)
        {
            thumbnailIds[thumbnailCount] = messageId;
            thumbnails[thumbnailCount] = image;
            thumbnailCount++;
        }
        else
        {
            System.arraycopy(thumbnailIds, 1, thumbnailIds, 0,
                    thumbnailCapacity - 1);
            System.arraycopy(thumbnails, 1, thumbnails, 0,
                    thumbnailCapacity - 1);
            thumbnailIds[thumbnailCapacity - 1] = messageId;
            thumbnails[thumbnailCapacity - 1] = image;
        }
        repaint();
    }

    private synchronized Image thumbnail(int messageId)
    {
        for (int i = 0; i < thumbnailCount; i++)
        {
            if (thumbnailIds[i] == messageId) { return thumbnails[i]; }
        }
        return null;
    }

    /**
     * Drop every decoded thumbnail. Public because the memory-pressure ladder
     * calls it from a worker thread, and this is the cheapest useful thing the
     * client can give back - roughly 190 KB when the cache is full.
     */
    public synchronized void clearThumbnails()
    {
        for (int i = 0; i < thumbnailCount; i++)
        {
            thumbnailIds[i] = 0;
            thumbnails[i] = null;
        }
        thumbnailCount = 0;
    }

    /**
     * Turn the native command bar off and reserve room for the drawn one.
     *
     * The mode change has to happen before the canvas is shown for the extra rows
     * to be there on the first paint.
     */
    public void setFullscreen(boolean on)
    {
        if (fullscreen == on) { return; }
        fullscreen = on;
        setFullScreenMode(on);
        layoutMessages(currentMessages, true);
    }

    /**
     * The commands the drawn menu lists, and the one the right key runs.
     *
     * A Canvas cannot be asked for its own commands, so the owner hands them over
     * once. Back is expected to be in {@code entries}: with the AMS bar gone, the
     * menu is the only way out of the conversation.
     */
    public void setSoftKeys(Command right, Command[] entries)
    {
        softRight = right;
        menu = entries == null ? new Command[0] : entries;
        if (menuSelection >= menu.length) { menuSelection = 0; }
        repaint();
    }

    public void setCommandListener(CommandListener listener)
    {
        super.setCommandListener(listener);
        commandDispatch = listener;
    }

    private void updateMetrics()
    {
        // A fixed inset rather than metrics.padding: padding is only known
        // after update(), so deriving the footer from it would make the body
        // height change between the layout and the paint that follows it, and
        // the newest bubble would be clipped by the bar it did not know about.
        metrics.footerHeight = fullscreen ? lineHeight + 8 : 0;
        metrics.update(getWidth(), getHeight(), font, metaFont);
    }
}
