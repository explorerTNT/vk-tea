package com.vktea.feed;

import android.content.Context;
import java.util.List;
import com.vktea.share.ShareAttachment;

/** Recipient lookup and a single explicit VK attachment send. Callbacks are delivered on main. */
public interface PhotoShareRepository extends AutoCloseable {
    interface Request { void cancel(); }
    interface Callback<T> { void success(T result); void error(PhotoSocialError error); }
    final class Recipient {
        public final long peerId;
        public final String title, photo;
        public Recipient(long peerId, String title, String photo) {
            this.peerId = peerId; this.title = title == null ? "" : title; this.photo = photo == null ? "" : photo;
        }
    }
    final class Community {
        public final long id;
        public final String title, photo;
        public Community(long id, String title, String photo) {
            this.id = id; this.title = title == null ? "" : title; this.photo = photo == null ? "" : photo;
        }
    }
    static PhotoShareRepository create(Context context) { return new VkPhotoShareRepository(context.getApplicationContext()); }
    Request recipients(String query, Callback<List<Recipient>> callback);
    default Request friends(String query, Callback<List<Recipient>> callback) { return unsupported(callback); }
    default Request send(ViewerPhoto photo, Recipient recipient, int randomId, String draft, Callback<Long> callback) { return unsupported(callback); }
    /** Compatibility default keeps photo-only callers/fakes valid while the production transport supports both types. */
    default Request send(ShareAttachment attachment, Recipient recipient, int randomId, String draft, Callback<Long> callback) {
        if (attachment != null && attachment.type.equals("photo"))
            return send(new ViewerPhoto("", attachment.id, attachment.ownerId, attachment.accessKey, 0, 0, 0, false,
                    false, false, attachment.canRepost), recipient, randomId, draft, callback);
        return unsupported(callback);
    }
    /** Returns every page of writable administrator/editor communities, never a partial success. */
    default Request communities(Callback<List<Community>> callback) { return unsupported(callback); }
    default Request repost(ShareAttachment attachment, long groupId, String draft, Callback<Long> callback) { return unsupported(callback); }
    static <T> Request unsupported(Callback<T> callback) {
        java.util.concurrent.atomic.AtomicBoolean canceled = new java.util.concurrent.atomic.AtomicBoolean();
        new android.os.Handler(android.os.Looper.getMainLooper()).post(() -> {
            if (!canceled.get()) callback.error(new PhotoSocialError(PhotoSocialError.Kind.UNAVAILABLE));
        });
        return () -> canceled.set(true);
    }
    @Override void close();
}
