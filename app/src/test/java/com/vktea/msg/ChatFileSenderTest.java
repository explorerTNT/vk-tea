package com.vktea.msg;

import static org.junit.Assert.*;

import org.junit.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;

public class ChatFileSenderTest {
    @Test public void fileNamesCannotContainPathsOrControlCharacters() {
        assertEquals("a_b_c_.jpg", ChatFileSender.safeName("a/b\\c\n.jpg"));
        assertEquals("file", ChatFileSender.safeName(".."));
        assertEquals(120, ChatFileSender.safeName(new String(new char[130]).replace('\0', 'a')).length());
    }

    @Test public void streamedContentMustFitTheLimitAndCancellationStopsCopy() throws Exception {
        byte[] data = new byte[]{1, 2, 3, 4};
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        assertEquals(4, ChatFileSender.copyLimited(new ByteArrayInputStream(data), output, 4, () -> true));
        assertArrayEquals(data, output.toByteArray());
        try {
            ChatFileSender.copyLimited(new ByteArrayInputStream(data), new ByteArrayOutputStream(), 3, () -> true);
            fail("Oversized files must not be copied");
        } catch (IllegalStateException expected) { }
        try {
            ChatFileSender.copyLimited(new ByteArrayInputStream(data), new ByteArrayOutputStream(), 4, () -> false);
            fail("Canceled files must not be copied");
        } catch (IllegalStateException expected) { }
    }
}
