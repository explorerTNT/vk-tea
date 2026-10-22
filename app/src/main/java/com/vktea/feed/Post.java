package com.vktea.feed;

import java.util.ArrayList;
import java.util.List;

public class Post {

    public static final int TYPE_POST = 0;
    public static final int TYPE_PHOTO = 1;

    public final long sourceId;
    public final String sourceName;
    public final String sourcePhoto;
    public final String text;
    public final long date;
    public final List<String> photoUrls;
    public int likes;
    public final int comments;
    public final int reposts;
    public final int views;
    public boolean userLikes;
    public final int likeType;
    public final long likeOwnerId;
    public final long likeItemId;
    public boolean expanded;

    public String repostAuthorName = "";
    public String repostAuthorPhoto = "";
    public long repostDate;
    public String repostText = "";
    public List<String> repostPhotos = new ArrayList<>();
    public long repostOwnerId;
    public long repostPostId;

    public String videoPreview;
    public String videoTitle = "";
    public long videoOwner;
    public long videoId;
    public int videoDuration;

    public Post(long sourceId, String sourceName, String sourcePhoto, String text, long date,
                List<String> photoUrls, int likes, int comments, int reposts, int views,
                boolean userLikes, int likeType, long likeOwnerId, long likeItemId) {
        this.sourceId = sourceId;
        this.sourceName = sourceName;
        this.sourcePhoto = sourcePhoto;
        this.text = text;
        this.date = date;
        this.photoUrls = photoUrls;
        this.likes = likes;
        this.comments = comments;
        this.reposts = reposts;
        this.views = views;
        this.userLikes = userLikes;
        this.likeType = likeType;
        this.likeOwnerId = likeOwnerId;
        this.likeItemId = likeItemId;
    }

    public boolean isRepost() {
        return !repostAuthorName.isEmpty() || !repostText.isEmpty() || !repostPhotos.isEmpty();
    }

    public boolean hasVideo() {
        return videoPreview != null && !videoPreview.isEmpty();
    }
}
