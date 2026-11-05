package com.vktea.feed;

import android.app.Activity;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.view.Menu;
import android.view.MenuItem;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.Toast;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.widget.Toolbar;

import com.bumptech.glide.Glide;
import com.vktea.R;

import java.util.ArrayList;
import java.util.List;

/** Создание записи на стену. Схема экрана повторяет Coffee update_status.xml. */
public class NewPostActivity extends AppCompatActivity {

    /** Открыть экран сразу с выбором фото (кнопка «Фото» на профиле). */
    public static final String EXTRA_PICK_PHOTO = "pick_photo";

    private static final int MAX_PHOTOS = 10;

    private EditText input;
    private LinearLayout attachments;
    private final List<Uri> photos = new ArrayList<>();
    private ActivityResultLauncher<Intent> picker;
    private boolean publishing;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_new_post);

        Toolbar toolbar = findViewById(R.id.newpost_toolbar);
        toolbar.setTitle(R.string.post_create_title);
        setSupportActionBar(toolbar);
        if (getSupportActionBar() != null) getSupportActionBar().setDisplayHomeAsUpEnabled(true);
        toolbar.setNavigationOnClickListener(v -> finish());

        input = findViewById(R.id.status_text_edit);
        attachments = findViewById(R.id.newpost_attachments);

        picker = registerForActivityResult(new ActivityResultContracts.StartActivityForResult(), result -> {
            Intent data = result.getData();
            if (result.getResultCode() != Activity.RESULT_OK || data == null) return;
            if (data.getClipData() != null) {
                android.content.ClipData clip = data.getClipData();
                for (int i = 0; i < clip.getItemCount() && photos.size() < MAX_PHOTOS; i++) {
                    addPhoto(clip.getItemAt(i).getUri());
                }
            } else if (data.getData() != null) {
                addPhoto(data.getData());
            }
            renderAttachments();
        });

        ImageView photoButton = findViewById(R.id.newpost_btn_photo);
        photoButton.setOnClickListener(v -> choosePhotos());

        if (savedInstanceState == null && getIntent().getBooleanExtra(EXTRA_PICK_PHOTO, false)) {
            choosePhotos();
        }
    }

    private void choosePhotos() {
        if (photos.size() >= MAX_PHOTOS) {
            Toast.makeText(this, getString(R.string.post_create_limit, MAX_PHOTOS), Toast.LENGTH_SHORT).show();
            return;
        }
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT)
                .addCategory(Intent.CATEGORY_OPENABLE)
                .setType("image/*")
                .putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true)
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        try {
            picker.launch(intent);
        } catch (RuntimeException ignored) {
            Toast.makeText(this, R.string.post_create_no_picker, Toast.LENGTH_SHORT).show();
        }
    }

    private void addPhoto(Uri uri) {
        if (uri != null && !photos.contains(uri) && photos.size() < MAX_PHOTOS) photos.add(uri);
    }

    private void renderAttachments() {
        attachments.removeAllViews();
        for (Uri uri : photos) {
            ImageView preview = new ImageView(this);
            LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    (int) (200 * getResources().getDisplayMetrics().density));
            params.bottomMargin = (int) (8 * getResources().getDisplayMetrics().density);
            preview.setLayoutParams(params);
            preview.setScaleType(ImageView.ScaleType.CENTER_CROP);
            preview.setOnClickListener(v -> {
                photos.remove(uri);
                renderAttachments();
            });
            Glide.with(this).load(uri).into(preview);
            attachments.addView(preview);
        }
    }

    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        menu.add(0, 1, 0, R.string.post_create_send)
                .setShowAsAction(MenuItem.SHOW_AS_ACTION_ALWAYS);
        return true;
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        if (item.getItemId() == 1) {
            publish();
            return true;
        }
        return super.onOptionsItemSelected(item);
    }

    private void publish() {
        if (publishing) return;
        String message = input.getText().toString();
        if (message.trim().isEmpty() && photos.isEmpty()) {
            Toast.makeText(this, R.string.post_create_empty, Toast.LENGTH_SHORT).show();
            return;
        }
        publishing = true;
        Toast.makeText(this, R.string.post_create_publishing, Toast.LENGTH_SHORT).show();
        new WallPostPublisher(this).publish(message, new ArrayList<>(photos),
                new WallPostPublisher.Callback() {
                    @Override public void onPublished(long postId) {
                        publishing = false;
                        Toast.makeText(NewPostActivity.this, R.string.post_create_done,
                                Toast.LENGTH_SHORT).show();
                        setResult(RESULT_OK);
                        finish();
                    }

                    @Override public void onError(String message) {
                        publishing = false;
                        Toast.makeText(NewPostActivity.this, message, Toast.LENGTH_LONG).show();
                    }
                });
    }
}
