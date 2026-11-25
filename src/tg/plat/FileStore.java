package tg.plat;

import java.io.InputStream;
import java.io.OutputStream;

import javax.microedition.io.Connector;
import javax.microedition.io.file.FileConnection;

/**
 * The phone's own file system, behind one small door.
 *
 * JSR-75 is optional and on a device without it every class in this file is
 * unloadable, so callers must ask {@link #available()} first and catch Throwable
 * around the rest. That is why nothing here touches FileConnection until a method
 * that needs it is called: the check itself must not load the class.
 */
public final class FileStore
{
    /** One directory entry, flattened so the picker does not hold FileConnections. */
    public static final class Entry
    {
        public final String name;
        public final String url;
        public final boolean directory;
        public final int size;

        Entry(String name, String url, boolean directory, int size)
        {
            this.name = name;
            this.url = url;
            this.directory = directory;
            this.size = size;
        }
    }

    public interface Progress
    {
        void onProgress(int written, int total);
    }

    private FileStore() {}

    public static boolean available()
    {
        try
        {
            return System.getProperty(
                    "microedition.io.file.FileConnection.version") != null;
        }
        catch (Throwable ignored) { return false; }
    }

    /** The Images folder, or the memory card when the phone names no photos dir. */
    public static String photosDir()
    {
        String dir = property("fileconn.dir.photos");
        if (dir != null && dir.length() > 0) { return dir; }
        dir = property("fileconn.dir.memorycard");
        if (dir != null && dir.length() > 0) { return dir; }
        return "file:///C/Images/";
    }

    /** Where a download lands: the phone's own download folder if it has one. */
    public static String downloadsDir()
    {
        String dir = property("fileconn.dir.downloads");
        if (dir != null && dir.length() > 0) { return dir; }
        dir = property("fileconn.dir.memorycard");
        if (dir != null && dir.length() > 0) { return dir + "ResyGram/"; }
        return "file:///C/ResyGram/";
    }

    /**
     * Private folder for temporary videos. Downloads no longer land beside the
     * user's own files, which lets us delete everything here on resume without
     * ever guessing which clip is ours.
     */
    public static String videosDir()
    {
        String card = property("fileconn.dir.memorycard");
        if (card != null && card.length() > 0)
        {
            if (!card.endsWith("/")) { card += "/"; }
            return card + "ResyGram/Videos/";
        }
        return "file:///C/ResyGram/Videos/";
    }

    /** Remove the previous video handoff. Safe to call after every resume. */
    public static void cleanupVideos()
    {
        FileConnection dir = null;
        try
        {
            dir = (FileConnection) Connector.open(videosDir(),
                    Connector.READ_WRITE);
            if (!dir.exists()) { return; }
            java.util.Enumeration names = dir.list();
            while (names != null && names.hasMoreElements())
            {
                String name = (String) names.nextElement();
                if (name == null || name.length() == 0) { continue; }
                FileConnection file = null;
                try
                {
                    file = (FileConnection) Connector.open(
                            videosDir() + name, Connector.READ_WRITE);
                    if (file.exists() && !file.isDirectory()) { file.delete(); }
                }
                catch (Throwable ignored) { }
                finally
                {
                    if (file != null)
                    {
                        try { file.close(); } catch (Throwable ignored) { }
                    }
                }
            }
        }
        catch (Throwable ignored) { }
        finally
        {
            if (dir != null)
            {
                try { dir.close(); } catch (Throwable ignored) { }
            }
        }
    }

    /** The folders worth browsing first, labelled the way the phone labels them. */
    public static String[] roots()
    {
        String card = property("fileconn.dir.memorycard");
        String photos = property("fileconn.dir.photos");
        String music = property("fileconn.dir.music");
        String others = property("fileconn.dir.others");
        String[] out = new String[8];
        int count = 0;
        count = addRoot(out, count, "Images", photos);
        count = addRoot(out, count, "Music", music);
        count = addRoot(out, count, "Files", others);
        count = addRoot(out, count, "Memory card", card);
        count = addRoot(out, count, "Phone memory", "file:///C/");
        String[] trimmed = new String[count];
        System.arraycopy(out, 0, trimmed, 0, count);
        return trimmed;
    }

    private static int addRoot(String[] out, int count, String label,
                               String url)
    {
        if (url == null || url.length() == 0) { return count; }
        for (int i = 0; i < count; i++)
        {
            if (out[i].endsWith("\n" + url)) { return count; }
        }
        out[count] = label + "\n" + url;
        return count + 1;
    }

    /** Label of a root entry produced by {@link #roots()}. */
    public static String rootLabel(String root)
    {
        int at = root.indexOf('\n');
        return at < 0 ? root : root.substring(0, at);
    }

    /** URL of a root entry produced by {@link #roots()}. */
    public static String rootUrl(String root)
    {
        int at = root.indexOf('\n');
        return at < 0 ? root : root.substring(at + 1);
    }

    /**
     * List one directory, directories first. Bounded: a folder with thousands of
     * songs would cost more heap than a conversation does.
     */
    public static Entry[] list(String dirUrl) throws Exception
    {
        FileConnection dir = (FileConnection) Connector.open(dirUrl,
                Connector.READ);
        try
        {
            if (!dir.exists() || !dir.isDirectory())
            {
                throw new Exception("not a folder: " + dirUrl);
            }
            java.util.Enumeration names = dir.list();
            Entry[] all = new Entry[200];
            int count = 0;
            while (names.hasMoreElements() && count < all.length)
            {
                String name = (String) names.nextElement();
                if (name == null || name.length() == 0) { continue; }
                String url = dirUrl.endsWith("/") ? dirUrl + name
                        : dirUrl + "/" + name;
                boolean isDir = false;
                int size = 0;
                try
                {
                    FileConnection child = (FileConnection) Connector.open(url,
                            Connector.READ);
                    try
                    {
                        isDir = child.isDirectory();
                        size = isDir ? 0 : (int) Math.min(child.fileSize(),
                                0x7fffffffL);
                    }
                    finally { child.close(); }
                }
                catch (Throwable ignored) { continue; }
                all[count++] = new Entry(name, url, isDir, size);
            }
            return sort(all, count);
        }
        finally { dir.close(); }
    }

    private static Entry[] sort(Entry[] all, int count)
    {
        Entry[] dirs = new Entry[count];
        Entry[] files = new Entry[count];
        int d = 0;
        int f = 0;
        for (int i = 0; i < count; i++)
        {
            if (all[i].directory) { dirs[d++] = all[i]; }
            else { files[f++] = all[i]; }
        }
        Entry[] out = new Entry[count];
        int at = 0;
        for (int i = 0; i < d; i++) { out[at++] = dirs[i]; }
        for (int i = 0; i < f; i++) { out[at++] = files[i]; }
        return out;
    }

    /** A name that does not overwrite an existing file in {@code dirUrl}. */
    public static String uniqueName(String dirUrl, String wanted) throws Exception
    {
        String name = sanitize(wanted);
        int dot = name.lastIndexOf('.');
        String stem = dot > 0 ? name.substring(0, dot) : name;
        String ext = dot > 0 ? name.substring(dot) : "";
        String candidate = name;
        for (int i = 1; i < 500; i++)
        {
            FileConnection probe = (FileConnection) Connector.open(
                    join(dirUrl, candidate), Connector.READ_WRITE);
            try
            {
                if (!probe.exists()) { return candidate; }
            }
            finally { probe.close(); }
            candidate = stem + "-" + i + ext;
        }
        return System.currentTimeMillis() + "-" + name;
    }

    /** Copy a download to {@code dirUrl}; returns the URL that was written. */
    public static String save(String dirUrl, String name, InputStream in,
                              int total, Progress progress) throws Exception
    {
        FileConnection dir = (FileConnection) Connector.open(dirUrl,
                Connector.READ_WRITE);
        try
        {
            if (!dir.exists()) { dir.mkdir(); }
        }
        finally { dir.close(); }

        String target = join(dirUrl, uniqueName(dirUrl, name));
        FileConnection out = (FileConnection) Connector.open(target,
                Connector.READ_WRITE);
        try
        {
            out.create();
            OutputStream stream = out.openOutputStream();
            try
            {
                byte[] buffer = new byte[8192];
                int written = 0;
                int n;
                while ((n = in.read(buffer)) > 0)
                {
                    stream.write(buffer, 0, n);
                    written += n;
                    if (progress != null) { progress.onProgress(written, total); }
                }
                stream.flush();
            }
            finally { stream.close(); }
        }
        catch (Exception failed)
        {
            try { out.delete(); } catch (Throwable ignored) {}
            throw failed;
        }
        finally { out.close(); }
        return target;
    }

    /** Stream a file's bytes; closing the stream closes the connection too. */
    public static InputStream open(String url) throws Exception
    {
        FileConnection file = (FileConnection) Connector.open(url,
                Connector.READ);
        try
        {
            if (!file.exists()) { throw new Exception("file no longer exists"); }
            return new FileStream(file, file.openInputStream());
        }
        catch (Exception failed)
        {
            try { file.close(); } catch (Throwable ignored) {}
            throw failed;
        }
    }

    private static final class FileStream extends InputStream
    {
        private final FileConnection file;
        private InputStream in;

        FileStream(FileConnection file, InputStream in)
        {
            this.file = file;
            this.in = in;
        }

        public int read() throws java.io.IOException { return in.read(); }

        public int read(byte[] b, int off, int len) throws java.io.IOException
        {
            return in.read(b, off, len);
        }

        public void close() throws java.io.IOException
        {
            InputStream open = in;
            in = null;
            try { if (open != null) { open.close(); } }
            finally { file.close(); }
        }
    }

    public static String fileName(String url)
    {
        int at = url == null ? -1 : url.lastIndexOf('/');
        return at < 0 ? String.valueOf(url) : url.substring(at + 1);
    }

    public static String humanSize(int bytes)
    {
        if (bytes <= 0) { return "unknown size"; }
        if (bytes < 1024) { return bytes + " B"; }
        if (bytes < 1024 * 1024) { return (bytes / 1024) + " KB"; }
        return ((bytes / (1024 * 1024)) + "." + ((bytes / 1024) % 1024 / 103))
                + " MB";
    }

    private static String sanitize(String name)
    {
        if (name == null) { return "file.bin"; }
        StringBuffer out = new StringBuffer();
        for (int i = 0; i < name.length() && out.length() < 60; i++)
        {
            char c = name.charAt(i);
            if (c == '/' || c == '\\' || c == ':' || c == '*' || c == '?'
                    || c == '"' || c == '<' || c == '>' || c == '|')
            {
                out.append('_');
            }
            else { out.append(c); }
        }
        return out.length() == 0 ? "file.bin" : out.toString();
    }

    private static String join(String dirUrl, String name)
    {
        return dirUrl.endsWith("/") ? dirUrl + name : dirUrl + "/" + name;
    }

    private static String property(String key)
    {
        try { return System.getProperty(key); }
        catch (Throwable ignored) { return null; }
    }
}
