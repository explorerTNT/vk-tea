package com.vktea.feed;

import android.os.Parcel;
import android.os.Parcelable;

import org.json.JSONObject;

/** A VK photo identity is response metadata; CDN filenames never establish ownership or permissions. */
public final class ViewerPhoto implements Parcelable {
    public static final String EXTRA_PHOTOS = "viewer_photos";
    public final String url, accessKey;
    public final long id, ownerId;
    public final int likes, comments, reposts;
    public final boolean liked, canLike, canComment, canRepost;
    public final String ownerName, ownerPhoto, text;
    public final long date;
    private static final String PARCEL_VERSION = "com.vktea.feed.ViewerPhoto:v2";

    public ViewerPhoto(String url, long id, long ownerId, String accessKey, int likes, int comments,
                       int reposts, boolean liked, boolean canLike, boolean canComment, boolean canRepost) {
        this(url, id, ownerId, accessKey, likes, comments, reposts, liked, canLike, canComment, canRepost,
                "", "", 0, "");
    }

    public ViewerPhoto(String url, long id, long ownerId, String accessKey, int likes, int comments,
                       int reposts, boolean liked, boolean canLike, boolean canComment, boolean canRepost,
                       String ownerName, String ownerPhoto, long date, String text) {
        this.url = url == null ? "" : url;
        this.id = id;
        this.ownerId = ownerId;
        this.accessKey = accessKey == null ? "" : accessKey;
        this.likes = Math.max(0, likes);
        this.comments = Math.max(0, comments);
        this.reposts = Math.max(0, reposts);
        this.liked = liked;
        this.canLike = hasIdentity() && canLike;
        this.canComment = hasIdentity() && canComment;
        this.canRepost = hasIdentity() && canRepost;
        this.ownerName = ownerName == null ? "" : ownerName;
        this.ownerPhoto = ownerPhoto == null ? "" : ownerPhoto;
        this.date = Math.max(0, date);
        this.text = text == null ? "" : text;
    }

    public boolean hasIdentity() { return id > 0 && ownerId != 0; }

    public static ViewerPhoto fromUrl(String url) {
        return new ViewerPhoto(url, 0, 0, "", 0, 0, 0, false, false, false, false);
    }

    public static ViewerPhoto parse(JSONObject photo) {
        if (photo == null) return fromUrl("");
        JSONObject likes = photo.optJSONObject("likes"), comments = photo.optJSONObject("comments");
        JSONObject reposts = photo.optJSONObject("reposts");
        return new ViewerPhoto(PostPhoto.bestUrl(photo), photo.optLong("id", photo.optLong("pid", 0)),
                photo.optLong("owner_id", 0), string(photo, "access_key"), count(likes), count(comments), count(reposts),
                flag(likes, "user_likes", false), flag(photo, "can_like", flag(likes, "can_like", false)),
                flag(photo, "can_comment", flag(comments, "can_post", false)), flag(photo, "can_repost", false),
                string(photo, "owner_name"), string(photo, "owner_photo"),
                photo.optLong("date", photo.optLong("created", 0)), string(photo, "text"));
    }

    private static int count(JSONObject value) {
        return value == null ? 0 : (int) Math.min(Integer.MAX_VALUE, Math.max(0, value.optLong("count", 0)));
    }
    private static String string(JSONObject value, String key) {
        Object field = value.opt(key);
        return field instanceof String ? (String) field : "";
    }
    private static boolean flag(JSONObject value, String key, boolean fallback) {
        Object field = value == null ? null : value.opt(key);
        if (field instanceof Boolean) return (Boolean) field;
        if (field instanceof Number) return ((Number) field).intValue() == 1;
        return fallback;
    }

    @Override public int describeContents() { return 0; }
    @Override public void writeToParcel(Parcel out, int flags) {
        out.writeString(PARCEL_VERSION);
        out.writeString(url); out.writeLong(id); out.writeLong(ownerId); out.writeString(accessKey);
        out.writeInt(likes); out.writeInt(comments); out.writeInt(reposts);
        out.writeByte((byte) (liked ? 1 : 0)); out.writeByte((byte) (canLike ? 1 : 0));
        out.writeByte((byte) (canComment ? 1 : 0)); out.writeByte((byte) (canRepost ? 1 : 0));
        out.writeString(ownerName); out.writeString(ownerPhoto); out.writeLong(date); out.writeString(text);
    }
    public static final Creator<ViewerPhoto> CREATOR = new Creator<ViewerPhoto>() {
        @Override public ViewerPhoto createFromParcel(Parcel in) {
            String first = in.readString();
            boolean enriched = PARCEL_VERSION.equals(first);
            String url = enriched ? in.readString() : first;
            long id = in.readLong(), owner = in.readLong();
            String access = in.readString(); int likes = in.readInt(), comments = in.readInt(), reposts = in.readInt();
            boolean liked = in.readByte() != 0, canLike = in.readByte() != 0;
            boolean canComment = in.readByte() != 0, canRepost = in.readByte() != 0;
            return new ViewerPhoto(url, id, owner, access, likes, comments, reposts, liked, canLike, canComment, canRepost,
                    enriched ? in.readString() : "", enriched ? in.readString() : "",
                    enriched ? in.readLong() : 0, enriched ? in.readString() : "");
        }
        @Override public ViewerPhoto[] newArray(int size) { return new ViewerPhoto[size]; }
    };
}
