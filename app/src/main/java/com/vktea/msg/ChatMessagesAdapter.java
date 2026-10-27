package com.vktea.msg;

import android.graphics.drawable.Drawable;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.recyclerview.widget.RecyclerView;

import com.bumptech.glide.Glide;
import com.bumptech.glide.load.DataSource;
import com.bumptech.glide.load.engine.GlideException;
import com.bumptech.glide.request.RequestListener;
import com.bumptech.glide.request.target.Target;
import com.vktea.R;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/** Recycled display rows only; API requests and opening viewers belong to the activity. */
public final class ChatMessagesAdapter extends RecyclerView.Adapter<ChatMessagesAdapter.Holder> {
    public interface Callback {
        void onAttachmentClicked(ChatAttachment attachment);
        default void onMessageLongClicked(ChatMessage message) { }
    }
    interface ImageSize { void onSize(int width, int height); }
    interface Images {
        void load(ImageView target, String url, boolean circle, @Nullable ImageSize size);
        void clear(ImageView target);
    }

    static final int SINGLE = 0, TOP = 1, MIDDLE = 2, BOTTOM = 3;
    private static final int[] IN_BACKGROUNDS = {R.drawable.bg_msg_in_full,
            R.drawable.bg_msg_in_top, R.drawable.bg_msg_in_mid, R.drawable.bg_msg_in_bottom};
    private static final int[] OUT_BACKGROUNDS = {R.drawable.bg_msg_out_full,
            R.drawable.bg_msg_out_top, R.drawable.bg_msg_out_mid, R.drawable.bg_msg_out_bottom};
    private final List<ChatMessage> messages = new ArrayList<>();
    private final Callback callback;
    private final Images images;

    public ChatMessagesAdapter(Callback callback) { this(callback, new GlideImages()); }

    ChatMessagesAdapter(Callback callback, Images images) {
        this.callback = callback;
        this.images = images;
    }

    public void replaceAll(List<ChatMessage> rows) {
        messages.clear();
        if (rows != null) messages.addAll(rows);
        notifyDataSetChanged();
    }

    public void add(ChatMessage message) {
        if (message == null) return;
        int position = messages.size();
        messages.add(message);
        if (position > 0) notifyItemChanged(position - 1);
        notifyItemInserted(position);
    }

    @Override public int getItemCount() { return messages.size(); }
    @Override public int getItemViewType(int position) { return messages.get(position).out ? 1 : 0; }

    @NonNull @Override public Holder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        return new Holder(LayoutInflater.from(parent.getContext()).inflate(
                viewType == 1 ? R.layout.item_msg_out : R.layout.item_msg_in, parent, false));
    }

    @Override public void onBindViewHolder(@NonNull Holder holder, int position) {
        clearImages(holder.itemView);
        holder.attachments.removeAllViews();
        ChatMessage message = messages.get(position);
        holder.bubble.setOnLongClickListener(message.id > 0 && callback != null ? v -> {
            callback.onMessageLongClicked(message);
            return true;
        } : null);
        int group = groupAt(position);
        holder.bubble.setBackgroundResource((message.out ? OUT_BACKGROUNDS : IN_BACKGROUNDS)[group]);
        holder.bubble.setPaddingRelative(dp(holder.bubble, message.out ? 12 : 22), dp(holder.bubble, 7),
                dp(holder.bubble, message.out ? 22 : 12), dp(holder.bubble, 6));
        holder.row.setPaddingRelative(holder.row.getPaddingStart(),
                dp(holder.row, group == SINGLE || group == TOP ? 4 : 1),
                holder.row.getPaddingEnd(), dp(holder.row, 1));
        boolean showDate = position == 0 || !sameDay(messages.get(position - 1).date, message.date);
        holder.date.setVisibility(showDate ? View.VISIBLE : View.GONE);
        holder.date.setText(showDate ? formatDate(message.date) : "");
        holder.text.setText(message.body.isEmpty() && message.attachments.isEmpty()
                ? holder.text.getContext().getString(R.string.chat_message_empty) : message.body);
        holder.text.setVisibility(holder.text.length() == 0 ? View.GONE : View.VISIBLE);
        String time = new SimpleDateFormat("HH:mm", Locale.getDefault())
                .format(new Date(message.date * 1000L));
        if (message.out && message.sendStatus != ChatMessage.SendStatus.NONE) {
            int label = message.sendStatus == ChatMessage.SendStatus.SENDING
                    ? R.string.chat_status_sending : R.string.chat_status_unconfirmed;
            String status = holder.time.getContext().getString(label);
            holder.time.setText(time + " · " + status);
            holder.time.setContentDescription(time + ", " + status);
        } else if (message.out && message.readState >= 0) {
            holder.time.setText(time + (message.readState == 1 ? "  ✓✓" : "  ✓"));
            holder.time.setContentDescription(time + ", " + holder.time.getContext().getString(
                    message.readState == 1 ? R.string.chat_status_read : R.string.chat_status_sent));
        } else {
            holder.time.setText(time);
            holder.time.setContentDescription(time);
        }

        if (holder.avatar != null) {
            boolean endOfGroup = group == SINGLE || group == BOTTOM;
            holder.avatar.setVisibility(endOfGroup ? View.VISIBLE : View.INVISIBLE);
            holder.avatar.setImageResource(R.drawable.chat_avatar_placeholder);
            holder.avatar.setContentDescription(message.author);
            if (endOfGroup && !message.avatar.isEmpty()) images.load(holder.avatar, message.avatar, true, null);
        }
        for (ChatAttachment attachment : message.attachments) addAttachment(holder, attachment);
        holder.attachments.setVisibility(holder.attachments.getChildCount() == 0 ? View.GONE : View.VISIBLE);
    }

    private void addAttachment(Holder holder, ChatAttachment attachment) {
        boolean hasPreview = !attachment.previewUrl.isEmpty();
        View child = LayoutInflater.from(holder.itemView.getContext()).inflate(hasPreview
                ? R.layout.item_msg_attachment_media : R.layout.item_msg_attachment_label,
                holder.attachments, false);
        child.setTag(attachment);
        if (hasPreview) {
            ChatMediaPreview preview = child.findViewById(R.id.chat_attachment_media);
            preview.setSourceDimensions(attachment.width, attachment.height);
            ImageView image = child.findViewById(R.id.chat_attachment_image);
            image.setContentDescription(attachmentTitle(child, attachment));
            boolean video = attachment.type.equals("video") || attachment.type.equals("clip");
            child.findViewById(R.id.chat_attachment_play).setVisibility(video ? View.VISIBLE : View.GONE);
            TextView duration = child.findViewById(R.id.chat_attachment_duration);
            duration.setVisibility(attachment.duration > 0 ? View.VISIBLE : View.GONE);
            duration.setText(formatDuration(attachment.duration));
            TextView caption = child.findViewById(R.id.chat_attachment_caption);
            boolean showCaption = video || !isPicture(attachment.type);
            caption.setVisibility(showCaption ? View.VISIBLE : View.GONE);
            caption.setText(showCaption ? attachmentTitle(child, attachment) : "");
            images.load(image, attachment.previewUrl, false,
                    attachment.width > 0 && attachment.height > 0 ? null : (width, height) -> {
                        if (child.getTag() == attachment) preview.setSourceDimensions(width, height);
                    });
        } else {
            ((TextView) child.findViewById(R.id.chat_attachment_title)).setText(attachmentTitle(child, attachment));
            TextView subtitle = child.findViewById(R.id.chat_attachment_subtitle);
            String detail = attachment.subtitle;
            if (detail.isEmpty() && attachment.duration > 0) detail = formatDuration(attachment.duration);
            subtitle.setText(detail);
            subtitle.setVisibility(detail.isEmpty() ? View.GONE : View.VISIBLE);
        }
        boolean canOpen = ChatAttachmentActions.canOpen(attachment)
                || ("audio_message".equals(attachment.type) && !attachment.url.isEmpty())
                || hasPreview || (attachment.id != 0
                && (attachment.type.equals("video") || attachment.type.equals("clip")));
        if (callback != null && canOpen) {
            child.setOnClickListener(v -> callback.onAttachmentClicked(attachment));
        }
        if (holder.attachments.getChildCount() > 0 || holder.text.getVisibility() == View.VISIBLE) {
            ((LinearLayout.LayoutParams) child.getLayoutParams()).topMargin = dp(child, 5);
        }
        holder.attachments.addView(child);
    }

    private static boolean isPicture(String type) {
        return type.equals("photo") || type.equals("posted_photo") || type.equals("sticker") || type.equals("graffiti");
    }

    private static String attachmentTitle(View view, ChatAttachment attachment) {
        int label;
        switch (attachment.type) {
            case "photo": case "posted_photo": label = R.string.chat_attachment_photo; break;
            case "video": case "clip": label = R.string.chat_attachment_video; break;
            case "sticker": label = R.string.chat_attachment_sticker; break;
            case "doc": label = R.string.chat_attachment_doc; break;
            case "audio": label = R.string.chat_attachment_audio; break;
            case "audio_message": label = R.string.chat_attachment_voice; break;
            case "link": label = R.string.chat_attachment_link; break;
            case "wall": case "wall_reply": label = R.string.chat_attachment_wall; break;
            case "gift": label = R.string.chat_attachment_gift; break;
            case "poll": label = R.string.chat_attachment_poll; break;
            case "market": label = R.string.chat_attachment_market; break;
            case "story": label = R.string.chat_attachment_story; break;
            default: label = R.string.chat_attachment_unknown;
        }
        String type = view.getContext().getString(label);
        if (attachment.title.isEmpty() || attachment.title.equals("Вложение")) return type;
        return attachment.title.equals(type) || isPicture(attachment.type) ? attachment.title : type + ": " + attachment.title;
    }

    int groupAt(int position) {
        boolean previous = position > 0 && grouped(messages.get(position - 1), messages.get(position));
        boolean next = position + 1 < messages.size() && grouped(messages.get(position), messages.get(position + 1));
        return previous ? (next ? MIDDLE : BOTTOM) : (next ? TOP : SINGLE);
    }

    private static boolean grouped(ChatMessage previous, ChatMessage current) {
        long difference = current.date - previous.date;
        return previous.out == current.out && (current.out || previous.fromId == current.fromId)
                && difference >= 0 && difference < 300 && sameDay(previous.date, current.date);
    }

    private static boolean sameDay(long first, long second) {
        Calendar a = Calendar.getInstance(), b = Calendar.getInstance();
        a.setTimeInMillis(first * 1000L);
        b.setTimeInMillis(second * 1000L);
        return a.get(Calendar.ERA) == b.get(Calendar.ERA) && a.get(Calendar.YEAR) == b.get(Calendar.YEAR)
                && a.get(Calendar.DAY_OF_YEAR) == b.get(Calendar.DAY_OF_YEAR);
    }

    private static String formatDate(long seconds) {
        Calendar date = Calendar.getInstance();
        date.setTimeInMillis(seconds * 1000L);
        String pattern = date.get(Calendar.YEAR) == Calendar.getInstance().get(Calendar.YEAR) ? "d MMMM" : "d MMMM yyyy";
        return new SimpleDateFormat(pattern, Locale.getDefault()).format(date.getTime());
    }

    static String formatDuration(int seconds) {
        return seconds >= 3600 ? String.format(Locale.ROOT, "%d:%02d:%02d", seconds / 3600, (seconds / 60) % 60, seconds % 60)
                : String.format(Locale.ROOT, "%d:%02d", seconds / 60, seconds % 60);
    }

    @Override public void onViewRecycled(@NonNull Holder holder) {
        clearImages(holder.itemView);
        holder.attachments.removeAllViews();
        holder.attachments.setVisibility(View.GONE);
        holder.text.setText("");
        holder.time.setText("");
        holder.date.setText("");
        super.onViewRecycled(holder);
    }

    private void clearImages(View view) {
        if (view instanceof ImageView) images.clear((ImageView) view);
        if (view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) view;
            for (int i = 0; i < group.getChildCount(); i++) clearImages(group.getChildAt(i));
        }
        if (view.getTag() instanceof ChatAttachment) view.setTag(null);
    }

    private static int dp(View view, int value) { return Math.round(value * view.getResources().getDisplayMetrics().density); }

    static final class Holder extends RecyclerView.ViewHolder {
        final View row, bubble;
        final TextView text, time, date;
        final ImageView avatar;
        final LinearLayout attachments;
        Holder(View itemView) {
            super(itemView);
            row = itemView.findViewById(R.id.msg_row);
            bubble = itemView.findViewById(R.id.msg_wrap);
            text = itemView.findViewById(R.id.msg_text);
            time = itemView.findViewById(R.id.msg_time);
            date = itemView.findViewById(R.id.msg_date);
            avatar = itemView.findViewById(R.id.msg_sender_photo);
            attachments = itemView.findViewById(R.id.msg_attachments);
        }
    }

    private static final class GlideImages implements Images {
        @Override public void load(ImageView target, String url, boolean circle, @Nullable ImageSize size) {
            if (circle) {
                MessageAvatars.bind(target, url, false);
                return;
            }
            com.bumptech.glide.RequestBuilder<Drawable> request = Glide.with(target).load(url)
                    .placeholder(R.drawable.chat_attachment_placeholder)
                    .error(R.drawable.chat_attachment_placeholder).dontAnimate().fitCenter();
            if (size != null) request = request.listener(new RequestListener<Drawable>() {
                @Override public boolean onLoadFailed(@Nullable GlideException error, Object model,
                                                       Target<Drawable> target, boolean first) { return false; }
                @Override public boolean onResourceReady(Drawable drawable, Object model,
                        Target<Drawable> target, DataSource source, boolean first) {
                    size.onSize(drawable.getIntrinsicWidth(), drawable.getIntrinsicHeight());
                    return false;
                }
            });
            request.into(target);
        }
        @Override public void clear(ImageView target) {
            Glide.with(target.getContext().getApplicationContext()).clear(target);
            target.setImageDrawable(null);
        }
    }
}
