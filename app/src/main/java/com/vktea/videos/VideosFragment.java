package com.vktea.videos;

import android.content.Intent;
import android.content.Context;
import android.net.Uri;
import android.text.InputFilter;
import android.widget.LinearLayout;
import android.widget.Toast;
import android.widget.VideoView;
import android.widget.ScrollView;
import android.widget.Button;
import android.text.InputType;
import android.os.Handler;
import android.os.Looper;
import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.appcompat.app.AlertDialog;
import com.vktea.auth.Session;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.inputmethod.InputMethodManager;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.TextView;
import androidx.activity.OnBackPressedCallback;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;
import androidx.lifecycle.ViewModelProvider;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout;
import com.bumptech.glide.Glide;
import com.google.android.material.tabs.TabLayout;
import com.vktea.R;
import com.vktea.feed.VideoPlayerActivity;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

/** Browses the selected owner's videos and albums, plus VK video search. */
@androidx.annotation.OptIn(markerClass = androidx.media3.common.util.UnstableApi.class)
public class VideosFragment extends Fragment {
    private static final String OWNER = "videos_owner";
    public static VideosFragment newInstance(long ownerId) {
        VideosFragment fragment = new VideosFragment(); Bundle args = new Bundle(); args.putLong(OWNER, ownerId); fragment.setArguments(args); return fragment;
    }
    private VideosViewModel model;
    private TabLayout tabs;
    private RecyclerView list;
    private SwipeRefreshLayout refresh;
    private EditText search;
    private TextView notice, empty, more, albumBack, writeAction;
    private VideoWriter writer;
    private VideoTrimmer trimmer;
    private AlertDialog saving;
    private final ActivityResultLauncher<String> videoPicker = registerForActivityResult(new ActivityResultContracts.GetContent(), uri -> {
        if (uri != null && "content".equals(uri.getScheme()) && isAdded() && ownsSection()) editDialog(null, uri);
    });
    private View noticeRow, emptyState, progress, searchRow;
    private Adapter adapter;
    private boolean rendering, writePending;
    private OnBackPressedCallback albumBackHandler;

    @Override public void onCreate(@Nullable Bundle saved) {
        super.onCreate(saved);
        long owner = getArguments() == null ? 0 : getArguments().getLong(OWNER, 0);
        model = new ViewModelProvider(this, new VideosViewModel.Factory(requireContext(), owner)).get(VideosViewModel.class);
    }
    @Nullable @Override public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup parent, @Nullable Bundle saved) {
        return inflater.inflate(R.layout.fragment_videos, parent, false);
    }
    @Override public void onViewCreated(@NonNull View view, @Nullable Bundle saved) {
        albumBackHandler = new OnBackPressedCallback(false) {
            @Override public void handleOnBackPressed() { model.backToAlbums(); }
        };
        requireActivity().getOnBackPressedDispatcher().addCallback(getViewLifecycleOwner(), albumBackHandler);
        tabs = view.findViewById(R.id.videos_tabs); list = view.findViewById(R.id.videos_list);
        refresh = view.findViewById(R.id.videos_refresh); search = view.findViewById(R.id.videos_search_input);
        writeAction = view.findViewById(R.id.videos_write_action);
        writeAction.setOnClickListener(v -> { if (!ownsSection()) return; VideosViewModel.State state = model.current();
            if (state.mode == VideosViewModel.ALBUMS && state.album == 0) editDialog(null, null);
            else videoPicker.launch("video/*"); });
        notice = view.findViewById(R.id.videos_notice); empty = view.findViewById(R.id.videos_empty_label);
        more = view.findViewById(R.id.videos_more); albumBack = view.findViewById(R.id.videos_album_back);
        noticeRow = view.findViewById(R.id.videos_notice_row); emptyState = view.findViewById(R.id.videos_empty_state);
        progress = view.findViewById(R.id.videos_progress); searchRow = view.findViewById(R.id.videos_search_row);
        tabs.addTab(tabs.newTab().setText(R.string.videos_my)); tabs.addTab(tabs.newTab().setText(R.string.videos_albums));
        tabs.addTab(tabs.newTab().setText(R.string.videos_search_tab));
        tabs.addOnTabSelectedListener(new TabLayout.OnTabSelectedListener() {
            @Override public void onTabSelected(TabLayout.Tab tab) { if (!rendering) { model.setMode(tab.getPosition()); list.scrollToPosition(0); } }
            @Override public void onTabUnselected(TabLayout.Tab tab) { }
            @Override public void onTabReselected(TabLayout.Tab tab) { }
        });
        adapter = new Adapter(this::open, this::editDialog); list.setLayoutManager(new LinearLayoutManager(requireContext())); list.setAdapter(adapter);
        refresh.setColorSchemeResources(R.color.vk_blue); refresh.setOnRefreshListener(model::refresh);
        refresh.setOnChildScrollUpCallback((parent, child) -> list != null && list.canScrollVertically(-1));
        view.findViewById(R.id.videos_retry).setOnClickListener(v -> model.loadMore());
        more.setOnClickListener(v -> model.loadMore()); albumBack.setOnClickListener(v -> model.backToAlbums());
        view.findViewById(R.id.videos_search_button).setOnClickListener(v -> performSearch());
        search.setOnEditorActionListener((v, action, event) -> { performSearch(); return true; });
        model.state().observe(getViewLifecycleOwner(), this::render); model.ensureLoaded();
        if (consumeInterruptedWrite(saved)) {
            new AlertDialog.Builder(requireContext()).setMessage(R.string.videos_write_uncertain).setPositiveButton(android.R.string.ok, null).show();
        }
    }
    public void showVideosSearch() {
        if (search == null || !isAdded()) return;
        model.setMode(VideosViewModel.SEARCH); search.requestFocus();
        search.post(() -> { if (search != null && search.getWindowToken() != null) {
            InputMethodManager keyboard = (InputMethodManager) search.getContext().getSystemService(Context.INPUT_METHOD_SERVICE);
            if (keyboard != null) keyboard.showSoftInput(search, InputMethodManager.SHOW_IMPLICIT);
        }});
    }
    private void performSearch() {
        if (search == null) return;
        model.search(search.getText().toString()); list.scrollToPosition(0);
        InputMethodManager keyboard = (InputMethodManager) search.getContext().getSystemService(Context.INPUT_METHOD_SERVICE);
        if (keyboard != null) keyboard.hideSoftInputFromWindow(search.getWindowToken(), 0);
        search.clearFocus();
    }
    private void open(VideoEntry row) {
        if (!isAdded()) return;
        if (row.album) { model.openAlbum(row); list.scrollToPosition(0); return; }
        Intent intent = new Intent(requireContext(), VideoPlayerActivity.class).putExtra("owner_id", row.ownerId).putExtra("video_id", row.id);
        if (!row.accessKey.isEmpty()) intent.putExtra("access_key", row.accessKey);
        startActivity(intent);
    }
    private boolean ownsSection() {
        try { return model != null && model.ownerId() > 0 && model.ownerId() == Long.parseLong(Session.getUserId(requireContext())); }
        catch (Exception ignored) { return false; }
    }
    private void editDialog(VideoEntry row) { editDialog(row, null); }
    private void editDialog(VideoEntry row, Uri uri) {
        if (!isAdded() || !ownsSection() || writer != null) return;
        boolean createAlbum = row == null && uri == null;
        boolean upload = uri != null;
        if (row != null && row.ownerId != model.ownerId()) return;
        LinearLayout fields = new LinearLayout(requireContext()); fields.setOrientation(LinearLayout.VERTICAL);
        int pad = (int)(24 * getResources().getDisplayMetrics().density); fields.setPadding(pad, 0, pad, 0);
        EditText title = new EditText(requireContext()); title.setSingleLine(true); title.setHint(R.string.videos_title_hint);
        setExistingTextAndLimit(title, row == null ? "" : row.title, 255);
        fields.addView(title);
        EditText description = new EditText(requireContext()); description.setHint(R.string.videos_description_hint);
        boolean canEditDescription = upload || row != null && !row.album && row.description != null;
        if (canEditDescription) { setExistingTextAndLimit(description, row == null ? "" : row.description, 2048); fields.addView(description); }
        VideoView preview = new VideoView(requireContext()); EditText start = new EditText(requireContext()); EditText end = new EditText(requireContext());
        final long[] duration = {0}; VideoPreviewBoundary boundary = new VideoPreviewBoundary(); Handler previewHandler = new Handler(Looper.getMainLooper());
        if (upload) {
            fields.addView(preview, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, (int)(190 * getResources().getDisplayMetrics().density)));
            LinearLayout times = new LinearLayout(requireContext());
            start.setHint(R.string.videos_trim_start); end.setHint(R.string.videos_trim_end);
            start.setInputType(InputType.TYPE_CLASS_NUMBER | InputType.TYPE_NUMBER_FLAG_DECIMAL); end.setInputType(InputType.TYPE_CLASS_NUMBER | InputType.TYPE_NUMBER_FLAG_DECIMAL);
            start.setText("0"); times.addView(start, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
            times.addView(end, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1)); fields.addView(times);
            Button play = new Button(requireContext()); play.setText(R.string.videos_preview); fields.addView(play);
            preview.setVideoURI(uri); preview.setOnPreparedListener(player -> { duration[0] = player.getDuration();
                end.setText(String.format(Locale.US, "%.3f", duration[0] / 1000.0)); preview.seekTo(1); });
            preview.setOnCompletionListener(player -> boundary.stop());
            play.setOnClickListener(v -> { long[] range = trimRange(start, end, duration[0]);
                if (range == null) { end.setError(getString(R.string.videos_trim_invalid)); return; }
                preview.seekTo((int) range[0]); preview.start(); boundary.start(range[1]); previewHandler.removeCallbacksAndMessages(null);
                Runnable stop = new Runnable() { @Override public void run() {
                    if (!boundary.isActive()) return;
                    if (boundary.shouldPause(preview.getCurrentPosition())) { preview.pause(); }
                    else previewHandler.postDelayed(this, 100); } };
                previewHandler.postDelayed(stop, 100);
            });
        }
        ScrollView scroll = new ScrollView(requireContext()); scroll.addView(fields);
        int heading = upload ? R.string.videos_upload : createAlbum ? R.string.videos_create_album : row.album ? R.string.videos_edit_album : R.string.videos_edit_video;
        AlertDialog dialog = new AlertDialog.Builder(requireContext()).setTitle(heading).setView(scroll)
                .setNegativeButton(R.string.videos_cancel, null).setPositiveButton(upload ? R.string.videos_upload_confirm : R.string.videos_save, null).create();
        dialog.setOnDismissListener(ignored -> { boundary.stop(); previewHandler.removeCallbacksAndMessages(null); preview.stopPlayback(); });
        dialog.setOnShowListener(ignored -> dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> {
            String name = title.getText().toString().trim(); if (name.isEmpty()) { title.setError(getString(R.string.videos_title_required)); return; }
            String desc = canEditDescription ? description.getText().toString() : null;
            long[] range = upload ? trimRange(start, end, duration[0]) : null;
            if (upload && range == null) { end.setError(getString(R.string.videos_trim_invalid)); return; }
            dialog.dismiss(); writePending = true; writer = new VideoWriter(requireContext()); VideoWriter active = writer;
            saving = new AlertDialog.Builder(requireContext()).setMessage(R.string.videos_saving).setCancelable(false).create(); saving.show();
            VideoWriter.Callback callback = failure -> { if (writer != active) return; writer = null; writePending = false;
                if (saving != null) { saving.dismiss(); saving = null; } if (trimmer != null) { trimmer.cleanup(); trimmer = null; } if (!isAdded()) return;
                if (failure == null) { Toast.makeText(requireContext(), R.string.videos_write_success, Toast.LENGTH_SHORT).show(); model.refresh(); }
                else { int message = "uncertain".equals(failure) ? R.string.videos_write_uncertain : "file".equals(failure) ? R.string.videos_write_file : "auth".equals(failure) ? R.string.videos_error_auth : "rate".equals(failure) ? R.string.videos_error_rate : R.string.videos_write_error;
                    new AlertDialog.Builder(requireContext()).setMessage(message).setPositiveButton(android.R.string.ok, null).show(); }
            };
            if (upload && (range[0] != 0 || range[1] != duration[0])) {
                trimmer = new VideoTrimmer(); VideoTrimmer current = trimmer;
                try { current.start(requireContext(), uri, range[0], range[1], file -> {
                    if (trimmer != current || writer != active) { current.cleanup(); return; }
                    if (file == null) { writer = null; writePending = false; current.cleanup(); trimmer = null;
                        if (saving != null) { saving.dismiss(); saving = null; }
                        if (isAdded()) new AlertDialog.Builder(requireContext()).setMessage(R.string.videos_trim_failed).setPositiveButton(android.R.string.ok, null).show();
                    } else active.uploadEdited(file, name, desc, callback);
                }); } catch (Exception failure) { current.cleanup(); trimmer = null; writer = null; writePending = false;
                    if (saving != null) { saving.dismiss(); saving = null; }
                    new AlertDialog.Builder(requireContext()).setMessage(R.string.videos_trim_failed).setPositiveButton(android.R.string.ok, null).show(); }
            } else if (upload) active.upload(uri, name, desc, callback);
            else if (createAlbum || row.album) active.album(createAlbum ? 0 : row.id, name, callback);
            else active.edit(row, name, desc, callback);
        })); dialog.show();
    }
    static void setExistingTextAndLimit(EditText field, String text, int max) {
        field.setText(text);
        field.setFilters(new InputFilter[]{new InputFilter.LengthFilter(max)});
    }
    boolean consumeInterruptedWrite(Bundle saved) {
        boolean interrupted = writePending || saved != null && saved.getBoolean("video_write_pending");
        writePending = false;
        return interrupted;
    }
    private static long[] trimRange(EditText start, EditText end, long duration) {
        try {
            double a = Double.parseDouble(start.getText().toString()), b = Double.parseDouble(end.getText().toString());
            if (!Double.isFinite(a) || !Double.isFinite(b) || a < 0 || b <= a || duration <= 0) return null;
            long from = Math.round(a * 1000), to = Math.round(b * 1000);
            return from < to && to <= duration && duration <= Integer.MAX_VALUE ? new long[]{from, to} : null;
        } catch (NumberFormatException e) { return null; }
    }
    private void render(VideosViewModel.State state) {
        if (adapter == null) return;
        rendering = true;
        boolean writeVisible = ownsSection() && state.mode != VideosViewModel.SEARCH && !(state.mode == VideosViewModel.ALBUMS && state.album != 0);
        writeAction.setVisibility(writeVisible ? View.VISIBLE : View.GONE);
        writeAction.setText(state.mode == VideosViewModel.ALBUMS ? R.string.videos_create_album : R.string.videos_upload);
        adapter.owner = ownsSection() ? model.ownerId() : 0;
        tabs.selectTab(tabs.getTabAt(state.mode));
        searchRow.setVisibility(state.mode == VideosViewModel.SEARCH ? View.VISIBLE : View.GONE);
        if (!search.hasFocus() && !search.getText().toString().equals(state.query)) search.setText(state.query);
        albumBackHandler.setEnabled(state.album != 0);
        albumBack.setVisibility(state.album != 0 ? View.VISIBLE : View.GONE);
        albumBack.setText(getString(R.string.videos_all_albums) + "  /  " + state.albumTitle);
        adapter.submit(state.rows);
        refresh.setRefreshing(state.loading && state.loaded);
        emptyState.setVisibility(state.rows.isEmpty() ? View.VISIBLE : View.GONE);
        progress.setVisibility(state.loading && state.rows.isEmpty() ? View.VISIBLE : View.GONE);
        int text = state.loading ? R.string.videos_loading : state.mode == VideosViewModel.SEARCH && state.query.isEmpty() ? R.string.videos_search_prompt
                : state.mode == VideosViewModel.SEARCH ? R.string.videos_search_empty
                : state.mode == VideosViewModel.ALBUMS && state.album == 0 ? R.string.videos_albums_empty : R.string.videos_empty;
        empty.setText(state.error != null ? "" : getString(text));
        noticeRow.setVisibility(state.error == null ? View.GONE : View.VISIBLE);
        if (state.error != null) notice.setText(errorText(state.error));
        more.setVisibility(state.hasMore() && state.error == null ? View.VISIBLE : View.GONE);
        more.setEnabled(!state.loading);
        rendering = false;
    }
    private static int errorText(String error) {
        switch (error) {
            case "auth": return R.string.videos_error_auth;
            case "access": return R.string.videos_error_access;
            case "rate": return R.string.videos_error_rate;
            case "network": return R.string.videos_error_network;
            default: return R.string.videos_error_generic;
        }
    }
    @Override public void onSaveInstanceState(@NonNull Bundle out) {
        out.putBoolean("video_write_pending", writePending); super.onSaveInstanceState(out);
    }
    @Override public void onDestroyView() {
        if (writer != null) { writer.cancel(); writer = null; }
        if (trimmer != null) { trimmer.cleanup(); trimmer = null; }
        if (saving != null) { saving.dismiss(); saving = null; }
        if (list != null) list.setAdapter(null);
        list = null; adapter = null; search = null; tabs = null; refresh = null; albumBackHandler = null;
        notice = empty = more = albumBack = writeAction = null; noticeRow = emptyState = progress = searchRow = null;
        super.onDestroyView();
    }
    private interface Listener { void open(VideoEntry item); }
    private static final class Adapter extends RecyclerView.Adapter<Adapter.Holder> {
        private final Listener listener, editor; private List<VideoEntry> items = Collections.emptyList();
        long owner;
        Adapter(Listener listener, Listener editor) { this.listener = listener; this.editor = editor; }
        void submit(List<VideoEntry> rows) { items = rows; notifyDataSetChanged(); }
        @NonNull @Override public Holder onCreateViewHolder(@NonNull ViewGroup parent, int type) {
            return new Holder(LayoutInflater.from(parent.getContext()).inflate(R.layout.item_video_catalog, parent, false));
        }
        @Override public void onBindViewHolder(@NonNull Holder h, int position) {
            VideoEntry row = items.get(position); Context context = h.itemView.getContext();
            h.title.setText(row.title.isEmpty() ? context.getString(R.string.videos_untitled) : row.title);
            h.subtitle.setVisibility(row.album ? View.VISIBLE : View.GONE);
            h.subtitle.setText(row.album ? context.getString(R.string.videos_album_count, row.count) : "");
            h.play.setVisibility(row.album ? View.GONE : View.VISIBLE);
            h.duration.setVisibility(!row.album && row.duration > 0 ? View.VISIBLE : View.GONE);
            h.duration.setText(duration(row.duration));
            Glide.with(h.image).clear(h.image);
            if (!row.image.isEmpty()) Glide.with(h.image).load(row.image).centerCrop().into(h.image);
            h.edit.setVisibility(owner != 0 && row.ownerId == owner && (!row.album || row.id > 0) ? View.VISIBLE : View.GONE);
            h.edit.setOnClickListener(v -> editor.open(row));
            h.itemView.setOnClickListener(v -> listener.open(row));
        }
        static String duration(int seconds) { return seconds >= 3600 ? String.format(Locale.US, "%d:%02d:%02d", seconds / 3600, seconds / 60 % 60, seconds % 60) : String.format(Locale.US, "%d:%02d", seconds / 60, seconds % 60); }
        @Override public void onViewRecycled(@NonNull Holder h) { Glide.with(h.image).clear(h.image); h.itemView.setOnClickListener(null); super.onViewRecycled(h); }
        @Override public int getItemCount() { return items.size(); }
        static final class Holder extends RecyclerView.ViewHolder {
            final ImageView image, play; final TextView title, subtitle, duration, edit;
            Holder(View view) { super(view); image = view.findViewById(R.id.videos_image); play = view.findViewById(R.id.videos_play); title = view.findViewById(R.id.videos_title); subtitle = view.findViewById(R.id.videos_subtitle); duration = view.findViewById(R.id.videos_duration); edit = view.findViewById(R.id.videos_edit); }
        }
    }
}
