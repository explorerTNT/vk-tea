package com.vktea.feed;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.os.Bundle;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.View;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.widget.Toolbar;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowCompat;
import androidx.core.view.WindowInsetsCompat;
import androidx.core.widget.TextViewCompat;
import androidx.lifecycle.ViewModelProvider;

import com.vktea.R;
import com.vktea.msg.MessageAvatars;
import com.vktea.util.RuDate;
import com.vktea.share.ShareActivity;
import com.vktea.share.ShareAttachment;

import java.util.Collections;
import java.util.List;

/** Photo discussion only: photo IDs always refer to the photo entity, never its containing wall post. */
public class PhotoDiscussionActivity extends AppCompatActivity {
    public static final String EXTRA_PHOTO = "photo";
    private PhotoSocialViewModel model;
    private EditText input;
    private View send, loading, retry;
    private TextView author, date, caption, status, title, like;
    private ImageView avatar;
    private LinearLayout photoContainer, rows;
    private boolean requestedComments;
    private String boundUrl, boundAvatar;
    private List<PhotoSocialRepository.Comment> boundComments;

    public static void start(Context context, ViewerPhoto photo) {
        Intent intent = new Intent(context, PhotoDiscussionActivity.class).putExtra(EXTRA_PHOTO, photo);
        if (!(context instanceof Activity)) intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        context.startActivity(intent);
    }

    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        ViewerPhoto photo = getIntent().getParcelableExtra(EXTRA_PHOTO);
        if (photo == null) { finish(); return; }
        WindowCompat.setDecorFitsSystemWindows(getWindow(), false);
        setContentView(R.layout.activity_photo_discussion);
        View root = findViewById(R.id.discussion_root);
        ViewCompat.setOnApplyWindowInsetsListener(root, (view, insets) -> {
            Insets bars = insets.getInsets(WindowInsetsCompat.Type.systemBars() | WindowInsetsCompat.Type.displayCutout());
            Insets ime = insets.getInsets(WindowInsetsCompat.Type.ime());
            view.setPadding(bars.left, bars.top, bars.right, Math.max(bars.bottom, ime.bottom));
            return WindowInsetsCompat.CONSUMED;
        });
        WindowCompat.getInsetsController(getWindow(), root).setAppearanceLightStatusBars(false);
        WindowCompat.getInsetsController(getWindow(), root).setAppearanceLightNavigationBars(false);
        getWindow().setStatusBarColor(Color.TRANSPARENT);
        getWindow().setNavigationBarColor(Color.TRANSPARENT);
        Toolbar toolbar = findViewById(R.id.discussion_toolbar);
        toolbar.setNavigationOnClickListener(view -> finish());
        input = findViewById(R.id.discussion_input);
        send = findViewById(R.id.discussion_send);
        author = findViewById(R.id.discussion_author);
        date = findViewById(R.id.discussion_date);
        caption = findViewById(R.id.discussion_caption);
        avatar = findViewById(R.id.discussion_avatar);
        photoContainer = findViewById(R.id.discussion_photos);
        rows = findViewById(R.id.discussion_comments);
        title = findViewById(R.id.discussion_comments_title);
        status = findViewById(R.id.discussion_status);
        loading = findViewById(R.id.discussion_loading);
        retry = findViewById(R.id.discussion_retry);
        like = findViewById(R.id.discussion_like);
        model = createPhotoSocialViewModel();
        input.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence text, int start, int count, int after) { }
            @Override public void onTextChanged(CharSequence text, int start, int before, int count) { model.setCommentDraft(text.toString()); }
            @Override public void afterTextChanged(Editable text) { }
        });
        send.setOnClickListener(view -> model.sendComment());
        like.setOnClickListener(view -> model.toggleLike());
        findViewById(R.id.discussion_repost).setOnClickListener(view -> {
            PhotoSocialViewModel.State current = model.state().getValue();
            if (current != null && current.photo != null) ShareActivity.start(this, ShareAttachment.photo(current.photo));
        });
        retry.setOnClickListener(view -> {
            if (requestedComments) model.loadComments(); else model.refresh();
        });
        model.select(photo);
        model.state().observe(this, this::render);
        model.ensureOwner();
        ViewCompat.requestApplyInsets(root);
    }

    protected PhotoSocialViewModel createPhotoSocialViewModel() {
        return new ViewModelProvider(this).get(PhotoSocialViewModel.class);
    }

    protected void bindOwnerAvatar(ImageView view, String url, boolean group) { MessageAvatars.bind(view, url, group); }
    protected void renderPhoto(LinearLayout container, ViewerPhoto photo) {
        PostPhotosRenderer.render(container, Collections.singletonList(photo.url), Collections.singletonList(photo));
    }

    private void render(PhotoSocialViewModel.State state) {
        if (state == null || state.photo == null || isFinishing()) return;
        ViewerPhoto photo = state.photo;
        author.setText(photo.ownerName.isEmpty() ? getString(photo.ownerId < 0
                ? R.string.photo_discussion_group : R.string.photo_discussion_owner) : photo.ownerName);
        date.setText(photo.date > 0 ? RuDate.format(photo.date) : "");
        date.setVisibility(photo.date > 0 ? View.VISIBLE : View.GONE);
        caption.setText(photo.text);
        caption.setVisibility(photo.text.isEmpty() ? View.GONE : View.VISIBLE);
        if (!photo.ownerPhoto.equals(boundAvatar)) {
            boundAvatar = photo.ownerPhoto; bindOwnerAvatar(avatar, photo.ownerPhoto, photo.ownerId < 0);
        }
        if (!photo.url.equals(boundUrl)) { boundUrl = photo.url; renderPhoto(photoContainer, photo); }
        boolean canAct = photo.hasIdentity() && !state.loading && !state.busy;
        findViewById(R.id.discussion_repost).setEnabled(canAct);
        like.setText(photo.likes > 0 ? FeedAdapter.formatCount(photo.likes) : getString(R.string.photo_viewer_like));
        like.setContentDescription(getString(R.string.photo_viewer_action_count,
                getString(photo.liked ? R.string.photo_viewer_unlike : R.string.photo_viewer_like), photo.likes));
        int color = photo.liked ? 0xff5685a6 : 0xff6d7885;
        like.setTextColor(color);
        TextViewCompat.setCompoundDrawableTintList(like, ColorStateList.valueOf(color));
        like.setEnabled(canAct && (photo.canLike || photo.liked));
        if (!input.getText().toString().equals(state.commentDraft)) {
            input.setText(state.commentDraft); input.setSelection(state.commentDraft.length());
        }
        input.setEnabled(canAct && photo.canComment);
        input.setHint(photo.hasIdentity() && !state.loading && !photo.canComment
                ? R.string.photo_social_no_comment : R.string.photo_discussion_hint);
        send.setEnabled(canAct && photo.canComment && !state.commentDraft.trim().isEmpty());
        send.setAlpha(send.isEnabled() ? 1f : .4f);
        loading.setVisibility(state.loading || state.commentsLoading ? View.VISIBLE : View.GONE);
        title.setText(state.commentsLoaded ? getString(R.string.photo_discussion_latest, state.comments.size(), state.commentsTotal)
                : getString(R.string.photo_discussion_comments));
        status.setText(!photo.hasIdentity() ? getString(R.string.photo_discussion_unavailable)
                : state.error != null ? getString(error(state.error))
                : state.commentsLoaded && state.comments.isEmpty() ? getString(R.string.photo_social_empty) : "");
        status.setVisibility(status.length() == 0 ? View.GONE : View.VISIBLE);
        retry.setVisibility(state.error != null ? View.VISIBLE : View.GONE);
        retry.setEnabled(!state.loading && !state.commentsLoading && !state.busy);
        if (boundComments != state.comments) {
            boundComments = state.comments; rows.removeAllViews();
            for (PhotoSocialRepository.Comment comment : state.comments) {
                TextView row = new TextView(this);
                String heading = comment.date > 0 ? getString(R.string.photo_discussion_comment_author,
                        comment.author, RuDate.format(comment.date)) : comment.author;
                row.setText(getString(R.string.photo_discussion_comment, heading, comment.text));
                row.setTextSize(15); row.setTextColor(0xff2e3033); row.setTextIsSelectable(true);
                int padding = Math.round(12 * getResources().getDisplayMetrics().density);
                row.setPadding(0, padding, 0, padding); rows.addView(row);
            }
        }
        if (state.commentsOpen) requestedComments = true;
        if (!requestedComments && photo.hasIdentity() && !state.loading && !state.busy && state.error == null) {
            requestedComments = true;
            model.openComments();
        }
        if (state.event != null) {
            model.consumeEvent();
            if (state.event == PhotoSocialViewModel.Event.COMMENT_SENT)
                Toast.makeText(this, R.string.photo_social_comment_sent, Toast.LENGTH_SHORT).show();
        }
    }

    private static int error(PhotoSocialError failure) {
        switch (failure.kind) {
            case AUTH: return R.string.photo_social_error_auth;
            case ACCESS: return R.string.photo_social_error_access;
            case RATE_LIMITED: return R.string.photo_social_error_rate;
            case NETWORK: return R.string.photo_social_error_network;
            default: return R.string.photo_social_error_generic;
        }
    }

    @Override protected void onDestroy() {
        if (avatar != null) MessageAvatars.clear(avatar);
        if (photoContainer != null) PostPhotosRenderer.render(photoContainer, null);
        super.onDestroy();
    }
}
