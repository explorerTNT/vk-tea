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

    public ViewerPhoto(String url, long id, long ownerId, String accessKey, int likes, int comments,
                       int reposts, boolean liked, boolean canLike, boolean canComment, boolean canRepost) {
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
                flag(photo, "can_comment", flag(comments, "can_post", false)), flag(photo, "can_repost", false));
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

    private ViewerPhoto(Parcel in) {
        this(in.readString(), in.readLong(), in.readLong(), in.readString(), in.readInt(), in.readInt(),
                in.readInt(), in.readByte() != 0, in.readByte() != 0, in.readByte() != 0, in.readByte() != 0);
    }
    @Override public int describeContents() { return 0; }
    @Override public void writeToParcel(Parcel out, int flags) {
        out.writeString(url); out.writeLong(id); out.writeLong(ownerId); out.writeString(accessKey);
        out.writeInt(likes); out.writeInt(comments); out.writeInt(reposts);
        out.writeByte((byte) (liked ? 1 : 0)); out.writeByte((byte) (canLike ? 1 : 0));
        out.writeByte((byte) (canComment ? 1 : 0)); out.writeByte((byte) (canRepost ? 1 : 0));
    }
    public static final Creator<ViewerPhoto> CREATOR = new Creator<ViewerPhoto>() {
        @Override public ViewerPhoto createFromParcel(Parcel in) { return new ViewerPhoto(in); }
        @Override public ViewerPhoto[] newArray(int size) { return new ViewerPhoto[size]; }
    };
}
