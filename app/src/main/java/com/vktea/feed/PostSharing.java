package com.vktea.feed;

import android.content.Context;
import com.vktea.share.ShareActivity;
import com.vktea.share.ShareAttachment;

/** Shares the post entity itself, using its wall owner rather than the author's identity. */
public final class PostSharing {
    private PostSharing() { }

    public static ShareAttachment attachment(Post post) {
        if (post == null) return ShareAttachment.post(0, 0, "", false);
        String key = post.accessKey;
        if (post.likeType == Post.TYPE_PHOTO && key.isEmpty()) {
            for (ViewerPhoto photo : post.photoDetails) {
                if (photo.id == post.likeItemId && photo.ownerId == post.likeOwnerId) { key = photo.accessKey; break; }
            }
        }
        return new ShareAttachment(post.likeType == Post.TYPE_PHOTO ? "photo" : "wall",
                post.likeOwnerId, post.likeItemId, key, post.canRepost);
    }

    public static void open(Context context, Post post) {
        ShareAttachment attachment = attachment(post);
        if (attachment.hasIdentity()) ShareActivity.start(context, attachment);
    }
}
