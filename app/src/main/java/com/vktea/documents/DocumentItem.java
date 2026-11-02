package com.vktea.documents;

import org.json.JSONObject;
import java.util.Locale;
import okhttp3.HttpUrl;

/** Read-only metadata returned by VK; no document content is fetched by the list. */
public final class DocumentItem {
    public final long id, ownerId, size;
    public final String title, extension, url;

    DocumentItem(long id, long ownerId, String title, String extension, long size, String url) {
        this.id = id; this.ownerId = ownerId;
        this.title = title == null || title.trim().isEmpty() ? "Документ" : title.trim();
        this.extension = extension == null ? "" : extension.trim();
        this.size = Math.max(0, size); this.url = url == null ? "" : url;
    }

    static DocumentItem parse(JSONObject row) {
        if (row == null || row.optLong("id") <= 0 || row.optLong("owner_id") == 0
                || row.optBoolean("is_deleted", false) || row.optInt("is_deleted", 0) != 0
                || row.optBoolean("content_restricted", false) || row.optInt("content_restricted", 0) != 0) return null;
        return new DocumentItem(row.optLong("id"), row.optLong("owner_id"),
                row.optString("title", ""), row.optString("ext", ""), row.optLong("size", 0), row.optString("url", ""));
    }

    /** Browser targets are restricted to VK's known HTTPS domains and the default port. */
    public static boolean isSafeUrl(String value) {
        try {
            HttpUrl uri = HttpUrl.parse(value == null ? "" : value);
            if (uri == null || !uri.isHttps() || uri.port() != 443 || !uri.username().isEmpty()
                    || !uri.password().isEmpty()) return false;
            String host = uri.host().toLowerCase(Locale.ROOT);
            return host.equals("vk.com") || host.endsWith(".vk.com") || host.equals("vk.ru")
                    || host.endsWith(".vk.ru") || host.equals("userapi.com") || host.endsWith(".userapi.com");
        } catch (RuntimeException ignored) { return false; }
    }

    /** Unknown and executable formats are not opened from this screen. */
    public boolean canOpen() {
        if (!isSafeUrl(url)) return false;
        switch (extension.toLowerCase(Locale.ROOT)) {
            case "pdf": case "txt": case "rtf": case "doc": case "docx": case "odt":
            case "xls": case "xlsx": case "ods": case "ppt": case "pptx": case "odp":
            case "jpg": case "jpeg": case "png": case "gif": case "webp":
            case "mp3": case "ogg": case "mp4": case "webm": return true;
            default: return false;
        }
    }
}
