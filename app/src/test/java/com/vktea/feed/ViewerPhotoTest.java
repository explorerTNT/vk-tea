package com.vktea.feed;

import static org.junit.Assert.*;
import static org.robolectric.Shadows.shadowOf;

import android.content.Intent;
import android.os.Parcel;
import android.widget.LinearLayout;

import org.json.JSONObject;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.LooperMode;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;

/** All identifiers and photo sources are synthetic; parsing never calls VK or reads CDN filenames. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 28)
@LooperMode(LooperMode.Mode.PAUSED)
public class ViewerPhotoTest {
    @Test public void realResponseIdentityCountersPermissionsAndUncroppedSourceAreRetained() throws Exception {
        ViewerPhoto photo = ViewerPhoto.parse(new JSONObject("{\"id\":23,\"owner_id\":-3000000001,"
                + "\"access_key\":\"synthetic-key\",\"can_like\":true,\"can_comment\":1,\"can_repost\":1,"
                + "\"likes\":{\"count\":7,\"user_likes\":1},\"comments\":{\"count\":4},\"reposts\":{\"count\":2},"
                + "\"sizes\":[{\"type\":\"p\",\"width\":300,\"height\":300,\"url\":\"file:///crop\"},"
                + "{\"type\":\"w\",\"width\":900,\"height\":1600,\"url\":\"file:///full\"}]}"));
        assertEquals("file:///full", photo.url);
        assertEquals(23, photo.id);
        assertEquals(-3000000001L, photo.ownerId);
        assertEquals("synthetic-key", photo.accessKey);
        assertEquals(7, photo.likes);
        assertEquals(4, photo.comments);
        assertEquals(2, photo.reposts);
        assertTrue(photo.hasIdentity() && photo.liked && photo.canLike && photo.canComment && photo.canRepost);
    }

    @Test public void urlOnlyAndIncompleteIdentityNeverInventPhotoIdsOrSocialCapabilities() throws Exception {
        ViewerPhoto fallback = ViewerPhoto.fromUrl("https://example.invalid/photo-42_123_access.jpg");
        assertEquals(0, fallback.id);
        assertEquals(0, fallback.ownerId);
        assertFalse(fallback.hasIdentity() || fallback.liked || fallback.canLike || fallback.canComment || fallback.canRepost);
        ViewerPhoto incomplete = ViewerPhoto.parse(new JSONObject("{\"id\":12,\"photo_604\":\"file:///x\",\"can_like\":1,\"can_comment\":1,\"can_repost\":1}"));
        assertFalse(incomplete.hasIdentity() || incomplete.canLike || incomplete.canComment || incomplete.canRepost);
        assertEquals("", ViewerPhoto.parse(null).url);
    }

    @Test public void legacyPidAndMissingPermissionsRemainConservativeUntilMetadataLoads() throws Exception {
        ViewerPhoto photo = ViewerPhoto.parse(new JSONObject("{\"pid\":31,\"owner_id\":42,\"photo_604\":\"file:///legacy\","
                + "\"access_key\":{},\"likes\":{\"count\":-1},\"comments\":{\"count\":4000000000}}"));
        assertTrue(photo.hasIdentity());
        assertEquals(31, photo.id);
        assertEquals("", photo.accessKey);
        assertEquals(0, photo.likes);
        assertEquals(Integer.MAX_VALUE, photo.comments);
        assertFalse(photo.canLike || photo.canComment || photo.canRepost);
    }

    @Test public void parcelRoundTripRetainsEveryField() {
        ViewerPhoto original = new ViewerPhoto("file:///photo", 17, -91, "synthetic-key", 7, 8, 9, true, true, false, true);
        Parcel parcel = Parcel.obtain();
        try {
            original.writeToParcel(parcel, 0);
            parcel.setDataPosition(0);
            ViewerPhoto restored = ViewerPhoto.CREATOR.createFromParcel(parcel);
            assertEquals(original.url, restored.url);
            assertEquals(original.id, restored.id);
            assertEquals(original.ownerId, restored.ownerId);
            assertEquals(original.accessKey, restored.accessKey);
            assertEquals(original.likes, restored.likes);
            assertEquals(original.comments, restored.comments);
            assertEquals(original.reposts, restored.reposts);
            assertEquals(original.liked, restored.liked);
            assertEquals(original.canLike, restored.canLike);
            assertEquals(original.canComment, restored.canComment);
            assertEquals(original.canRepost, restored.canRepost);
            assertEquals(0, restored.describeContents());
        } finally { parcel.recycle(); }
    }

    @Test public void renderedIntentFiltersUrlsAndKeepsParallelMetadataIndexWithoutMutableInputReferences() {
        ArrayList<String> urls = new ArrayList<>(Arrays.asList(null, "file:///a", " ", "file:///b"));
        ViewerPhoto first = new ViewerPhoto("file:///a", 1, 42, "a-key", 0, 0, 0, false, false, false, false);
        ViewerPhoto second = new ViewerPhoto("file:///b", 2, -91, "b-key", 0, 0, 0, false, false, false, false);
        ArrayList<ViewerPhoto> details = new ArrayList<>(Arrays.asList(null, first, null, second));
        LinearLayout container = new LinearLayout(RuntimeEnvironment.getApplication());
        // No layout is performed, so the real loader waits for a width and starts no request.
        PostPhotosRenderer.render(container, urls, details);
        urls.clear(); details.clear();
        container.getChildAt(1).performClick();
        Intent intent = shadowOf(RuntimeEnvironment.getApplication()).getNextStartedActivity();
        assertEquals(Arrays.asList("file:///a", "file:///b"), intent.getStringArrayListExtra("photos"));
        assertEquals(1, intent.getIntExtra("index", -1));
        ArrayList<ViewerPhoto> opened = intent.getParcelableArrayListExtra(ViewerPhoto.EXTRA_PHOTOS);
        assertEquals(2, opened.size());
        assertEquals(1, opened.get(0).id);
        assertEquals(2, opened.get(1).id);
        assertEquals("b-key", opened.get(1).accessKey);
    }

    @Test public void mismatchedMetadataCannotEnableActionsForAnUnrelatedUrl() {
        ViewerPhoto unrelated = new ViewerPhoto("file:///other", 17, 42, "key", 7, 0, 0, false, true, true, true);
        PostPhotosRenderer.openViewer(RuntimeEnvironment.getApplication(), Collections.singletonList("file:///selected"),
                Collections.singletonList(unrelated), 0);
        Intent intent = shadowOf(RuntimeEnvironment.getApplication()).getNextStartedActivity();
        ArrayList<ViewerPhoto> opened = intent.getParcelableArrayListExtra(ViewerPhoto.EXTRA_PHOTOS);
        assertEquals("file:///selected", opened.get(0).url);
        assertFalse(opened.get(0).hasIdentity());
    }
}
