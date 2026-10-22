package com.vktea.auth;

import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.net.Uri;
import android.os.Bundle;
import android.text.InputType;
import android.view.View;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.Toast;

import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;

import com.vktea.MainActivity;
import com.vktea.R;

import org.json.JSONObject;

import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.Scanner;

public class AuthActivity extends AppCompatActivity {

    private static final String TOKEN_URL = "https://oauth.vk.com/token";
    private static final String CLIENT_ID = "2274003";
    private static final String CLIENT_SECRET = "hHbZxrka2uZ6jB1inYsH";
    private static final String SCOPE = "offline,friends,photos,audio,video,wall,messages,docs,groups,status,notifications";
    private static final String API_V = "5.131";

    private EditText loginField;
    private EditText passField;
    private String captchaSid;
    private int validationAttempts = 0;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_auth);

        loginField = findViewById(R.id.auth_login);
        passField = findViewById(R.id.auth_pass);
        View loginButton = findViewById(R.id.auth_login_btn);
        View forgotButton = findViewById(R.id.auth_forgot);

        loginButton.setOnClickListener(v -> doAuth());
        View tokenButton = findViewById(R.id.auth_token_btn);
        tokenButton.setOnClickListener(v -> showTokenLogin());
        forgotButton.setOnClickListener(v ->
                startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse("https://m.vk.com/restore"))));
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == 1 && validationAttempts < 3) {
            doAuth();
        }
    }

    private void doAuth() {
        String login = loginField.getText().toString().trim();
        String pass = passField.getText().toString();
        if (login.isEmpty() || pass.isEmpty()) {
            Toast.makeText(this, R.string.auth_failed, Toast.LENGTH_SHORT).show();
            return;
        }
        new Thread(() -> {
            try {
                StringBuilder url = new StringBuilder(TOKEN_URL)
                        .append("?grant_type=password")
                        .append("&client_id=").append(CLIENT_ID)
                        .append("&client_secret=").append(CLIENT_SECRET)
                        .append("&username=").append(URLEncoder.encode(login, "UTF-8"))
                        .append("&password=").append(URLEncoder.encode(pass, "UTF-8"))
                        .append("&scope=").append(SCOPE)
                        .append("&v=").append(API_V);
                if (captchaSid != null) {
                    url.append("&captcha_sid=").append(captchaSid)
                       .append("&captcha_key=").append(URLEncoder.encode(captchaKey, "UTF-8"));
                }
                HttpURLConnection conn = (HttpURLConnection) new URL(url.toString()).openConnection();
                conn.setConnectTimeout(15000);
                conn.setReadTimeout(15000);
                int code = conn.getResponseCode();
                InputStream stream = code < 400 ? conn.getInputStream() : conn.getErrorStream();
                Scanner scanner = new Scanner(stream, "UTF-8").useDelimiter("\\A");
                String body = scanner.hasNext() ? scanner.next() : "";
                scanner.close();
                JSONObject json = new JSONObject(body);
                if (json.has("access_token")) {
                    String token = json.getString("access_token");
                    String userId = String.valueOf(json.getLong("user_id"));
                    runOnUiThread(() -> {
                        Session.save(this, token, userId);
                        TokenRevealDialog.show(this, token);
                    });
                } else {
                    String error = json.optString("error", "");
                    if (json.has("captcha_sid")) {
                        captchaSid = json.getString("captcha_sid");
                        String captchaImg = json.getString("captcha_img");
                        runOnUiThread(() -> showCaptcha(captchaImg));
                    } else if ("need_validation".equals(error) && json.has("validation_sid")) {
                        String redirect = json.optString("redirect_uri",
                                "https://m.vk.com/login?act=authcheck&sid=" + json.getString("validation_sid"));
                        validationAttempts++;
                        runOnUiThread(() -> {
                            Intent intent = new Intent(this, AuthWebViewActivity.class);
                            intent.setData(Uri.parse(redirect));
                            startActivityForResult(intent, 1);
                        });
                    } else {
                        String message = json.optString("error_description", getString(R.string.auth_failed));
                        captchaSid = null;
                        runOnUiThread(() -> Toast.makeText(this, message, Toast.LENGTH_LONG).show());
                    }
                }
            } catch (Exception e) {
                runOnUiThread(() -> Toast.makeText(this, R.string.auth_failed, Toast.LENGTH_LONG).show());
            }
        }).start();
    }

    private void showTokenLogin() {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        int pad = (int) (16 * getResources().getDisplayMetrics().density);
        box.setPadding(pad, pad, pad, 0);
        EditText input = new EditText(this);
        input.setHint(R.string.token_hint);
        input.setSingleLine(false);
        input.setMinLines(2);
        box.addView(input, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));
        new AlertDialog.Builder(this)
                .setTitle(R.string.token_title)
                .setView(box)
                .setPositiveButton(R.string.ok, (d, w) -> checkToken(input.getText().toString().trim()))
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    private void checkToken(String token) {
        if (token.isEmpty()) {
            return;
        }
        new Thread(() -> {
            try {
                HttpURLConnection conn = (HttpURLConnection) new URL(
                        "https://api.vk.ru/method/users.get?access_token=" + token + "&v=5.199").openConnection();
                conn.setConnectTimeout(15000);
                conn.setReadTimeout(15000);
                Scanner scanner = new Scanner(conn.getInputStream(), "UTF-8").useDelimiter("\\A");
                String body = scanner.hasNext() ? scanner.next() : "";
                scanner.close();
                JSONObject json = new JSONObject(body);
                if (json.has("response")) {
                    JSONObject user = json.getJSONArray("response").getJSONObject(0);
                    String userId = String.valueOf(user.getLong("id"));
                    runOnUiThread(() -> {
                        Session.save(this, token, userId);
                        TokenRevealDialog.show(this, token);
                    });
                } else {
                    runOnUiThread(() -> Toast.makeText(this, R.string.token_invalid, Toast.LENGTH_LONG).show());
                }
            } catch (Exception e) {
                runOnUiThread(() -> Toast.makeText(this, R.string.token_invalid, Toast.LENGTH_LONG).show());
            }
        }).start();
    }

    private String captchaKey = "";

    private void showCaptcha(String imageUrl) {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        int pad = (int) (16 * getResources().getDisplayMetrics().density);
        box.setPadding(pad, pad, pad, 0);
        ImageView image = new ImageView(this);
        image.setScaleType(ImageView.ScaleType.FIT_CENTER);
        box.addView(image, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));
        EditText input = new EditText(this);
        input.setHint(R.string.captcha_title);
        input.setInputType(InputType.TYPE_CLASS_TEXT);
        input.setSingleLine(true);
        box.addView(input, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));
        AlertDialog dialog = new AlertDialog.Builder(this)
                .setTitle(R.string.captcha_title)
                .setView(box)
                .setPositiveButton(R.string.ok, (d, w) -> {
                    captchaKey = input.getText().toString();
                    doAuth();
                })
                .setNegativeButton(android.R.string.cancel, (d, w) -> captchaSid = null)
                .setOnCancelListener(d -> captchaSid = null)
                .show();
        new Thread(() -> {
            try {
                HttpURLConnection conn = (HttpURLConnection) new URL(imageUrl).openConnection();
                Bitmap bmp = BitmapFactory.decodeStream(conn.getInputStream());
                runOnUiThread(() -> image.setImageBitmap(bmp));
            } catch (Exception ignored) {
            }
        }).start();
    }
}
