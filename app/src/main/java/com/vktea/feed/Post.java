package com.vktea.feed;

import java.util.ArrayList;
import java.util.List;

public class Post {

    public final long sourceId;
    public final String sourceName;
    public final String sourcePhoto;
    public final String text;
    public final long date;
    public final List<String> photoUrls;
    public final int likes;
    public final int comments;
    public final int reposts;
    public final int views;
    public boolean expanded;

    public Post(long sourceId, String sourceName, String sourcePhoto, String text, long date,
                List<String> photoUrls, int likes, int comments, int reposts, int views) {
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
    }
}
