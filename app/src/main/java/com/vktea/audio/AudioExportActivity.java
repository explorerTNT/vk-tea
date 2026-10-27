package com.vktea.audio;

import android.content.Intent;
import android.database.Cursor;
import android.net.Uri;
import android.os.Bundle;
import android.os.ParcelFileDescriptor;
import android.provider.OpenableColumns;
import android.widget.Toast;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.appcompat.app.AppCompatActivity;
import androidx.media3.common.MediaItem;

import com.vktea.R;
import com.vktea.auth.Session;

import java.io.InputStream;
import java.io.OutputStream;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** User chooses an output document; the original audio document is never modified. */
public class AudioExportActivity extends AppCompatActivity {
    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private Uri source;
    private final ActivityResultLauncher<Intent> destination = registerForActivityResult(
            new ActivityResultContracts.StartActivityForResult(), result -> {
                Uri output = result.getData() == null ? null : result.getData().getData();
                if (result.getResultCode() != RESULT_OK || output == null) { finish(); return; }
                if (source == null || sameDocument(source, output) || !Session.isAuthorized(this)) {
                    complete(false); return;
                }
                copying = true;
                worker.execute(() -> {
                    boolean success = false;
                    try (InputStream in = getContentResolver().openInputStream(source)) {
                        if (in == null) throw new java.io.IOException();
                        // Open without truncation, then require an empty, seekable output.
                        // Different provider URIs can name the same underlying file.
                        try (ParcelFileDescriptor descriptor = getContentResolver().openFileDescriptor(output, "rw")) {
                            if (descriptor == null || descriptor.getStatSize() != 0) throw new java.io.IOException();
                            try (OutputStream out = new ParcelFileDescriptor.AutoCloseOutputStream(descriptor)) {
                                byte[] buffer = new byte[64 * 1024];
                                int length;
                                while ((length = in.read(buffer)) != -1) out.write(buffer, 0, length);
                                out.flush();
                                success = true;
                            }
                        }
                    } catch (Exception ignored) { /* Providers may include private URIs in errors. */ }
                    boolean copied = success;
                    runOnUiThread(() -> complete(copied));
                });
            });

    @Override protected void onCreate(Bundle savedInstanceState) {
        boolean pendingReveal = com.vktea.auth.Session.hasPendingReveal(this);
        super.onCreate(pendingReveal ? null : savedInstanceState);
        if (com.vktea.auth.TokenRevealActivity.redirectIfPending(this)) return;
        if (!Session.isAuthorized(this)) { finish(); return; }
        MediaItem item = AudioSources.find(getIntent().getStringExtra("media_id"));
        if (item == null || item.localConfiguration == null) { complete(false); return; }
        source = item.localConfiguration.uri;
        if (savedInstanceState != null && savedInstanceState.getBoolean("copying")) { finish(); return; }
        pickerLaunched = savedInstanceState != null && savedInstanceState.getBoolean("picker_launched");
        if (pickerLaunched) return;
        worker.execute(() -> {
            String name = "audio";
            String mime = "audio/*";
            try {
                String type = getContentResolver().getType(source);
                if (type != null) mime = type;
                try (Cursor cursor = getContentResolver().query(source,
                        new String[]{OpenableColumns.DISPLAY_NAME}, null, null, null)) {
                    if (cursor != null && cursor.moveToFirst() && !cursor.isNull(0)) name = cursor.getString(0);
                }
            } catch (Exception ignored) { }
            Intent create = new Intent(Intent.ACTION_CREATE_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE)
                    .setType(mime).putExtra(Intent.EXTRA_TITLE, name);
            runOnUiThread(() -> { if (!isFinishing() && !isDestroyed()) {
                pickerLaunched = true;
                destination.launch(create);
            } });
        });
    }

    private boolean pickerLaunched;
    private boolean copying;

    @Override protected void onSaveInstanceState(Bundle state) {
        state.putBoolean("picker_launched", pickerLaunched);
        state.putBoolean("copying", copying);
        super.onSaveInstanceState(state);
    }

    private static boolean sameDocument(Uri left, Uri right) {
        if (left.equals(right)) return true;
        try {
            return java.util.Objects.equals(left.getAuthority(), right.getAuthority())
                    && android.provider.DocumentsContract.getDocumentId(left)
                    .equals(android.provider.DocumentsContract.getDocumentId(right));
        } catch (IllegalArgumentException ignored) { return false; }
    }

    private void complete(boolean success) {
        Toast.makeText(this, success ? R.string.audio_export_done : R.string.audio_export_failed,
                Toast.LENGTH_SHORT).show();
        finish();
    }

    @Override protected void onDestroy() { worker.shutdown(); super.onDestroy(); }
}
