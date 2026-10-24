package com.vktea.share;

import android.app.Activity;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.graphics.Rect;
import android.os.Build;
import android.os.Bundle;
import android.text.Editable;
import android.text.TextUtils;
import android.text.TextWatcher;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewTreeObserver;
import android.view.Window;
import android.view.WindowManager;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputMethodManager;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.OnBackPressedCallback;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.view.ViewCompat;
import androidx.lifecycle.ViewModelProvider;

import com.google.android.material.bottomsheet.BottomSheetBehavior;
import com.google.android.material.bottomsheet.BottomSheetDialog;
import com.vktea.R;
import com.vktea.feed.PhotoSocialError;
import com.vktea.msg.MessageAvatars;

import java.util.List;

/** Transparent carrier: all destination choices and explicit confirmation stay in one bottom sheet. */
public class ShareActivity extends AppCompatActivity {
    public static final String EXTRA_ATTACHMENT = "share_attachment";
    private ShareViewModel model;
    private BottomSheetDialog sheet;
    private View content, destinations, targetsPanel, confirmation, footer, loading, morePanel, back, search;
    private View ownWall, community;
    private EditText query, draft;
    private TextView heading, status, destinationLabel, confirmStatus, recentStatus;
    private Button retry, confirm, recentRetry;
    private LinearLayout rows, recentRows;
    private SharePanelScrollView viewport;
    private List<ShareViewModel.Target> rendered, renderedRecent;
    private ViewTreeObserver.OnGlobalLayoutListener frameListener;
    private boolean bindingInputs, moreVisible, expandedForInput, destroyed;
    private ShareViewModel.Destination boundDestination;

    public static void start(Context context, ShareAttachment attachment) {
        Intent intent = new Intent(context, ShareActivity.class).putExtra(EXTRA_ATTACHMENT, attachment);
        if (!(context instanceof Activity)) intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        context.startActivity(intent);
    }

    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        ShareAttachment attachment = getIntent().getParcelableExtra(EXTRA_ATTACHMENT);
        if (attachment == null || !attachment.hasIdentity()) { finish(); return; }
        moreVisible = state != null && state.getBoolean("share_more", false);
        expandedForInput = state != null && state.getBoolean("share_expanded", false);
        sheet = new BottomSheetDialog(this, R.style.Theme_VKTea_PhotoShare);
        content = getLayoutInflater().inflate(R.layout.activity_share, null, false);
        sheet.setContentView(content);
        destinations = content.findViewById(R.id.share_destinations);
        morePanel = content.findViewById(R.id.share_more_panel);
        targetsPanel = content.findViewById(R.id.share_targets_panel);
        confirmation = content.findViewById(R.id.share_confirmation_panel);
        footer = content.findViewById(R.id.share_footer);
        loading = content.findViewById(R.id.share_loading);
        heading = content.findViewById(R.id.share_targets_heading);
        query = content.findViewById(R.id.share_query);
        status = content.findViewById(R.id.share_status);
        retry = content.findViewById(R.id.share_retry);
        rows = content.findViewById(R.id.share_targets);
        recentRows = content.findViewById(R.id.share_recent_targets);
        recentStatus = content.findViewById(R.id.share_recent_status);
        recentRetry = content.findViewById(R.id.share_recent_retry);
        destinationLabel = content.findViewById(R.id.share_destination);
        draft = content.findViewById(R.id.share_draft);
        confirmStatus = content.findViewById(R.id.share_confirm_status);
        confirm = content.findViewById(R.id.share_confirm);
        back = content.findViewById(R.id.share_header_back);
        search = content.findViewById(R.id.share_header_search);
        viewport = content.findViewById(R.id.share_viewport);
        model = createShareViewModel();
        LinearLayout actions = content.findViewById(R.id.share_actions);
        ownWall = action(actions, R.id.share_own_wall, R.drawable.ic_repost_24dp, R.string.share_sheet_wall);
        community = action(actions, R.id.share_community, R.drawable.ic_menu_communities, R.string.share_sheet_community);
        View copy = action(actions, R.id.share_copy, R.drawable.share_sheet_copy, R.string.share_sheet_copy);
        View more = action(actions, R.id.share_more, R.drawable.share_sheet_more, R.string.share_sheet_more);
        ownWall.setOnClickListener(view -> choose(ShareViewModel.Destination.OWN_WALL));
        community.setOnClickListener(view -> choose(ShareViewModel.Destination.COMMUNITY));
        copy.setOnClickListener(view -> copyLink());
        more.setOnClickListener(view -> { moreVisible = true; expandedForInput = false; render(model.state().getValue()); });
        content.findViewById(R.id.share_friends).setOnClickListener(view -> choose(ShareViewModel.Destination.FRIENDS));
        content.findViewById(R.id.share_dialog).setOnClickListener(view -> choose(ShareViewModel.Destination.DIALOG));
        search.setOnClickListener(view -> {
            ShareViewModel.State current = model.state().getValue();
            if (current == null || current.destination == null) choose(ShareViewModel.Destination.DIALOG);
            query.requestFocus();
            query.post(() -> {
                InputMethodManager keyboard = (InputMethodManager) getSystemService(INPUT_METHOD_SERVICE);
                if (keyboard != null) keyboard.showSoftInput(query, InputMethodManager.SHOW_IMPLICIT);
            });
        });
        for (EditText input : new EditText[]{query, draft}) {
            input.setImeOptions(input.getImeOptions() | EditorInfo.IME_FLAG_NO_EXTRACT_UI | EditorInfo.IME_FLAG_NO_FULLSCREEN);
            input.setOnFocusChangeListener((view, focused) -> { if (focused) { expandedForInput = true; fitVisibleWindow(); } });
        }
        watch(query, value -> model.setQuery(value));
        watch(draft, value -> model.setDraft(value));
        retry.setOnClickListener(view -> model.refresh());
        recentRetry.setOnClickListener(view -> model.retryRecent());
        confirm.setOnClickListener(view -> model.submit());
        back.setOnClickListener(view -> goBack());
        getOnBackPressedDispatcher().addCallback(this, new OnBackPressedCallback(true) {
            @Override public void handleOnBackPressed() { goBack(); }
        });
        sheet.setOnKeyListener((dialog, key, event) -> {
            if (key != KeyEvent.KEYCODE_BACK) return false;
            if (event.getAction() == KeyEvent.ACTION_UP) goBack();
            return true;
        });
        sheet.setOnDismissListener(dialog -> { if (!destroyed) finish(); });
        sheet.show();
        Window window = sheet.getWindow();
        if (window != null) {
            window.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE | WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_HIDDEN);
            window.addFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND);
            window.setDimAmount(.55f);
        }
        View container = sheet.findViewById(com.google.android.material.R.id.design_bottom_sheet);
        if (container != null) container.setBackgroundColor(0xffffffff);
        sheet.getBehavior().setSkipCollapsed(true);
        sheet.getBehavior().setState(BottomSheetBehavior.STATE_EXPANDED);
        content.requestFocus();
        frameListener = this::fitVisibleWindow;
        content.getViewTreeObserver().addOnGlobalLayoutListener(frameListener);
        ViewCompat.setOnApplyWindowInsetsListener(content, (view, insets) -> { view.post(this::fitVisibleWindow); return insets; });
        if (model.state().getValue() == null || model.state().getValue().attachment == null) model.open(attachment);
        model.state().observe(this, this::render);
        model.preloadRecent();
        content.post(this::fitVisibleWindow);
    }

    protected ShareViewModel createShareViewModel() { return new ViewModelProvider(this).get(ShareViewModel.class); }
    protected void bindAvatar(ImageView view, String url, boolean group) { MessageAvatars.bind(view, url, group); }
    private void choose(ShareViewModel.Destination value) {
        moreVisible = false; expandedForInput = false;
        model.chooseDestination(value);
    }
    private void goBack() {
        ShareViewModel.State state = model.state().getValue();
        if (state != null && state.busy) return;
        hideKeyboard(); expandedForInput = false;
        if (state == null || state.destination == null) {
            if (moreVisible) { moreVisible = false; render(state); } else finish();
        } else model.back();
    }
    private void render(ShareViewModel.State state) {
        if (state == null || isFinishing() || destroyed) return;
        if (!state.open) { showEvent(state); finish(); return; }
        boolean choosing = state.destination == null;
        boolean selecting = !choosing && state.selected == null;
        boolean composing = !choosing && state.selected != null;
        boolean friends = state.destination == ShareViewModel.Destination.FRIENDS;
        boolean dialog = friends || state.destination == ShareViewModel.Destination.DIALOG;
        destinations.setVisibility(choosing && !moreVisible ? View.VISIBLE : View.GONE);
        morePanel.setVisibility(choosing && moreVisible ? View.VISIBLE : View.GONE);
        targetsPanel.setVisibility(selecting ? View.VISIBLE : View.GONE);
        confirmation.setVisibility(composing ? View.VISIBLE : View.GONE);
        footer.setVisibility(composing ? View.VISIBLE : View.GONE);
        back.setVisibility(!choosing || moreVisible ? View.VISIBLE : View.GONE);
        search.setVisibility(!composing && !moreVisible ? View.VISIBLE : View.GONE);
        ownWall.setEnabled(state.attachment.canRepost); ownWall.setAlpha(state.attachment.canRepost ? 1f : .4f);
        community.setEnabled(state.attachment.canRepost); community.setAlpha(state.attachment.canRepost ? 1f : .4f);
        sheet.setCancelable(!state.busy); sheet.setCanceledOnTouchOutside(!state.busy);
        sheet.getBehavior().setDraggable(!state.busy);
        if (choosing) {
            if (renderedRecent != state.recentTargets) renderRecent(state.recentTargets);
            recentStatus.setText(state.recentLoading ? getString(R.string.share_loading) : state.recentError != null
                    ? getString(errorResource(state.recentError)) : state.recentLoaded && state.recentTargets.isEmpty()
                    ? getString(R.string.share_no_recipients) : "");
            recentStatus.setVisibility(recentStatus.length() == 0 ? View.GONE : View.VISIBLE);
            recentRetry.setVisibility(state.recentError != null ? View.VISIBLE : View.GONE);
            recentRetry.setEnabled(!state.recentLoading);
        }
        if (boundDestination != state.destination) { clearRows(rows); rendered = null; boundDestination = state.destination; }
        if (selecting) {
            heading.setText(friends ? R.string.share_friends_title : dialog ? R.string.share_dialogs_title : R.string.share_communities_title);
            query.setVisibility(View.VISIBLE);
            query.setHint(friends ? R.string.share_search_friends : dialog ? R.string.share_search : R.string.share_search_communities);
            setText(query, state.query);
            loading.setVisibility(state.loading ? View.VISIBLE : View.GONE);
            status.setText(state.loading ? R.string.share_loading : state.error != null ? errorResource(state.error)
                    : state.targets.isEmpty() ? friends ? R.string.share_no_friends : dialog ? R.string.share_no_recipients : R.string.share_no_communities
                    : !dialog ? R.string.share_communities_hint : state.query.trim().isEmpty()
                    ? friends ? R.string.share_friends_hint : R.string.share_dialogs_hint : R.string.share_search_results);
            retry.setVisibility(state.error != null ? View.VISIBLE : View.GONE); retry.setEnabled(!state.loading);
            if (rendered != state.targets) renderTargets(state.targets, dialog);
        } else if (rendered != null) { clearRows(rows); rendered = null; }
        if (composing) {
            String target = state.destination == ShareViewModel.Destination.OWN_WALL ? getString(R.string.share_own_wall) : state.selected.title;
            destinationLabel.setText(getString(dialog ? R.string.share_target : R.string.share_publish_target, target));
            draft.setHint(dialog ? R.string.share_message_hint : R.string.share_comment_hint);
            setText(draft, state.draft); draft.setEnabled(!state.busy && !state.retrying);
            confirm.setEnabled(state.canSubmit && !state.busy);
            confirm.setText(state.retrying && state.canSubmit ? dialog ? R.string.share_send_again : R.string.share_publish_again
                    : dialog ? R.string.share_send : R.string.share_publish);
            confirmStatus.setText(state.busy ? getString(dialog ? R.string.share_sending : R.string.share_publishing)
                    : state.error == null ? "" : uncertain(state.error) ? getString(dialog ? R.string.share_unknown_send : R.string.share_unknown_publish)
                    : getString(errorResource(state.error)));
            confirmStatus.setVisibility(confirmStatus.length() == 0 ? View.GONE : View.VISIBLE);
        }
        showEvent(state);
        content.post(this::fitVisibleWindow);
    }
    private View action(LinearLayout parent, int id, int icon, int label) {
        LinearLayout cell = new LinearLayout(this); cell.setId(id); cell.setOrientation(LinearLayout.VERTICAL);
        cell.setGravity(Gravity.CENTER_HORIZONTAL); cell.setPadding(dp(2), dp(4), dp(2), dp(4));
        cell.setClickable(true); cell.setFocusable(true); cell.setMinimumHeight(dp(108));
        ImageView image = new ImageView(this); image.setImageResource(icon); image.setPadding(dp(20), dp(20), dp(20), dp(20));
        image.setImageTintList(android.content.res.ColorStateList.valueOf(0xff909499));
        image.setBackgroundResource(R.drawable.share_sheet_circle); image.setElevation(dp(1));
        image.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        cell.addView(image, new LinearLayout.LayoutParams(dp(64), dp(64)));
        TextView text = new TextView(this); text.setText(label); text.setTextColor(0xff424446); text.setTextSize(12);
        text.setGravity(Gravity.CENTER); text.setMaxLines(2); text.setPadding(0, dp(8), 0, 0);
        cell.addView(text, new LinearLayout.LayoutParams(-1, -2)); parent.addView(cell, new LinearLayout.LayoutParams(0, -2, 1));
        return cell;
    }
    private void renderRecent(List<ShareViewModel.Target> targets) {
        clearRows(recentRows); renderedRecent = targets;
        for (ShareViewModel.Target target : targets) {
            LinearLayout cell = new LinearLayout(this); cell.setOrientation(LinearLayout.VERTICAL); cell.setGravity(Gravity.CENTER_HORIZONTAL);
            cell.setPadding(dp(2), dp(4), dp(2), dp(8)); cell.setClickable(true); cell.setFocusable(true);
            ImageView avatar = new ImageView(this); avatar.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
            cell.addView(avatar, new LinearLayout.LayoutParams(dp(64), dp(64)));
            bindAvatar(avatar, target.photo, target.id < 0 || target.id >= 2_000_000_000L);
            TextView title = new TextView(this); title.setText(target.title); title.setTextColor(0xff424446); title.setTextSize(12);
            title.setGravity(Gravity.CENTER); title.setMaxLines(2); title.setEllipsize(TextUtils.TruncateAt.END); title.setPadding(0, dp(6), 0, 0);
            cell.addView(title, new LinearLayout.LayoutParams(-1, -2));
            cell.setOnClickListener(view -> { expandedForInput = false; model.selectRecent(target); });
            recentRows.addView(cell, new LinearLayout.LayoutParams(dp(80), -2));
        }
    }
    private void renderTargets(List<ShareViewModel.Target> targets, boolean dialog) {
        clearRows(rows); rendered = targets;
        for (ShareViewModel.Target target : targets) {
            LinearLayout row = new LinearLayout(this); row.setOrientation(LinearLayout.HORIZONTAL); row.setGravity(Gravity.CENTER_VERTICAL);
            row.setMinimumHeight(dp(64)); row.setPadding(dp(4), dp(8), dp(4), dp(8)); row.setClickable(true); row.setFocusable(true);
            TypedValue ripple = new TypedValue(); getTheme().resolveAttribute(android.R.attr.selectableItemBackground, ripple, true);
            row.setBackgroundResource(ripple.resourceId);
            ImageView avatar = new ImageView(this); avatar.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
            row.addView(avatar, new LinearLayout.LayoutParams(dp(48), dp(48)));
            bindAvatar(avatar, target.photo, !dialog || target.id < 0 || target.id >= 2_000_000_000L);
            TextView name = new TextView(this); name.setText(target.title); name.setTextColor(0xff2e3033); name.setTextSize(16); name.setPadding(dp(12), 0, 0, 0);
            row.addView(name, new LinearLayout.LayoutParams(0, -2, 1));
            row.setOnClickListener(view -> { hideKeyboard(); expandedForInput = false; model.selectTarget(target); });
            rows.addView(row, new LinearLayout.LayoutParams(-1, -2));
        }
    }
    private void clearRows(LinearLayout list) {
        if (list == null) return;
        for (int i = 0; i < list.getChildCount(); i++) {
            View child = ((LinearLayout) list.getChildAt(i)).getChildAt(0);
            if (child instanceof ImageView) MessageAvatars.clear((ImageView) child);
        }
        list.removeAllViews();
    }
    private void fitVisibleWindow() {
        if (destroyed || sheet == null || sheet.getWindow() == null || content == null) return;
        View decor = sheet.getWindow().getDecorView(); Rect visible = new Rect(); decor.getWindowVisibleDisplayFrame(visible);
        int available = visible.height() > 0 ? visible.height() : getResources().getDisplayMetrics().heightPixels;
        int[] origin = new int[2]; content.getLocationOnScreen(origin);
        int overlap = visible.isEmpty() || content.getHeight() == 0 ? 0 : Math.max(0, origin[1] + content.getHeight() - visible.bottom);
        int left = visible.isEmpty() ? 0 : Math.max(0, visible.left - origin[0]);
        int right = visible.isEmpty() || content.getWidth() == 0 ? 0 : Math.max(0, origin[0] + content.getWidth() - visible.right);
        if (content.getPaddingBottom() != overlap || content.getPaddingLeft() != left || content.getPaddingRight() != right)
            content.setPadding(left, 0, right, overlap);
        int limit = expandedForInput ? available : Math.round(available * .8f);
        int footerHeight = footer.getVisibility() == View.VISIBLE ? Math.max(dp(72), footer.getMeasuredHeight()) : 0;
        viewport.setMaximumHeight(Math.max(1, limit - dp(56) - footerHeight));
    }
    private void copyLink() {
        ShareViewModel.State state = model.state().getValue();
        if (state == null || state.attachment == null) return;
        ClipboardManager clipboard = (ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
        if (clipboard != null) clipboard.setPrimaryClip(ClipData.newPlainText(getString(R.string.share_title), state.attachment.canonicalLink()));
        if (Build.VERSION.SDK_INT < 33) Toast.makeText(this, R.string.share_copied, Toast.LENGTH_SHORT).show();
        finish();
    }
    private void showEvent(ShareViewModel.State state) {
        if (state.event == null) return;
        model.consumeEvent();
        if (state.event == ShareViewModel.Event.SENT || state.event == ShareViewModel.Event.PUBLISHED)
            Toast.makeText(this, state.event == ShareViewModel.Event.SENT ? R.string.share_sent : R.string.share_published, Toast.LENGTH_SHORT).show();
    }
    private void setText(EditText input, String value) {
        if (input.getText().toString().equals(value)) return;
        bindingInputs = true; input.setText(value); input.setSelection(value.length()); bindingInputs = false;
    }
    private interface Changed { void value(String value); }
    private void watch(EditText input, Changed changed) {
        input.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence text, int start, int count, int after) { }
            @Override public void onTextChanged(CharSequence text, int start, int before, int count) { if (!bindingInputs) changed.value(text.toString()); }
            @Override public void afterTextChanged(Editable text) { }
        });
    }
    private void hideKeyboard() {
        InputMethodManager keyboard = (InputMethodManager) getSystemService(INPUT_METHOD_SERVICE);
        if (keyboard != null) keyboard.hideSoftInputFromWindow(content.getWindowToken(), 0);
        content.requestFocus();
    }
    private static boolean uncertain(PhotoSocialError error) { return error.kind == PhotoSocialError.Kind.NETWORK || error.kind == PhotoSocialError.Kind.UNAVAILABLE; }
    private static int errorResource(PhotoSocialError error) {
        switch (error.kind) {
            case AUTH: return R.string.share_error_auth;
            case ACCESS: return R.string.share_error_access;
            case RATE_LIMITED: return R.string.share_error_rate;
            case NETWORK: return R.string.share_error_network;
            default: return R.string.share_error;
        }
    }
    private int dp(int value) { return Math.round(value * getResources().getDisplayMetrics().density); }
    @Override protected void onSaveInstanceState(Bundle state) {
        state.putBoolean("share_more", moreVisible); state.putBoolean("share_expanded", expandedForInput); super.onSaveInstanceState(state);
    }
    @Override protected void onDestroy() {
        destroyed = true;
        if (content != null && frameListener != null && content.getViewTreeObserver().isAlive()) content.getViewTreeObserver().removeOnGlobalLayoutListener(frameListener);
        clearRows(rows); clearRows(recentRows);
        if (sheet != null) { sheet.setOnDismissListener(null); sheet.dismiss(); sheet = null; }
        super.onDestroy();
    }
}
