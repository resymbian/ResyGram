package tg.api;

/** Identifies one finished upload so messages.sendMedia can reference it. */
public final class UploadedFile
{
    public final long id;
    public final int parts;
    public final String name;
    public final String mimeType;
    public final boolean big;

    public UploadedFile(long id, int parts, String name, String mimeType,
                        boolean big)
    {
        this.id = id;
        this.parts = parts;
        this.name = name == null ? "file" : name;
        this.mimeType = mimeType == null ? "application/octet-stream" : mimeType;
        this.big = big;
    }
}
