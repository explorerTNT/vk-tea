package com.vktea.videos;

import java.io.InputStream;
import java.util.UUID;
import okhttp3.HttpUrl;
import org.json.JSONObject;

/** Orchestrates exactly one save and one request per chunk; it never retries an uncertain operation. */
final class VideoUploadProtocol {
    static final int CHUNK_SIZE = 1024 * 1024;
    interface Source { InputStream open() throws Exception; }
    interface Transport {
        JSONObject save(String title, String description) throws Exception;
        Reply send(HttpUrl url, String session, String filename, long start, long end, long total, byte[] body) throws Exception;
        boolean valid();
    }
    static final class Reply {
        final int status; final String range; final JSONObject body;
        Reply(int status, String range, JSONObject body) { this.status = status; this.range = range; this.body = body; }
    }
    static void upload(Source source, long length, String title, String description, String filename, Transport transport) throws Exception {
        if (length <= 0) throw new VideoWriter.Failure("file");
        JSONObject data = transport.save(title, description);
        if (data == null || data.optLong("video_id") <= 0) throw new VideoWriter.Failure("uncertain");
        HttpUrl url = VideoWriter.safeUploadUrl(data.optString("upload_url", ""));
        if (url == null) throw new VideoWriter.Failure("uncertain");
        long videoId = data.optLong("video_id"), offset = 0;
        String session = UUID.randomUUID().toString();
        try (InputStream input = source.open()) {
            if (input == null) throw new VideoWriter.Failure("uncertain");
            while (offset < length) {
                if (!transport.valid()) throw new VideoWriter.Failure("uncertain");
                int size = (int)Math.min(CHUNK_SIZE, length - offset); byte[] bytes = new byte[size];
                int used = 0;
                while (used < size) { int n = input.read(bytes, used, size - used); if (n < 0) throw new VideoWriter.Failure("uncertain"); if (n == 0) continue; used += n; }
                long end = offset + size - 1;
                Reply response = transport.send(url, session, filename, offset, end, length, bytes);
                if (response == null) throw new VideoWriter.Failure("uncertain");
                if (end == length - 1) {
                    if (response.status != 200 || !VideoWriter.confirmedUpload(response.body, videoId, length)) throw new VideoWriter.Failure("uncertain");
                } else if (response.status != 201 || !VideoWriter.confirmedRange(response.range, end)) throw new VideoWriter.Failure("uncertain");
                offset += size;
            }
        }
    }
}
