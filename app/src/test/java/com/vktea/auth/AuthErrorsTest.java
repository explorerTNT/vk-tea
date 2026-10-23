package com.vktea.auth;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.json.JSONObject;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 28)
public class AuthErrorsTest {
    @Test
    public void http429RemainsRateLimitedWithoutAJsonBody() {
        assertTrue(AuthErrors.isRateLimited(AuthResponse.password(429, null)));
        assertTrue(AuthErrors.isRateLimited(AuthResponse.token(429, null, null, null)));
        assertTrue(AuthErrors.isRateLimited(AuthResponse.vkId(429, null)));
    }

    @Test
    public void recognizesNamedOauthFloodErrors() throws Exception {
        for (String error : new String[]{"flood_control", "too_many_requests",
                "too_many_auth_attempts", "FLOOD_CONTROL"}) {
            assertTrue(error, AuthErrors.isRateLimited(AuthResponse.password(400,
                    new JSONObject().put("error", error))));
        }
    }

    @Test
    public void recognizesNestedAndTopLevelApiFloodCodes() throws Exception {
        for (int code : new int[]{6, 9, 1105}) {
            JSONObject nested = new JSONObject().put("error",
                    new JSONObject().put("error_code", code));
            assertTrue("Nested code " + code, AuthErrors.isRateLimited(
                    AuthResponse.token(200, nested, null, null)));
            assertTrue("Top-level code " + code, AuthErrors.isRateLimited(
                    AuthResponse.password(400, new JSONObject().put("error_code", code))));
        }
    }

    @Test
    public void recognizesEnglishAndRussianDescriptionsWithoutDisplayingThem() throws Exception {
        for (String description : new String[]{
                "СЛИШКОМ МНОГО ПОПЫТОК ВХОДА",
                "Too many auth attempts",
                "Too many login attempts",
                "Too many requests",
                "Flood control: synthetic detail"}) {
            assertTrue(description, AuthErrors.isRateLimited(AuthResponse.password(400,
                    new JSONObject().put("error", "invalid_request")
                            .put("error_description", description))));
        }
    }

    @Test
    public void wrongCredentialsAndUnrelatedApiErrorsAreNotFloodControl() throws Exception {
        assertFalse(AuthErrors.isRateLimited(AuthResponse.password(400,
                new JSONObject().put("error", "invalid_grant")
                        .put("error_description", "Username or password is incorrect"))));
        assertFalse(AuthErrors.isRateLimited(AuthResponse.token(200,
                new JSONObject().put("error", new JSONObject().put("error_code", 5)
                        .put("error_msg", "User authorization failed")), null, null)));
        assertFalse(AuthErrors.isRateLimited(AuthResponse.password(200, new JSONObject())));
    }

    @Test
    public void missingBodiesAndNetworkFailuresAreNotServerFloodResponses() {
        assertFalse(AuthErrors.isRateLimited(AuthResponse.password(400, null)));
        for (AuthResponse.Method method : AuthResponse.Method.values()) {
            assertFalse(method.name(), AuthErrors.isRateLimited(AuthResponse.failure(method)));
        }
    }
}
