package com.vktea.share;

import android.os.Parcel;
import android.os.Parcelable;
import com.vktea.feed.ViewerPhoto;

/** Only a server-provided VK identity establishes a shareable attachment. URLs do not. */
public final class ShareAttachment implements Parcelable {
    public final String type, accessKey;
    public final long ownerId, id;
    public final boolean canRepost;
    public ShareAttachment(String type, long ownerId, long id, String accessKey, boolean canRepost) {
        this.type = type == null ? "" : type; this.ownerId = ownerId; this.id = id;
        this.accessKey = accessKey == null ? "" : accessKey; this.canRepost = hasIdentity() && canRepost;
    }
    public static ShareAttachment photo(ViewerPhoto photo) {
        return photo == null ? new ShareAttachment("photo", 0, 0, "", false)
                : new ShareAttachment("photo", photo.ownerId, photo.id, photo.accessKey, photo.canRepost);
    }
    public static ShareAttachment photo(long ownerId, long id, String accessKey, boolean canRepost) {
        return new ShareAttachment("photo", ownerId, id, accessKey, canRepost);
    }
    public static ShareAttachment post(long ownerId, long id, String accessKey) { return post(ownerId, id, accessKey, true); }
    public static ShareAttachment post(long ownerId, long id, String accessKey, boolean canRepost) {
        return new ShareAttachment("wall", ownerId, id, accessKey, canRepost);
    }
    public boolean hasIdentity() { return (type.equals("wall") || type.equals("photo")) && ownerId != 0 && ownerId != Long.MIN_VALUE && id > 0; }
    public String object() { return hasIdentity() ? type + ownerId + "_" + id : ""; }
    public String canonicalLink() { return hasIdentity() ? "https://vk.com/" + object() : ""; }
    /** Coffee's photo message attachment carries its key; wall attachments use the plain wall identity. */
    public String messageAttachment() { return object() + (hasIdentity() && type.equals("photo") && !accessKey.isEmpty() ? "_" + accessKey : ""); }
    private ShareAttachment(Parcel in) { this(in.readString(), in.readLong(), in.readLong(), in.readString(), in.readByte() != 0); }
    @Override public int describeContents() { return 0; }
    @Override public void writeToParcel(Parcel out, int flags) { out.writeString(type); out.writeLong(ownerId); out.writeLong(id); out.writeString(accessKey); out.writeByte((byte) (canRepost ? 1 : 0)); }
    public static final Creator<ShareAttachment> CREATOR = new Creator<ShareAttachment>() {
        @Override public ShareAttachment createFromParcel(Parcel in) { return new ShareAttachment(in); }
        @Override public ShareAttachment[] newArray(int size) { return new ShareAttachment[size]; }
    };
}
