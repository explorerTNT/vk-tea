package com.vktea.msg;

import android.graphics.drawable.Drawable;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.SeekBar;
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
import java.util.Map;
import java.util.WeakHashMap;

/** Recycled display rows only; API requests and opening viewers belong to the activity. */
public final class ChatMessagesAdapter extends RecyclerView.Adapter<ChatMessagesAdapter.Holder> {
    public interface Callback {
        void onAttachmentClicked(ChatAttachment attachment);
        default void onVoiceSeek(ChatAttachment attachment, float fraction) { }
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
    private final Map<View, ChatAttachment> voiceRows = new WeakHashMap<>();
    @Nullable private ChatAttachment activeVoice;
    private boolean voicePlaying;
    private long voicePositionMs, voiceDurationMs;

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

    /** Позиция сообщения по id, -1 если не найдено (для скролла к закрепу). */
    public int findMessagePosition(long messageId) {
        for (int i = 0; i < messages.size(); i++) {
            if (messages.get(i).id == messageId) return i;
        }
        return -1;
    }

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
        boolean night = (holder.itemView.getResources().getConfiguration().uiMode
                & android.content.res.Configuration.UI_MODE_NIGHT_MASK)
                == android.content.res.Configuration.UI_MODE_NIGHT_YES;
        if (night && !message.out) {
            holder.bubble.setBackgroundTintList(android.content.res.ColorStateList.valueOf(0xFF2C2D2E));
            holder.text.setTextColor(0xFFE7E8EA);
        } else {
            holder.bubble.setBackgroundTintList(null);
            holder.text.setTextColor(0xFF20252B);
        }
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
        boolean voice = "audio_message".equals(attachment.type);
        int layout = voice ? R.layout.item_msg_attachment_voice : hasPreview
                ? R.layout.item_msg_attachment_media : R.layout.item_msg_attachment_label;
        View child = LayoutInflater.from(holder.itemView.getContext()).inflate(layout, holder.attachments, false);
        child.setTag(attachment);
        if (hasPreview && !voice) {
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
        boolean canOpen = voice ? voiceCanOpen(attachment) : ChatAttachmentActions.canOpen(attachment)
                || hasPreview || (attachment.id != 0
                && (attachment.type.equals("video") || attachment.type.equals("clip")
                || ((attachment.type.equals("wall") || attachment.type.equals("wall_reply"))
                && attachment.ownerId != 0)))
                || (attachment.type.equals("story") && !attachment.url.isEmpty());
        if (callback != null && canOpen) {
            child.setOnClickListener(v -> callback.onAttachmentClicked(attachment));
        }
        if (voice) {
            SeekBar progress = child.findViewById(R.id.chat_voice_progress);
            progress.setProgressDrawable(new VoiceWaveformDrawable(child.getContext(), attachment.waveform()));
            progress.setTag(R.id.chat_voice_progress, Boolean.FALSE);
            progress.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
                private boolean tracking;
                @Override public void onStartTrackingTouch(SeekBar bar) {
                    tracking = true;
                    bar.setTag(R.id.chat_voice_progress, Boolean.TRUE);
                }
                @Override public void onProgressChanged(SeekBar bar, int value, boolean fromUser) {
                    if (!fromUser) return;
                    long length = VoiceMessagePlayer.sameVoice(activeVoice, attachment) && voiceDurationMs > 0
                            ? voiceDurationMs : attachment.duration * 1000L;
                    setVoiceTime(child, length * value / 1000, length, true);
                    // Accessibility and keyboard changes do not have a touch tracking callback.
                    if (!tracking && callback != null) callback.onVoiceSeek(attachment, value / 1000f);
                }
                @Override public void onStopTrackingTouch(SeekBar bar) {
                    tracking = false;
                    bar.setTag(R.id.chat_voice_progress, Boolean.FALSE);
                    if (callback != null) callback.onVoiceSeek(attachment, bar.getProgress() / 1000f);
                }
            });
            voiceRows.put(child, attachment);
            updateVoiceRow(child, attachment);
        }
        if (holder.attachments.getChildCount() > 0 || holder.text.getVisibility() == View.VISIBLE) {
            ((LinearLayout.LayoutParams) child.getLayoutParams()).topMargin = dp(child, 5);
        }
        holder.attachments.addView(child);
    }

    /** Updates bound rows in place so progress ticks never rebind or disturb message scrolling. */
    public void setVoicePlayback(@Nullable ChatAttachment attachment, boolean playing,
                                 long positionMs, long durationMs) {
        activeVoice = attachment;
        voicePlaying = playing;
        voicePositionMs = Math.max(0, positionMs);
        voiceDurationMs = Math.max(0, durationMs);
        for (Map.Entry<View, ChatAttachment> entry : new ArrayList<>(voiceRows.entrySet())) {
            View row = entry.getKey();
            if (row != null && row.getTag() == entry.getValue()) updateVoiceRow(row, entry.getValue());
        }
    }

    private void updateVoiceRow(View row, ChatAttachment attachment) {
        boolean active = VoiceMessagePlayer.sameVoice(activeVoice, attachment);
        boolean canOpen = voiceCanOpen(attachment);
        long duration = active && voiceDurationMs > 0 ? voiceDurationMs : attachment.duration * 1000L;
        long position = active ? Math.min(voicePositionMs, duration > 0 ? duration : voicePositionMs) : 0;
        ImageView icon = row.findViewById(R.id.chat_voice_play_icon);
        icon.setImageResource(active && voicePlaying ? R.drawable.chat_voice_pause : R.drawable.chat_voice_play);
        icon.setAlpha(canOpen ? 1f : .5f);
        String time = attachment.duration > 0 ? formatDuration(attachment.duration) : "";
        row.setContentDescription(!canOpen ? row.getContext().getString(R.string.chat_voice_unavailable)
                : active && voicePlaying ? row.getContext().getString(R.string.chat_voice_pause)
                : time.isEmpty() ? row.getContext().getString(R.string.chat_voice_play)
                : row.getContext().getString(R.string.chat_voice_play_with_duration, time));
        SeekBar progress = row.findViewById(R.id.chat_voice_progress);
        progress.setEnabled(canOpen && active && duration > 0);
        if (!Boolean.TRUE.equals(progress.getTag(R.id.chat_voice_progress))) {
            progress.setProgress(duration > 0 ? (int) Math.min(1000, position * 1000 / duration) : 0);
            setVoiceTime(row, position, duration, active);
        }
    }

    private static void setVoiceTime(View row, long position, long duration, boolean active) {
        TextView time = row.findViewById(R.id.chat_attachment_subtitle);
        String total = duration > 0 ? formatDuration((int) Math.min(Integer.MAX_VALUE, duration / 1000)) : "";
        if (active && duration > 0) {
            String current = formatDuration((int) Math.min(Integer.MAX_VALUE, position / 1000));
            time.setText(row.getContext().getString(R.string.chat_voice_elapsed, current, total));
        } else time.setText(total);
        time.setVisibility(time.length() == 0 ? View.GONE : View.VISIBLE);
    }

    private static boolean voiceCanOpen(ChatAttachment attachment) {
        return !attachment.url.isEmpty() || (attachment.ownerId != 0 && attachment.id > 0);
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
            case "wall": case "wall_reply": return attachment.subtitle.isEmpty()
                    ? view.getContext().getString(R.string.chat_attachment_wall) : attachment.subtitle;
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
        voiceRows.remove(view);
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
