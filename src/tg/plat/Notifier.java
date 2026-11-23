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
 * bridge is a tiny Symbian EXE that listens on loopback and shows a global note
 * (and, where the SDK allows it, stores an entry in Inbox). When it is not
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
        SocketConnection connection = null;
        OutputStream out = null;
        InputStream in = null;
        try
        {
            connection = (SocketConnection) Connector.open(ADDRESS,
                    Connector.READ_WRITE, true);
            out = connection.openOutputStream();
            writeUtf(out, trim(title, 64));
            writeUtf(out, trim(text, 512));
            out.flush();
            in = connection.openInputStream();
            return in.read() == 1 ? null : "the bridge refused the note";
        }
        catch (Throwable failed)
        {
            return "no notification bridge on " + ADDRESS;
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
            char c = value.charAt(i);
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
        }
        out.write((at >> 8) & 0xFF);
        out.write(at & 0xFF);
        out.write(body, 0, at);
    }

    private static String trim(String value, int max)
    {
        if (value == null) { return ""; }
        String single = value.replace('\n', ' ').replace('\r', ' ');
        return single.length() <= max ? single : single.substring(0, max - 1) + "\u2026";
    }
}
