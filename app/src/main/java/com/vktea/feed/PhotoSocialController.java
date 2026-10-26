package com.vktea.feed;

import android.content.Context;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.ContextThemeWrapper;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;
import androidx.appcompat.app.AlertDialog;
import androidx.fragment.app.FragmentActivity;
import androidx.lifecycle.Observer;
import com.vktea.R;
import java.util.List;

/** Dialogs are disposable Activity views; open state, requests and drafts live in the ViewModel. */
public final class PhotoSocialController {
    private final FragmentActivity activity;
    private final PhotoSocialViewModel model;
    private final Observer<PhotoSocialViewModel.State> observer = this::render;
    private AlertDialog commentsDialog, repostDialog;
    private EditText commentInput, repostInput;
    private TextView commentsStatus;
    private LinearLayout commentRows;
    private List<PhotoSocialRepository.Comment> renderedComments;
    private Button retry;
    private boolean destroyed, closing;

    public PhotoSocialController(FragmentActivity activity, PhotoSocialViewModel model) {
        this.activity = activity; this.model = model;
        model.state().observe(activity, observer);
    }
    public void toggleLike() { model.toggleLike(); }
    public void openComments() { model.openComments(); }
    public void openRepost() { model.openRepost(); }
    public void destroy() {
        destroyed = true; model.state().removeObserver(observer);
        dismissComments(); dismissRepost();
    }
    private void render(PhotoSocialViewModel.State state) {
        if (destroyed || state == null || activity.isFinishing()) return;
        if (state.commentsOpen) {
            if (commentsDialog == null) createComments(state);
            renderComments(state);
        } else dismissComments();
        if (state.repostOpen) {
            if (repostDialog == null) createRepost(state);
            setDraft(repostInput, state.repostDraft);
            repostInput.setEnabled(!state.busy);
            repostDialog.getButton(AlertDialog.BUTTON_POSITIVE).setEnabled(!state.busy && state.photo != null && state.photo.canRepost);
            repostDialog.getButton(AlertDialog.BUTTON_NEGATIVE).setEnabled(!state.busy);
            repostDialog.setCancelable(!state.busy);
        } else dismissRepost();
        if (state.event != null) {
            String message = state.event == PhotoSocialViewModel.Event.COMMENT_SENT ? text(R.string.photo_social_comment_sent)
                    : state.event == PhotoSocialViewModel.Event.REPOSTED ? text(R.string.photo_social_reposted) : error(state.error);
            model.consumeEvent();
            Toast.makeText(activity, message, Toast.LENGTH_LONG).show();
        }
    }
    private void createComments(PhotoSocialViewModel.State state) {
        Context context = dialogContext();
        LinearLayout body = body(context);
        commentsStatus = new TextView(context); body.addView(commentsStatus);
        ScrollView scroll = new ScrollView(context);
        commentRows = new LinearLayout(context); commentRows.setOrientation(LinearLayout.VERTICAL);
        renderedComments = null;
        scroll.addView(commentRows);
        body.addView(scroll, new LinearLayout.LayoutParams(-1, Math.min(dp(250), activity.getResources().getDisplayMetrics().heightPixels / 3)));
        retry = new Button(context); retry.setText(R.string.photo_social_retry);
        retry.setOnClickListener(view -> model.loadComments()); body.addView(retry);
        commentInput = new EditText(context); commentInput.setHint(R.string.photo_social_comment_hint);
        commentInput.setMinLines(1); commentInput.setMaxLines(3); setDraft(commentInput, state.commentDraft);
        body.addView(commentInput);
        watch(commentInput, model::setCommentDraft);
        commentsDialog = new AlertDialog.Builder(context).setTitle(R.string.photo_social_comments).setView(body)
                .setPositiveButton(R.string.photo_social_send, null).setNegativeButton(R.string.photo_social_close, null).create();
        commentsDialog.setOnDismissListener(ignored -> {
            if (!closing && !destroyed) { commentsDialog = null; model.closeComments(); }
        });
        commentsDialog.show();
        commentsDialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(view -> model.sendComment());
        commentsDialog.getButton(AlertDialog.BUTTON_NEGATIVE).setOnClickListener(view -> model.closeComments());
    }
    private void renderComments(PhotoSocialViewModel.State state) {
        commentsStatus.setText(state.commentsLoading ? text(R.string.photo_social_loading) : state.error != null ? error(state.error)
                : state.comments.isEmpty() ? text(R.string.photo_social_empty)
                : activity.getString(R.string.photo_social_latest, state.comments.size(), state.commentsTotal));
        if (renderedComments != state.comments) {
            renderedComments = state.comments; commentRows.removeAllViews();
            for (PhotoSocialRepository.Comment comment : state.comments) {
                TextView row = new TextView(dialogContext());
            row.setText(activity.getString(R.string.photo_social_comment_row, comment.author, comment.text)); row.setTextIsSelectable(true);
                row.setPadding(0, dp(8), 0, dp(8)); commentRows.addView(row);
            }
        }
        retry.setEnabled(!state.commentsLoading && !state.busy);
        retry.setVisibility(state.error == null ? View.GONE : View.VISIBLE);
        boolean allowed = state.photo != null && state.photo.canComment;
        setDraft(commentInput, state.commentDraft);
        commentInput.setEnabled(allowed && !state.busy);
        commentInput.setHint(allowed ? R.string.photo_social_comment_hint : R.string.photo_social_no_comment);
        commentsDialog.getButton(AlertDialog.BUTTON_POSITIVE).setEnabled(allowed && !state.busy && !state.commentDraft.trim().isEmpty());
        commentsDialog.getButton(AlertDialog.BUTTON_NEGATIVE).setEnabled(!state.busy);
        commentsDialog.setCancelable(!state.busy);
    }
    private void createRepost(PhotoSocialViewModel.State state) {
        Context context = dialogContext();
        LinearLayout body = body(context);
        repostInput = new EditText(context); repostInput.setHint(R.string.photo_social_repost_hint);
        repostInput.setMinLines(2); repostInput.setMaxLines(5); setDraft(repostInput, state.repostDraft);
        watch(repostInput, model::setRepostDraft); body.addView(repostInput);
        repostDialog = new AlertDialog.Builder(context).setTitle(R.string.photo_social_repost).setView(body)
                .setPositiveButton(R.string.photo_social_publish, null).setNegativeButton(R.string.photo_social_cancel, null).create();
        repostDialog.setOnDismissListener(ignored -> {
            if (!closing && !destroyed) { repostDialog = null; model.closeRepost(); }
        });
        repostDialog.show();
        repostDialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(view -> model.submitRepost());
        repostDialog.getButton(AlertDialog.BUTTON_NEGATIVE).setOnClickListener(view -> model.closeRepost());
    }
    private void dismissComments() {
        if (commentsDialog == null) return;
        closing = true; commentsDialog.dismiss(); commentsDialog = null; closing = false;
    }
    private void dismissRepost() {
        if (repostDialog == null) return;
        closing = true; repostDialog.dismiss(); repostDialog = null; closing = false;
    }
    private Context dialogContext() { return new ContextThemeWrapper(activity, androidx.appcompat.R.style.Theme_AppCompat_Light_Dialog_Alert); }
    private LinearLayout body(Context context) {
        LinearLayout body = new LinearLayout(context); body.setOrientation(LinearLayout.VERTICAL);
        body.setPadding(dp(24), dp(8), dp(24), dp(8)); return body;
    }
    private static void setDraft(EditText input, String draft) {
        if (!input.getText().toString().equals(draft)) { input.setText(draft); input.setSelection(draft.length()); }
    }
    private interface DraftChanged { void changed(String value); }
    private static void watch(EditText input, DraftChanged changed) {
        input.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence value, int start, int count, int after) { }
            @Override public void onTextChanged(CharSequence value, int start, int before, int count) { changed.changed(value.toString()); }
            @Override public void afterTextChanged(Editable value) { }
        });
    }
    private String error(PhotoSocialError failure) {
        if (failure == null) return text(R.string.photo_social_error_generic);
        switch (failure.kind) {
            case AUTH: return text(R.string.photo_social_error_auth);
            case ACCESS: return text(R.string.photo_social_error_access);
            case RATE_LIMITED: return text(R.string.photo_social_error_rate);
            case NETWORK: return text(R.string.photo_social_error_network);
            default: return text(R.string.photo_social_error_generic);
        }
    }
    private String text(int resource) { return activity.getString(resource); }
    private int dp(int value) { return Math.round(value * activity.getResources().getDisplayMetrics().density); }
}
