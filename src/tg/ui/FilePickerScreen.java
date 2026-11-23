package tg.ui;

import javax.microedition.lcdui.List;

import tg.plat.FileStore;

/**
 * A folder browser over the phone's own storage.
 *
 * A List rather than a Canvas on purpose: the AMS already knows how to draw and
 * scroll a list, and a picker is used once per send. The picker owns no policy -
 * it lists a folder, walks into subfolders and reports the selected file URL;
 * what the file is for belongs to the screen that opened it.
 */
public final class FilePickerScreen extends List
{
    public interface Listener
    {
        void onFileSelected(String url, String name, int size);
    }

    private static final String UP = "..";

    private final Listener listener;
    private String dirUrl;
    private FileStore.Entry[] entries = new FileStore.Entry[0];
    private String status = "";

    public FilePickerScreen(String startUrl, Listener listener)
    {
        super("Files", List.IMPLICIT);
        this.listener = listener;
        this.dirUrl = startUrl;
    }

    public String dirUrl() { return dirUrl; }
    public String status() { return status; }

    /** Re-read the current folder; false when it could not be listed. */
    public boolean reload()
    {
        deleteAll();
        FileStore.Entry[] listed;
        try
        {
            listed = FileStore.list(dirUrl);
        }
        catch (Throwable failed)
        {
            entries = new FileStore.Entry[0];
            status = "cannot open " + dirUrl;
            append(status, null);
            return false;
        }
        entries = new FileStore.Entry[listed.length + 1];
        entries[0] = null;                   // the ".." row
        System.arraycopy(listed, 0, entries, 1, listed.length);
        append(UP, null);
        for (int i = 0; i < listed.length; i++)
        {
            String label = listed[i].name;
            if (listed[i].directory) { label = label + "/"; }
            else if (listed[i].size > 0)
            {
                label = label + "  " + FileStore.humanSize(listed[i].size);
            }
            append(label, null);
        }
        if (listed.length == 0)
        {
            append("(empty folder)", null);
        }
        status = listed.length + " item(s)";
        return true;
    }

    /** Walk one level up. Returns false at a filesystem root. */
    public boolean up()
    {
        String trimmed = dirUrl;
        if (trimmed.endsWith("/")) { trimmed = trimmed.substring(0, trimmed.length() - 1); }
        int slash = trimmed.lastIndexOf('/');
        // file:///C/ keeps its single drive slash: stopping at "file:///" would
        // list the roots as one virtual folder, which exists on some handsets
        // and hangs on others.
        if (slash <= "file://".length()) { return false; }
        dirUrl = trimmed.substring(0, slash + 1);
        reload();
        return true;
    }

    /**
     * Act on the highlighted row.
     *
     * @return true when a folder was entered (the screen changed itself),
     *         false when a file was handed to the listener.
     */
    public boolean activate()
    {
        int index = getSelectedIndex();
        if (index < 0) { return false; }
        if (index == 0)
        {
            up();
            return true;
        }
        if (index >= entries.length || entries[index] == null)
        {
            return false;
        }
        FileStore.Entry entry = entries[index];
        if (entry.directory)
        {
            dirUrl = entry.url.endsWith("/") ? entry.url : entry.url + "/";
            reload();
            return true;
        }
        if (listener != null)
        {
            listener.onFileSelected(entry.url, entry.name, entry.size);
        }
        return false;
    }
}
