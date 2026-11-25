package tg.api;

import tg.tl.TlObj;

/** Flattened message-media classification used by the text-first UI. */
public final class Media
{
    public static final int PHOTO = 1;
    public static final int VIDEO = 2;
    public static final int ROUND_VIDEO = 3;
    public static final int ANIMATION = 4;
    public static final int STICKER = 5;
    public static final int VOICE = 6;
    public static final int AUDIO = 7;
    public static final int FILE = 8;
    public static final int LOCATION = 9;
    public static final int CONTACT = 10;
    public static final int POLL = 11;
    public static final int DICE = 12;
    public static final int GAME = 13;
    public static final int INVOICE = 14;
    public static final int STORY = 15;
    public static final int LINK = 16;
    public static final int UNKNOWN = 17;

    public int kind;
    public String label;
    public PhotoRef photo;
    public DocumentRef document;
    public Poll poll;

    public static Media from(TlObj obj)
    {
        if (obj == null) { return null; }
        Media out = new Media();
        if (obj.id == Api.MESSAGE_MEDIA_PHOTO)
        {
            out.kind = PHOTO;
            out.label = "[photo]";
            out.photo = PhotoRef.from(obj.obj(Api.F_MESSAGE_MEDIA_PHOTO__PHOTO));
            return out;
        }
        if (obj.id == Api.MESSAGE_MEDIA_DOCUMENT)
        {
            TlObj document = documentField(obj);
            TlObj[] alternatives = vecField(obj,
                    Api.F_MESSAGE_MEDIA_DOCUMENT__ALT_DOCUMENTS);
            if (obj.num(Api.F_MESSAGE_MEDIA_DOCUMENT__VOICE) != 0)
            {
                return documentMedia(VOICE, "[voice]", document, alternatives);
            }
            if (obj.num(Api.F_MESSAGE_MEDIA_DOCUMENT__ROUND) != 0)
            {
                return documentMedia(ROUND_VIDEO, "[round video]", document,
                        alternatives);
            }
            if (obj.num(Api.F_MESSAGE_MEDIA_DOCUMENT__VIDEO) != 0)
            {
                return documentMedia(VIDEO, "[video]", document, alternatives);
            }
            Media classified = document(document, alternatives);
            if (classified != null) { return classified; }
            for (int i = 0; i < alternatives.length; i++)
            {
                classified = document(alternatives[i], alternatives);
                if (classified != null) { return classified; }
            }
            Media file = simple(FILE, "[file]");
            file.document = DocumentRef.from(document);
            if (file.document != null && file.document.fileName.length() > 0)
            {
                file.label = "[" + file.document.fileName + "]";
            }
            return file;
        }
        if (obj.id == Api.MESSAGE_MEDIA_GEO || obj.id == Api.MESSAGE_MEDIA_GEO_LIVE
                || obj.id == Api.MESSAGE_MEDIA_VENUE)
        {
            return simple(LOCATION, "[location]");
        }
        if (obj.id == Api.MESSAGE_MEDIA_CONTACT) { return simple(CONTACT, "[contact]"); }
        if (obj.id == Api.MESSAGE_MEDIA_POLL)
        {
            Media pollMedia = simple(POLL, "[poll]");
            pollMedia.poll = Poll.from(
                    obj.obj(Api.F_MESSAGE_MEDIA_POLL__POLL),
                    obj.obj(Api.F_MESSAGE_MEDIA_POLL__RESULTS));
            return pollMedia;
        }
        if (obj.id == Api.MESSAGE_MEDIA_DICE) { return simple(DICE, "[dice]"); }
        if (obj.id == Api.MESSAGE_MEDIA_GAME) { return simple(GAME, "[game]"); }
        if (obj.id == Api.MESSAGE_MEDIA_INVOICE) { return simple(INVOICE, "[invoice]"); }
        if (obj.id == Api.MESSAGE_MEDIA_STORY) { return simple(STORY, "[story]"); }
        if (obj.id == Api.MESSAGE_MEDIA_WEB_PAGE) { return simple(LINK, "[link]"); }
        return simple(UNKNOWN, "[media]");
    }

    private static Media simple(int kind, String label)
    {
        Media out = new Media();
        out.kind = kind;
        out.label = label;
        return out;
    }

    /** Short wire fixtures may stop before alt_documents; treat that as empty. */
    private static TlObj[] vecField(TlObj obj, int field)
    {
        if (obj == null || obj.refs == null || field >= obj.refs.length)
        {
            return new TlObj[0];
        }
        return obj.vec(field);
    }

    /** Tolerates the short fixtures used by wire tests and partial TL objects. */
    private static TlObj documentField(TlObj media)
    {
        int field = Api.F_MESSAGE_MEDIA_DOCUMENT__DOCUMENT;
        Object[] refs = media.refs;
        if (refs == null || field >= refs.length) { return null; }
        return media.obj(field);
    }

    /**
     * A flagged voice/round/video still has to keep its DocumentRef. Dropping it
     * here made the tap handler think the message had no media at all, so the
     * only thing a video tap could do was open the reaction palette.
     */
    private static Media documentMedia(int kind, String label, TlObj document,
                                       TlObj[] alternatives)
    {
        Media out = simple(kind, label);
        DocumentRef main = DocumentRef.from(document);
        out.document = (kind == VIDEO || kind == ROUND_VIDEO)
                ? smallestVideo(main, alternatives) : main;
        return out;
    }

    private static Media document(TlObj document, TlObj[] alternatives)
    {
        if (document == null || document.id != Api.DOCUMENT) { return null; }
        Media out = classify(document);
        // Every classified document keeps its reference: a voice note, a video
        // and a plain file all download through upload.getFile, and the UI can
        // offer the same Open command for each of them.
        if (out != null)
        {
            out.document = (out.kind == VIDEO || out.kind == ROUND_VIDEO)
                    ? smallestVideo(DocumentRef.from(document), alternatives)
                    : DocumentRef.from(document);
        }
        return out;
    }

    /**
     * Telegram sometimes ships the same video in several formats. Pick the
     * smallest playable MP4/3GP variant: on an old handset that is the only
     * "low quality" control the wire format actually gives us.
     */
    private static DocumentRef smallestVideo(DocumentRef main,
                                             TlObj[] alternatives)
    {
        DocumentRef best = main;
        if (alternatives == null) { return best; }
        for (int i = 0; i < alternatives.length; i++)
        {
            TlObj alt = alternatives[i];
            if (alt == null || alt.id != Api.DOCUMENT) { continue; }
            String mime = alt.strOrEmpty(Api.F_DOCUMENT__MIME_TYPE);
            if (!isPhoneVideo(mime)) { continue; }
            DocumentRef candidate = DocumentRef.from(alt);
            if (candidate == null || candidate.size <= 0) { continue; }
            if (best == null || best.size <= 0 || candidate.size < best.size)
            {
                best = candidate;
            }
        }
        return best;
    }

    private static boolean isPhoneVideo(String mime)
    {
        if (mime == null) { return false; }
        String lower = mime.toLowerCase();
        return lower.startsWith("video/mp4")
                || lower.startsWith("video/3gpp")
                || lower.startsWith("video/quicktime");
    }

    private static Media classify(TlObj document)
    {
        TlObj[] attrs = document.vec(Api.F_DOCUMENT__ATTRIBUTES);
        for (int i = 0; i < attrs.length; i++)
        {
            if (attrs[i] == null) { continue; }
            if (attrs[i].id == Api.DOCUMENT_ATTRIBUTE_STICKER)
            {
                return simple(STICKER, "[sticker]");
            }
            if (attrs[i].id == Api.DOCUMENT_ATTRIBUTE_ANIMATED)
            {
                return simple(ANIMATION, "[animation]");
            }
            if (attrs[i].id == Api.DOCUMENT_ATTRIBUTE_VIDEO)
            {
                return simple(attrs[i].num(
                        Api.F_DOCUMENT_ATTRIBUTE_VIDEO__ROUND_MESSAGE) != 0
                        ? ROUND_VIDEO : VIDEO,
                        attrs[i].num(
                        Api.F_DOCUMENT_ATTRIBUTE_VIDEO__ROUND_MESSAGE) != 0
                        ? "[round video]" : "[video]");
            }
            if (attrs[i].id == Api.DOCUMENT_ATTRIBUTE_AUDIO)
            {
                return simple(attrs[i].num(
                        Api.F_DOCUMENT_ATTRIBUTE_AUDIO__VOICE) != 0
                        ? VOICE : AUDIO,
                        attrs[i].num(Api.F_DOCUMENT_ATTRIBUTE_AUDIO__VOICE) != 0
                        ? "[voice]" : "[audio]");
            }
        }
        String mime = document.strOrEmpty(Api.F_DOCUMENT__MIME_TYPE);
        if (mime.startsWith("video/")) { return simple(VIDEO, "[video]"); }
        if (mime.startsWith("audio/")) { return simple(AUDIO, "[audio]"); }
        if ("image/gif".equals(mime)) { return simple(ANIMATION, "[animation]"); }
        return null;
    }
}
