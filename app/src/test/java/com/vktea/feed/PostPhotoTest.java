package com.vktea.feed;

import static org.junit.Assert.assertEquals;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

/** Synthetic source metadata only; no image downloads or live feed data. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 28)
public class PostPhotoTest {
    @Test
    public void unorderedSizesUseActualAreaRatherThanLastEntryOrReversedTypeRank() throws Exception {
        JSONObject bigger = size("x", "https://images.invalid/bigger", 1400, 1000);
        JSONObject misleadingType = size("w", "https://images.invalid/smaller", 1200, 800);
        JSONObject tiny = size("s", "https://images.invalid/tiny", 75, 50);
        assertEquals("https://images.invalid/bigger", PostPhoto.bestUrl(photo(bigger, misleadingType, tiny)));
        assertEquals("https://images.invalid/bigger", PostPhoto.bestUrl(photo(tiny, bigger, misleadingType)));
    }

    @Test
    public void uncroppedPortraitWinsOverLargerCroppedImageAndUrlIsUntouched() throws Exception {
        String exact = "https://images.invalid/portrait?synthetic=1&cs=1200x1600&sig=synthetic-signature";
        JSONObject photo = photo(size("q", "https://images.invalid/cropped", 2500, 2500),
                size("x", exact, 1200, 1600), size("p", "https://images.invalid/cropped-small", 800, 800));
        assertEquals(exact, PostPhoto.bestUrl(photo));
    }

    @Test
    public void missingDimensionsUseCanonicalTypeOrderAndTiesAreDeterministic() throws Exception {
        JSONObject w = size("w", "https://images.invalid/w", 0, 0);
        JSONObject s = size("s", "https://images.invalid/s", 0, 0);
        JSONObject z = size("z", "https://images.invalid/z", 0, 0);
        assertEquals("https://images.invalid/w", PostPhoto.bestUrl(photo(w, z, s)));
        assertEquals("https://images.invalid/w", PostPhoto.bestUrl(photo(s, z, w)));
        JSONObject first = size("x", "https://images.invalid/a", 800, 600);
        JSONObject second = size("x", "https://images.invalid/b", 800, 600);
        assertEquals(PostPhoto.bestUrl(photo(first, second)), PostPhoto.bestUrl(photo(second, first)));
        assertEquals("https://images.invalid/a", PostPhoto.bestUrl(photo(w, first, second)));
        assertEquals("https://images.invalid/a", PostPhoto.bestUrl(photo(second, w, first)));
    }

    @Test
    public void acceptsSrcAndSkipsNullMalformedOrNonStringUrls() throws Exception {
        JSONObject good = new JSONObject().put("type", "y").put("src", "https://images.invalid/src?cs=800x600")
                .put("width", 800).put("height", 600);
        JSONObject malformed = size("w", "", 4000, 3000).put("url", new JSONObject().put("synthetic", true));
        assertEquals("https://images.invalid/src?cs=800x600", PostPhoto.bestUrl(photo(
                JSONObject.NULL, "Synthetic malformed size", malformed, good)));
    }

    @Test
    public void legacyHighestQualityIsFallbackAndPrecedesCropOnlyVariants() throws Exception {
        JSONObject legacy = new JSONObject().put("photo_130", "https://images.invalid/130")
                .put("photo_2560", "https://images.invalid/2560?cs=2560x1920&sig=synthetic");
        assertEquals("https://images.invalid/2560?cs=2560x1920&sig=synthetic", PostPhoto.bestUrl(legacy));
        legacy.put("sizes", new JSONArray().put(size("r", "https://images.invalid/crop", 3000, 3000)));
        assertEquals("https://images.invalid/2560?cs=2560x1920&sig=synthetic", PostPhoto.bestUrl(legacy));
        assertEquals("https://images.invalid/src-big", PostPhoto.bestUrl(new JSONObject()
                .put("src", "https://images.invalid/src-small").put("src_big", "https://images.invalid/src-big")));
    }

    @Test
    public void croppedOnlyFallbackUsesAreaAndInvalidMetadataReturnsEmpty() throws Exception {
        assertEquals("https://images.invalid/crop-big", PostPhoto.bestUrl(photo(
                size("r", "https://images.invalid/crop-small", 300, 200),
                size("p", "https://images.invalid/crop-big", 800, 600))));
        assertEquals("", PostPhoto.bestUrl(null));
        assertEquals("", PostPhoto.bestUrl(new JSONObject()));
        assertEquals("", PostPhoto.bestUrl(photo(JSONObject.NULL, "Synthetic invalid item", new JSONObject())));
    }

    @Test
    public void largeDimensionMultiplicationCannotOverflowIntoLowQualityChoice() throws Exception {
        JSONObject large = size("x", "https://images.invalid/large", Integer.MAX_VALUE, Integer.MAX_VALUE);
        JSONObject smaller = size("w", "https://images.invalid/smaller", 1280, 720);
        assertEquals("https://images.invalid/large", PostPhoto.bestUrl(photo(large, smaller)));
    }

    private static JSONObject size(String type, String url, int width, int height) throws Exception {
        return new JSONObject().put("type", type).put("url", url).put("width", width).put("height", height);
    }

    private static JSONObject photo(Object... sizes) throws Exception {
        JSONArray array = new JSONArray();
        for (Object size : sizes) array.put(size);
        return new JSONObject().put("sizes", array);
    }
}
