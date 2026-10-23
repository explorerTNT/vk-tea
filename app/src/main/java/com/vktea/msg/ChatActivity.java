package com.vktea.msg;

import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.bumptech.glide.Glide;
import com.bumptech.glide.request.RequestOptions;
import com.vktea.R;
import com.vktea.api.VKApi;

import org.json.JSONArray;
import org.json.JSONObject;

import java.net.URLEncoder;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

public class ChatActivity extends AppCompatActivity {

    public static final String EXTRA_PEER_ID = "peer_id";
    public static final String EXTRA_TITLE = "title";
    public static final String EXTRA_PHOTO = "photo";

    private long peerId;
    private String peerPhoto;
    private ChatAdapter adapter;
    private RecyclerView list;
    private boolean loading;
    private long lastMessageId;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_chat);

        peerId = getIntent().getLongExtra(EXTRA_PEER_ID, 0);
        peerPhoto = getIntent().getStringExtra(EXTRA_PHOTO);
        String title = getIntent().getStringExtra(EXTRA_TITLE);

        androidx.appcompat.widget.Toolbar toolbar = findViewById(R.id.chat_toolbar);
        toolbar.setTitle(title != null ? title : "");
        setSupportActionBar(toolbar);
        if (getSupportActionBar() != null) {
            getSupportActionBar().setDisplayHomeAsUpEnabled(true);
        }
        toolbar.setNavigationOnClickListener(v -> finish());

        list = findViewById(R.id.chat_list);
        LinearLayoutManager lm = new LinearLayoutManager(this);
        lm.setStackFromEnd(true);
        list.setLayoutManager(lm);
        adapter = new ChatAdapter();
        list.setAdapter(adapter);

        View send = findViewById(R.id.chat_send);
        EditText input = findViewById(R.id.chat_input);
        send.setOnClickListener(v -> {
            String text = input.getText().toString().trim();
            if (text.isEmpty()) {
                return;
            }
            input.setText("");
            sendMessage(text);
        });

        loadHistory();
        markRead();
    }

    private void markRead() {
        VKApi.get(this, "messages.markAsRead", "peer_id=" + peerId, new VKApi.Callback() {
            @Override
            public void success(JSONObject response) {
            }

            @Override
            public void error(Exception e) {
            }
        });
    }

    private void loadHistory() {
        loading = true;
        VKApi.get(this, "messages.getHistory", "peer_id=" + peerId + "&count=50&rev=0&extended=1",
                new VKApi.Callback() {
                    @Override
                    public void success(JSONObject response) {
                        try {
                            JSONObject data = response.getJSONObject("response");
                            Map<Long, String> avatars = new HashMap<>();
                            Map<Long, String> names = new HashMap<>();
                            JSONArray profiles = data.optJSONArray("profiles");
                            if (profiles != null) {
                                for (int i = 0; i < profiles.length(); i++) {
                                    JSONObject p = profiles.getJSONObject(i);
                                    avatars.put(p.getLong("id"), p.optString("photo_100", ""));
                                    names.put(p.getLong("id"),
                                            p.optString("first_name") + " " + p.optString("last_name"));
                                }
                            }
                            JSONArray groups = data.optJSONArray("groups");
                            if (groups != null) {
                                for (int i = 0; i < groups.length(); i++) {
                                    JSONObject g = groups.getJSONObject(i);
                                    avatars.put(-g.getLong("id"), g.optString("photo_100", ""));
                                    names.put(-g.getLong("id"), g.optString("name"));
                                }
                            }

                            List<ChatMsg> msgs = new ArrayList<>();
                            JSONArray items = data.getJSONArray("items");
                            for (int i = 0; i < items.length(); i++) {
                                JSONObject m = items.getJSONObject(i);
                                long fromId = m.optLong("from_id", peerId);
                                ChatMsg msg = new ChatMsg(
                                        m.getLong("id"),
                                        m.optInt("out", 0) == 1,
                                        fromId,
                                        names.containsKey(fromId) ? names.get(fromId) : "",
                                        avatars.containsKey(fromId) ? avatars.get(fromId) : "",
                                        m.optString("text", m.optString("body", "")),
                                        m.optLong("date", 0));
                                JSONArray atts = m.optJSONArray("attachments");
                                if (atts != null && atts.length() > 0) {
                                    JSONObject att = atts.getJSONObject(0);
                                    String type = att.optString("type", "");
                                    if ("photo".equals(type)) {
                                        msg.attachUrl = chatPhotoUrl(att.optJSONObject("photo"));
                                    } else if ("sticker".equals(type)) {
                                        JSONObject st = att.optJSONObject("sticker");
                                        if (st != null) {
                                            msg.attachUrl = st.optString("photo_256", st.optString("photo_128", ""));
                                        }
                                    } else if ("video".equals(type)) {
                                        msg.body = appendLine(msg.body, "🎬 Видео");
                                    } else if ("audio".equals(type)) {
                                        msg.body = appendLine(msg.body, "🎵 Аудио");
                                    } else if ("audio_message".equals(type)) {
                                        msg.body = appendLine(msg.body, "🎤 Голосовое сообщение");
                                    } else if ("doc".equals(type)) {
                                        msg.body = appendLine(msg.body, "📄 Документ");
                                    } else if ("wall".equals(type)) {
                                        msg.body = appendLine(msg.body, "📝 Запись");
                                    } else if ("gift".equals(type)) {
                                        msg.body = appendLine(msg.body, "🎁 Подарок");
                                    }
                                }
                                JSONArray fwd = m.optJSONArray("fwd_messages");
                                if (fwd != null && fwd.length() > 0) {
                                    msg.body = appendLine(msg.body,
                                            "↪ Пересланное сообщение (" + fwd.length() + ")");
                                }
                                msgs.add(msg);
                            }
                            Collections.reverse(msgs);
                            runOnUiThread(() -> {
                                adapter.replaceAll(msgs);
                                list.scrollToPosition(adapter.getItemCount() - 1);
                            });
                        } catch (Exception ignored) {
                        }
                        loading = false;
                    }

                    @Override
                    public void error(Exception e) {
                        loading = false;
                    }
                });
    }

    private void sendMessage(String text) {
        adapter.add(new ChatMsg(--localId, true, 0, "", "", text,
                System.currentTimeMillis() / 1000L));
        list.scrollToPosition(adapter.getItemCount() - 1);
        VKApi.get(this, "messages.send", "peer_id=" + peerId + "&message=" + urlEncode(text),
                new VKApi.Callback() {
                    @Override
                    public void success(JSONObject response) {
                    }

                    @Override
                    public void error(Exception e) {
                        runOnUiThread(() -> Toast.makeText(ChatActivity.this,
                                R.string.send_failed, Toast.LENGTH_SHORT).show());
                    }
                });
    }

    private static long localId = -100;

    private static String chatPhotoUrl(JSONObject photo) {
        if (photo == null) {
            return null;
        }
        String[] candidates = {"photo_2560", "photo_1280", "photo_807", "photo_604", "photo_130"};
        for (String key : candidates) {
            String u = photo.optString(key, null);
            if (u != null && !u.isEmpty()) {
                return u;
            }
        }
        JSONArray sizes = photo.optJSONArray("sizes");
        if (sizes != null && sizes.length() > 0) {
            String url = sizes.optJSONObject(sizes.length() - 1).optString("url", null);
            if (url != null) {
                url = url.replaceAll("&cs=\\d+x\\d+", "");
            }
            return url;
        }
        return null;
    }

    private static String appendLine(String base, String line) {
        return base.isEmpty() ? line : base + "\n" + line;
    }

    private static String urlEncode(String s) {
        try {
            return URLEncoder.encode(s, "UTF-8");
        } catch (Exception e) {
            return s;
        }
    }

    static class ChatMsg {
        final long id;
        final boolean out;
        final long fromId;
        final String author;
        final String avatar;
        String body;
        final long date;
        String attachUrl;

        ChatMsg(long id, boolean out, long fromId, String author, String avatar, String body, long date) {
            this.id = id;
            this.out = out;
            this.fromId = fromId;
            this.author = author;
            this.avatar = avatar;
            this.body = body;
            this.date = date;
        }
    }

    static class MsgGroup {
        static final int SINGLE = 0;
        static final int TOP = 1;
        static final int MID = 2;
        static final int BOTTOM = 3;
    }

    class ChatAdapter extends RecyclerView.Adapter<RecyclerView.ViewHolder> {

        private static final int TYPE_IN = 0;
        private static final int TYPE_OUT = 1;

        private final List<ChatMsg> msgs = new ArrayList<>();

        void replaceAll(List<ChatMsg> newMsgs) {
            msgs.clear();
            msgs.addAll(newMsgs);
            notifyDataSetChanged();
        }

        void add(ChatMsg msg) {
            msgs.add(msg);
            notifyItemInserted(msgs.size() - 1);
        }

        private int groupPos(int position) {
            ChatMsg cur = msgs.get(position);
            boolean samePrev = false;
            boolean sameNext = false;
            if (position > 0) {
                ChatMsg prev = msgs.get(position - 1);
                samePrev = prev.out == cur.out
                        && (!cur.out && prev.fromId == cur.fromId)
                        && cur.date - prev.date < 300;
            }
            if (position < msgs.size() - 1) {
                ChatMsg next = msgs.get(position + 1);
                sameNext = next.out == cur.out
                        && (!cur.out && next.fromId == cur.fromId)
                        && next.date - cur.date < 300;
            }
            if (samePrev && sameNext) {
                return MsgGroup.MID;
            }
            if (samePrev) {
                return MsgGroup.BOTTOM;
            }
            if (sameNext) {
                return MsgGroup.TOP;
            }
            return MsgGroup.SINGLE;
        }

        @NonNull
        @Override
        public RecyclerView.ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            int layout = viewType == TYPE_OUT ? R.layout.item_msg_out : R.layout.item_msg_in;
            return new Holder(LayoutInflater.from(parent.getContext()).inflate(layout, parent, false), viewType);
        }

        @Override
        public void onBindViewHolder(@NonNull RecyclerView.ViewHolder holder, int position) {
            ChatMsg msg = msgs.get(position);
            Holder h = (Holder) holder;
            h.text.setText(msg.body);
            h.text.setVisibility(msg.body.isEmpty() ? View.GONE : View.VISIBLE);
            h.time.setText(new SimpleDateFormat("HH:mm", Locale.US).format(new Date(msg.date * 1000L)));

            if (msg.attachUrl != null && !msg.attachUrl.isEmpty()) {
                h.attach.setVisibility(View.VISIBLE);
                int target = h.attach.getContext().getResources().getDisplayMetrics().widthPixels / 2;
                Glide.with(h.attach).load(msg.attachUrl).override(target,
                        com.bumptech.glide.request.target.Target.SIZE_ORIGINAL).into(h.attach);
            } else {
                h.attach.setVisibility(View.GONE);
            }

            int group = groupPos(position);
            String prefixIn = msg.out ? "bg_msg_out" : "bg_msg_in";
            String suffix;
            switch (group) {
                case MsgGroup.TOP:
                    suffix = "_top";
                    break;
                case MsgGroup.MID:
                    suffix = "_mid";
                    break;
                case MsgGroup.BOTTOM:
                    suffix = "_bottom";
                    break;
                default:
                    suffix = "_full";
                    break;
            }
            int bgId = h.text.getContext().getResources().getIdentifier(
                    prefixIn + suffix, "drawable", h.text.getContext().getPackageName());
            if (bgId != 0) {
                h.wrap.setBackgroundResource(bgId);
            }

            if (h.viewType == TYPE_IN) {
                if (msg.avatar != null && !msg.avatar.isEmpty()) {
                    Glide.with(h.avatar).load(msg.avatar)
                            .apply(RequestOptions.circleCropTransform())
                            .into(h.avatar);
                }
                boolean firstOfGroup = group == MsgGroup.TOP || group == MsgGroup.SINGLE;
                h.avatar.setVisibility(firstOfGroup ? View.VISIBLE : View.INVISIBLE);
            }
        }

        @Override
        public int getItemCount() {
            return msgs.size();
        }

        @Override
        public int getItemViewType(int position) {
            return msgs.get(position).out ? TYPE_OUT : TYPE_IN;
        }

        class Holder extends RecyclerView.ViewHolder {
            final int viewType;
            final ImageView avatar;
            final TextView text;
            final ImageView attach;
            final TextView time;
            final View wrap;

            Holder(@NonNull View itemView, int viewType) {
                super(itemView);
                this.viewType = viewType;
                avatar = itemView.findViewById(R.id.msg_sender_photo);
                text = itemView.findViewById(R.id.msg_text);
                attach = itemView.findViewById(R.id.msg_attach);
                time = itemView.findViewById(R.id.msg_time);
                wrap = itemView.findViewById(R.id.msg_wrap);
            }
        }
    }
}
