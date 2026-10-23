package com.vktea.auth;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertThrows;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import okhttp3.HttpUrl;

/** Real HTTP requests stay on loopback and contain only synthetic query values. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 28)
public class AuthHttpClientTest {
    @Test(timeout = 8000)
    public void passwordRequestUsesUpstreamCoffeeUserAgentInOneLoopbackRequest() throws Exception {
        AuthHttpClient client = new AuthHttpClient();
        try (LoopbackServer server = new LoopbackServer(400, "",
                "{\"error\":\"synthetic_invalid_grant\"}")) {
            AuthResponse response = client.password(server.url("user-agent"));

            assertEquals(400, response.httpStatus);
            server.assertRequestCount(1);
            assertEquals("VKAndroidApp/8.09-1113 (Android 13; SDK 33; arm64-v8a; samsung SM-S908N; ru)",
                    server.firstUserAgent.get());
        }
    }

    @Test(timeout = 8000)
    public void floodResponseRetains429AndJsonWithoutRepeatingRequest() throws Exception {
        AuthHttpClient client = new AuthHttpClient();
        try (LoopbackServer server = new LoopbackServer(429, "",
                "{\"error\":\"flood_control\",\"error_code\":9}")) {
            AuthResponse response = client.password(server.url("flood"));

            assertEquals(429, response.httpStatus);
            assertEquals(AuthResponse.Method.PASSWORD, response.method);
            assertFalse(response.networkFailure);
            assertNotNull(response.json);
            assertEquals("flood_control", response.json.getString("error"));
            assertEquals(9, response.json.getInt("error_code"));
            server.assertRequestCount(1);
        }
    }

    @Test(timeout = 8000)
    public void zeroRetryAfter503DoesNotUseAvailableSecondResponse() throws Exception {
        AuthHttpClient client = new AuthHttpClient();
        try (LoopbackServer server = new LoopbackServer(503, "Retry-After: 0\r\n",
                "{\"error\":\"synthetic_unavailable\"}")) {
            AuthResponse response = client.password(server.url("unavailable"));

            assertEquals(503, response.httpStatus);
            assertNotNull(response.json);
            assertEquals("synthetic_unavailable", response.json.getString("error"));
            server.assertRequestCount(1);
        }
    }

    @Test(timeout = 8000)
    public void redirectDoesNotSendRequestToAnotherLoopbackDestination() throws Exception {
        AuthHttpClient client = new AuthHttpClient();
        try (LoopbackServer destination = new LoopbackServer(200, "", "{}")) {
            String location = "Location: " + destination.url("redirect-target") + "\r\n";
            try (LoopbackServer origin = new LoopbackServer(302, location, "{}")) {
                AuthResponse response = client.password(origin.url("redirect-origin"));

                assertEquals(302, response.httpStatus);
                origin.assertRequestCount(1);
                destination.assertRequestCount(0);
            }
        }
    }

    @Test(timeout = 8000)
    public void droppedConnectionAfterRequestDoesNotResendIt() throws Exception {
        AuthHttpClient client = new AuthHttpClient();
        // Status zero makes the first connection close after reading the full request.
        try (LoopbackServer server = new LoopbackServer(0, "", "")) {
            assertThrows(IOException.class, () -> client.password(server.url("dropped")));

            server.assertRequestCount(1);
        }
    }

    /**
     * Sends the configured first response, then a successful fallback if the client
     * repeats its request. Connection: close makes new attempts visible as accepts.
     */
    private static final class LoopbackServer implements AutoCloseable {
        private static final int SOCKET_TIMEOUT_MS = 600;
        private final ServerSocket listener;
        private final Thread worker;
        private final AtomicInteger requests = new AtomicInteger();
        private final AtomicReference<Throwable> failure = new AtomicReference<>();
        private final AtomicReference<String> firstUserAgent = new AtomicReference<>();
        private final int firstStatus;
        private final String firstHeaders;
        private final String firstBody;
        private volatile Socket activeSocket;
        private volatile boolean finishAfterQuietPeriod;
        private volatile boolean closed;

        LoopbackServer(int firstStatus, String firstHeaders, String firstBody) throws IOException {
            this.firstStatus = firstStatus;
            this.firstHeaders = firstHeaders;
            this.firstBody = firstBody;
            listener = new ServerSocket(0, 4, InetAddress.getByName("127.0.0.1"));
            listener.setSoTimeout(SOCKET_TIMEOUT_MS);
            worker = new Thread(this::serve, "synthetic-auth-loopback");
            worker.setDaemon(true);
            worker.start();
        }

        HttpUrl url(String testCase) {
            return new HttpUrl.Builder().scheme("http").host("127.0.0.1")
                    .port(listener.getLocalPort()).addPathSegment("synthetic-auth")
                    .addQueryParameter("case", testCase).build();
        }

        void assertRequestCount(int expected) throws InterruptedException {
            finishAfterQuietPeriod = true;
            worker.join(2000);
            assertFalse("Loopback server must finish after a bounded quiet period", worker.isAlive());
            Throwable problem = failure.get();
            if (problem != null) {
                throw new AssertionError("Synthetic loopback server failed", problem);
            }
            assertEquals("Authentication transport must not resend requests", expected, requests.get());
        }

        private void serve() {
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
            try {
                while (!closed && System.nanoTime() < deadline) {
                    Socket connection;
                    try {
                        connection = listener.accept();
                    } catch (SocketTimeoutException quiet) {
                        if (finishAfterQuietPeriod) {
                            return;
                        }
                        continue;
                    }
                    activeSocket = connection;
                    try (Socket ignored = connection) {
                        connection.setSoTimeout(SOCKET_TIMEOUT_MS);
                        readRequest(connection);
                        int requestNumber = requests.incrementAndGet();
                        if (requestNumber == 1 && firstStatus == 0) {
                            continue;
                        }
                        writeResponse(connection, requestNumber == 1 ? firstStatus : 200,
                                requestNumber == 1 ? firstHeaders : "",
                                requestNumber == 1 ? firstBody : "{\"synthetic_retry\":true}");
                    } finally {
                        activeSocket = null;
                    }
                }
            } catch (Throwable problem) {
                if (!closed) {
                    failure.set(problem);
                }
            } finally {
                try {
                    listener.close();
                } catch (IOException ignored) {
                    // The owner also closes this listener in close().
                }
            }
        }

        private void readRequest(Socket connection) throws IOException {
            BufferedReader reader = new BufferedReader(new InputStreamReader(
                    connection.getInputStream(), StandardCharsets.US_ASCII));
            String line = reader.readLine();
            if (line == null || !line.startsWith("GET /synthetic-auth?case=")) {
                throw new IOException("Expected a synthetic loopback GET request");
            }
            while ((line = reader.readLine()) != null && !line.isEmpty()) {
                if (requests.get() == 0 && line.regionMatches(true, 0, "User-Agent:", 0, 11)) {
                    firstUserAgent.set(line.substring(11).trim());
                }
                // Consume headers so connection-drop tests cover a fully sent request.
            }
            if (line == null) {
                throw new IOException("Synthetic HTTP request headers were incomplete");
            }
        }

        private static void writeResponse(Socket connection, int status, String headers, String body)
                throws IOException {
            byte[] payload = body.getBytes(StandardCharsets.UTF_8);
            String head = "HTTP/1.1 " + status + " Synthetic\r\n"
                    + "Content-Type: application/json; charset=utf-8\r\n"
                    + "Content-Length: " + payload.length + "\r\n"
                    + "Connection: close\r\n" + headers + "\r\n";
            OutputStream output = connection.getOutputStream();
            output.write(head.getBytes(StandardCharsets.US_ASCII));
            output.write(payload);
            output.flush();
        }

        @Override
        public void close() throws Exception {
            closed = true;
            listener.close();
            Socket connection = activeSocket;
            if (connection != null) {
                connection.close();
            }
            worker.join(2000);
            assertFalse("Loopback server worker must be closed", worker.isAlive());
        }
    }
}
