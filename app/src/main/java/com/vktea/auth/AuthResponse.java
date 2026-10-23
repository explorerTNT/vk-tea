package com.vktea.auth;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import org.json.JSONObject;

/** An ephemeral result passed from the authentication worker to its current UI owner. */
public final class AuthResponse {
    public enum Method { PASSWORD, TOKEN, VK_ID }

    @NonNull public final Method method;
    public final int httpStatus;
    @Nullable public final JSONObject json;
    @Nullable public final String suppliedToken;
    @Nullable public final String suppliedSecret;
    public final boolean networkFailure;

    private AuthResponse(Method method, int httpStatus, @Nullable JSONObject json,
                         @Nullable String suppliedToken, @Nullable String suppliedSecret,
                         boolean networkFailure) {
        this.method = method;
        this.httpStatus = httpStatus;
        this.json = json;
        this.suppliedToken = suppliedToken;
        this.suppliedSecret = suppliedSecret;
        this.networkFailure = networkFailure;
    }

    public static AuthResponse password(int httpStatus, @Nullable JSONObject json) {
        return new AuthResponse(Method.PASSWORD, httpStatus, json, null, null, false);
    }

    public static AuthResponse token(int httpStatus, @Nullable JSONObject json,
                                     @Nullable String token, @Nullable String secret) {
        return new AuthResponse(Method.TOKEN, httpStatus, json, token, secret, false);
    }

    public static AuthResponse vkId(int httpStatus, @Nullable JSONObject json) {
        return new AuthResponse(Method.VK_ID, httpStatus, json, null, null, false);
    }

    /** Deliberately excludes exception messages, request data, and transport bodies. */
    public static AuthResponse failure(Method method) {
        return new AuthResponse(method, 0, null, null, null, true);
    }
}
