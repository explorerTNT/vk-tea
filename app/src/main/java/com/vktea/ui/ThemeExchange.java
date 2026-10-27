package com.vktea.ui;

import android.util.JsonReader;
import android.util.JsonToken;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.StringReader;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/** A bounded, data-only VK Tea theme exchange format, independent of Coffee's formats. */
public final class ThemeExchange {
    public static final int MAX_BYTES = 4096;
    public static final int MAX_NAME_CODE_POINTS = 64;
    private static final Set<String> PALETTES = new HashSet<>(Arrays.asList(
            "system", "light", "dark", "amoled", "dark_blue", "custom"));

    private ThemeExchange() { }

    public static final class Document {
        private final String name;
        private final String palette;
        private final Map<String, Integer> colors;

        private Document(String name, String palette, Map<String, Integer> colors) {
            this.name = name;
            this.palette = palette;
            this.colors = Collections.unmodifiableMap(new LinkedHashMap<>(colors));
        }

        public String getName() { return name; }
        public String getPalette() { return palette; }
        /** Opaque ARGB values; empty for built-in palettes. */
        public Map<String, Integer> getColors() { return colors; }
    }

    /** Does not close the caller's stream and does not modify application settings. */
    public static Document decode(InputStream input) throws IOException {
        if (input == null) throw new IllegalArgumentException("Missing document");
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        byte[] buffer = new byte[1024];
        int count;
        while ((count = input.read(buffer, 0, Math.min(buffer.length, MAX_BYTES + 1 - bytes.size()))) != -1) {
            if (count == 0) {
                int next = input.read();
                if (next == -1) break;
                bytes.write(next);
            } else {
                bytes.write(buffer, 0, count);
            }
            if (bytes.size() > MAX_BYTES) throw new IllegalArgumentException("Document is too large");
        }
        return decode(bytes.toByteArray());
    }

    public static Document decode(byte[] bytes) {
        if (bytes == null || bytes.length > MAX_BYTES) {
            throw new IllegalArgumentException("Missing or oversized document");
        }
        final String json;
        try {
            json = StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(bytes)).toString();
        } catch (CharacterCodingException e) {
            throw new IllegalArgumentException("Invalid UTF-8", e);
        }
        String name = null;
        String palette = null;
        Map<String, Integer> colors = null;
        boolean versionSeen = false;
        try (JsonReader reader = new JsonReader(new StringReader(json))) {
            reader.setLenient(false);
            reader.beginObject();
            while (reader.hasNext()) {
                String key = reader.nextName();
                switch (key) {
                    case "version":
                        if (versionSeen || reader.peek() != JsonToken.NUMBER || !"1".equals(reader.nextString())) {
                            throw new IllegalArgumentException("Unsupported version");
                        }
                        versionSeen = true;
                        break;
                    case "name":
                        if (name != null || reader.peek() != JsonToken.STRING) {
                            throw new IllegalArgumentException("Invalid name");
                        }
                        name = reader.nextString();
                        break;
                    case "palette":
                        if (palette != null || reader.peek() != JsonToken.STRING) {
                            throw new IllegalArgumentException("Invalid palette");
                        }
                        palette = reader.nextString();
                        break;
                    case "colors":
                        if (colors != null || reader.peek() != JsonToken.BEGIN_OBJECT) {
                            throw new IllegalArgumentException("Invalid colors");
                        }
                        colors = readColors(reader);
                        break;
                    default:
                        throw new IllegalArgumentException("Unknown field");
                }
            }
            reader.endObject();
            if (reader.peek() != JsonToken.END_DOCUMENT || !versionSeen) {
                throw new IllegalArgumentException("Invalid document");
            }
        } catch (IOException | IllegalStateException e) {
            throw new IllegalArgumentException("Invalid document", e);
        }
        validate(name, palette, colors);
        return new Document(name, palette, colors == null ? Collections.emptyMap() : colors);
    }

    private static Map<String, Integer> readColors(JsonReader reader) throws IOException {
        Map<String, Integer> colors = new LinkedHashMap<>();
        reader.beginObject();
        while (reader.hasNext()) {
            String key = reader.nextName();
            if (!CoffeePalette.KEYS.contains(key) || colors.containsKey(key) || reader.peek() != JsonToken.STRING) {
                throw new IllegalArgumentException("Unknown, duplicate or invalid color");
            }
            String value = reader.nextString();
            if (!value.matches("#[0-9a-fA-F]{6}([0-9a-fA-F]{2})?")) {
                throw new IllegalArgumentException("Invalid color");
            }
            long number = Long.parseLong(value.substring(1), 16);
            if (value.length() == 7) number |= 0xff000000L;
            if ((number >>> 24) != 0xff) throw new IllegalArgumentException("Color must be opaque");
            colors.put(key, (int) number);
        }
        reader.endObject();
        return colors;
    }

    public static byte[] encode(String name, String palette) {
        return encode(name, palette, null);
    }

    /** Colors are required only for palette custom; all values are opaque ARGB. */
    public static byte[] encode(String name, String palette, Map<String, Integer> colors) {
        validate(name, palette, colors);
        // Allowed name characters need no JSON escaping.
        StringBuilder json = new StringBuilder("{\"version\":1,\"name\":\"")
                .append(name).append("\",\"palette\":\"").append(palette).append('"');
        if (colors != null) {
            json.append(",\"colors\":{");
            boolean first = true;
            for (String key : CoffeePalette.KEYS) {
                if (!first) json.append(',');
                first = false;
                json.append('"').append(key).append("\":\"")
                        .append(String.format(Locale.ROOT, "#%06X", colors.get(key) & 0xffffff))
                        .append('"');
            }
            json.append('}');
        }
        return json.append('}').toString().getBytes(StandardCharsets.UTF_8);
    }

    private static void validate(String name, String palette, Map<String, Integer> colors) {
        if (name == null || name.isEmpty() || !name.equals(name.trim())
                || name.codePointCount(0, name.length()) > MAX_NAME_CODE_POINTS) {
            throw new IllegalArgumentException("Invalid name");
        }
        for (int i = 0; i < name.length();) {
            int c = name.codePointAt(i);
            int type = Character.getType(c);
            if (!Character.isLetterOrDigit(c) && type != Character.NON_SPACING_MARK
                    && type != Character.COMBINING_SPACING_MARK && c != ' ' && c != '-' && c != '_') {
                throw new IllegalArgumentException("Invalid name");
            }
            i += Character.charCount(c);
        }
        if (palette == null || !PALETTES.contains(palette)) {
            throw new IllegalArgumentException("Unsupported palette");
        }
        if ("custom".equals(palette)) {
            if (colors == null || colors.size() != CoffeePalette.KEYS.size()) {
                throw new IllegalArgumentException("Incomplete colors");
            }
            for (String key : CoffeePalette.KEYS) {
                Integer color = colors.get(key);
                if (color == null || (color >>> 24) != 0xff) {
                    throw new IllegalArgumentException("Missing or non-opaque color");
                }
            }
        } else if (colors != null) {
            throw new IllegalArgumentException("Colors are only supported for custom palettes");
        }
    }
}
