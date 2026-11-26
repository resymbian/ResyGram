package tg.plat;

import java.io.InputStream;
import java.io.OutputStream;

import javax.microedition.io.Connector;
import javax.microedition.io.SocketConnection;

/**
 * Client for the native notification bridge on 127.0.0.1:4711.
 *
 * A MIDlet cannot post into the phone's Messaging application: without a
 * trusted certificate there is no notification API and no background push. The
 * bridge is a tiny Symbian GUI app that listens on loopback and shows a global
 * note (and, where the SDK allows it, stores an entry in Inbox). When it is not
 * installed the open fails at once - loopback is refused, not filtered - so this
 * is a best-effort courtesy that never costs the client anything else.
 *
 * Protocol, one connection per notification: writeUTF(title), writeUTF(text),
 * read one byte (1 = displayed). Java's modified UTF-8 is what DataOutputStream
 * writes, and the native side decodes exactly that.
 */
public final class Notifier
{
    public static final String ADDRESS = "socket://127.0.0.1:4711";

    /**
     * Some S60 Java stacks resolve "127.0.0.1" through the selected IAP rather
     * than the IPv4 loopback interface. "localhost" reaches the same listener
     * through that route; trying it costs one refused socket when the first
     * address works.
     */
    private static final String[] ADDRESSES =
    {
        ADDRESS, "socket://localhost:4711"
    };

    /** Hard wire limits in NotiHelper, minus room for the ellipsis. */
    private static final int MAX_TITLE_BYTES = 256;
    private static final int MAX_TEXT_BYTES = 1536;

    /** Last failure, kept so Settings can show a reason other than "no bridge". */
    public static String lastError;

    /** Set while a delivery thread is alive, so a burst sends one at a time. */
    private static boolean busy;

    private Notifier() {}

    /**
     * Deliver a note without blocking the caller.
     *
     * Called from the display thread when an update arrives; opening a socket
     * there would freeze the UI for as long as the handset takes to refuse it.
     * Failures are silent by design: a missing bridge is the normal case.
     */
    public static void post(final String title, final String text)
    {
        synchronized (Notifier.class)
        {
            if (busy) { return; }
            busy = true;
        }
        Thread worker = new Thread(new Runnable()
        {
            public void run()
            {
                try { deliver(title, text); }
                catch (Throwable ignored) { }
                finally
                {
                    synchronized (Notifier.class) { busy = false; }
                }
            }
        });
        worker.start();
    }

    /**
     * Send one note synchronously.
     *
     * @return null when the bridge accepted it, otherwise a one-line reason the
     *         caller can show - "no bridge" rather than a stack trace.
     */
    public static String deliver(String title, String text)
    {
        String reason = null;
        for (int i = 0; i < ADDRESSES.length; i++)
        {
            String outcome = deliverTo(ADDRESSES[i], title, text);
            if (outcome == null) { return null; }
            reason = outcome;
            // A permission failure repeats on every alias; a refusal by the bridge
            // means connection succeeded, so do not overwrite with -34 from localhost.
            if (lastError != null && (lastError.indexOf("SecurityException") >= 0
                    || lastError.indexOf("refused") >= 0))
            {
                break;
            }
        }
        return reason;
    }

    private static String deliverTo(String address, String title, String text)
    {
        SocketConnection connection = null;
        OutputStream out = null;
        InputStream in = null;
        try
        {
            connection = (SocketConnection) Connector.open(address,
                    Connector.READ_WRITE, true);
            out = connection.openOutputStream();
            writeUtf(out, trimUtf(title, MAX_TITLE_BYTES));
            writeUtf(out, trimUtf(text, MAX_TEXT_BYTES));
            out.flush();
            in = connection.openInputStream();
            final SocketConnection conn = connection;
            final boolean[] done = new boolean[1];
            Thread timer = new Thread(new Runnable()
            {
                public void run()
                {
                    try
                    {
                        Thread.sleep(25000);
                        if (!done[0] && conn != null)
                        {
                            conn.close();
                        }
                    }
                    catch (Throwable ignored) {}
                }
            });
            timer.start();
            int reply = in.read();
            done[0] = true;
            return reply == 1 ? null : "the bridge refused the note";
        }
        catch (Throwable failed)
        {
            lastError = failed.toString();
            return "no notification bridge on " + address + " (" + lastError + ")";
        }
        finally
        {
            try { if (in != null) { in.close(); } } catch (Throwable ignored) {}
            try { if (out != null) { out.close(); } } catch (Throwable ignored) {}
            try { if (connection != null) { connection.close(); } }
            catch (Throwable ignored) {}
        }
    }

    /**
     * writeUTF, without DataOutputStream.
     *
     * java.io.DataInputStream extends FilterInputStream in J2SE but not in
     * CLDC, and the API check walks superclasses - referencing it would drag an
     * SE-only class into an artifact that must run on CLDC 1.1. The wire format
     * is small enough to write out: two-byte length, then modified UTF-8, with
     * all non-ASCII as two or three bytes (the native decoder expects exactly
     * that).
     */
    private static void writeUtf(OutputStream out, String value)
            throws java.io.IOException
    {
        byte[] body = new byte[value.length() * 3];
        int at = 0;
        for (int i = 0; i < value.length(); i++)
        {
            at = appendUtf(body, at, value.charAt(i));
        }
        out.write((at >> 8) & 0xFF);
        out.write(at & 0xFF);
        out.write(body, 0, at);
    }

    private static int appendUtf(byte[] body, int at, char c)
    {
        if (c >= 1 && c < 0x80)
        {
            body[at++] = (byte) c;
        }
        else if (c < 0x800)
        {
            body[at++] = (byte) (0xC0 | ((c >> 6) & 0x1F));
            body[at++] = (byte) (0x80 | (c & 0x3F));
        }
        else
        {
            body[at++] = (byte) (0xE0 | ((c >> 12) & 0x0F));
            body[at++] = (byte) (0x80 | ((c >> 6) & 0x3F));
            body[at++] = (byte) (0x80 | (c & 0x3F));
        }
        return at;
    }

    private static int utfBytes(char c)
    {
        return c >= 1 && c < 0x80 ? 1 : (c < 0x800 ? 2 : 3);
    }

    /**
     * Trim by encoded bytes, not by characters. Three bytes per BMP character
     * is the worst case NotiHelper's decoder accepts, so a 512-character
     * message must be cut at 512 bytes or the bridge answers 0.
     */
    private static String trimUtf(String value, int maxBytes)
    {
        if (value == null) { return ""; }
        String single = value.replace('\n', ' ').replace('\r', ' ');
        int budget = maxBytes - 3;
        int used = 0;
        for (int i = 0; i < single.length(); i++)
        {
            used += utfBytes(single.charAt(i));
            if (used > budget)
            {
                return single.substring(0, i) + "\u2026";
            }
        }
        return single;
    }
}
