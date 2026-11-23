package tg.plat;

import java.io.IOException;

import tg.diag.Diag;
import tg.io.Transport;
import tg.mt.Dc;

/**
 * Direct socket transport that falls back to a non-privileged port.
 *
 * {@link MidpTransport} opens exactly the port it is given, which is 443 for
 * every direct link. An S60 3rd handset refuses that to an untrusted MIDlet at
 * {@code Connector.open}: not a prompt the user can answer, a
 * {@code SecurityException} thrown before the socket exists. The data centres
 * also serve MTProto on 5222, above the privileged range, so a direct route
 * does exist on an unsigned application - it just cannot start at 443.
 *
 * Only a refusal rooted in the security policy moves to the next port. A
 * timeout, a reset or an unreachable host is the network's answer about that
 * address and retrying elsewhere would turn one failed connection into three
 * slow ones on a link where each attempt costs seconds.
 *
 * The port that worked is remembered for the process, so a media connection to
 * another data centre opens where it knows the handset allows, rather than
 * paying the refusal again on every file.
 */
public final class DirectTransport implements Transport
{
    private final MidpTransport inner = new MidpTransport();

    /** Preferred port, learned on the first successful connect. */
    private static int learned = 0;

    public void connect(String host, int port, int timeoutMs) throws IOException
    {
        int[] order = order(port);
        RuntimeException denied = null;
        for (int i = 0; i < order.length; i++)
        {
            try
            {
                inner.connect(host, order[i], timeoutMs);
                if (learned != order[i])
                {
                    learned = order[i];
                    Diag.info("net direct port " + order[i] + " accepted");
                }
                return;
            }
            catch (SecurityException e)
            {
                denied = e;
                Diag.warn("net " + host + ":" + order[i]
                          + " refused by the handset's security policy");
            }
        }
        // Every port refused: the caller's error is the same one it would have
        // seen without the fallback, naming the port the link actually wanted.
        if (denied != null) { throw denied; }
        throw new IOException("no direct port available for " + host);
    }

    /**
     * Ports to try for a connection that asked for {@code port}.
     *
     * A media endpoint carries its own port from {@code help.getConfig}, so the
     * learned port goes first when it matches the bootstrap one and is tried
     * ahead of the rest otherwise; 443 always keeps its place at the head for a
     * handset that permits it.
     */
    private static int[] order(int port)
    {
        if (port != Dc.PORT)
        {
            if (learned == 0 || learned == port) { return new int[] { port }; }
            int[] pair = new int[2];
            pair[0] = learned;
            pair[1] = port;
            return pair;
        }
        if (learned == 0 || learned == Dc.PORT) { return Dc.DIRECT_PORTS; }
        int[] ordered = new int[Dc.DIRECT_PORTS.length];
        ordered[0] = learned;
        int n = 1;
        for (int i = 0; i < Dc.DIRECT_PORTS.length; i++)
        {
            if (Dc.DIRECT_PORTS[i] != learned) { ordered[n++] = Dc.DIRECT_PORTS[i]; }
        }
        return ordered;
    }

    public int read(byte[] buf, int off, int len) throws IOException
    {
        return inner.read(buf, off, len);
    }

    public void readFully(byte[] buf, int off, int len) throws IOException
    {
        inner.readFully(buf, off, len);
    }

    public void write(byte[] buf, int off, int len) throws IOException
    {
        inner.write(buf, off, len);
    }

    public void flush() throws IOException
    {
        inner.flush();
    }

    public boolean isConnected()
    {
        return inner.isConnected();
    }

    public void close()
    {
        inner.close();
    }

    public long bytesRead()
    {
        return inner.bytesRead();
    }

    public long bytesWritten()
    {
        return inner.bytesWritten();
    }
}
