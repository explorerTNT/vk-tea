package com.vktea.feed;

import android.content.Context;
import java.util.List;

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
    static PhotoShareRepository create(Context context) { return new VkPhotoShareRepository(context.getApplicationContext()); }
    Request recipients(String query, Callback<List<Recipient>> callback);
    Request send(ViewerPhoto photo, Recipient recipient, int randomId, String draft, Callback<Long> callback);
    @Override void close();
}
