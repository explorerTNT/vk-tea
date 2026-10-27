package com.vktea.bookmarks;

import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.TextView;
import android.widget.Toast;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.widget.PopupMenu;
import androidx.fragment.app.Fragment;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout;
import com.bumptech.glide.Glide;
import com.google.android.material.tabs.TabLayout;
import com.vktea.MainActivity;
import com.vktea.R;
import com.vktea.api.VKApi;
import com.vktea.auth.Session;
import com.vktea.feed.ProfileFragment;
import com.vktea.feed.VideoPlayerActivity;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import org.json.JSONObject;

public class BookmarksFragment extends Fragment {
    private static final int PAGE_SIZE = 50;
    private final List<BookmarksParser.Item> items = new ArrayList<>();
    private RecyclerView list;
    private SwipeRefreshLayout refresh;
    private TextView empty;
    private View notice;
    private Adapter adapter;
    private int tab, offset, total, generation;
    private boolean loading, failed;
    private String sessionToken, sessionSecret, sessionUser;

    @Nullable @Override public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup parent, @Nullable Bundle state) {
        return inflater.inflate(R.layout.fragment_bookmarks, parent, false);
    }
    @Override public void onViewCreated(@NonNull View view, @Nullable Bundle state) {
        super.onViewCreated(view, state);
        tab = state == null ? 0 : state.getInt("bookmarks_tab", 0);
        if (tab != 1) tab = 0;
        list = view.findViewById(R.id.bookmarks_list);
        refresh = view.findViewById(R.id.bookmarks_refresh);
        empty = view.findViewById(R.id.bookmarks_empty);
        notice = view.findViewById(R.id.bookmarks_notice_row);
        ((TextView) view.findViewById(R.id.bookmarks_notice)).setText(R.string.bookmarks_error);
        list.setLayoutManager(new LinearLayoutManager(requireContext()));
        adapter = new Adapter(); list.setAdapter(adapter);
        refresh.setColorSchemeResources(R.color.vk_blue);
        refresh.setOnRefreshListener(this::restart);
        view.findViewById(R.id.bookmarks_retry).setOnClickListener(v -> { if (offset == 0) restart(); else load(); });
        TabLayout tabs = view.findViewById(R.id.bookmarks_tabs);
        tabs.addTab(tabs.newTab().setText(R.string.bookmarks_content));
        tabs.addTab(tabs.newTab().setText(R.string.bookmarks_pages));
        tabs.selectTab(tabs.getTabAt(tab));
        tabs.addOnTabSelectedListener(new TabLayout.OnTabSelectedListener() {
            @Override public void onTabSelected(TabLayout.Tab selected) { tab = selected.getPosition(); restart(); }
            @Override public void onTabUnselected(TabLayout.Tab selected) { }
            @Override public void onTabReselected(TabLayout.Tab selected) { }
        });
        list.addOnScrollListener(new RecyclerView.OnScrollListener() {
            @Override public void onScrolled(@NonNull RecyclerView rv, int dx, int dy) {
                LinearLayoutManager lm = (LinearLayoutManager) rv.getLayoutManager();
                if (dy > 0 && !loading && !failed && offset < total && lm != null && lm.findLastVisibleItemPosition() >= items.size() - 5) load();
            }
        });
        restart();
    }
    private void restart() {
        generation++; loading = false; failed = false; offset = total = 0;
        sessionToken = Session.getToken(requireContext());
        sessionSecret = Session.getSecret(requireContext());
        sessionUser = Session.getUserId(requireContext());
        items.clear(); adapter.notifyDataSetChanged(); list.scrollToPosition(0); load();
    }
    private boolean sessionMatches(String token, String secret, String user) {
        return Objects.equals(token, Session.getToken(requireContext()))
                && Objects.equals(secret, Session.getSecret(requireContext()))
                && Objects.equals(user, Session.getUserId(requireContext()));
    }
    private void invalidateSession() {
        generation++; loading = false; failed = true; offset = total = 0;
        items.clear(); adapter.notifyDataSetChanged(); render();
        sessionToken = Session.getToken(requireContext());
        sessionSecret = Session.getSecret(requireContext());
        sessionUser = Session.getUserId(requireContext());
    }
    @Override public void onResume() {
        super.onResume();
        if (getView() != null && !sessionMatches(sessionToken, sessionSecret, sessionUser)) invalidateSession();
    }
    private boolean current(int id, View target, String token, String secret, String user) {
        if (id != generation || getView() != target || !isAdded()) return false;
        if (!sessionMatches(token, secret, user)) { invalidateSession(); return false; }
        return true;
    }
    private void load() {
        if (loading || getView() == null) return;
        loading = true; failed = false; render();
        final int id = generation, pageOffset = offset;
        final boolean pages = tab == 1;
        final View target = getView();
        final String token = Session.getToken(requireContext()), secret = Session.getSecret(requireContext()), user = Session.getUserId(requireContext());
        VKBookmarksApi.get(requireContext(), pages ? "fave.getPages" : "fave.get",
                "count=" + PAGE_SIZE + "&offset=" + pageOffset + (pages ? "&fields=photo_100" : "&extended=1"), new VKApi.Callback() {
            @Override public void success(JSONObject response) {
                if (!current(id, target, token, secret, user)) return;
                try {
                    BookmarksParser.Page page = BookmarksParser.parse(response, pages);
                    if (page.consumed == 0 && pageOffset < page.count) throw new IllegalStateException();
                    items.addAll(page.items); offset = pageOffset + page.consumed; total = page.count;
                    adapter.notifyDataSetChanged(); loading = false; render();
                } catch (Exception ignored) { loading = false; failed = true; render(); }
            }
            @Override public void error(Exception ignored) {
                if (!current(id, target, token, secret, user)) return;
                loading = false; failed = true; render();
            }
        });
    }
    private void render() {
        refresh.setRefreshing(loading);
        notice.setVisibility(failed ? View.VISIBLE : View.GONE);
        empty.setVisibility(items.isEmpty() ? View.VISIBLE : View.GONE);
        empty.setText(loading ? R.string.bookmarks_loading : failed ? R.string.bookmarks_error : R.string.bookmarks_empty);
    }
    private void open(BookmarksParser.Item item) {
        if (item.owner != 0 && ("user".equals(item.type) || "group".equals(item.type))) {
            if (getActivity() instanceof MainActivity) ((MainActivity) getActivity()).openFragment(ProfileFragment.newInstance(item.owner), R.string.profile_title);
            return;
        }
        if ("video".equals(item.type) && item.owner != 0 && item.id > 0) {
            Intent intent = new Intent(requireContext(), VideoPlayerActivity.class).putExtra("owner_id", item.owner).putExtra("video_id", item.id).putExtra("access_key", item.accessKey);
            startActivity(intent); return;
        }
        String url = item.url;
        if ("post".equals(item.type) && item.owner != 0 && item.id > 0) {
            url = "https://vk.com/wall" + item.owner + "_" + item.id + (item.accessKey.isEmpty() ? "" : "?access_key=" + Uri.encode(item.accessKey));
        } else if ("product".equals(item.type) && item.owner != 0 && item.id > 0) {
            url = "https://vk.com/market" + item.owner + "_" + item.id;
        }
        Uri uri = Uri.parse(url);
        if (("https".equalsIgnoreCase(uri.getScheme()) || "http".equalsIgnoreCase(uri.getScheme())) && uri.getHost() != null) {
            try { startActivity(new Intent(Intent.ACTION_VIEW, uri)); return; } catch (ActivityNotFoundException ignored) { }
        }
        Toast.makeText(requireContext(), R.string.bookmarks_unavailable, Toast.LENGTH_SHORT).show();
    }
    private void showMenu(View anchor, BookmarksParser.Item item) {
        String method, params;
        switch (item.type) {
            case "user": method = "fave.removePage"; params = "user_id=" + item.owner; break;
            case "group": method = "fave.removePage"; params = "group_id=" + (-item.owner); break;
            case "post": method = "fave.removePost"; params = "owner_id=" + item.owner + "&id=" + item.id; break;
            case "video": method = "fave.removeVideo"; params = "owner_id=" + item.owner + "&id=" + item.id; break;
            case "product": method = "fave.removeProduct"; params = "owner_id=" + item.owner + "&id=" + item.id; break;
            case "link":
                if (item.url.isEmpty()) return;
                method = "fave.removeLink"; params = "link=" + Uri.encode(item.url); break;
            default: return;
        }
        if ((!"link".equals(item.type) && item.owner == 0) || (("post".equals(item.type) || "video".equals(item.type) || "product".equals(item.type)) && item.id <= 0)) return;
        PopupMenu menu = new PopupMenu(requireContext(), anchor);
        menu.getMenu().add(R.string.bookmarks_remove);
        menu.setOnMenuItemClickListener(selected -> {
            int id = generation; View target = getView();
            String token = Session.getToken(requireContext()), secret = Session.getSecret(requireContext()), user = Session.getUserId(requireContext());
            anchor.setEnabled(false);
            VKBookmarksApi.get(requireContext(), method, params, new VKApi.Callback() {
                @Override public void success(JSONObject response) {
                    if (!current(id, target, token, secret, user)) return;
                    anchor.setEnabled(true);
                    if (response.optInt("response") == 1) { Toast.makeText(requireContext(), R.string.bookmarks_removed, Toast.LENGTH_SHORT).show(); restart(); }
                    else Toast.makeText(requireContext(), R.string.action_failed, Toast.LENGTH_SHORT).show();
                }
                @Override public void error(Exception ignored) {
                    if (!current(id, target, token, secret, user)) return;
                    anchor.setEnabled(true); Toast.makeText(requireContext(), R.string.action_failed, Toast.LENGTH_SHORT).show();
                }
            });
            return true;
        });
        menu.show();
    }
    @Override public void onSaveInstanceState(@NonNull Bundle out) { out.putInt("bookmarks_tab", tab); super.onSaveInstanceState(out); }
    @Override public void onDestroyView() { generation++; list.setAdapter(null); list = null; adapter = null; refresh = null; empty = null; notice = null; super.onDestroyView(); }

    private final class Adapter extends RecyclerView.Adapter<Adapter.Holder> {
        @NonNull @Override public Holder onCreateViewHolder(@NonNull ViewGroup parent, int type) {
            return new Holder(LayoutInflater.from(parent.getContext()).inflate(R.layout.item_bookmark, parent, false));
        }
        @Override public void onBindViewHolder(@NonNull Holder holder, int position) {
            BookmarksParser.Item item = items.get(position);
            holder.title.setText(item.title); holder.detail.setText(item.detail);
            Glide.with(holder.image).clear(holder.image); holder.image.setImageResource(R.drawable.ic_menu_bookmarks);
            if (!item.image.isEmpty()) Glide.with(holder.image).load(item.image).placeholder(R.drawable.ic_menu_bookmarks).into(holder.image);
            holder.itemView.setOnClickListener(v -> open(item));
            holder.more.setOnClickListener(v -> showMenu(v, item));
            holder.more.setVisibility(("user".equals(item.type) || "group".equals(item.type) || "post".equals(item.type)
                    || "video".equals(item.type) || "product".equals(item.type) || "link".equals(item.type)) ? View.VISIBLE : View.INVISIBLE);
            holder.more.setEnabled(true);
        }
        @Override public int getItemCount() { return items.size(); }
        final class Holder extends RecyclerView.ViewHolder {
            final TextView title, detail; final ImageView image; final View more;
            Holder(View v) { super(v); title = v.findViewById(R.id.bookmark_title); detail = v.findViewById(R.id.bookmark_detail); image = v.findViewById(R.id.bookmark_image); more = v.findViewById(R.id.bookmark_more); }
        }
    }
}
