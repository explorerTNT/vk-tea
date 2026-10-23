package com.vktea.audio.catalog;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.robolectric.Shadows.shadowOf;

import android.os.Handler;
import android.os.Looper;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.LooperMode;

import java.io.IOException;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import okhttp3.HttpUrl;
import okhttp3.Interceptor;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Protocol;
import okhttp3.Response;
import okhttp3.ResponseBody;

/** Synthetic transport fixtures; the retry guard additionally uses an isolated loopback socket. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 28)
@LooperMode(LooperMode.Mode.PAUSED)
public class DefaultVkAudioRepositoryTest {
    private final AtomicInteger calls = new AtomicInteger();
    private final AtomicReference<okhttp3.Request> captured = new AtomicReference<>();
    private final AtomicReference<DefaultVkAudioRepository.Credentials> credentials = new AtomicReference<>();
    private DefaultVkAudioRepository repository;
    private Locale priorLocale;

    @Before
    public void syntheticSession() {
        priorLocale = Locale.getDefault();
        Locale.setDefault(Locale.ENGLISH);
        credentials.set(new DefaultVkAudioRepository.Credentials("synthetic-token", "synthetic-secret", "41"));
    }

    @After
    public void close() {
        if (repository != null) repository.close();
        Locale.setDefault(priorLocale);
        shadowOf(Looper.getMainLooper()).idle();
    }

    @Test
    public void signedGetUsesOwnLibraryAndRawConsumedCountWithoutDroppingUrlLessRows() throws Exception {
        create(chain -> response(chain, 200, "{\"response\":{\"count\":7,\"items\":["
                + "{\"id\":9,\"owner_id\":41,\"artist\":\"Synthetic artist\",\"title\":\"Synthetic title\",\"duration\":180,\"url\":\"\"},"
                + "{\"id\":0,\"owner_id\":41},"
                + "{\"id\":10,\"owner_id\":41,\"content_restricted\":24,\"url\":\"https://audio.invalid/restricted.mp3\"}]}}"));
        Result<VkAudioPage> result = new Result<>();
        repository.getMyAudio(3, 100, result);
        await(result);

        okhttp3.Request request = captured.get();
        assertEquals("GET", request.method());
        assertEquals("/method/audio.get", request.url().encodedPath());
        assertEquals("41", request.url().queryParameter("owner_id"));
        assertEquals("100", request.url().queryParameter("count"));
        assertEquals("3", request.url().queryParameter("offset"));
        assertEquals(md5("/method/audio.get?v=5.82&lang=en&https=1&access_token=synthetic-token"
                        + "&owner_id=41&count=100&offset=3synthetic-secret"), request.url().queryParameter("sig"));
        assertNotNull(request.header("User-Agent"));
        assertTrue(result.onMain);
        assertNull(result.error);
        assertEquals(7, result.value.totalCount);
        assertEquals(3, result.value.consumedCount);
        assertEquals(2, result.value.items.size());
        VkAudioTrack track = result.value.items.get(0);
        assertTrue(track.available);
        assertFalse(track.hasPlayableUrl());
        assertEquals("vk-audio:41_9", track.mediaId());
        assertNull(track.toMediaItem().localConfiguration);
        assertEquals(Boolean.TRUE, track.toMediaItem().mediaMetadata.isPlayable);
        assertFalse(result.value.items.get(1).available);
    }

    @Test
    public void getByIdParsesDirectArrayAndEncodesButSignsRawCommaJoinedIdentities() throws Exception {
        create(chain -> response(chain, 200, "{\"response\":[{\"id\":8,\"owner_id\":-42,"
                + "\"title\":\"Synthetic\",\"duration\":2,\"access_key\":\"synthetic-key\","
                + "\"url\":\"https://audio.invalid/track.m3u8?synthetic=1\"}]}"));
        Result<List<VkAudioTrack>> result = new Result<>();
        repository.getById(Arrays.asList("-42_8_synthetic-key", "41_9"), result);
        await(result);

        assertEquals("-42_8_synthetic-key,41_9", captured.get().url().queryParameter("audios"));
        assertEquals(md5("/method/audio.getById?v=5.82&lang=en&https=1&access_token=synthetic-token"
                + "&audios=-42_8_synthetic-key,41_9synthetic-secret"), captured.get().url().queryParameter("sig"));
        assertEquals(1, result.value.size());
        VkAudioTrack track = result.value.get(0);
        assertEquals("-42_8_synthetic-key", track.apiId());
        assertEquals("vk-audio:-42_8", track.toMediaItem().mediaId);
        assertNotNull(track.toMediaItem().localConfiguration);
        assertEquals(1, track.toMediaItem().mediaMetadata.extras.size());
    }

    @Test
    public void serverErrorsExposeOnlySafeKindAndNumericCodeWithoutRetry() throws Exception {
        create(chain -> response(chain, 200, "{\"error\":{\"error_code\":6,"
                + "\"error_msg\":\"SYNTHETIC_PRIVATE_RESPONSE\",\"request_params\":[{\"value\":\"SYNTHETIC_PRIVATE_TOKEN\"}]}}"));
        Result<VkAudioPage> result = new Result<>();
        repository.getMyAudio(0, 100, result);
        await(result);

        assertEquals(VkAudioError.Kind.RATE_LIMITED, result.error.kind);
        assertEquals(6, result.error.apiCode);
        assertNull(result.error.getCause());
        assertFalse(result.error.toString().contains("SYNTHETIC_PRIVATE"));
        assertEquals(1, calls.get());
    }

    @Test
    public void ioFailureIsSanitizedAndNotRetried() throws Exception {
        create(chain -> { throw new IOException("SYNTHETIC_PRIVATE_URL_AND_TOKEN"); });
        Result<VkAudioPage> result = new Result<>();
        repository.getMyAudio(0, 100, result);
        await(result);

        assertEquals(VkAudioError.Kind.NETWORK, result.error.kind);
        assertFalse(result.error.toString().contains("SYNTHETIC_PRIVATE"));
        assertNull(result.error.getCause());
        assertEquals(1, calls.get());
    }

    @Test
    public void redirectIsReportedWithoutFollowingAnotherDestination() throws Exception {
        create(chain -> response(chain, 302, "").newBuilder()
                .header("Location", "https://redirect.invalid/private").build());
        Result<VkAudioPage> result = new Result<>();
        repository.getMyAudio(0, 100, result);
        await(result);

        assertEquals(VkAudioError.Kind.HTTP, result.error.kind);
        assertEquals(302, result.error.httpStatus);
        assertEquals(1, calls.get());
    }

    @Test
    public void httpRateLimitIsTypedAndCarriesOnlyStatus() throws Exception {
        create(chain -> response(chain, 429, "SYNTHETIC_PRIVATE_RESPONSE"));
        Result<VkAudioPage> result = new Result<>();
        repository.getMyAudio(0, 100, result);
        await(result);
        assertEquals(VkAudioError.Kind.RATE_LIMITED, result.error.kind);
        assertEquals(429, result.error.httpStatus);
        assertFalse(result.error.toString().contains("SYNTHETIC_PRIVATE"));
        assertEquals(1, calls.get());
    }

    @Test
    public void realOkHttpFollowupDoesNotRetry503WithZeroRetryAfter() throws Exception {
        AtomicInteger received = new AtomicInteger();
        CountDownLatch finished = new CountDownLatch(1);
        try (ServerSocket server = new ServerSocket(0, 2, InetAddress.getByName("127.0.0.1"))) {
            server.setSoTimeout(3000);
            Thread listener = new Thread(() -> {
                try {
                    while (!server.isClosed()) {
                        try (Socket socket = server.accept()) {
                            socket.setSoTimeout(1000);
                            BufferedReader reader = new BufferedReader(new InputStreamReader(
                                    socket.getInputStream(), StandardCharsets.US_ASCII));
                            String line;
                            while ((line = reader.readLine()) != null && !line.isEmpty()) { /* discard */ }
                            received.incrementAndGet();
                            server.setSoTimeout(700);
                            socket.getOutputStream().write(("HTTP/1.1 503 Synthetic response\r\n"
                                    + "Retry-After: 0\r\nContent-Length: 0\r\nConnection: close\r\n\r\n")
                                    .getBytes(StandardCharsets.US_ASCII));
                            socket.getOutputStream().flush();
                        }
                    }
                } catch (SocketTimeoutException expected) {
                    // The bounded quiet interval establishes no hidden follow-up request.
                } catch (IOException ignored) {
                    // Closing the test's server also closes its listener.
                } finally {
                    finished.countDown();
                }
            }, "synthetic-audio-loopback");
            listener.start();
            repository = new DefaultVkAudioRepository(new OkHttpClient(),
                    HttpUrl.get("http://127.0.0.1:" + server.getLocalPort() + "/"),
                    credentials::get, new Handler(Looper.getMainLooper()));
            Result<VkAudioPage> result = new Result<>();
            repository.getMyAudio(0, 100, result);
            await(result);
            assertEquals(VkAudioError.Kind.HTTP, result.error.kind);
            assertEquals(503, result.error.httpStatus);
            assertTrue(finished.await(2, TimeUnit.SECONDS));
            assertEquals(1, received.get());
        }
    }

    @Test
    public void cancellationSuppressesEvenAnAlreadyQueuedMainThreadResult() throws Exception {
        CountDownLatch intercepted = new CountDownLatch(1);
        create(chain -> {
            intercepted.countDown();
            return response(chain, 200, "{\"response\":{\"count\":0,\"items\":[]}}");
        });
        Result<VkAudioPage> result = new Result<>();
        VkAudioRepository.Request request = repository.getMyAudio(0, 100, result);
        assertTrue(intercepted.await(3, TimeUnit.SECONDS));
        request.cancel();
        assertTrue(request.isCanceled());
        for (int pass = 0; pass < 20; pass++) {
            shadowOf(Looper.getMainLooper()).idle();
            Thread.sleep(2);
        }
        assertEquals(0, result.callbacks);
    }

    @Test
    public void changedSessionCannotReceiveOldLibraryResponse() throws Exception {
        CountDownLatch requested = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        create(chain -> {
            requested.countDown();
            try {
                if (!release.await(3, TimeUnit.SECONDS)) throw new IOException("Synthetic timeout");
            } catch (InterruptedException ignored) {
                throw new IOException("Synthetic interruption");
            }
            return response(chain, 200, "{\"response\":{\"count\":0,\"items\":[]}}");
        });
        Result<VkAudioPage> result = new Result<>();
        repository.getMyAudio(0, 100, result);
        assertTrue(requested.await(3, TimeUnit.SECONDS));
        credentials.set(new DefaultVkAudioRepository.Credentials("different-synthetic-token", "", "43"));
        release.countDown();
        await(result);

        assertNull(result.value);
        assertEquals(VkAudioError.Kind.AUTH_REQUIRED, result.error.kind);
    }

    @Test
    public void missingSessionAndArbitraryGetByIdInputNeverReachTransport() throws Exception {
        create(chain -> response(chain, 200, "{}"));
        Result<List<VkAudioTrack>> invalid = new Result<>();
        repository.getById(Collections.singletonList("https://untrusted.invalid/track"), invalid);
        await(invalid);
        assertEquals(VkAudioError.Kind.INVALID_REQUEST, invalid.error.kind);

        credentials.set(new DefaultVkAudioRepository.Credentials("", "", ""));
        Result<VkAudioPage> unauthorized = new Result<>();
        repository.getMyAudio(0, 100, unauthorized);
        await(unauthorized);
        assertEquals(VkAudioError.Kind.AUTH_REQUIRED, unauthorized.error.kind);
        assertEquals(0, calls.get());
    }

    private void create(Interceptor transport) {
        OkHttpClient client = new OkHttpClient.Builder().addInterceptor(chain -> {
            calls.incrementAndGet();
            captured.set(chain.request());
            return transport.intercept(chain);
        }).build();
        repository = new DefaultVkAudioRepository(client, HttpUrl.get("https://synthetic-api.invalid/"),
                credentials::get, new Handler(Looper.getMainLooper()));
    }

    private static Response response(Interceptor.Chain chain, int code, String body) {
        return new Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1)
                .code(code).message("Synthetic response")
                .body(ResponseBody.create(body, MediaType.get("application/json"))).build();
    }

    private static String md5(String value) throws Exception {
        StringBuilder result = new StringBuilder();
        for (byte digit : MessageDigest.getInstance("MD5").digest(value.getBytes(StandardCharsets.UTF_8))) {
            result.append(String.format(Locale.ROOT, "%02x", digit & 255));
        }
        return result.toString();
    }

    private static void await(Result<?> result) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(4);
        while (result.callbacks == 0 && System.nanoTime() < deadline) {
            shadowOf(Looper.getMainLooper()).idle();
            Thread.sleep(1);
        }
        assertEquals("Expected exactly one offline callback", 1, result.callbacks);
    }

    private static final class Result<T> implements VkAudioRepository.Callback<T> {
        T value;
        VkAudioError error;
        int callbacks;
        boolean onMain;

        @Override
        public void onSuccess(T result) {
            value = result;
            completed();
        }

        @Override
        public void onError(VkAudioError error) {
            this.error = error;
            completed();
        }

        private void completed() {
            callbacks++;
            onMain = Looper.myLooper() == Looper.getMainLooper();
        }
    }
}
