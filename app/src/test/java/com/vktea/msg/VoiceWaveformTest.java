package com.vktea.msg;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.io.File;
import java.io.FileOutputStream;

import okhttp3.MultipartBody;
import okio.Buffer;

public class VoiceWaveformTest {
    @Test public void measuredPeaksAreNormalizedWithoutInventingSamples() {
        VoiceWaveform peaks = new VoiceWaveform();
        assertNull(peaks.toJson());
        peaks.add(-1);
        assertNull(peaks.toJson());
        peaks.add(0);
        peaks.add(500);
        peaks.add(1000);
        assertEquals("[0,16,31]", peaks.toJson());
        peaks.clear();
        assertNull(peaks.toJson());
        peaks.add(0);
        peaks.add(0);
        assertEquals("[0,0]", peaks.toJson());
    }

    @Test public void lengthIsBoundedAndSampleRangeIsClamped() {
        VoiceWaveform peaks = new VoiceWaveform();
        for (int i = 0; i < 1000; i++) peaks.add(50000);
        assertTrue(peaks.isFull());
        String output = peaks.toJson();
        assertEquals(480, output.substring(1, output.length() - 1).split(",").length);
        assertFalse(output.contains("32"));
        peaks.clear();
        assertFalse(peaks.isFull());
    }

    @Test public void multipartIncludesActualWaveformOnlyWhenAvailable() throws Exception {
        File file = File.createTempFile("synthetic-voice", ".ogg");
        try {
            try (FileOutputStream out = new FileOutputStream(file)) { out.write(new byte[]{1, 2, 3}); }
            MultipartBody withWaveform = (MultipartBody) VoiceMessageRecorder.uploadBody(file, "[0,16,31]");
            assertEquals(2, withWaveform.size());
            assertTrue(withWaveform.part(0).headers().get("Content-Disposition").contains("name=\"waveform\""));
            Buffer values = new Buffer();
            withWaveform.part(0).body().writeTo(values);
            assertEquals("[0,16,31]", values.readUtf8());
            String fileDisposition = withWaveform.part(1).headers().get("Content-Disposition");
            assertTrue(fileDisposition.contains("name=\"file\""));
            assertTrue(fileDisposition.contains("filename=\"Audio Message\""));
            assertEquals("audio/ogg", withWaveform.part(1).body().contentType().toString());
            MultipartBody withoutWaveform = (MultipartBody) VoiceMessageRecorder.uploadBody(file, null);
            assertEquals(1, withoutWaveform.size());
        } finally {
            file.delete();
        }
    }
}
