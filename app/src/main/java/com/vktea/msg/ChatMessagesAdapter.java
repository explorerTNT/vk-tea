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
import java.util.Set;
import java.util.WeakHashMap;

/** Recycled display rows only; API requests and opening viewers belong to the activity. */
public final class ChatMessagesAdapter extends RecyclerView.Adapter<ChatMessagesAdapter.Holder> {
    public interface Callback {
        void onAttachmentClicked(ChatAttachment attachment);
        default void onVoiceSeek(ChatAttachment attachment, float fraction) { }
        default void onMessageLongClicked(ChatMessage message) { }
        default void onMessageClicked(ChatMessage message) { }
        default void onAuthorClicked(long fromId) { }
        default void onQuoteClicked(long messageId) { }
        default void onAttachmentLongClicked(ChatAttachment attachment) { }
        default void onLinkClicked(String url) { }
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
    @Nullable private Set<Long> selectedIds;
    private boolean selectionMode;
    private long readCursor;

    public ChatMessagesAdapter(Callback callback) { this(callback, 0L); }

    public ChatMessagesAdapter(Callback callback, long peerId) {
        this.callback = callback;
        this.peerId = peerId;
        this.images = new GlideImages();
    }

    ChatMessagesAdapter(Callback callback, Images images) {
        this.callback = callback;
        this.peerId = 0;
        this.images = images;
    }

    private final long peerId;

    /**
     * Ответ (наш reply_to или свайп официального клиента) на чтении в v5.82 приходит
     * как единственная пересылка без id и peer_id (живой дамп 18.11.2026) -
     * показываем её цитатой. Настоящие пересылки всегда несут id.
     */
    @Nullable private ChatMessage replyShapedForward(ChatMessage message) {
        if (message.replyTo != null || message.forwarded.size() != 1) return null;
        ChatMessage fwd = message.forwarded.get(0);
        if (fwd.id != 0) return null;
        return fwd.peerId == 0 || fwd.peerId == peerId ? fwd : null;
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

    @Nullable public ChatMessage findMessage(long messageId) {
        for (ChatMessage message : messages) if (message.id == messageId) return message;
        return null;
    }

    public List<ChatMessage> currentMessages() { return new ArrayList<>(messages); }

    public void setSelectionMode(boolean active) {
        if (selectionMode == active) return;
        selectionMode = active;
        notifyDataSetChanged();
    }

    public void setSelectedIds(@Nullable Set<Long> ids) {
        selectedIds = ids;
        notifyDataSetChanged();
    }

    /** Coffee/официальный клиент: исходящее прочитано, когда id <= conversation.out_read. */
    public void setReadCursor(long outRead) {
        if (readCursor == outRead) return;
        readCursor = outRead;
        notifyDataSetChanged();
    }

    @NonNull @Override public Holder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        return new Holder(LayoutInflater.from(parent.getContext()).inflate(
                viewType == 1 ? R.layout.item_msg_out : R.layout.item_msg_in, parent, false));
    }

    @Override public void onBindViewHolder(@NonNull Holder holder, int position) {
        clearImages(holder.itemView);
        holder.attachments.removeAllViews();
        holder.header.removeAllViews();
        ChatMessage message = messages.get(position);
        boolean night = (holder.itemView.getResources().getConfiguration().uiMode
                & android.content.res.Configuration.UI_MODE_NIGHT_MASK)
                == android.content.res.Configuration.UI_MODE_NIGHT_YES;
        boolean selected = selectedIds != null && selectedIds.contains(message.id);
        if (selectionMode && callback != null) {
            holder.bubble.setOnClickListener(v -> callback.onMessageClicked(message));
            holder.bubble.setOnLongClickListener(v -> {
                callback.onMessageClicked(message);
                return true;
            });
        } else {
            holder.bubble.setOnClickListener(null);
            holder.bubble.setOnLongClickListener(message.id > 0 && callback != null ? v -> {
                callback.onMessageLongClicked(message);
                return true;
            } : null);
        }
        int group = groupAt(position);
        holder.bubble.setBackgroundResource((message.out ? OUT_BACKGROUNDS : IN_BACKGROUNDS)[group]);
        if (selected) {
            holder.bubble.setBackgroundTintList(android.content.res.ColorStateList.valueOf(0x334774A8));
        } else if (night && !message.out) {
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
        renderReply(holder, message, night);
        ChatMessage replyForward = replyShapedForward(message);
        if (replyForward != null) renderQuote(holder, replyForward, message, night);
        else renderForwarded(holder.header, message, 0, night);
        holder.header.setVisibility(holder.header.getChildCount() == 0 ? View.GONE : View.VISIBLE);
        holder.text.setText(message.body.isEmpty() && message.attachments.isEmpty()
                && message.forwarded.isEmpty() && message.replyTo == null
                ? holder.text.getContext().getString(R.string.chat_message_empty)
                : com.vktea.util.VkTextUtils.parse(message.body, linkColor(holder, night),
                        url -> {
                            if (callback != null) callback.onLinkClicked(url);
                        }));
        holder.text.setVisibility(holder.text.length() == 0 ? View.GONE : View.VISIBLE);
        String time = new SimpleDateFormat("HH:mm", Locale.getDefault())
                .format(new Date(message.date * 1000L));
        bindStatusIcon(holder, message, night, time);
        ((LinearLayout.LayoutParams) holder.text.getLayoutParams()).topMargin =
                holder.text.getVisibility() == View.VISIBLE && holder.header.getChildCount() > 0
                        && message.replyTo == null ? dp(holder.text, 4) : 0;

        if (holder.avatar != null) {
            boolean endOfGroup = group == SINGLE || group == BOTTOM;
            holder.avatar.setVisibility(endOfGroup ? View.VISIBLE : View.INVISIBLE);
            holder.avatar.setImageResource(R.drawable.chat_avatar_placeholder);
            holder.avatar.setContentDescription(message.author);
            if (endOfGroup && !message.avatar.isEmpty()) images.load(holder.avatar, message.avatar, true, null);
        }
        for (ChatAttachment attachment : message.attachments) addAttachment(holder.attachments, holder.text, attachment);
        holder.attachments.setVisibility(holder.attachments.getChildCount() == 0 ? View.GONE : View.VISIBLE);
    }

    /** Coffee MessageStatusDrawable: часы при отправке, точка при доставке, пусто при прочтении, ошибка. */
    private void bindStatusIcon(Holder holder, ChatMessage message, boolean night, String time) {
        if (!message.out) {
            holder.time.setText(time);
            holder.time.setCompoundDrawablesRelativeWithIntrinsicBounds(null, null, null, null);
            holder.time.setContentDescription(time);
            return;
        }
        android.graphics.drawable.Drawable icon = null;
        String status = null;
        if (message.sendStatus == ChatMessage.SendStatus.SENDING) {
            icon = statusDrawable(holder, R.drawable.ic_chat_sending_13dp);
            status = holder.time.getContext().getString(R.string.chat_status_sending);
        } else if (message.sendStatus == ChatMessage.SendStatus.UNCONFIRMED) {
            icon = statusDrawable(holder, R.drawable.ic_msg_error);
            status = holder.time.getContext().getString(R.string.chat_status_unconfirmed);
        } else if (message.readState == 1 || message.readState < 0 && message.id > 0
                && readCursor > 0 && message.id <= readCursor) {
            status = holder.time.getContext().getString(R.string.chat_status_read);
        } else {
            icon = statusDrawable(holder, R.drawable.dot_unread);
            status = holder.time.getContext().getString(R.string.chat_status_sent);
        }
        holder.time.setText(time);
        holder.time.setCompoundDrawablesRelativeWithIntrinsicBounds(icon, null, null, null);
        holder.time.setCompoundDrawablePadding(dp(holder.time, 3));
        holder.time.setContentDescription(status == null ? time : time + ", " + status);
    }

    private android.graphics.drawable.Drawable statusDrawable(Holder holder, int res) {
        android.graphics.drawable.Drawable drawable = holder.time.getContext().getDrawable(res);
        if (drawable == null) return null;
        drawable = drawable.mutate();
        drawable.setColorFilter(new android.graphics.PorterDuffColorFilter(
                0xFF7AA0CC, android.graphics.PorterDuff.Mode.SRC_ATOP));
        return drawable;
    }

    private static int linkColor(Holder holder, boolean night) {
        return night ? 0xFF71AAEB : 0xFF4C75A3;
    }

    /** Короткая цитата ответа над содержимым: полоска акцента + автор + текст/вложение. */
    private void renderReply(Holder holder, ChatMessage message, boolean night) {
        if (message.replyTo == null) return;
        renderQuote(holder, message.replyTo, message, night);
    }

    private void renderQuote(Holder holder, ChatMessage quote, ChatMessage message, boolean night) {
        View block = LayoutInflater.from(holder.itemView.getContext())
                .inflate(R.layout.item_msg_reply, holder.header, false);
        TextView name = block.findViewById(R.id.reply_name);
        TextView text = block.findViewById(R.id.reply_text);
        name.setText(quote.author.isEmpty() ? "Сообщение" : quote.author);
        String body = quote.body;
        if (body.isEmpty()) for (ChatAttachment attachment : quote.attachments) {
            body = body.isEmpty() ? attachment.title : body + " · " + attachment.title;
        }
        if (body.isEmpty() && !quote.forwarded.isEmpty()) body = "Пересланное сообщение";
        text.setText(body);
        text.setTextColor(night && !message.out ? 0xFF939CA5 : 0xFF8A939C);
        if (quote.id > 0 && callback != null) {
            // Тап по цитате ведёт к исходному сообщению (как в официальном клиенте).
            block.setOnClickListener(v -> callback.onQuoteClicked(quote.id));
        } else if (quote.fromId != 0 && callback != null) {
            block.setOnClickListener(v -> callback.onAuthorClicked(quote.fromId));
        }
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        params.bottomMargin = dp(block, 4);
        holder.header.addView(block, params);
    }

    /** Вложенные пересланные в стиле Coffee message_fwd_include: шапка автора + текст + вложения. */
    private void renderForwarded(LinearLayout container, ChatMessage message, int depth, boolean night) {
        for (ChatMessage fwd : message.forwarded) {
            View block = LayoutInflater.from(container.getContext())
                    .inflate(R.layout.item_msg_fwd, container, false);
            View stripe = block.findViewById(R.id.fwd_stripe);
            if (depth > 0) {
                stripe.setVisibility(View.VISIBLE);
                LinearLayout.LayoutParams params = (LinearLayout.LayoutParams) stripe.getLayoutParams();
                params.width = dp(stripe, 2);
                params.leftMargin = dp(stripe, (depth - 1) * 8);
                stripe.setLayoutParams(params);
                block.setPaddingRelative(dp(block, 8), 0, 0, 0);
            }
            ImageView photo = block.findViewById(R.id.fwd_photo);
            TextView name = block.findViewById(R.id.fwd_name);
            TextView time = block.findViewById(R.id.fwd_time);
            TextView text = block.findViewById(R.id.fwd_text);
            LinearLayout attachments = block.findViewById(R.id.fwd_attachments);
            View header = block.findViewById(R.id.fwd_header);
            name.setText(fwd.author.isEmpty() ? "Сообщение" : fwd.author);
            time.setText(new SimpleDateFormat("d MMMM HH:mm", Locale.getDefault())
                    .format(new Date(fwd.date * 1000L)));
            if (!fwd.avatar.isEmpty()) images.load(photo, fwd.avatar, true, null);
            View.OnClickListener openAuthor = fwd.fromId != 0 && callback != null
                    ? v -> callback.onAuthorClicked(fwd.fromId) : null;
            header.setClickable(openAuthor != null);
            header.setOnClickListener(openAuthor);
            if (!fwd.body.isEmpty()) {
                text.setText(fwd.body);
                text.setTextColor(night && !message.out ? 0xFFE7E8EA : 0xFF20252B);
                text.setVisibility(View.VISIBLE);
            }
            for (ChatAttachment attachment : fwd.attachments) addAttachment(attachments, text, attachment);
            attachments.setVisibility(attachments.getChildCount() == 0 ? View.GONE : View.VISIBLE);
            LinearLayout nested = block.findViewById(R.id.fwd_nested);
            renderForwarded(nested, fwd, depth + 1, night);
            nested.setVisibility(nested.getChildCount() == 0 ? View.GONE : View.VISIBLE);
            LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
            params.bottomMargin = dp(block, 4);
            container.addView(block, params);
        }
    }

    private void addAttachment(LinearLayout container, TextView textRef, ChatAttachment attachment) {
        boolean hasPreview = !attachment.previewUrl.isEmpty();
        boolean voice = "audio_message".equals(attachment.type);
        int layout = voice ? R.layout.item_msg_attachment_voice : hasPreview
                ? R.layout.item_msg_attachment_media : R.layout.item_msg_attachment_label;
        View child = LayoutInflater.from(container.getContext()).inflate(layout, container, false);
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
        if (callback != null && ("doc".equals(attachment.type) || "link".equals(attachment.type)
                || "article".equals(attachment.type)
                || ((attachment.type.equals("wall") || attachment.type.equals("wall_reply"))
                && attachment.ownerId != 0 && attachment.id != 0)
                || !attachment.url.isEmpty())) {
            child.setOnLongClickListener(v -> {
                callback.onAttachmentLongClicked(attachment);
                return true;
            });
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
        if (container.getChildCount() > 0 || textRef != null && textRef.getVisibility() == View.VISIBLE
                && textRef.length() > 0) {
            ((LinearLayout.LayoutParams) child.getLayoutParams()).topMargin = dp(child, 5);
        }
        container.addView(child);
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
        holder.header.removeAllViews();
        holder.header.setVisibility(View.GONE);
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
        final LinearLayout header;
        Holder(View itemView) {
            super(itemView);
            row = itemView.findViewById(R.id.msg_row);
            bubble = itemView.findViewById(R.id.msg_wrap);
            text = itemView.findViewById(R.id.msg_text);
            time = itemView.findViewById(R.id.msg_time);
            date = itemView.findViewById(R.id.msg_date);
            avatar = itemView.findViewById(R.id.msg_sender_photo);
            attachments = itemView.findViewById(R.id.msg_attachments);
            header = itemView.findViewById(R.id.msg_header);
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
