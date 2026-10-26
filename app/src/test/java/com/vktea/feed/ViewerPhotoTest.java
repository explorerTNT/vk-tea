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
        ViewerPhoto original = new ViewerPhoto("file:///photo", 17, -91, "synthetic-key", 7, 8, 9, true, true, false, true,
                "Synthetic author", "file:///avatar", 1700000000, "Synthetic caption");
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
            assertEquals(original.ownerName, restored.ownerName);
            assertEquals(original.ownerPhoto, restored.ownerPhoto);
            assertEquals(original.date, restored.date);
            assertEquals(original.text, restored.text);
            assertEquals(0, restored.describeContents());
        } finally { parcel.recycle(); }
    }

    @Test public void legacyParcelStillRestoresPhotoAndDefaultsOptionalDiscussionMetadata() {
        Parcel parcel = Parcel.obtain();
        try {
            parcel.writeString("file:///legacy"); parcel.writeLong(17); parcel.writeLong(-91); parcel.writeString("synthetic-key");
            parcel.writeInt(7); parcel.writeInt(8); parcel.writeInt(9);
            parcel.writeByte((byte) 1); parcel.writeByte((byte) 1); parcel.writeByte((byte) 0); parcel.writeByte((byte) 1);
            parcel.setDataPosition(0);
            ViewerPhoto restored = ViewerPhoto.CREATOR.createFromParcel(parcel);
            assertEquals("file:///legacy", restored.url);
            assertEquals(17, restored.id);
            assertEquals(-91, restored.ownerId);
            assertTrue(restored.liked && restored.canLike && restored.canRepost);
            assertFalse(restored.canComment);
            assertEquals("", restored.ownerName);
            assertEquals("", restored.ownerPhoto);
            assertEquals("", restored.text);
            assertEquals(0, restored.date);
        } finally { parcel.recycle(); }
    }

    @Test public void photoMetadataRetainsCaptionCreationTimeAndOptionalOwnerWithoutUsingPostId() throws Exception {
        ViewerPhoto parsed = ViewerPhoto.parse(new JSONObject("{\"id\":17,\"owner_id\":-91,\"post_id\":999,"
                + "\"created\":1700000000,\"text\":\"Synthetic caption\",\"owner_name\":\"Synthetic author\","
                + "\"owner_photo\":\"file:///avatar\",\"photo_604\":\"file:///photo\"}"));
        assertEquals(17, parsed.id);
        assertEquals(1700000000, parsed.date);
        assertEquals("Synthetic caption", parsed.text);
        assertEquals("Synthetic author", parsed.ownerName);
        assertEquals("file:///avatar", parsed.ownerPhoto);
    }

    @Test public void adjacentLegacyAndEnrichedParcelsPreserveNextObjectBoundaries() {
        Parcel parcel = Parcel.obtain();
        try {
            parcel.writeString("file:///legacy"); parcel.writeLong(17); parcel.writeLong(-91); parcel.writeString("synthetic-key");
            parcel.writeInt(7); parcel.writeInt(8); parcel.writeInt(9);
            parcel.writeByte((byte) 1); parcel.writeByte((byte) 1); parcel.writeByte((byte) 0); parcel.writeByte((byte) 1);
            new ViewerPhoto("file:///new", 18, 42, "", 0, 0, 0, false, true, true, true,
                    "Synthetic author", "file:///avatar", 1700000000, "Synthetic caption").writeToParcel(parcel, 0);
            ViewerPhoto.fromUrl("file:///last").writeToParcel(parcel, 0);
            parcel.writeString("sentinel");
            parcel.setDataPosition(0);
            ViewerPhoto legacy = ViewerPhoto.CREATOR.createFromParcel(parcel);
            ViewerPhoto enriched = ViewerPhoto.CREATOR.createFromParcel(parcel);
            ViewerPhoto last = ViewerPhoto.CREATOR.createFromParcel(parcel);
            assertEquals("file:///legacy", legacy.url);
            assertEquals("", legacy.ownerName);
            assertEquals("file:///new", enriched.url);
            assertEquals("Synthetic author", enriched.ownerName);
            assertEquals("Synthetic caption", enriched.text);
            assertEquals("file:///last", last.url);
            assertFalse(last.hasIdentity());
            assertEquals("sentinel", parcel.readString());
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
