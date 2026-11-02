package com.vktea.documents;

import static org.junit.Assert.*;
import static org.robolectric.Shadows.shadowOf;
import android.content.Intent;
import android.os.Bundle;
import android.os.Looper;
import android.view.View;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.TextView;
import androidx.annotation.NonNull;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.lifecycle.ViewModel;
import androidx.lifecycle.ViewModelProvider;
import androidx.recyclerview.widget.RecyclerView;
import com.vktea.R;
import com.vktea.auth.Session;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.android.controller.ActivityController;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.LooperMode;
import org.robolectric.shadows.ShadowDialog;
import java.time.Duration;
import java.util.Collections;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 28, qualifiers = "w360dp-h800dp-mdpi")
@LooperMode(LooperMode.Mode.PAUSED)
public class DocumentsFragmentTest {
    static FakeRepository repository;
    private ActivityController<Host> controller;
    @Before public void setup() {
        repository = new FakeRepository(); controller = Robolectric.buildActivity(Host.class).setup().visible();
        controller.get().getSupportFragmentManager().beginTransaction().add(R.id.content, new TestFragment()).commitNow();
        settle();
    }
    @After public void close() {
        controller.close();
        controller.get().getSharedPreferences("session", android.content.Context.MODE_PRIVATE).edit().clear().commit();
        repository = null;
    }
    @Test public void emptyErrorsRetryAndSearchAreVisibleAndDebounced() {
        assertEquals(1, repository.calls);
        repository.callback.error(new DocumentsRepository.Error(DocumentsRepository.Error.Kind.ACCESS)); settle();
        assertEquals(View.VISIBLE, view(R.id.documents_notice_row).getVisibility());
        assertEquals(controller.get().getString(R.string.documents_error_access), ((TextView) view(R.id.documents_notice)).getText().toString());
        assertTrue(view(R.id.documents_retry).performClick()); assertEquals(2, repository.calls);
        repository.callback.success(new DocumentsRepository.Page(Collections.emptyList(), 0, 0)); settle();
        assertEquals(controller.get().getString(R.string.documents_empty), ((TextView) view(R.id.documents_empty)).getText().toString());
        ((EditText) view(R.id.documents_search)).setText("contract");
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(350));
        assertEquals(3, repository.calls); assertEquals("contract", repository.query);
    }
    @Test public void openingSafeDocumentRequiresExplicitConfirmation() {
        repository.callback.success(new DocumentsRepository.Page(Collections.singletonList(
                new DocumentItem(1, 7, "Report.pdf", "pdf", 1024, "https://vk.com/doc1")), 1, 1));
        settle(); RecyclerView list = (RecyclerView) view(R.id.documents_list);
        RecyclerView.ViewHolder holder = list.findViewHolderForAdapterPosition(0);
        assertNotNull(holder); assertTrue(holder.itemView.performClick());
        assertNull(shadowOf(controller.get()).getNextStartedActivity());
        AlertDialog dialog = (AlertDialog) ShadowDialog.getLatestDialog();
        assertNotNull(dialog); assertTrue(dialog.isShowing()); dialog.getButton(AlertDialog.BUTTON_POSITIVE).performClick();
        shadowOf(Looper.getMainLooper()).idle();
        Intent opened = shadowOf(controller.get()).getNextStartedActivity();
        assertNotNull(opened); assertEquals(Intent.ACTION_VIEW, opened.getAction());
        assertEquals("https://vk.com/doc1", opened.getDataString());
    }
    @Test public void emptyFilteredPageStillOffersManualNextPage() {
        repository.callback.success(new DocumentsRepository.Page(Collections.emptyList(), 100, 50)); settle();
        assertEquals(controller.get().getString(R.string.documents_empty_page), ((TextView) view(R.id.documents_empty)).getText().toString());
        assertEquals(View.VISIBLE, view(R.id.documents_more).getVisibility());
        assertTrue(view(R.id.documents_more).performClick()); assertEquals(2, repository.calls); assertEquals(50, repository.offset);
    }
    @Test public void confirmationCannotOpenPreviousAccountsDocument() {
        Session.save(controller.get(), "synthetic-a", "101");
        repository.callback.success(new DocumentsRepository.Page(Collections.singletonList(
                new DocumentItem(1, 101, "Report.pdf", "pdf", 1024, "https://vk.com/doc1")), 1, 1));
        settle(); RecyclerView list = (RecyclerView) view(R.id.documents_list);
        RecyclerView.ViewHolder holder = list.findViewHolderForAdapterPosition(0);
        assertNotNull(holder); holder.itemView.performClick();
        AlertDialog dialog = (AlertDialog) ShadowDialog.getLatestDialog();
        Session.save(controller.get(), "synthetic-b", "202");
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).performClick();
        shadowOf(Looper.getMainLooper()).idle();
        assertNull(shadowOf(controller.get()).getNextStartedActivity());
    }
    @Test public void recreationReleasesOldViewsAndKeepsLoadedSnapshot() {
        repository.callback.success(new DocumentsRepository.Page(Collections.singletonList(
                new DocumentItem(1, 7, "Report.pdf", "pdf", 1024, "https://vk.com/doc1")), 1, 1));
        settle(); RecyclerView previous = (RecyclerView) view(R.id.documents_list);
        controller.recreate(); settle();
        assertNull(previous.getAdapter());
        assertEquals(1, ((RecyclerView) view(R.id.documents_list)).getAdapter().getItemCount());
        assertEquals(1, repository.calls);
    }
    private View view(int id) { return controller.get().findViewById(id); }
    private void settle() {
        for (int i = 0; i < 2; i++) {
            View decor = controller.get().getWindow().getDecorView();
            decor.measure(View.MeasureSpec.makeMeasureSpec(360, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(800, View.MeasureSpec.EXACTLY));
            decor.layout(0, 0, 360, 800); shadowOf(Looper.getMainLooper()).idle();
        }
    }
    public static class Host extends AppCompatActivity {
        @Override protected void onCreate(Bundle state) {
            setTheme(R.style.Theme_VKTea); super.onCreate(state);
            FrameLayout content = new FrameLayout(this); content.setId(R.id.content); setContentView(content);
        }
    }
    public static class TestFragment extends DocumentsFragment {
        @Override protected ViewModelProvider.Factory createModelFactory() {
            return new ViewModelProvider.Factory() {
                @NonNull @Override public <T extends ViewModel> T create(@NonNull Class<T> type) {
                    return type.cast(new DocumentsViewModel(repository));
                }
            };
        }
    }
    static final class FakeRepository implements DocumentsRepository {
        int calls, offset; String query; Callback callback;
        @Override public Request load(String query, int offset, Callback callback) {
            calls++; this.query = query; this.offset = offset; this.callback = callback; return () -> { };
        }
        @Override public void close() { }
    }
}
