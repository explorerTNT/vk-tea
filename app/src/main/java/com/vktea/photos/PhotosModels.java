package com.vktea.photos;

import com.vktea.feed.ViewerPhoto;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** Display rows for the photos section; URLs keep VK signing parameters intact. */
final class PhotosModels {

    static final class PhotoEntry {
        final String url;
        final ViewerPhoto meta;
        PhotoEntry(String url, ViewerPhoto meta) {
            this.url = url;
            this.meta = meta;
        }
    }

    static final class AlbumEntry {
        final long id;
        final String title;
        final int size;
        final String coverUrl;
        AlbumEntry(long id, String title, int size, String coverUrl) {
            this.id = id;
            this.title = title == null || title.trim().isEmpty() ? "Альбом" : title;
            this.size = Math.max(0, size);
            this.coverUrl = coverUrl == null ? "" : coverUrl;
        }
    }

    /** Picks the largest size that keeps original proportions, mirroring the chat photo selector. */
    static String bestUrl(JSONObject photo) {
        if (photo == null) return "";
        JSONArray sizes = photo.optJSONArray("sizes");
        String best = "";
        long bestArea = 0;
        if (sizes != null) for (int i = 0; i < sizes.length(); i++) {
            JSONObject size = sizes.optJSONObject(i);
            if (size == null) continue;
            String url = size.optString("url", size.optString("src", ""));
            if (url.isEmpty()) continue;
            String type = size.optString("type", "");
            if ("o".equals(type) || "p".equals(type) || "q".equals(type) || "r".equals(type)) continue;
            long area = (long) size.optInt("width", 1) * size.optInt("height", 1);
            if (area >= bestArea) {
                best = url;
                bestArea = area;
            }
        }
        if (!best.isEmpty()) return best;
        String[] legacy = {"photo_2560", "photo_1280", "photo_807", "photo_604", "photo_130", "src"};
        for (String key : legacy) {
            String url = photo.optString(key, "");
            if (!url.isEmpty()) return url;
        }
        return "";
    }

    static List<PhotoEntry> parsePhotos(JSONArray items) {
        if (items == null) return Collections.emptyList();
        List<PhotoEntry> photos = new ArrayList<>();
        for (int i = 0; i < items.length(); i++) {
            JSONObject photo = items.optJSONObject(i);
            if (photo == null) continue;
            String url = bestUrl(photo);
            if (url.isEmpty()) continue;
            photos.add(new PhotoEntry(url, ViewerPhoto.parse(photo)));
        }
        return photos;
    }

    static List<AlbumEntry> parseAlbums(JSONArray items) {
        if (items == null) return Collections.emptyList();
        List<AlbumEntry> albums = new ArrayList<>();
        for (int i = 0; i < items.length(); i++) {
            JSONObject album = items.optJSONObject(i);
            if (album == null) continue;
            JSONObject thumb = album.optJSONObject("thumb");
            String cover = thumb == null ? "" : bestUrl(thumb);
            if (cover.isEmpty()) cover = album.optString("thumb_256",
                    album.optString("thumb_128", ""));
            albums.add(new AlbumEntry(album.optLong("id", 0),
                    album.optString("title", ""), album.optInt("size", 0), cover));
        }
        return albums;
    }

    private PhotosModels() {}
}
