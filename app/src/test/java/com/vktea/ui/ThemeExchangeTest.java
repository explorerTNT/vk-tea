package com.vktea.ui;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertThrows;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Map;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 28)
public class ThemeExchangeTest {
    @Test
    public void everySupportedPaletteRoundTrips() throws IOException {
        for (String palette : new String[]{"system", "light", "dark", "amoled", "dark_blue"}) {
            byte[] exported = ThemeExchange.encode("Тема Coffee 2", palette);
            ThemeExchange.Document result = ThemeExchange.decode(new ByteArrayInputStream(exported));
            assertEquals("Тема Coffee 2", result.getName());
            assertEquals(palette, result.getPalette());
        }
    }

    @Test
    public void malformedDocumentsAndUnknownFieldsAreRejected() {
        String[] invalid = {
                "{}", "[]", "{\"version\":2,\"name\":\"A\",\"palette\":\"dark\"}",
                "{\"version\":1.0,\"name\":\"A\",\"palette\":\"dark\"}",
                "{\"version\":\"1\",\"name\":\"A\",\"palette\":\"dark\"}",
                "{\"version\":1,\"name\":\"A\",\"palette\":\"blue\"}",
                "{\"version\":1,\"name\":null,\"palette\":\"dark\"}",
                "{\"version\":1,\"name\":\"A\",\"palette\":\"dark\",\"url\":\"https://x\"}",
                "{\"version\":1,\"name\":\"A\",\"name\":\"B\",\"palette\":\"dark\"}",
                "{\"version\":1,\"name\":\"A\",\"palette\":\"dark\"} {}",
                "{\"version\":1,\"name\":\"A\",\"palette\":\"dark\",}"
        };
        for (String json : invalid) {
            assertThrows(json, IllegalArgumentException.class,
                    () -> ThemeExchange.decode(json.getBytes(StandardCharsets.UTF_8)));
        }
    }

    @Test
    public void namesCannotContainMarkupPathsLinksOrControls() {
        for (String name : new String[]{"", "  name", "name ", "../theme", "a/b", "https://a",
                "<script>", "a\nB", "\u202Eabc", "\uD800", "a".repeat(65)}) {
            assertThrows(name, IllegalArgumentException.class, () -> ThemeExchange.encode(name, "dark"));
        }
    }

    @Test
    public void rejectsInvalidUtf8AndOversizedStreams() {
        assertThrows(IllegalArgumentException.class, () -> ThemeExchange.decode(new byte[]{(byte) 0xc3, 0x28}));
        byte[] tooLarge = new byte[ThemeExchange.MAX_BYTES + 1];
        Arrays.fill(tooLarge, (byte) ' ');
        assertThrows(IllegalArgumentException.class,
                () -> ThemeExchange.decode(new ByteArrayInputStream(tooLarge)));
    }

    @Test
    public void propagatesReadErrorsWithoutTouchingSettings() {
        InputStream badStream = new InputStream() {
            @Override public int read() throws IOException { throw new IOException("failure"); }
        };
        assertThrows(IOException.class, () -> ThemeExchange.decode(badStream));
    }

    private static Map<String, Integer> sampleColors() {
        Map<String, Integer> colors = new LinkedHashMap<>();
        int value = 0xff102030;
        for (String key : CoffeePalette.KEYS) colors.put(key, value++);
        return colors;
    }

    @Test
    public void customPaletteRoundTripsAndIsImmutable() {
        Map<String, Integer> colors = sampleColors();
        byte[] encoded = ThemeExchange.encode("Моя тема", "custom", colors);
        ThemeExchange.Document decoded = ThemeExchange.decode(encoded);
        assertEquals("custom", decoded.getPalette());
        assertEquals(colors, decoded.getColors());
        assertThrows(UnsupportedOperationException.class, () -> decoded.getColors().clear());
        colors.clear();
        assertEquals(CoffeePalette.KEYS.size(), decoded.getColors().size());
    }

    @Test
    public void customPaletteAcceptsOnlyCompleteOpaqueColors() {
        Map<String, Integer> colors = sampleColors();
        String valid = new String(ThemeExchange.encode("Тема", "custom", colors), StandardCharsets.UTF_8);
        String[] invalid = {
                valid.replace("#102030", "#00102030"),
                valid.replace("#102030", "#12345"),
                valid.replace("#102030", "https://example.com"),
                valid.replace("\"brand\":\"#102030\",", ""),
                valid.replace("\"brand\":\"#102030\"", "\"brand\":\"#102030\",\"brand\":\"#102030\""),
                valid.replace("\"brand\"", "\"script\""),
                valid.replace("\"palette\":\"custom\"", "\"palette\":\"dark\""),
                "{\"version\":1,\"name\":\"A\",\"palette\":\"custom\"}"
        };
        for (String json : invalid) {
            assertThrows(json, IllegalArgumentException.class,
                    () -> ThemeExchange.decode(json.getBytes(StandardCharsets.UTF_8)));
        }
        String argb = valid.replace("#102030", "#FF102030");
        assertEquals(colors, ThemeExchange.decode(argb.getBytes(StandardCharsets.UTF_8)).getColors());
        colors.remove("brand");
        assertThrows(IllegalArgumentException.class, () -> ThemeExchange.encode("A", "custom", colors));
        colors.put("brand", 0x00102030);
        assertThrows(IllegalArgumentException.class, () -> ThemeExchange.encode("A", "custom", colors));
        assertThrows(IllegalArgumentException.class, () -> ThemeExchange.encode("A", "dark", sampleColors()));
    }
}
