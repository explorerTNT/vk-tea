package com.vktea.share;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.graphics.Color;
import android.os.Bundle;
import android.text.Editable;
import android.text.TextWatcher;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
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
import androidx.appcompat.widget.Toolbar;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowCompat;
import androidx.core.view.WindowInsetsCompat;
import androidx.lifecycle.ViewModelProvider;

import com.vktea.R;
import com.vktea.feed.PhotoSocialError;
import com.vktea.msg.MessageAvatars;

import java.util.List;

/** A destination and an explicit final confirmation are required before any VK mutation. */
public class ShareActivity extends AppCompatActivity {
    public static final String EXTRA_ATTACHMENT = "share_attachment";
    private ShareViewModel model;
    private View destinations, targetsPanel, confirmation, footer, loading, permissionHint, ownWall, community;
    private EditText query, draft;
    private TextView attachmentLabel, heading, status, destinationLabel, confirmStatus;
    private Button retry, confirm;
    private LinearLayout rows;
    private List<ShareViewModel.Target> rendered;
    private boolean bindingInputs;
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
        WindowCompat.setDecorFitsSystemWindows(getWindow(), false);
        setContentView(R.layout.activity_share);
        View root = findViewById(R.id.share_root);
        root.setFocusableInTouchMode(true);
        root.requestFocus();
        ViewCompat.setOnApplyWindowInsetsListener(root, (view, insets) -> {
            Insets bars = insets.getInsets(WindowInsetsCompat.Type.systemBars() | WindowInsetsCompat.Type.displayCutout());
            Insets ime = insets.getInsets(WindowInsetsCompat.Type.ime());
            view.setPadding(bars.left, bars.top, bars.right, Math.max(bars.bottom, ime.bottom));
            return WindowInsetsCompat.CONSUMED;
        });
        getWindow().setStatusBarColor(Color.TRANSPARENT);
        getWindow().setNavigationBarColor(Color.TRANSPARENT);
        WindowCompat.getInsetsController(getWindow(), root).setAppearanceLightStatusBars(false);
        WindowCompat.getInsetsController(getWindow(), root).setAppearanceLightNavigationBars(false);
        destinations = findViewById(R.id.share_destinations);
        targetsPanel = findViewById(R.id.share_targets_panel);
        confirmation = findViewById(R.id.share_confirmation_panel);
        footer = findViewById(R.id.share_footer);
        loading = findViewById(R.id.share_loading);
        attachmentLabel = findViewById(R.id.share_attachment);
        permissionHint = findViewById(R.id.share_permission_hint);
        heading = findViewById(R.id.share_targets_heading);
        query = findViewById(R.id.share_query);
        status = findViewById(R.id.share_status);
        retry = findViewById(R.id.share_retry);
        rows = findViewById(R.id.share_targets);
        destinationLabel = findViewById(R.id.share_destination);
        draft = findViewById(R.id.share_draft);
        confirmStatus = findViewById(R.id.share_confirm_status);
        confirm = findViewById(R.id.share_confirm);
        ownWall = findViewById(R.id.share_own_wall);
        community = findViewById(R.id.share_community);
        for (EditText input : new EditText[]{query, draft}) {
            input.setImeOptions(input.getImeOptions() | EditorInfo.IME_FLAG_NO_EXTRACT_UI | EditorInfo.IME_FLAG_NO_FULLSCREEN);
        }
        model = createShareViewModel();
        watch(query, value -> model.setQuery(value));
        watch(draft, value -> model.setDraft(value));
        ownWall.setOnClickListener(view -> model.chooseDestination(ShareViewModel.Destination.OWN_WALL));
        community.setOnClickListener(view -> model.chooseDestination(ShareViewModel.Destination.COMMUNITY));
        findViewById(R.id.share_friends).setOnClickListener(view -> model.chooseDestination(ShareViewModel.Destination.FRIENDS));
        findViewById(R.id.share_dialog).setOnClickListener(view -> model.chooseDestination(ShareViewModel.Destination.DIALOG));
        retry.setOnClickListener(view -> model.refresh());
        confirm.setOnClickListener(view -> model.submit());
        ((Toolbar) findViewById(R.id.share_toolbar)).setNavigationOnClickListener(view -> goBack());
        getOnBackPressedDispatcher().addCallback(this, new OnBackPressedCallback(true) {
            @Override public void handleOnBackPressed() { goBack(); }
        });
        if (model.state().getValue() == null || model.state().getValue().attachment == null) model.open(attachment);
        model.state().observe(this, this::render);
        ViewCompat.requestApplyInsets(root);
    }

    protected ShareViewModel createShareViewModel() { return new ViewModelProvider(this).get(ShareViewModel.class); }
    protected void bindAvatar(ImageView view, String url, boolean group) { MessageAvatars.bind(view, url, group); }

    private void goBack() {
        ShareViewModel.State state = model.state().getValue();
        if (state != null && state.busy) return;
        hideKeyboard();
        if (state == null || state.destination == null) finish(); else model.back();
    }

    private void render(ShareViewModel.State state) {
        if (state == null || isFinishing()) return;
        if (!state.open) {
            showEvent(state);
            finish();
            return;
        }
        boolean choosing = state.destination == null;
        boolean selecting = !choosing && state.selected == null;
        boolean composing = !choosing && state.selected != null;
        boolean friends = state.destination == ShareViewModel.Destination.FRIENDS;
        boolean dialog = friends || state.destination == ShareViewModel.Destination.DIALOG;
        destinations.setVisibility(choosing ? View.VISIBLE : View.GONE);
        targetsPanel.setVisibility(selecting ? View.VISIBLE : View.GONE);
        confirmation.setVisibility(composing ? View.VISIBLE : View.GONE);
        footer.setVisibility(composing ? View.VISIBLE : View.GONE);
        attachmentLabel.setText("photo".equals(state.attachment.type) ? R.string.share_photo : R.string.share_post);
        ownWall.setEnabled(state.attachment.canRepost);
        community.setEnabled(state.attachment.canRepost);
        permissionHint.setVisibility(state.attachment.canRepost ? View.GONE : View.VISIBLE);
        if (boundDestination != state.destination) {
            clearRows();
            boundDestination = state.destination;
        }
        if (selecting) {
            heading.setText(friends ? R.string.share_friends_title : dialog ? R.string.share_dialogs_title : R.string.share_communities_title);
            query.setVisibility(dialog ? View.VISIBLE : View.GONE);
            query.setHint(friends ? R.string.share_search_friends : R.string.share_search);
            setText(query, state.query);
            query.setEnabled(!state.busy);
            loading.setVisibility(state.loading ? View.VISIBLE : View.GONE);
            status.setText(state.loading ? R.string.share_loading : state.error != null ? errorResource(state.error)
                    : state.targets.isEmpty() ? friends ? R.string.share_no_friends : dialog ? R.string.share_no_recipients : R.string.share_no_communities
                    : !dialog ? R.string.share_communities_hint : state.query.trim().isEmpty()
                    ? friends ? R.string.share_friends_hint : R.string.share_dialogs_hint : R.string.share_search_results);
            retry.setVisibility(state.error != null ? View.VISIBLE : View.GONE);
            retry.setEnabled(!state.loading && !state.busy);
            if (rendered != state.targets) renderTargets(state.targets, dialog);
        } else if (rendered != null) clearRows();
        if (composing) {
            String target = state.destination == ShareViewModel.Destination.OWN_WALL ? getString(R.string.share_own_wall) : state.selected.title;
            destinationLabel.setText(getString(dialog ? R.string.share_target : R.string.share_publish_target, target));
            draft.setHint(dialog ? R.string.share_message_hint : R.string.share_comment_hint);
            setText(draft, state.draft);
            draft.setEnabled(!state.busy && !state.retrying);
            confirm.setEnabled(state.canSubmit && !state.busy);
            confirm.setText(state.retrying && state.canSubmit ? dialog ? R.string.share_send_again : R.string.share_publish_again
                    : dialog ? R.string.share_send : R.string.share_publish);
            confirmStatus.setText(state.busy ? getString(dialog ? R.string.share_sending : R.string.share_publishing)
                    : state.error == null ? "" : uncertain(state.error)
                    ? getString(dialog ? R.string.share_unknown_send : R.string.share_unknown_publish)
                    : getString(errorResource(state.error)));
            confirmStatus.setVisibility(confirmStatus.length() == 0 ? View.GONE : View.VISIBLE);
        }
        showEvent(state);
    }

    private void renderTargets(List<ShareViewModel.Target> targets, boolean dialog) {
        clearRows();
        rendered = targets;
        for (ShareViewModel.Target target : targets) {
            LinearLayout row = new LinearLayout(this);
            row.setOrientation(LinearLayout.HORIZONTAL);
            row.setGravity(Gravity.CENTER_VERTICAL);
            row.setMinimumHeight(dp(64));
            row.setPadding(dp(4), dp(8), dp(4), dp(8));
            row.setClickable(true); row.setFocusable(true);
            TypedValue ripple = new TypedValue();
            getTheme().resolveAttribute(android.R.attr.selectableItemBackground, ripple, true);
            row.setBackgroundResource(ripple.resourceId);
            ImageView avatar = new ImageView(this);
            avatar.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
            row.addView(avatar, new LinearLayout.LayoutParams(dp(48), dp(48)));
            bindAvatar(avatar, target.photo, !dialog || target.id < 0 || target.id >= 2_000_000_000L);
            TextView name = new TextView(this);
            name.setText(target.title); name.setTextColor(0xff2e3033); name.setTextSize(16);
            name.setPadding(dp(12), 0, 0, 0);
            row.addView(name, new LinearLayout.LayoutParams(0, -2, 1));
            row.setOnClickListener(view -> {
                hideKeyboard();
                model.selectTarget(target);
            });
            rows.addView(row, new LinearLayout.LayoutParams(-1, -2));
        }
    }

    private void clearRows() {
        if (rows == null) return;
        for (int i = 0; i < rows.getChildCount(); i++) {
            View child = ((LinearLayout) rows.getChildAt(i)).getChildAt(0);
            if (child instanceof ImageView) MessageAvatars.clear((ImageView) child);
        }
        rows.removeAllViews(); rendered = null;
    }
    private void showEvent(ShareViewModel.State state) {
        if (state.event == null) return;
        model.consumeEvent();
        if (state.event == ShareViewModel.Event.SENT || state.event == ShareViewModel.Event.PUBLISHED) {
            Toast.makeText(this, state.event == ShareViewModel.Event.SENT ? R.string.share_sent : R.string.share_published,
                    Toast.LENGTH_SHORT).show();
        }
    }
    private void setText(EditText input, String value) {
        if (input.getText().toString().equals(value)) return;
        bindingInputs = true;
        input.setText(value); input.setSelection(value.length());
        bindingInputs = false;
    }
    private interface Changed { void value(String value); }
    private void watch(EditText input, Changed changed) {
        input.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence text, int start, int count, int after) { }
            @Override public void onTextChanged(CharSequence text, int start, int before, int count) {
                if (!bindingInputs) changed.value(text.toString());
            }
            @Override public void afterTextChanged(Editable text) { }
        });
    }
    private void hideKeyboard() {
        InputMethodManager keyboard = (InputMethodManager) getSystemService(INPUT_METHOD_SERVICE);
        if (keyboard != null) keyboard.hideSoftInputFromWindow(findViewById(R.id.share_root).getWindowToken(), 0);
        findViewById(R.id.share_root).requestFocus();
    }
    private static boolean uncertain(PhotoSocialError error) {
        return error.kind == PhotoSocialError.Kind.NETWORK || error.kind == PhotoSocialError.Kind.UNAVAILABLE;
    }
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
    @Override protected void onDestroy() { clearRows(); super.onDestroy(); }
}
