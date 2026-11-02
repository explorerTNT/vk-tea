package com.vktea.documents;

import android.content.ActivityNotFoundException;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.text.Editable;
import android.text.TextWatcher;
import android.text.format.Formatter;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.inputmethod.InputMethodManager;
import android.widget.EditText;
import android.widget.TextView;
import android.widget.Toast;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AlertDialog;
import androidx.fragment.app.Fragment;
import androidx.lifecycle.ViewModelProvider;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout;
import com.vktea.R;
import com.vktea.auth.Session;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

/** The account's documents, without writing to VK or downloading files in the background. */
public class DocumentsFragment extends Fragment {
    private DocumentsViewModel model;
    private DocumentsAdapter adapter;
    private EditText search;
    private TextView notice, empty;
    private View clear, noticeRow, emptyState, loading, more;
    private SwipeRefreshLayout refresh;
    private RecyclerView list;
    private TextWatcher watcher;
    private boolean rendering;

    @Override public void onCreate(@Nullable Bundle state) {
        super.onCreate(state);
        model = new ViewModelProvider(this, createModelFactory()).get(DocumentsViewModel.class);
    }
    protected ViewModelProvider.Factory createModelFactory() { return new DocumentsViewModel.Factory(requireContext()); }
    @Nullable @Override public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup parent, @Nullable Bundle state) {
        return inflater.inflate(R.layout.fragment_documents, parent, false);
    }
    @Override public void onViewCreated(@NonNull View view, @Nullable Bundle state) {
        search = view.findViewById(R.id.documents_search); clear = view.findViewById(R.id.documents_clear);
        notice = view.findViewById(R.id.documents_notice); noticeRow = view.findViewById(R.id.documents_notice_row);
        empty = view.findViewById(R.id.documents_empty); emptyState = view.findViewById(R.id.documents_empty_state);
        more = view.findViewById(R.id.documents_more); more.setOnClickListener(v -> model.loadNext());
        loading = view.findViewById(R.id.documents_loading); refresh = view.findViewById(R.id.documents_refresh);
        list = view.findViewById(R.id.documents_list);
        LinearLayoutManager layout = new LinearLayoutManager(requireContext()); list.setLayoutManager(layout);
        adapter = new DocumentsAdapter(this::openDocument); list.setAdapter(adapter);
        list.addOnScrollListener(new RecyclerView.OnScrollListener() {
            @Override public void onScrolled(@NonNull RecyclerView recycler, int dx, int dy) {
                if (dy > 0 && layout.getItemCount() > 0 && layout.findLastVisibleItemPosition() >= layout.getItemCount() - 4)
                    model.loadNext();
            }
        });
        watcher = new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence text, int start, int count, int after) { }
            @Override public void onTextChanged(CharSequence text, int start, int before, int count) {
                if (!rendering) { model.setQuery(text.toString()); list.scrollToPosition(0); }
            }
            @Override public void afterTextChanged(Editable text) { }
        };
        search.addTextChangedListener(watcher);
        clear.setOnClickListener(v -> search.setText(""));
        refresh.setColorSchemeResources(R.color.vk_blue); refresh.setOnRefreshListener(model::refresh);
        refresh.setOnChildScrollUpCallback((parent, child) -> list != null && list.canScrollVertically(-1));
        view.findViewById(R.id.documents_retry).setOnClickListener(v -> model.retry());
        model.ensureLoaded();
        model.state().observe(getViewLifecycleOwner(), this::render);
    }
    @Override public void onStart() { super.onStart(); model.ensureLoaded(); }
    private void render(DocumentsViewModel.State state) {
        if (adapter == null) return;
        rendering = true;
        if (!search.getText().toString().trim().equals(state.query)) {
            search.setText(state.query); search.setSelection(search.length());
        }
        clear.setVisibility(search.length() == 0 ? View.INVISIBLE : View.VISIBLE);
        adapter.replace(state.items);
        noticeRow.setVisibility(state.error == null ? View.GONE : View.VISIBLE);
        if (state.error != null) notice.setText(errorText(state.error.kind));
        more.setVisibility(state.loaded && state.hasMore && !state.loading && state.error == null ? View.VISIBLE : View.GONE);
        refresh.setRefreshing(state.loading && !state.items.isEmpty());
        boolean showEmpty = state.items.isEmpty() && state.error == null;
        emptyState.setVisibility(showEmpty ? View.VISIBLE : View.GONE);
        loading.setVisibility(state.loading ? View.VISIBLE : View.GONE);
        empty.setText(state.loading ? R.string.documents_loading : state.loaded && state.hasMore
                ? R.string.documents_empty_page : state.query.isEmpty() ? R.string.documents_empty : R.string.documents_search_empty);
        rendering = false;
    }
    private static int errorText(DocumentsRepository.Error.Kind kind) {
        switch (kind) {
            case AUTH: return R.string.documents_error_auth;
            case ACCESS: return R.string.documents_error_access;
            case RATE_LIMIT: return R.string.documents_error_rate;
            case NETWORK: return R.string.documents_error_network;
            default: return R.string.documents_error_api;
        }
    }
    public void showDocumentsSearch() {
        if (search == null) return;
        search.requestFocus();
        InputMethodManager keyboard = (InputMethodManager) requireContext().getSystemService(Context.INPUT_METHOD_SERVICE);
        if (keyboard != null) keyboard.showSoftInput(search, InputMethodManager.SHOW_IMPLICIT);
    }
    private void openDocument(DocumentItem item) {
        if (!item.canOpen()) { Toast.makeText(requireContext(), R.string.documents_unavailable, Toast.LENGTH_SHORT).show(); return; }
        final long sessionGeneration = Session.generation();
        new AlertDialog.Builder(requireContext()).setTitle(R.string.documents_open_title)
                .setMessage(R.string.documents_open_message)
                .setPositiveButton(R.string.documents_open, (dialog, which) -> {
                    if (!isAdded() || getView() == null || Session.generation() != sessionGeneration || !item.canOpen()) return;
                    try {
                        Intent intent = new Intent(Intent.ACTION_VIEW, Uri.parse(item.url));
                        intent.addCategory(Intent.CATEGORY_BROWSABLE);
                        startActivity(intent);
                    } catch (ActivityNotFoundException | SecurityException unavailable) {
                        Toast.makeText(requireContext(), R.string.documents_no_handler, Toast.LENGTH_SHORT).show();
                    }
                }).setNegativeButton(android.R.string.cancel, null).show();
    }
    @Override public void onDestroyView() {
        if (search != null && watcher != null) search.removeTextChangedListener(watcher);
        if (list != null) list.setAdapter(null);
        adapter = null; list = null; search = null; watcher = null; notice = null; empty = null;
        clear = null; noticeRow = null; emptyState = null; loading = null; more = null; refresh = null;
        super.onDestroyView();
    }
    interface Click { void open(DocumentItem item); }
    static final class DocumentsAdapter extends RecyclerView.Adapter<DocumentsAdapter.Holder> {
        private List<DocumentItem> items = Collections.emptyList();
        private final Click click;
        DocumentsAdapter(Click click) { this.click = click; }
        void replace(List<DocumentItem> values) { if (items != values) { items = values; notifyDataSetChanged(); } }
        @NonNull @Override public Holder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            return new Holder(LayoutInflater.from(parent.getContext()).inflate(R.layout.item_document, parent, false));
        }
        @Override public void onBindViewHolder(@NonNull Holder holder, int position) {
            DocumentItem item = items.get(position); Context context = holder.itemView.getContext();
            holder.title.setText(item.title);
            String ext = item.extension.isEmpty() ? context.getString(R.string.documents_no_extension) : item.extension.toUpperCase(Locale.ROOT);
            holder.detail.setText(item.size > 0 ? context.getString(R.string.documents_file_info, ext,
                    Formatter.formatShortFileSize(context, item.size)) : ext);
            holder.itemView.setOnClickListener(v -> click.open(item));
        }
        @Override public int getItemCount() { return items.size(); }
        static final class Holder extends RecyclerView.ViewHolder {
            final TextView title, detail;
            Holder(View row) { super(row); title = row.findViewById(R.id.document_title); detail = row.findViewById(R.id.document_detail); }
        }
    }
}
