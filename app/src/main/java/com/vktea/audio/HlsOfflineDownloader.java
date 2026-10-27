package com.vktea.audio;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import okhttp3.HttpUrl;

/** Builds a closed, finite HLS snapshot. Network URLs never enter the saved manifest. */
final class HlsOfflineDownloader {
    interface Fetcher { long fetch(String url, File destination, long remaining) throws Exception; }
    interface Active { boolean isActive(); }
    private static final int MAX_MANIFEST = 1024 * 1024, MAX_OBJECTS = 2048;
    private static final Pattern URI = Pattern.compile("(?:^|,)\\s*URI\\s*=\\s*\"([^\"]+)\"");
    private final Fetcher fetcher;
    private final Active active;
    private final File dir;
    private final long limit;
    private long used;
    private int count;
    private final Map<String, String> files = new HashMap<>();
    private final List<String> index = new ArrayList<>();

    HlsOfflineDownloader(Fetcher fetcher, Active active, File dir, long limit) {
        this.fetcher = fetcher; this.active = active; this.dir = dir; this.limit = limit;
    }

    void download(String url, File initial) throws Exception {
        String base = checkUrl(url);
        String manifest = readManifest(initial);
        initial.delete();
        for (int depth = 0; ; depth++) {
            if (depth > 2) throw new IOException("nested playlist");
            String[] lines = manifest.split("\\r?\\n", -1);
            if (lines.length == 0 || !lines[0].trim().equals("#EXTM3U")) throw new IOException("manifest");
            boolean master = false;
            for (String line : lines) if (line.trim().startsWith("#EXT-X-STREAM-INF:")) master = true;
            if (!master) { writeMedia(base, lines); return; }
            String variant = null;
            boolean waiting = false;
            for (String raw : lines) {
                String line = raw.trim();
                if (line.startsWith("#EXT-X-MEDIA:") || line.startsWith("#EXT-X-I-FRAME-STREAM-INF:")
                        || line.startsWith("#EXT-X-SESSION-KEY:") || line.startsWith("#EXT-X-DEFINE:")) throw new IOException("unsupported master");
                if (line.startsWith("#EXT-X-STREAM-INF:")) {
                    if (waiting || line.contains("AUDIO=") || line.contains("VIDEO=") || line.contains("SUBTITLES=")) throw new IOException("unsupported rendition");
                    waiting = true;
                } else if (!line.isEmpty() && !line.startsWith("#")) {
                    if (!waiting) throw new IOException("unexpected URI");
                    if (variant == null) variant = resolve(base, line);
                    waiting = false;
                } else if (line.startsWith("#") && line.contains("URI=")) throw new IOException("unsupported URI");
            }
            if (waiting || variant == null) throw new IOException("missing variant");
            File next = new File(dir, "playlist.part");
            long length = fetch(variant, next, Math.min(MAX_MANIFEST, limit - used));
            used += length;
            manifest = readManifest(next); next.delete(); base = variant;
        }
    }

    private void writeMedia(String base, String[] lines) throws Exception {
        boolean end = false, segment = false, expectSegment = false;
        boolean encrypted = false, explicitIv = false;
        String pendingRange = null, previousResource = null;
        long previousEnd = 0;
        StringBuilder out = new StringBuilder();
        for (String raw : lines) {
            String line = raw.trim();
            if (line.isEmpty()) continue;
            if (line.equals("#EXT-X-ENDLIST")) end = true;
            if (line.startsWith("#EXTINF:")) {
                if (expectSegment || end) throw new IOException("segment");
                expectSegment = true;
                int comma = line.indexOf(',');
                if (comma < 0) throw new IOException("duration");
                line = line.substring(0, comma + 1); // Do not retain arbitrary remote titles.
            }
            if (line.startsWith("#EXT-X-BYTERANGE:")) {
                if (!expectSegment || pendingRange != null) throw new IOException("range");
                pendingRange = line.substring(line.indexOf(':') + 1);
            }
            if (!line.startsWith("#")) {
                if (!expectSegment || end) throw new IOException("unexpected URI");
                line = resource(base, line, false);
                if (pendingRange != null) {
                    String[] range = pendingRange.split("@", -1);
                    if (range.length > 2) throw new IOException("range");
                    long length = Long.parseLong(range[0]);
                    long offset = range.length == 2 ? Long.parseLong(range[1])
                            : line.equals(previousResource) ? previousEnd : -1;
                    previousEnd = checkedEnd(offset, length, new File(dir, line).length());
                    previousResource = line; pendingRange = null;
                } else { previousResource = null; previousEnd = 0; }
                expectSegment = false; segment = true;
            } else if (line.startsWith("#EXT-X-KEY:")) {
                String attrs = line.substring(line.indexOf(':') + 1);
                String method = attribute(attrs, "METHOD"), format = attribute(attrs, "KEYFORMAT");
                if (format != null && !format.equals("identity")) throw new IOException("DRM");
                if (attribute(attrs, "KEYFORMATVERSIONS") != null) throw new IOException("unsupported key format");
                if ("NONE".equals(method)) {
                    if (attribute(attrs, "URI") != null) throw new IOException("key");
                    encrypted = false; explicitIv = false;
                } else {
                    if (!"AES-128".equals(method)) throw new IOException("unsupported encryption");
                    String iv = attribute(attrs, "IV");
                    if (iv != null && !iv.matches("0[xX][0-9a-fA-F]{1,32}")) throw new IOException("invalid IV");
                    encrypted = true; explicitIv = iv != null;
                    line = replaceUri(base, line, true);
                }
            } else if (line.startsWith("#EXT-X-MAP:")) {
                if (encrypted && !explicitIv) throw new IOException("encrypted map requires IV");
                line = replaceUri(base, line, false);
                Matcher range = Pattern.compile("(?:^|,)BYTERANGE=\"([0-9]+)@([0-9]+)\"").matcher(line.substring(line.indexOf(':') + 1));
                if (line.contains("BYTERANGE=")) {
                    if (!range.find()) throw new IOException("map range");
                    Matcher local = URI.matcher(line.substring(line.indexOf(':') + 1));
                    if (!local.find()) throw new IOException("map URI");
                    checkedEnd(Long.parseLong(range.group(2)), Long.parseLong(range.group(1)), new File(dir, local.group(1)).length());
                }
            } else if (line.startsWith("#") && !allowedMediaTag(line)) {
                throw new IOException("unsupported tag");
            }
            if (line.contains("://") || line.contains("{$")) throw new IOException("remote URI");
            out.append(line).append('\n');
        }
        if (!end || !segment || expectSegment) throw new IOException("not complete VOD");
        byte[] data = out.toString().getBytes(StandardCharsets.UTF_8);
        if (data.length > MAX_MANIFEST || used + data.length > limit) throw new IOException("limit");
        write(new File(dir, "index.m3u8"), data); used += data.length;
        index.add("index.m3u8 " + data.length);
        byte[] marker = String.join("\n", index).getBytes(StandardCharsets.UTF_8);
        if (used + marker.length > limit) throw new IOException("limit");
        write(new File(dir, "complete"), marker);
    }

    private static String attribute(String attrs, String name) throws IOException {
        Matcher matcher = Pattern.compile("(?:^|,)\\s*" + name + "\\s*=\\s*(\"[^\"]*\"|[^,]*)").matcher(attrs);
        if (!matcher.find()) return null;
        String value = matcher.group(1).trim();
        if (matcher.find()) throw new IOException("duplicate attribute");
        return value.startsWith("\"") ? value.substring(1, value.length() - 1) : value;
    }
    private static boolean allowedMediaTag(String line) {
        return line.equals("#EXTM3U") || line.startsWith("#EXTINF:")
                || line.startsWith("#EXT-X-VERSION:") || line.startsWith("#EXT-X-TARGETDURATION:")
                || line.startsWith("#EXT-X-MEDIA-SEQUENCE:") || line.startsWith("#EXT-X-DISCONTINUITY-SEQUENCE:")
                || line.equals("#EXT-X-DISCONTINUITY") || line.startsWith("#EXT-X-PLAYLIST-TYPE:")
                || line.equals("#EXT-X-ENDLIST") || line.startsWith("#EXT-X-BYTERANGE:")
                || line.startsWith("#EXT-X-PROGRAM-DATE-TIME:") || line.equals("#EXT-X-INDEPENDENT-SEGMENTS")
                || line.startsWith("#EXT-X-START:");
    }
    private static long checkedEnd(long offset, long length, long size) throws IOException {
        if (offset < 0 || length <= 0 || offset > size || length > size - offset) throw new IOException("invalid range");
        return offset + length;
    }
    private String replaceUri(String base, String line, boolean key) throws Exception {
        int colon = line.indexOf(':');
        String attrs = line.substring(colon + 1);
        Matcher m = URI.matcher(attrs);
        if (!m.find() || m.find()) throw new IOException("URI attribute");
        m.reset(); m.find();
        String name = resource(base, m.group(1), key);
        return line.substring(0, colon + 1) + attrs.substring(0, m.start(1)) + name + attrs.substring(m.end(1));
    }
    private String resource(String base, String ref, boolean key) throws Exception {
        String url = resolve(base, ref);
        String existing = files.get(url);
        if (existing != null) {
            if (key && new File(dir, existing).length() != 16) throw new IOException("key length");
            return existing;
        }
        if (++count > MAX_OBJECTS) throw new IOException("object limit");
        String name = String.format(Locale.ROOT, "object-%04d.bin", count);
        File dest = new File(dir, name);
        long size = fetch(url, dest, limit - used);
        if (size <= 0 || (key && size != 16)) throw new IOException("empty object or key length");
        used += size; files.put(url, name); index.add(name + " " + size);
        return name;
    }
    private long fetch(String url, File dest, long remaining) throws Exception {
        if (!active.isActive() || remaining <= 0) throw new IOException("cancelled or limit");
        long n = fetcher.fetch(url, dest, remaining);
        if (!active.isActive() || n <= 0 || n > remaining || !dest.isFile() || dest.length() != n) throw new IOException("incomplete");
        return n;
    }
    private static String checkUrl(String url) throws IOException {
        HttpUrl parsed = HttpUrl.parse(url);
        if (parsed == null || !parsed.isHttps() || !parsed.username().isEmpty() || !parsed.password().isEmpty()) throw new IOException("invalid URL");
        return parsed.toString();
    }
    private static String resolve(String base, String ref) throws IOException {
        HttpUrl origin = HttpUrl.parse(base);
        HttpUrl url = origin == null ? null : origin.resolve(ref);
        if (url == null || ref.contains("{$")) throw new IOException("invalid reference");
        return checkUrl(url.toString());
    }
    private static String readManifest(File file) throws IOException {
        if (!file.isFile() || file.length() <= 0 || file.length() > MAX_MANIFEST) throw new IOException("manifest size");
        byte[] bytes = new byte[(int) file.length()];
        try (FileInputStream in = new FileInputStream(file)) {
            int offset = 0, n; while (offset < bytes.length && (n = in.read(bytes, offset, bytes.length - offset)) > 0) offset += n;
            if (offset != bytes.length) throw new IOException("manifest read");
        }
        String text = new String(bytes, StandardCharsets.UTF_8);
        if (text.startsWith("\ufeff")) text = text.substring(1);
        if (text.indexOf('\0') >= 0) throw new IOException("manifest encoding");
        return text;
    }
    private static void write(File file, byte[] data) throws IOException {
        try (FileOutputStream out = new FileOutputStream(file)) { out.write(data); out.getFD().sync(); }
    }
    static boolean valid(File dir) {
        try {
            File marker = new File(dir, "complete");
            if (!marker.isFile() || marker.length() == 0 || marker.length() > 128 * 1024) return false;
            String list = readManifest(marker); boolean manifest = false; int count = 0;
            for (String line : list.split("\\n")) {
                String[] parts = line.split(" ");
                if (parts.length != 2 || !(parts[0].equals("index.m3u8") || parts[0].matches("object-[0-9]{4}\\.bin"))) return false;
                File file = new File(dir, parts[0]); long size = Long.parseLong(parts[1]);
                if (size <= 0 || !file.isFile() || file.length() != size || ++count > MAX_OBJECTS + 1) return false;
                if (parts[0].equals("index.m3u8")) manifest = true;
            }
            return manifest && count > 1;
        } catch (Exception ignored) { return false; }
    }
}
