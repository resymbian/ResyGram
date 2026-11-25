package tg.plat;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;

import javax.microedition.io.Connector;
import javax.microedition.io.StreamConnection;
import javax.microedition.io.StreamConnectionNotifier;

import tg.api.DownloadToken;
import tg.api.DocumentRef;
import tg.api.PhotoInputStream;
import tg.api.Telegram;

/**
 * Serve one Telegram video to the phone's own player over loopback HTTP.
 *
 * This is the storage-saving path for large clips: bytes are pulled from
 * upload.getFile in 32 KiB chunks and pushed straight to the socket, so no copy
 * is ever written to the device and there is nothing to delete afterwards.
 * platformRequest() is deliberately not followed by destroyApp() here: the
 * server has to stay alive while the phone reads from it.
 *
 * MIDP's ServerConnection is not in the CLDC/MIDP bootclasspath this project
 * compiles against, so the older StreamConnectionNotifier seam is used instead.
 */
public final class VideoStreamer
{
    private static final int FIRST_PORT = 4712;
    private static final int LAST_PORT = 4721;
    private static final int REQUEST_LIMIT = 4096;

    private static volatile boolean active;
    private static volatile boolean served;
    private static StreamConnectionNotifier notifier;
    private static DocumentRef document;
    private static Telegram telegram;
    private static String mimeType;

    private VideoStreamer() { }

    /**
     * Start the server and return the URL to hand to platformRequest(), or
     * null when no local port could be opened.
     */
    public static synchronized String start(DocumentRef doc, Telegram client,
                                            String mime)
    {
        if (active || doc == null || client == null) { return null; }
        StreamConnectionNotifier opened = null;
        int chosen = 0;
        for (int port = FIRST_PORT; port <= LAST_PORT; port++)
        {
            try
            {
                opened = (StreamConnectionNotifier) Connector.open(
                        "socket://:" + port);
                chosen = port;
                break;
            }
            catch (Throwable ignored) { }
        }
        if (opened == null) { return null; }

        notifier = opened;
        document = doc;
        telegram = client;
        mimeType = (mime == null || mime.length() == 0)
                ? "video/mp4" : mime;
        served = false;
        active = true;
        new Thread(new Runnable()
        {
            public void run() { serve(); }
        }).start();
        return urlForPort(chosen, mimeType);
    }

    /** Stop after the player has gone away; safe when no stream was started. */
    public static synchronized void stop()
    {
        active = false;
        StreamConnectionNotifier open = notifier;
        notifier = null;
        document = null;
        telegram = null;
        mimeType = null;
        if (open != null)
        {
            try { open.close(); } catch (Throwable ignored) { }
        }
    }

    /** True when the phone actually fetched at least one byte. */
    public static boolean wasUsed() { return served; }

    /** Kept separate so the URL can be checked without opening a socket. */
    public static String urlForPort(int port, String mime)
    {
        return "http://127.0.0.1:" + port + pathFor(mime);
    }

    public static String pathFor(String mime)
    {
        if (mime != null && mime.startsWith("video/3gpp")) { return "/resygram.3gp"; }
        if (mime != null && mime.startsWith("video/quicktime")) { return "/resygram.mov"; }
        return "/resygram.mp4";
    }

    /** Parse the start of a Range header; an unsupported range means from 0. */
    public static long rangeStart(String range)
    {
        if (range == null) { return 0; }
        int equals = range.indexOf('=');
        if (equals < 0) { return 0; }
        int dash = range.indexOf('-', equals + 1);
        if (dash < 0) { return 0; }
        String value = range.substring(equals + 1, dash).trim();
        if (value.length() == 0) { return 0; }
        try
        {
            long start = Long.parseLong(value);
            return start < 0 ? 0 : start;
        }
        catch (NumberFormatException bad) { return 0; }
    }

    public static String header(String request, String name)
    {
        int at = 0;
        String wanted = name.toLowerCase();
        while (at < request.length())
        {
            int end = request.indexOf('\n', at);
            if (end < 0) { end = request.length(); }
            String line = request.substring(at, end).trim();
            if (line.length() == 0) { break; }
            if (line.toLowerCase().startsWith(wanted))
            {
                int colon = line.indexOf(':');
                return colon < 0 ? "" : line.substring(colon + 1).trim();
            }
            at = end + 1;
        }
        return null;
    }

    private static void serve()
    {
        while (active)
        {
            StreamConnection connection = null;
            try
            {
                connection = notifier.acceptAndOpen();
                handle(connection);
            }
            catch (Throwable failed)
            {
                if (active) { return; }
            }
            finally
            {
                if (connection != null)
                {
                    try { connection.close(); } catch (Throwable ignored) { }
                }
            }
        }
    }

    private static void handle(StreamConnection connection) throws IOException
    {
        DocumentRef doc = document;
        Telegram client = telegram;
        if (doc == null || client == null) { return; }

        InputStream in = connection.openInputStream();
        String request = readRequest(in);
        String firstLine = request.indexOf('\n') < 0 ? request
                : request.substring(0, request.indexOf('\n')).trim();
        if (!firstLine.startsWith("GET ")) { return; }

        long total = doc.size;
        long start = rangeStart(header(request, "Range:"));
        if (start <= 0 || start >= total) { start = 0; }
        boolean partial = start > 0;

        OutputStream out = connection.openOutputStream();
        String type = mimeType == null ? "video/mp4" : mimeType;
        StringBuffer response = new StringBuffer(160);
        response.append(partial ? "HTTP/1.1 206 Partial Content\r\n"
                : "HTTP/1.1 200 OK\r\n");
        response.append("Content-Type: ").append(type).append("\r\n");
        response.append("Content-Length: ").append(total - start).append("\r\n");
        if (partial)
        {
            response.append("Content-Range: bytes ").append(start).append('-')
                    .append(total - 1).append('/').append(total).append("\r\n");
        }
        response.append("Accept-Ranges: bytes\r\n");
        response.append("Connection: close\r\n\r\n");
        out.write(bytes(response.toString()));
        out.flush();

        PhotoInputStream stream = client.openDocument(doc, start,
                new DownloadToken());
        try
        {
            byte[] buffer = new byte[8192];
            long left = total - start;
            while (active && left > 0)
            {
                int want = (int) Math.min((long) buffer.length, left);
                int count = stream.read(buffer, 0, want);
                if (count < 0) { break; }
                out.write(buffer, 0, count);
                left -= count;
            }
            out.flush();
            served = true;
        }
        finally { stream.close(); }
    }

    private static String readRequest(InputStream in) throws IOException
    {
        ByteArrayOutputStream collected = new ByteArrayOutputStream();
        byte[] buffer = new byte[256];
        while (collected.size() < REQUEST_LIMIT)
        {
            int count = in.read(buffer, 0, Math.min(buffer.length,
                    REQUEST_LIMIT - collected.size()));
            if (count < 0) { break; }
            collected.write(buffer, 0, count);
            String soFar = new String(collected.toByteArray());
            if (soFar.indexOf("\r\n\r\n") >= 0
                    || soFar.indexOf("\n\n") >= 0) { break; }
        }
        return new String(collected.toByteArray());
    }

    private static byte[] bytes(String text)
    {
        byte[] out = new byte[text.length()];
        for (int i = 0; i < out.length; i++)
        {
            char c = text.charAt(i);
            out[i] = (byte) (c < 128 ? c : '?');
        }
        return out;
    }
}
