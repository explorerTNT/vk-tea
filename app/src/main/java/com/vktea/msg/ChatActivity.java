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

import java.util.ArrayList;
import java.util.List;

public class ChatActivity extends AppCompatActivity {

    public static final String EXTRA_PEER_ID = "peer_id";
    public static final String EXTRA_TITLE = "title";

    private long peerId;
    private ChatAdapter adapter;
    private RecyclerView list;
    private boolean loading;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_chat);

        peerId = getIntent().getLongExtra(EXTRA_PEER_ID, 0);
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
    }

    private void loadHistory() {
        loading = true;
        VKApi.get(this, "messages.getHistory", "peer_id=" + peerId + "&count=40&rev=0",
                new VKApi.Callback() {
                    @Override
                    public void success(JSONObject response) {
                        try {
                            JSONArray items = response.getJSONObject("response").getJSONArray("items");
                            List<ChatMsg> msgs = new ArrayList<>();
                            for (int i = 0; i < items.length(); i++) {
                                JSONObject m = items.getJSONObject(i);
                                msgs.add(new ChatMsg(
                                        m.optInt("out", 0) == 1,
                                        m.optString("body", ""),
                                        m.optLong("date", 0)));
                            }
                            adapter.replaceAll(msgs);
                            list.scrollToPosition(adapter.getItemCount() - 1);
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
        VKApi.get(this, "messages.send", "peer_id=" + peerId + "&message=" + urlEncode(text),
                new VKApi.Callback() {
                    @Override
                    public void success(JSONObject response) {
                        adapter.add(new ChatMsg(true, text, System.currentTimeMillis() / 1000L));
                        list.scrollToPosition(adapter.getItemCount() - 1);
                    }

                    @Override
                    public void error(Exception e) {
                        runOnUiThread(() -> Toast.makeText(ChatActivity.this,
                                R.string.send_failed, Toast.LENGTH_SHORT).show());
                    }
                });
    }

    private static String urlEncode(String s) {
        try {
            return java.net.URLEncoder.encode(s, "UTF-8");
        } catch (Exception e) {
            return s;
        }
    }

    static class ChatMsg {
        final boolean out;
        final String body;
        final long date;

        ChatMsg(boolean out, String body, long date) {
            this.out = out;
            this.body = body;
            this.date = date;
        }
    }

    static class ChatAdapter extends RecyclerView.Adapter<RecyclerView.ViewHolder> {

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

        @NonNull
        @Override
        public RecyclerView.ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            int layout = viewType == TYPE_OUT ? R.layout.item_msg_out : R.layout.item_msg_in;
            return new Holder(LayoutInflater.from(parent.getContext()).inflate(layout, parent, false));
        }

        @Override
        public void onBindViewHolder(@NonNull RecyclerView.ViewHolder holder, int position) {
            ((Holder) holder).text.setText(msgs.get(position).body);
        }

        @Override
        public int getItemCount() {
            return msgs.size();
        }

        @Override
        public int getItemViewType(int position) {
            return msgs.get(position).out ? TYPE_OUT : TYPE_IN;
        }

        static class Holder extends RecyclerView.ViewHolder {
            final TextView text;

            Holder(@NonNull View itemView) {
                super(itemView);
                text = itemView.findViewById(R.id.msg_text);
            }
        }
    }
}
