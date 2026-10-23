package com.vktea.auth;

import org.json.JSONObject;

import java.util.Locale;

/** Classifies server failures without exposing their free-form descriptions to UI/logs. */
final class AuthErrors {
    private AuthErrors() {}

    static boolean isRateLimited(AuthResponse response) {
        if (response.httpStatus == 429) {
            return true;
        }
        JSONObject json = response.json;
        if (json == null) {
            return false;
        }
        JSONObject nested = json.optJSONObject("error");
        int code = nested != null ? nested.optInt("error_code") : json.optInt("error_code");
        if (code == 6 || code == 9 || code == 1105) {
            return true;
        }
        String error = json.optString("error", "").toLowerCase(Locale.ROOT);
        String description = json.optString("error_description", "").toLowerCase(Locale.ROOT);
        return "flood_control".equals(error) || "too_many_requests".equals(error)
                || "too_many_auth_attempts".equals(error)
                || description.contains("слишком много попыток")
                || description.contains("too many auth")
                || description.contains("too many login")
                || description.contains("too many requests")
                || description.contains("flood control");
    }
}
