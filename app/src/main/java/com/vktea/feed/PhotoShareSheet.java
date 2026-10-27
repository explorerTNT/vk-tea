package com.vktea.feed;

import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.graphics.Typeface;
import android.graphics.Rect;
import android.os.Build;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewTreeObserver;
import android.view.Window;
import android.view.WindowManager;
import android.view.inputmethod.EditorInfo;
import android.widget.Button;
import android.widget.EditText;
import android.widget.HorizontalScrollView;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;
import androidx.appcompat.app.AlertDialog;
import androidx.fragment.app.FragmentActivity;
import androidx.lifecycle.Observer;
import androidx.core.view.ViewCompat;
import androidx.core.widget.NestedScrollView;
import com.google.android.material.bottomsheet.BottomSheetBehavior;
import com.google.android.material.bottomsheet.BottomSheetDialog;
import com.vktea.R;
import com.vktea.msg.MessageAvatars;
import java.util.List;

/** Disposable views over retained state; choosing a recipient opens confirmation, never sends. */
public final class PhotoShareSheet {
    private final FragmentActivity activity;
    private final PhotoShareViewModel model;
    private final Runnable ownWall;
    private final Observer<PhotoShareViewModel.State> observer = this::render;
    private BottomSheetDialog sheet;
    private AlertDialog composer;
    private EditText search, draft;
    private TextView status, recipientName, sendStatus;
    private LinearLayout rows;
    private NestedScrollView viewport;
    private ViewTreeObserver.OnGlobalLayoutListener visibleFrameListener;
    private boolean searchExpanded;
    private Button retry, wall, copy;
    private List<PhotoShareRepository.Recipient> rendered;
    private boolean destroyed, closing;

    public PhotoShareSheet(FragmentActivity activity, PhotoShareViewModel model, Runnable ownWall) {
        this.activity = activity; this.model = model; this.ownWall = ownWall;
        model.state().observe(activity, observer);
    }
    public void show(ViewerPhoto photo) { model.open(photo); }
    public void destroy() {
        destroyed = true; model.state().removeObserver(observer); dismissSheet(); dismissComposer();
    }
    private void render(PhotoShareViewModel.State state) {
        if (destroyed || state == null || activity.isFinishing()) return;
        if (state.open && state.selected == null) {
            dismissComposer();
            if (sheet == null) createSheet(state);
            setText(search, state.query);
            status.setText(state.loading ? R.string.photo_share_loading : state.error != null ? errorResource(state.error)
                    : state.recipients.isEmpty() ? R.string.photo_share_empty : state.query.trim().isEmpty()
                    ? R.string.photo_share_recent_limit : R.string.photo_share_search_results);
            retry.setVisibility(state.error == null ? View.GONE : View.VISIBLE); retry.setEnabled(!state.loading);
            wall.setEnabled(state.photo != null && state.photo.canRepost);
            if (rendered != state.recipients) renderRecipients(state.recipients);
        } else dismissSheet();
        if (state.open && state.selected != null) {
            if (composer == null) createComposer(state);
            recipientName.setText(activity.getString(R.string.photo_share_recipient, state.selected.title));
            sendStatus.setVisibility(state.error == null ? View.GONE : View.VISIBLE);
            sendStatus.setText(state.error != null && (state.error.kind == PhotoSocialError.Kind.NETWORK
                    || state.error.kind == PhotoSocialError.Kind.UNAVAILABLE) ? R.string.photo_share_unknown_result : errorResource(state.error));
            setText(draft, state.draft); draft.setEnabled(!state.busy && !state.retrying);
            composer.getButton(AlertDialog.BUTTON_POSITIVE).setText(state.retrying && !state.busy
                    ? R.string.photo_share_confirm_retry : R.string.photo_share_send);
            composer.getButton(AlertDialog.BUTTON_POSITIVE).setEnabled(!state.busy);
            composer.getButton(AlertDialog.BUTTON_NEGATIVE).setEnabled(!state.busy);
            composer.setCancelable(!state.busy);
        } else dismissComposer();
        if (state.event != null) {
            int message = state.event == PhotoShareViewModel.Event.SENT ? R.string.photo_share_sent
                    : state.selected != null && state.error != null && (state.error.kind == PhotoSocialError.Kind.NETWORK
                    || state.error.kind == PhotoSocialError.Kind.UNAVAILABLE) ? R.string.photo_share_unknown_result : errorResource(state.error);
            model.consumeEvent(); Toast.makeText(activity, message, Toast.LENGTH_LONG).show();
        }
    }
    private void createSheet(PhotoShareViewModel.State state) {
        sheet = new BottomSheetDialog(activity, R.style.Theme_VKTea_PhotoShare);
        searchExpanded = false;
        Context context = sheet.getContext(); LinearLayout body = body(context);
        body.setFocusableInTouchMode(true);
        TextView title = new TextView(context); title.setText(R.string.photo_share_title); title.setTextSize(20);
        title.setTypeface(null, Typeface.BOLD); title.setTextColor(com.vktea.ui.TeaColors.get(context, R.color.almost_black)); title.setGravity(Gravity.START);
        title.setPadding(0, dp(8), 0, dp(16)); body.addView(title);
        search = new EditText(context); search.setId(R.id.photo_share_search_input); search.setSingleLine();
        search.setImeOptions(search.getImeOptions() | EditorInfo.IME_FLAG_NO_EXTRACT_UI | EditorInfo.IME_FLAG_NO_FULLSCREEN);
        search.setHint(R.string.photo_share_search); setText(search, state.query); body.addView(search);
        search.setOnFocusChangeListener((view, focused) -> { if (focused) expandForSearch(); });
        watch(search, model::setQuery);
        HorizontalScrollView scroll = new HorizontalScrollView(context); scroll.setHorizontalScrollBarEnabled(false);
        rows = new LinearLayout(context); rows.setId(R.id.photo_share_recipients); rows.setOrientation(LinearLayout.HORIZONTAL);
        scroll.addView(rows); body.addView(scroll); rendered = null;
        status = new TextView(context); status.setId(R.id.photo_share_status); status.setTextSize(13); status.setPadding(0, dp(8), 0, dp(8)); body.addView(status);
        retry = new Button(context); retry.setId(R.id.photo_share_refresh); retry.setText(R.string.photo_share_retry);
        retry.setOnClickListener(view -> model.refresh()); body.addView(retry);
        wall = action(context, R.id.photo_share_wall_action, R.string.photo_share_own_wall);
        wall.setOnClickListener(view -> { model.close(); ownWall.run(); }); body.addView(wall);
        copy = action(context, R.id.photo_share_copy_action, R.string.photo_share_copy);
        copy.setOnClickListener(view -> {
            PhotoShareViewModel.State current = model.state().getValue();
            if (current == null || current.photo == null || !current.photo.hasIdentity()) return;
            ClipboardManager clipboard = (ClipboardManager) activity.getSystemService(Context.CLIPBOARD_SERVICE);
            if (clipboard != null) clipboard.setPrimaryClip(ClipData.newPlainText(activity.getString(R.string.photo_share_title),
                    "https://vk.com/photo" + current.photo.ownerId + "_" + current.photo.id));
            model.close();
            if (Build.VERSION.SDK_INT < 33) Toast.makeText(activity, R.string.photo_share_copied, Toast.LENGTH_SHORT).show();
        }); body.addView(copy);
        viewport = new NestedScrollView(context); viewport.setId(R.id.photo_share_viewport);
        viewport.addView(body, new ViewGroup.LayoutParams(-1, -2));
        body.requestFocus();
        sheet.setContentView(viewport);
        sheet.setOnDismissListener(ignored -> {
            if (!closing && !destroyed) { removeFrameListener(); sheet = null; clearRows(); model.close(); }
        });
        sheet.show();
        Window window = sheet.getWindow();
        if (window != null) window.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE
                | WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_HIDDEN);
        body.requestFocus();
        visibleFrameListener = this::fitVisibleWindow;
        viewport.getViewTreeObserver().addOnGlobalLayoutListener(visibleFrameListener);
        ViewCompat.setOnApplyWindowInsetsListener(viewport, (view, insets) -> { fitVisibleWindow(); return insets; });
        ViewCompat.requestApplyInsets(viewport);
        viewport.post(this::fitVisibleWindow);
    }
    private void renderRecipients(List<PhotoShareRepository.Recipient> recipients) {
        clearRows(); rendered = recipients;
        for (PhotoShareRepository.Recipient recipient : recipients) {
            Context context = sheet.getContext();
            LinearLayout cell = new LinearLayout(context); cell.setOrientation(LinearLayout.VERTICAL); cell.setGravity(Gravity.CENTER_HORIZONTAL);
            cell.setPadding(dp(4), dp(12), dp(4), dp(8)); cell.setContentDescription(recipient.title);
            cell.setFocusable(true); cell.setClickable(true);
            ImageView avatar = new ImageView(context); avatar.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
            cell.addView(avatar, new LinearLayout.LayoutParams(dp(56), dp(56)));
            MessageAvatars.bind(avatar, recipient.photo, recipient.peerId < 0 || recipient.peerId > 2_000_000_000L);
            TextView name = new TextView(context); name.setText(recipient.title); name.setTextSize(12); name.setMaxLines(2);
            name.setGravity(Gravity.CENTER); name.setPadding(0, dp(6), 0, 0); cell.addView(name, new LinearLayout.LayoutParams(-1, -2));
            cell.setOnClickListener(view -> model.selectRecipient(recipient)); rows.addView(cell, new LinearLayout.LayoutParams(dp(84), -2));
        }
        if (searchExpanded) rows.post(this::revealRecipient);
    }
    private void createComposer(PhotoShareViewModel.State state) {
        Context context = new android.view.ContextThemeWrapper(activity, androidx.appcompat.R.style.Theme_AppCompat_Light_Dialog_Alert);
        LinearLayout body = body(context); body.setFocusableInTouchMode(true);
        recipientName = new TextView(context); body.addView(recipientName);
        sendStatus = new TextView(context); sendStatus.setPadding(0, dp(8), 0, dp(8)); body.addView(sendStatus);
        draft = new EditText(context); draft.setId(R.id.photo_share_draft_input); draft.setHint(R.string.photo_share_draft);
        draft.setImeOptions(draft.getImeOptions() | EditorInfo.IME_FLAG_NO_EXTRACT_UI | EditorInfo.IME_FLAG_NO_FULLSCREEN);
        draft.setMinLines(2); draft.setMaxLines(5); setText(draft, state.draft); watch(draft, model::setDraft); body.addView(draft);
        NestedScrollView scroll = new NestedScrollView(context); scroll.setId(R.id.photo_share_composer_viewport);
        scroll.addView(body, new ViewGroup.LayoutParams(-1, -2));
        body.requestFocus();
        composer = new AlertDialog.Builder(context).setTitle(R.string.photo_share_send_title).setView(scroll)
                .setPositiveButton(R.string.photo_share_send, null).setNegativeButton(R.string.photo_share_cancel, null).create();
        composer.setOnDismissListener(ignored -> { if (!closing && !destroyed) { composer = null; model.cancelRecipient(); } });
        composer.show();
        if (composer.getWindow() != null) composer.getWindow().setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE
                | WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_HIDDEN);
        body.requestFocus();
        composer.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(view -> model.send());
        composer.getButton(AlertDialog.BUTTON_NEGATIVE).setOnClickListener(view -> model.cancelRecipient());
    }
    private void clearRows() {
        if (rows == null) return;
        for (int i = 0; i < rows.getChildCount(); i++) {
            LinearLayout cell = (LinearLayout) rows.getChildAt(i);
            if (cell.getChildAt(0) instanceof ImageView) MessageAvatars.clear((ImageView) cell.getChildAt(0));
        }
        rows.removeAllViews(); rendered = null;
    }
    private void dismissSheet() {
        if (sheet == null) return;
        removeFrameListener();
        closing = true; sheet.setOnDismissListener(null); sheet.dismiss(); sheet = null; clearRows(); closing = false;
    }
    private void dismissComposer() {
        if (composer == null) return;
        closing = true; composer.setOnDismissListener(null); composer.dismiss(); composer = null; closing = false;
    }
    private void expandForSearch() {
        if (sheet == null || viewport == null) return;
        searchExpanded = true;
        View container = sheet.findViewById(com.google.android.material.R.id.design_bottom_sheet);
        if (container != null) {
            ViewGroup.LayoutParams params = container.getLayoutParams(); params.height = ViewGroup.LayoutParams.MATCH_PARENT;
            container.setLayoutParams(params);
        }
        ViewGroup.LayoutParams params = viewport.getLayoutParams(); params.height = ViewGroup.LayoutParams.MATCH_PARENT;
        viewport.setLayoutParams(params);
        sheet.getBehavior().setSkipCollapsed(true);
        sheet.getBehavior().setState(BottomSheetBehavior.STATE_EXPANDED);
        viewport.post(() -> { fitVisibleWindow(); revealRecipient(); });
    }
    /** Legacy resize and edge-to-edge windows report different sizes; pad only actual obscured pixels. */
    private void fitVisibleWindow() {
        if (sheet == null || sheet.getWindow() == null || viewport == null) return;
        View decor = sheet.getWindow().getDecorView();
        if (viewport.getHeight() == 0) return;
        Rect visible = new Rect(); decor.getWindowVisibleDisplayFrame(visible);
        if (visible.isEmpty()) return;
        int[] origin = new int[2]; viewport.getLocationOnScreen(origin);
        int bottom = Math.min(viewport.getHeight(), Math.max(0, origin[1] + viewport.getHeight() - visible.bottom));
        int top = searchExpanded ? Math.max(0, visible.top - origin[1]) : 0;
        if (viewport.getPaddingTop() != top || viewport.getPaddingBottom() != bottom) viewport.setPadding(0, top, 0, bottom);
    }
    private void revealRecipient() {
        if (sheet == null || !searchExpanded || rows == null || rows.getChildCount() == 0) return;
        View first = rows.getChildAt(0);
        if (first.getWidth() > 0 && first.getHeight() > 0) first.requestRectangleOnScreen(new Rect(0, 0, first.getWidth(), first.getHeight()), false);
    }
    private void removeFrameListener() {
        if (viewport != null && visibleFrameListener != null && viewport.getViewTreeObserver().isAlive())
            viewport.getViewTreeObserver().removeOnGlobalLayoutListener(visibleFrameListener);
        if (viewport != null) ViewCompat.setOnApplyWindowInsetsListener(viewport, null);
        visibleFrameListener = null;
    }
    private LinearLayout body(Context context) {
        LinearLayout body = new LinearLayout(context); body.setOrientation(LinearLayout.VERTICAL);
        body.setPadding(dp(20), dp(8), dp(20), dp(16)); return body;
    }
    private Button action(Context context, int id, int title) {
        Button button = new Button(context); button.setId(id); button.setText(title); button.setGravity(Gravity.START | Gravity.CENTER_VERTICAL);
        button.setAllCaps(false); return button;
    }
    private static void setText(EditText input, String value) { if (!input.getText().toString().equals(value)) { input.setText(value); input.setSelection(value.length()); } }
    private interface Changed { void value(String value); }
    private static void watch(EditText input, Changed changed) {
        input.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence value, int start, int count, int after) { }
            @Override public void onTextChanged(CharSequence value, int start, int before, int count) { changed.value(value.toString()); }
            @Override public void afterTextChanged(Editable value) { }
        });
    }
    private static int errorResource(PhotoSocialError error) {
        if (error == null) return R.string.photo_share_error;
        switch (error.kind) {
            case AUTH: return R.string.photo_share_error_auth;
            case ACCESS: return R.string.photo_share_error_access;
            case RATE_LIMITED: return R.string.photo_share_error_rate;
            default: return R.string.photo_share_error;
        }
    }
    private int dp(int value) { return Math.round(value * activity.getResources().getDisplayMetrics().density); }
}
