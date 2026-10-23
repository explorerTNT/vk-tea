package com.vktea.feed;

import org.json.JSONArray;
import org.json.JSONObject;

/** Selects an original-aspect photo source without rewriting its CDN parameters. */
public final class PostPhoto {
    private static final String[] LEGACY = {"photo_2560", "photo_1280", "photo_807", "photo_604",
            "src_xxbig", "src_xbig", "src_big", "photo_130", "photo_75", "src", "url"};

    private PostPhoto() {}

    /** Returns an empty string when no usable string URL is supplied. */
    public static String bestUrl(JSONObject photo) {
        if (photo == null) return "";
        Candidate best = null;
        JSONArray sizes = photo.optJSONArray("sizes");
        if (sizes != null) for (int index = 0; index < sizes.length(); index++) {
            JSONObject size = sizes.optJSONObject(index);
            if (size == null) continue;
            String url = value(size, "url");
            if (url.isEmpty()) url = value(size, "src");
            if (url.isEmpty()) continue;
            String type = value(size, "type");
            boolean cropped = type.length() == 1 && "opqr".indexOf(type.charAt(0)) >= 0;
            int rank = type.length() == 1 ? (cropped ? "opqr" : "smxyzw").indexOf(type.charAt(0)) + 1 : 0;
            long width = dimension(size, "width");
            long height = dimension(size, "height");
            Candidate candidate = new Candidate(url, width * height, rank, cropped);
            if (best == null || candidate.betterThan(best)) best = candidate;
        }
        if (best != null && !best.cropped) return best.url;
        // Legacy photo_N/src fields describe original-aspect sources, so prefer
        // them to a response containing only the cropped o/p/q/r variants.
        for (String key : LEGACY) {
            String url = value(photo, key);
            if (!url.isEmpty()) return url;
        }
        return best == null ? "" : best.url;
    }

    private static String value(JSONObject object, String key) {
        Object value = object.opt(key);
        return value instanceof String && !((String) value).trim().isEmpty() ? (String) value : "";
    }

    private static long dimension(JSONObject object, String key) {
        return Math.min(Integer.MAX_VALUE, Math.max(0, object.optLong(key, 0)));
    }

    private static final class Candidate {
        final String url;
        final long area;
        final int rank;
        final boolean cropped;

        Candidate(String url, long area, int rank, boolean cropped) {
            this.url = url; this.area = area; this.rank = rank; this.cropped = cropped;
        }

        boolean betterThan(Candidate other) {
            if (cropped != other.cropped) return !cropped;
            // Prefer measured variants; type rank orders missing-dimension
            // variants. Keeping these groups separate makes selection independent
            // of the API array order even when some dimensions are absent.
            if ((area > 0) != (other.area > 0)) return area > 0;
            if (area != other.area) return area > other.area;
            if (rank != other.rank) return rank > other.rank;
            return url.compareTo(other.url) < 0;
        }
    }
}
