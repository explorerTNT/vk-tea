package com.vktea.msg;

import static org.junit.Assert.*;

import android.graphics.Color;
import android.graphics.drawable.ColorDrawable;
import android.widget.ImageView;

import com.vktea.R;

import org.json.JSONObject;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.LooperMode;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 28)
@LooperMode(LooperMode.Mode.PAUSED)
public class MessageAvatarsTest {
    @Test public void largestAvailablePhotoFallsThroughBlankNullAndNonStringFields() throws Exception {
        assertEquals("large", MessageAvatars.photoUrl(new JSONObject("{\"photo_200\":\"large\",\"photo_100\":\"medium\",\"photo_50\":\"small\"}")));
        assertEquals("medium", MessageAvatars.photoUrl(new JSONObject("{\"photo_200\":\" \",\"photo_100\":\"medium\"}")));
        assertEquals("small", MessageAvatars.photoUrl(new JSONObject("{\"photo_200\":null,\"photo_100\":42,\"photo_50\":\"small\"}")));
        assertEquals("", MessageAvatars.photoUrl(new JSONObject("{\"photo_200\":{},\"photo_100\":false,\"photo_50\":[]}")));
        assertEquals("", MessageAvatars.photoUrl(null));
    }

    @Test public void chatPhotoObjectSupportsSameFallbackAndDirectFieldsTakePrecedence() throws Exception {
        assertEquals("chat-photo", MessageAvatars.photoUrl(new JSONObject("{\"photo\":{\"photo_200\":null,\"photo_100\":\"chat-photo\"}}")));
        assertEquals("direct", MessageAvatars.photoUrl(new JSONObject("{\"photo_100\":\"direct\",\"photo\":{\"photo_200\":\"nested\"}}")));
        assertEquals("", MessageAvatars.photoUrl(new JSONObject("{\"photo\":\"invalid-object\"}")));
    }

    @Test public void everyBindCancelsOldImageAndEmptyUrlLeavesVisiblePersonSilhouette() {
        ImageView target = new ImageView(RuntimeEnvironment.getApplication());
        FakeImages images = new FakeImages();
        MessageAvatars.bind(target, "synthetic-avatar", false, images);
        assertTrue(target.getDrawable() instanceof ColorDrawable);
        MessageAvatars.bind(target, " ", false, images);
        assertEquals(Arrays.asList("clear", "load:synthetic-avatar", "clear"), images.operations);
        assertNotNull(target.getDrawable());
        assertFalse(target.getDrawable() instanceof ColorDrawable);
        assertTrue(target.getDrawable().getIntrinsicWidth() > 0);
        MessageAvatars.bind(target, null, false, images);
        assertEquals(4, images.operations.size());
        assertEquals("clear", images.operations.get(3));
    }

    @Test public void groupLoadsHaveTheirOwnVisibleFailurePlaceholderAndClearCancelsRequest() {
        ImageView target = new ImageView(RuntimeEnvironment.getApplication());
        FakeImages images = new FakeImages();
        MessageAvatars.bind(target, "synthetic-community-avatar", true, images);
        assertEquals(R.drawable.message_avatar_group, images.lastPlaceholder);
        // The same drawable resource is passed to the real Glide placeholder/error options.
        target.setImageResource(images.lastPlaceholder);
        assertNotNull(target.getDrawable());
        assertTrue(target.getDrawable().getIntrinsicWidth() > 0);
        MessageAvatars.clear(target, images);
        assertEquals("clear", images.operations.get(images.operations.size() - 1));
        assertNotNull(target.getDrawable());
        assertFalse(target.getDrawable() instanceof ColorDrawable);
    }

    static final class FakeImages implements MessageAvatars.Images {
        final List<String> operations = new ArrayList<>();
        int lastPlaceholder;
        @Override public void clear(ImageView target) { operations.add("clear"); target.setImageDrawable(null); }
        @Override public void load(ImageView target, String url, int placeholder) {
            operations.add("load:" + url);
            lastPlaceholder = placeholder;
            target.setImageDrawable(new ColorDrawable(Color.BLUE));
        }
    }
}
