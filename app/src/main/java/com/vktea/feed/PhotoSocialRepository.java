package com.vktea.feed;

import android.content.Context;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** All callbacks are on main; cancellation suppresses delivery, never retries a mutation. */
public interface PhotoSocialRepository extends AutoCloseable {
    interface Request { void cancel(); }
    interface Callback<T> { void success(T result); void error(PhotoSocialError error); }
    final class Comment {
        public final long id, fromId, date;
        public final String author, text;
        public Comment(long id, long fromId, String author, String text, long date) {
            this.id = id; this.fromId = fromId; this.author = author; this.text = text; this.date = date;
        }
    }
    final class Comments {
        public final int total;
        public final List<Comment> items;
        public Comments(int total, List<Comment> items) {
            this.total = Math.max(0, total);
            this.items = Collections.unmodifiableList(new ArrayList<>(items));
        }
    }
    final class Owner {
        public final String name, photo;
        public Owner(String name, String photo) {
            this.name = name == null ? "" : name;
            this.photo = photo == null ? "" : photo;
        }
    }
    static PhotoSocialRepository create(Context context) { return new VkPhotoSocialRepository(context.getApplicationContext()); }
    Request load(ViewerPhoto photo, Callback<ViewerPhoto> callback);
    Request like(ViewerPhoto photo, boolean liked, Callback<Integer> callback);
    Request comments(ViewerPhoto photo, Callback<Comments> callback);
    Request comment(ViewerPhoto photo, String text, Callback<Long> callback);
    Request repost(ViewerPhoto photo, String text, Callback<Integer> callback);
    default Request owner(ViewerPhoto photo, Callback<Owner> callback) {
        callback.success(new Owner(photo.ownerName, photo.ownerPhoto));
        return () -> { };
    }
    @Override void close();
}
