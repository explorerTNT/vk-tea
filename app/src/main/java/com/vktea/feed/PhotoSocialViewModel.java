package com.vktea.feed;

import android.app.Application;
import androidx.lifecycle.AndroidViewModel;
import androidx.lifecycle.LiveData;
import androidx.lifecycle.MutableLiveData;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** Retains explicit user actions and drafts; selecting a page never submits a mutation. */
public class PhotoSocialViewModel extends AndroidViewModel {
    public enum Event { ERROR, COMMENT_SENT, REPOSTED }
    public static final class State {
        public final ViewerPhoto photo;
        public final boolean loading, busy, commentsLoading, commentsLoaded, commentsOpen, repostOpen;
        public final List<PhotoSocialRepository.Comment> comments;
        public final int commentsTotal;
        public final String commentDraft, repostDraft;
        public final PhotoSocialError error;
        public final Event event;
        private State(PhotoSocialViewModel model) {
            photo = model.photo; loading = model.loading; busy = model.busy;
            commentsLoading = model.commentsLoading; commentsLoaded = model.commentsLoaded;
            commentsOpen = model.commentsOpen; repostOpen = model.repostOpen;
            comments = model.comments; commentsTotal = model.commentsTotal;
            commentDraft = model.commentDraft; repostDraft = model.repostDraft;
            error = model.error; event = model.event;
        }
    }
    private final PhotoSocialRepository repository;
    private final MutableLiveData<State> state = new MutableLiveData<>();
    private final Map<String, ViewerPhoto> known = new HashMap<>();
    private final Map<String, Long> revisions = new HashMap<>();
    private ViewerPhoto photo;
    private boolean loading, busy, commentsLoading, commentsLoaded, commentsOpen, repostOpen, cleared;
    private List<PhotoSocialRepository.Comment> comments = Collections.emptyList();
    private int commentsTotal, generation, commentsGeneration;
    private String commentDraft = "", repostDraft = "";
    private PhotoSocialError error;
    private Event event;
    private PhotoSocialRepository.Request metadataRequest, commentsRequest, mutationRequest, ownerRequest;
    private boolean ownerAttempted;

    public PhotoSocialViewModel(Application application) { this(application, PhotoSocialRepository.create(application)); }
    public PhotoSocialViewModel(Application application, PhotoSocialRepository repository) {
        super(application); this.repository = repository; publish();
    }
    public LiveData<State> state() { return state; }

    public void select(ViewerPhoto selected) {
        if (cleared || selected == null) return;
        if (photo != null && key(photo).equals(key(selected))) {
            if (!selected.url.isEmpty() && !selected.url.equals(photo.url)) {
                photo = copy(photo, selected.url, photo.likes, photo.comments, photo.reposts, photo.liked);
                publish();
            }
            return;
        }
        generation++; commentsGeneration++;
        cancelReads();
        ownerAttempted = false;
        photo = enrich(known.getOrDefault(key(selected), selected), selected);
        if (!selected.url.isEmpty()) photo = copy(photo, selected.url, photo.likes, photo.comments, photo.reposts, photo.liked);
        comments = Collections.emptyList(); commentsTotal = photo.comments;
        commentsLoading = commentsLoaded = commentsOpen = repostOpen = false;
        commentDraft = repostDraft = ""; error = null; event = null;
        loading = photo.hasIdentity();
        publish();
        if (!loading) return;
        requestMetadata();
    }
    /** Explicit recovery after a metadata failure; observation and recreation do not call this. */
    public void refresh() {
        if (!canAct()) return;
        loading = true; error = null; event = null; publish();
        requestMetadata();
    }
    private void requestMetadata() {
        int requestGeneration = generation;
        ViewerPhoto target = photo;
        long revision = revisions.getOrDefault(key(target), 0L);
        metadataRequest = repository.load(target, new PhotoSocialRepository.Callback<ViewerPhoto>() {
            @Override public void success(ViewerPhoto result) {
                if (!current(requestGeneration)) return;
                metadataRequest = null; loading = false;
                if (revision != revisions.getOrDefault(key(target), 0L)) { publish(); return; }
                photo = copy(enrich(result, photo), photo.url, result.likes, result.comments, result.reposts, result.liked);
                known.put(key(photo), photo); commentsTotal = photo.comments; publish();
            }
            @Override public void error(PhotoSocialError failure) {
                if (!current(requestGeneration)) return;
                metadataRequest = null; loading = false;
                if (revision != revisions.getOrDefault(key(target), 0L)) publish(); else fail(failure);
            }
        });
    }
    /** Author lookup is requested only by the discussion screen, never by observing the viewer. */
    public void ensureOwner() {
        if (cleared || photo == null || !photo.hasIdentity() || ownerAttempted
                || (!photo.ownerName.isEmpty() && !photo.ownerPhoto.isEmpty())) return;
        ownerAttempted = true;
        int requestGeneration = generation;
        ownerRequest = repository.owner(photo, new PhotoSocialRepository.Callback<PhotoSocialRepository.Owner>() {
            @Override public void success(PhotoSocialRepository.Owner owner) {
                if (!current(requestGeneration)) return;
                ownerRequest = null;
                photo = new ViewerPhoto(photo.url, photo.id, photo.ownerId, photo.accessKey,
                        photo.likes, photo.comments, photo.reposts, photo.liked, photo.canLike, photo.canComment, photo.canRepost,
                        owner.name.isEmpty() ? photo.ownerName : owner.name,
                        owner.photo.isEmpty() ? photo.ownerPhoto : owner.photo, photo.date, photo.text);
                known.put(key(photo), photo); publish();
            }
            @Override public void error(PhotoSocialError failure) { if (current(requestGeneration)) ownerRequest = null; }
        });
    }
    public void toggleLike() {
        if (!canAct() || (!photo.canLike && !photo.liked)) return;
        ViewerPhoto target = photo; boolean liked = !target.liked;
        beginMutation();
        mutationRequest = repository.like(target, liked, new PhotoSocialRepository.Callback<Integer>() {
            @Override public void success(Integer count) {
                if (cleared) return;
                complete(copy(target, target.url, count, target.comments, target.reposts, liked));
            }
            @Override public void error(PhotoSocialError failure) { mutationFailed(failure); }
        });
    }
    public void openComments() {
        if (photo == null || !photo.hasIdentity() || loading || busy) return;
        commentsOpen = true; repostOpen = false; error = null; publish();
        if (!commentsLoaded) loadComments();
    }
    public void closeComments() { commentsOpen = false; publish(); }
    public void loadComments() {
        if (cleared || photo == null || !photo.hasIdentity() || commentsLoading || busy) return;
        commentsLoading = true; error = null; publish();
        int selectedGeneration = generation, requestGeneration = ++commentsGeneration;
        commentsRequest = repository.comments(photo, new PhotoSocialRepository.Callback<PhotoSocialRepository.Comments>() {
            @Override public void success(PhotoSocialRepository.Comments result) {
                if (!current(selectedGeneration) || commentsGeneration != requestGeneration) return;
                commentsRequest = null; commentsLoading = false; commentsLoaded = true;
                comments = result.items; commentsTotal = result.total;
                photo = copy(photo, photo.url, photo.likes, result.total, photo.reposts, photo.liked);
                known.put(key(photo), photo); publish();
            }
            @Override public void error(PhotoSocialError failure) {
                if (!current(selectedGeneration) || commentsGeneration != requestGeneration) return;
                commentsRequest = null; commentsLoading = false; fail(failure);
            }
        });
    }
    public void setCommentDraft(String text) { commentDraft = text == null ? "" : text; publish(); }
    public void sendComment() {
        if (!canAct() || !photo.canComment || commentDraft.trim().isEmpty()) return;
        ViewerPhoto target = photo; String draft = commentDraft.trim();
        beginMutation();
        mutationRequest = repository.comment(target, draft, new PhotoSocialRepository.Callback<Long>() {
            @Override public void success(Long id) {
                if (cleared) return;
                boolean selected = same(target);
                complete(copy(target, target.url, target.likes, plusOne(target.comments), target.reposts, target.liked));
                if (selected) {
                    commentDraft = ""; commentsLoaded = false; event = Event.COMMENT_SENT;
                    commentsGeneration++;
                    if (commentsRequest != null) commentsRequest.cancel();
                    commentsRequest = null; commentsLoading = false; publish();
                    if (commentsOpen) loadComments();
                }
            }
            @Override public void error(PhotoSocialError failure) { mutationFailed(failure); }
        });
    }
    public void openRepost() {
        if (!canAct() || !photo.canRepost) return;
        repostOpen = true; commentsOpen = false; error = null; publish();
    }
    public void closeRepost() { repostOpen = false; publish(); }
    public void setRepostDraft(String text) { repostDraft = text == null ? "" : text; publish(); }
    public void submitRepost() {
        if (!canAct() || !photo.canRepost || !repostOpen) return;
        ViewerPhoto target = photo; String draft = repostDraft.trim();
        beginMutation();
        mutationRequest = repository.repost(target, draft, new PhotoSocialRepository.Callback<Integer>() {
            @Override public void success(Integer count) {
                if (cleared) return;
                boolean selected = same(target);
                complete(copy(target, target.url, target.likes, target.comments, count, target.liked));
                if (selected) { repostDraft = ""; repostOpen = false; event = Event.REPOSTED; publish(); }
            }
            @Override public void error(PhotoSocialError failure) { mutationFailed(failure); }
        });
    }
    public void consumeEvent() { if (event != null) { event = null; publish(); } }
    private void beginMutation() { busy = true; error = null; event = null; publish(); }
    private void complete(ViewerPhoto updated) {
        if (same(updated)) updated = enrich(updated, photo);
        mutationRequest = null; busy = false; known.put(key(updated), updated);
        revisions.put(key(updated), revisions.getOrDefault(key(updated), 0L) + 1L);
        if (same(updated)) {
            photo = copy(updated, photo.url, updated.likes, updated.comments, updated.reposts, updated.liked);
            commentsTotal = photo.comments;
        }
        publish();
    }
    private void mutationFailed(PhotoSocialError failure) {
        if (cleared) return;
        mutationRequest = null; busy = false; fail(failure);
    }
    private void fail(PhotoSocialError failure) { error = failure; event = Event.ERROR; publish(); }
    private boolean canAct() { return !cleared && photo != null && photo.hasIdentity() && !loading && !busy; }
    private boolean current(int requestGeneration) { return !cleared && generation == requestGeneration; }
    private boolean same(ViewerPhoto target) { return photo != null && key(photo).equals(key(target)); }
    private static String key(ViewerPhoto value) { return value.hasIdentity() ? value.ownerId + "_" + value.id : "url:" + value.url; }
    private static int plusOne(int value) { return value == Integer.MAX_VALUE ? value : value + 1; }
    private static ViewerPhoto copy(ViewerPhoto value, String url, int likes, int comments, int reposts, boolean liked) {
        return new ViewerPhoto(url, value.id, value.ownerId, value.accessKey, likes, comments, reposts, liked,
                value.canLike, value.canComment, value.canRepost, value.ownerName, value.ownerPhoto, value.date, value.text);
    }
    private static ViewerPhoto enrich(ViewerPhoto value, ViewerPhoto previous) {
        return new ViewerPhoto(value.url, value.id, value.ownerId, value.accessKey, value.likes, value.comments,
                value.reposts, value.liked, value.canLike, value.canComment, value.canRepost,
                value.ownerName.isEmpty() ? previous.ownerName : value.ownerName,
                value.ownerPhoto.isEmpty() ? previous.ownerPhoto : value.ownerPhoto,
                value.date > 0 ? value.date : previous.date, value.text.isEmpty() ? previous.text : value.text);
    }
    private void publish() { if (!cleared) state.setValue(new State(this)); }
    private void cancelReads() {
        if (metadataRequest != null) metadataRequest.cancel();
        if (commentsRequest != null) commentsRequest.cancel();
        if (ownerRequest != null) ownerRequest.cancel();
        metadataRequest = commentsRequest = null;
        ownerRequest = null;
    }
    @Override protected void onCleared() {
        cleared = true; cancelReads();
        if (mutationRequest != null) mutationRequest.cancel();
        repository.close();
    }
}
