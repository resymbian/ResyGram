package tg.api;

import tg.tl.TlObj;

/**
 * Stable fields needed by inputDocumentFileLocation and to guess a file name.
 *
 * Same shape as {@link PhotoRef}: what a document needs to be downloaded again
 * later, without keeping the whole Document around. The file_reference is the
 * part Telegram rotates, so a failed download has to be resolved from a fresh
 * message instead of retried forever.
 */
public final class DocumentRef
{
    public long id;
    public long accessHash;
    public byte[] fileReference;
    public int dcId;
    public long size;
    public String mimeType = "";
    public String fileName = "";

    static DocumentRef from(TlObj document)
    {
        if (document == null || document.id != Api.DOCUMENT) { return null; }
        DocumentRef out = new DocumentRef();
        out.id = document.num(Api.F_DOCUMENT__ID);
        out.accessHash = document.num(Api.F_DOCUMENT__ACCESS_HASH);
        out.fileReference = document.bytes(Api.F_DOCUMENT__FILE_REFERENCE);
        out.dcId = document.intAt(Api.F_DOCUMENT__DC_ID);
        out.size = document.num(Api.F_DOCUMENT__SIZE);
        out.mimeType = document.strOrEmpty(Api.F_DOCUMENT__MIME_TYPE);
        out.fileName = name(document);
        return out;
    }

    /** The filename attribute, or a name derived from the MIME type. */
    private static String name(TlObj document)
    {
        TlObj[] attrs = document.vec(Api.F_DOCUMENT__ATTRIBUTES);
        for (int i = 0; i < attrs.length; i++)
        {
            if (attrs[i] != null
                    && attrs[i].id == Api.DOCUMENT_ATTRIBUTE_FILENAME)
            {
                String value = attrs[i].strOrEmpty(
                        Api.F_DOCUMENT_ATTRIBUTE_FILENAME__FILE_NAME);
                if (value.length() > 0) { return value; }
            }
        }
        String mime = document.strOrEmpty(Api.F_DOCUMENT__MIME_TYPE);
        if (mime.startsWith("video/")) { return "video.mp4"; }
        if (mime.startsWith("audio/")) { return "audio.mp3"; }
        if (mime.startsWith("image/")) { return "image.jpg"; }
        if ("application/pdf".equals(mime)) { return "document.pdf"; }
        return "file.bin";
    }

    public boolean downloadable() { return id != 0 && size > 0; }
}
