package tgtest;

import tg.plat.VideoStreamer;

/** Loopback video URL and HTTP range parsing, without opening a socket. */
public final class VideoStreamerTest implements Test
{
    public String name() { return "video-streamer"; }

    public void run() throws Exception
    {
        Assert.equal("loopback URL",
                "http://127.0.0.1:4712/resygram.mp4",
                VideoStreamer.urlForPort(4712, "video/mp4"));
        Assert.equal("3gp path", "/resygram.3gp",
                VideoStreamer.pathFor("video/3gpp"));
        Assert.equal("no range", 0L, VideoStreamer.rangeStart(null));
        Assert.equal("range from zero", 0L,
                VideoStreamer.rangeStart("bytes=0-4095"));
        Assert.equal("range from a seek", 32768L,
                VideoStreamer.rangeStart("bytes=32768-"));
        Assert.equal("bad range is ignored", 0L,
                VideoStreamer.rangeStart("bytes=abc-4095"));

        String request = "GET /resygram.mp4 HTTP/1.1\r\n"
                + "Host: 127.0.0.1:4712\r\n"
                + "Range: bytes=65536-\r\n"
                + "\r\n";
        Assert.equal("header lookup", "bytes=65536-",
                VideoStreamer.header(request, "Range:"));
        Assert.equal("missing header", null,
                VideoStreamer.header(request, "Referer:"));
        Assert.equal("header range", 65536L,
                VideoStreamer.rangeStart(VideoStreamer.header(request,
                        "Range:")));
    }
}
