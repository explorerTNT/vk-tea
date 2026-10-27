package com.vktea.msg;

import static org.junit.Assert.*;

import android.content.Context;
import android.content.res.Configuration;
import android.text.Layout;
import android.view.ContextThemeWrapper;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.TextView;

import androidx.recyclerview.widget.RecyclerView;

import com.vktea.R;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.GraphicsMode;
import org.robolectric.annotation.LooperMode;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Calendar;
import java.util.Collections;
import java.util.List;

/** Synthetic models and an inert drawable loader exercise real Android row measurement. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 28)
@LooperMode(LooperMode.Mode.PAUSED)
public class ChatMessagesAdapterTest {
    private final FakeImages images = new FakeImages();
    private final List<ChatAttachment> clicked = new ArrayList<>();
    private final ChatMessagesAdapter adapter = new ChatMessagesAdapter(clicked::add, images);

    @Test public void readIndicatorAppearsOnlyForKnownOutgoingStateAndRecyclingClearsIt() {
        ChatMessage unread = new ChatMessage(1, true, 0, "", "", "Text", 100,
                Collections.emptyList(), 0);
        ChatMessage read = new ChatMessage(2, true, 0, "", "", "Text", 100,
                Collections.emptyList(), 1);
        ChatMessage unknown = new ChatMessage(3, true, 0, "", "", "Text", 100,
                Collections.emptyList());
        adapter.replaceAll(Collections.singletonList(unread));
        ChatMessagesAdapter.Holder holder = bind(0, 320, 1f);
        assertTrue(holder.time.getText().toString().endsWith("✓"));
        assertTrue(holder.time.getContentDescription().toString().contains("не прочитано"));
        adapter.replaceAll(Collections.singletonList(read));
        adapter.onBindViewHolder(holder, 0);
        assertTrue(holder.time.getText().toString().endsWith("✓✓"));
        assertTrue(holder.time.getContentDescription().toString().contains("Прочитано"));
        adapter.replaceAll(Collections.singletonList(unknown));
        adapter.onBindViewHolder(holder, 0);
        assertFalse(holder.time.getText().toString().contains("✓"));
        assertFalse(holder.time.getContentDescription().toString().contains("прочитано"));
    }

    @Test @GraphicsMode(GraphicsMode.Mode.NATIVE)
    public void outgoingLongUnbrokenTextFitsNarrowLargeFontAndLandscape() {
        String body = "Synthetic text https://example.invalid/" + repeat("UnbrokenSegment", 35)
                + "\nПоследняя строка сообщения";
        adapter.replaceAll(Collections.singletonList(message(true, 1, body)));
        for (int width : new int[]{280, 320, 380, 640}) {
            ChatMessagesAdapter.Holder holder = bind(0, width, 1.5f);
            assertInsideRow(holder, width);
            Layout text = holder.text.getLayout();
            assertNotNull(text);
            assertTrue("unbroken words must wrap", text.getLineCount() > 1);
            assertTrue("real glyph widths are required", holder.text.getPaint().measureText("Synthetic") > 0);
            for (int line = 0; line < text.getLineCount(); line++) {
                assertTrue("visible glyphs must stay inside the text view", text.getLineMax(line) <= text.getWidth() + 1);
            }
            assertEquals(body.length(), text.getLineEnd(text.getLineCount() - 1));
            assertEquals(0, text.getEllipsisCount(text.getLineCount() - 1));
            assertTrue(holder.text.getHeight() >= text.getHeight());
            assertTrue(holder.time.getBottom() <= holder.bubble.getHeight() - holder.bubble.getPaddingBottom());
        }
    }

    @Test @GraphicsMode(GraphicsMode.Mode.NATIVE)
    public void incomingTextKeepsAvatarGutterAndTimestampInsideBubble() {
        adapter.replaceAll(Collections.singletonList(message(false, 1, repeat("Длинный текст ", 50))));
        ChatMessagesAdapter.Holder holder = bind(0, 280, 1.5f);
        assertInsideRow(holder, 280);
        assertTrue(holder.avatar.getRight() <= holder.bubble.getLeft());
        assertEquals(View.VISIBLE, holder.avatar.getVisibility());
        assertNotNull(holder.avatar.getDrawable());
        assertTrue(holder.text.getLayout().getLineCount() > 1);
    }

    @Test public void portraitAndWidePreviewsPreserveActualAspectAndBoundWidth() {
        for (boolean out : new boolean[]{false, true}) {
            for (int width : new int[]{280, 320, 380, 640}) {
                for (int[] size : new int[][]{{900, 1600}, {1600, 900}, {900, 4800}}) {
                    ChatAttachment photo = attachment("photo", "Фото", "synthetic-preview", size[0], size[1], 0);
                    adapter.replaceAll(Collections.singletonList(message(out, 1, "", photo)));
                    ChatMessagesAdapter.Holder holder = bind(0, width, 1.5f);
                    ChatMediaPreview preview = holder.itemView.findViewById(R.id.chat_attachment_media);
                    assertNotNull(preview);
                    double expected = (double) size[1] / size[0];
                    assertEquals(expected, (double) preview.getHeight() / preview.getWidth(), .04);
                    assertTrue(preview.getWidth() <= dp(preview.getContext(), 280));
                    assertTrue(preview.getHeight() <= dp(preview.getContext(), 420));
                    assertInsideRow(holder, width);
                    assertTrue(preview.getRight() <= ((View) preview.getParent()).getWidth());
                }
            }
        }
    }

    @Test public void mediaWidthFollowsUsableRowRatherThanGlobalScreenWidth() {
        adapter.replaceAll(Collections.singletonList(message(true, 1, "",
                attachment("photo", "Фото", "synthetic-preview", 1200, 800, 0))));
        ChatMessagesAdapter.Holder holder = bind(0, 380, 1f);
        View preview = holder.itemView.findViewById(R.id.chat_attachment_media);
        assertEquals(dp(preview.getContext(), 380) * .57, preview.getWidth(), dp(preview.getContext(), 2));
        int previousWidth = preview.getWidth();
        measure(holder.itemView, 280);
        assertTrue(preview.getWidth() < previousWidth);
        assertInsideRow(holder, 280);
    }

    @Test @GraphicsMode(GraphicsMode.Mode.NATIVE)
    public void photoOnlyBubbleFitsPreviewAndPaddingAlsoAfterRecyclingLongText() {
        ChatAttachment portrait = attachment("photo", "Фото", "synthetic-preview", 720, 1280, 0);
        for (boolean out : new boolean[]{false, true}) {
            for (int width : new int[]{280, 320, 380, 640}) {
                for (float fontScale : new float[]{1f, 1.5f}) {
                    adapter.replaceAll(Collections.singletonList(message(out, 1, "", portrait)));
                    ChatMessagesAdapter.Holder holder = bind(0, width, fontScale);
                    assertTightPhotoBubble(holder);
                    adapter.replaceAll(Collections.singletonList(message(out, 2,
                            repeat("Long text forces the widest permitted bubble. ", 15))));
                    adapter.onBindViewHolder(holder, 0);
                    measure(holder.itemView, width);
                    adapter.replaceAll(Collections.singletonList(message(out, 3, "", portrait)));
                    adapter.onBindViewHolder(holder, 0);
                    measure(holder.itemView, width);
                    assertTightPhotoBubble(holder);
                    assertInsideRow(holder, width);
                }
            }
        }
    }

    @Test public void everyAttachmentHasContentAndOnlySupportedViewerActionsAreClickable() {
        ChatAttachment video = attachment("video", "Synthetic film", "synthetic-preview", 1280, 720, 125);
        ChatAttachment doc = attachment("doc", "report.pdf", "", 0, 0, 0);
        ChatAttachment audio = attachment("audio_message", "Вложение", "", 0, 0, 30);
        ChatAttachment unknown = attachment("future_type", "Unsupported attachment", "", 0, 0, 0);
        adapter.replaceAll(Collections.singletonList(message(true, 1, "", video, doc, audio, unknown)));
        ChatMessagesAdapter.Holder holder = bind(0, 280, 1.5f);
        assertEquals(4, holder.attachments.getChildCount());
        assertEquals(View.VISIBLE, holder.itemView.findViewById(R.id.chat_attachment_play).getVisibility());
        assertEquals("2:05", ((TextView) holder.itemView.findViewById(R.id.chat_attachment_duration)).getText().toString());
        for (int i = 0; i < holder.attachments.getChildCount(); i++) {
            View child = holder.attachments.getChildAt(i);
            assertTrue(child.getHeight() > 0);
            if (i > 0) assertTrue(((TextView) child.findViewById(R.id.chat_attachment_title)).length() > 0);
            assertEquals(i == 0, child.performClick());
        }
        assertEquals(Collections.singletonList(video), clicked);
        assertEquals(View.GONE, holder.text.getVisibility());
        assertInsideRow(holder, 280);
    }

    @Test public void rebindingClearsOldAttachmentsAvatarAndPendingSizeCallback() {
        ChatAttachment photo = attachment("photo", "Фото", "synthetic-preview", 0, 0, 0);
        ChatMessage media = new ChatMessage(1, false, 42, "Synthetic author", "synthetic-avatar", "",
                time(), Collections.singletonList(photo));
        adapter.replaceAll(Collections.singletonList(media));
        ChatMessagesAdapter.Holder holder = bind(0, 320, 1f);
        ChatMediaPreview oldPreview = holder.itemView.findViewById(R.id.chat_attachment_media);
        ChatMessagesAdapter.ImageSize deferredSize = images.lastSize;
        ImageView oldImage = holder.itemView.findViewById(R.id.chat_attachment_image);
        int oldHeight = oldPreview.getHeight();
        adapter.replaceAll(Collections.singletonList(message(false, 2, "Replacement text")));
        adapter.onBindViewHolder(holder, 0);
        measure(holder.itemView, 320);
        assertTrue(images.cleared.contains(oldImage));
        assertTrue(images.cleared.contains(holder.avatar));
        assertEquals(0, holder.attachments.getChildCount());
        assertEquals(View.GONE, holder.attachments.getVisibility());
        assertEquals("Replacement text", holder.text.getText().toString());
        assertNotNull(holder.avatar.getDrawable());
        assertNotNull(deferredSize);
        deferredSize.onSize(100, 1000);
        oldPreview.measure(View.MeasureSpec.makeMeasureSpec(dp(oldPreview.getContext(), 320), View.MeasureSpec.AT_MOST),
                View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED));
        assertEquals(oldHeight, oldPreview.getMeasuredHeight());
        adapter.onViewRecycled(holder);
        assertEquals(0, holder.text.length());
        assertEquals(0, holder.time.length());
    }

    @Test public void outgoingMessagesGroupAndAddingUpdatesPreviousTail() {
        adapter.replaceAll(Arrays.asList(message(true, 1, "One"), message(true, 2, "Two"), message(true, 3, "Three")));
        assertEquals(ChatMessagesAdapter.TOP, adapter.groupAt(0));
        assertEquals(ChatMessagesAdapter.MIDDLE, adapter.groupAt(1));
        assertEquals(ChatMessagesAdapter.BOTTOM, adapter.groupAt(2));
        List<Integer> changed = new ArrayList<>();
        adapter.registerAdapterDataObserver(new RecyclerView.AdapterDataObserver() {
            @Override public void onItemRangeChanged(int start, int count) { changed.add(start); }
        });
        adapter.add(message(true, 4, "Four"));
        assertEquals(Collections.singletonList(2), changed);
        assertEquals(ChatMessagesAdapter.MIDDLE, adapter.groupAt(2));
        assertEquals(ChatMessagesAdapter.BOTTOM, adapter.groupAt(3));
        assertEquals(View.GONE, bind(1, 320, 1f).date.getVisibility());
    }

    @Test public void differentSendersAndDaysSplitGroupsAndDateMarkers() {
        ChatMessage first = message(false, 1, "First");
        ChatMessage other = new ChatMessage(2, false, 99, "Other", "", "Second", time() + 1, Collections.emptyList());
        ChatMessage nextDay = new ChatMessage(3, false, 99, "Other", "", "Third", time() + 86400, Collections.emptyList());
        adapter.replaceAll(Arrays.asList(first, other, nextDay));
        for (int i = 0; i < 3; i++) assertEquals(ChatMessagesAdapter.SINGLE, adapter.groupAt(i));
        assertEquals(View.VISIBLE, bind(0, 320, 1f).date.getVisibility());
        assertEquals(View.GONE, bind(1, 320, 1f).date.getVisibility());
        assertEquals(View.VISIBLE, bind(2, 320, 1f).date.getVisibility());
    }

    @Test public void emptyMessageAndUnavailablePhotoUseReadableFallback() {
        adapter.replaceAll(Collections.singletonList(message(true, 1, "")));
        ChatMessagesAdapter.Holder holder = bind(0, 280, 1.5f);
        assertEquals(View.VISIBLE, holder.text.getVisibility());
        assertTrue(holder.text.length() > 0);
        adapter.replaceAll(Collections.singletonList(message(true, 2, "", attachment("photo", "Фото", "", 0, 0, 0))));
        adapter.onBindViewHolder(holder, 0);
        measure(holder.itemView, 280);
        assertEquals(1, holder.attachments.getChildCount());
        assertTrue(((TextView) holder.itemView.findViewById(R.id.chat_attachment_title)).length() > 0);
        assertNull(holder.itemView.findViewById(R.id.chat_attachment_media));
    }

    private ChatMessagesAdapter.Holder bind(int position, int width, float fontScale) {
        Configuration configuration = new Configuration(RuntimeEnvironment.getApplication().getResources().getConfiguration());
        configuration.fontScale = fontScale;
        Context context = new ContextThemeWrapper(RuntimeEnvironment.getApplication().createConfigurationContext(configuration), R.style.Theme_VKTea);
        ChatMessagesAdapter.Holder holder = adapter.onCreateViewHolder(new FrameLayout(context), adapter.getItemViewType(position));
        adapter.onBindViewHolder(holder, position);
        measure(holder.itemView, width);
        return holder;
    }

    private static void measure(View view, int widthDp) {
        view.measure(View.MeasureSpec.makeMeasureSpec(dp(view.getContext(), widthDp), View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED));
        view.layout(0, 0, view.getMeasuredWidth(), view.getMeasuredHeight());
    }

    private static void assertInsideRow(ChatMessagesAdapter.Holder holder, int widthDp) {
        assertEquals(dp(holder.itemView.getContext(), widthDp), holder.itemView.getWidth());
        assertTrue(holder.bubble.getLeft() >= 0);
        assertTrue(holder.bubble.getRight() <= holder.row.getWidth());
        assertTrue(holder.time.getLeft() >= holder.bubble.getPaddingLeft());
        assertTrue(holder.time.getRight() <= holder.bubble.getWidth() - holder.bubble.getPaddingRight());
    }

    private static void assertTightPhotoBubble(ChatMessagesAdapter.Holder holder) {
        View preview = holder.itemView.findViewById(R.id.chat_attachment_media);
        assertEquals(View.GONE, holder.text.getVisibility());
        int expected = preview.getWidth() + holder.bubble.getPaddingLeft() + holder.bubble.getPaddingRight();
        assertEquals("photo-only bubble must have no extra gutter", expected,
                holder.bubble.getWidth(), dp(holder.bubble.getContext(), 1));
    }

    private static int dp(Context context, int value) { return Math.round(context.getResources().getDisplayMetrics().density * value); }
    private static String repeat(String value, int count) { return String.join("", Collections.nCopies(count, value)); }
    private static long time() {
        Calendar calendar = Calendar.getInstance();
        calendar.clear();
        calendar.set(2026, Calendar.OCTOBER, 23, 12, 0);
        return calendar.getTimeInMillis() / 1000L;
    }
    private static ChatMessage message(boolean out, long id, String body, ChatAttachment... attachments) {
        return new ChatMessage(id, out, 42, "Synthetic author", "", body, time() + id, Arrays.asList(attachments));
    }
    private static ChatAttachment attachment(String type, String title, String preview, int width, int height, int duration) {
        return new ChatAttachment(type, title, "", preview, "", width, height, duration, 1, 42, "");
    }
    private static final class FakeImages implements ChatMessagesAdapter.Images {
        final List<ImageView> cleared = new ArrayList<>();
        ChatMessagesAdapter.ImageSize lastSize;
        @Override public void load(ImageView target, String url, boolean circle, ChatMessagesAdapter.ImageSize size) {
            if (size != null) lastSize = size;
            target.setImageResource(circle ? R.drawable.chat_avatar_placeholder : R.drawable.chat_attachment_placeholder);
        }
        @Override public void clear(ImageView target) { cleared.add(target); target.setImageDrawable(null); }
    }
}
