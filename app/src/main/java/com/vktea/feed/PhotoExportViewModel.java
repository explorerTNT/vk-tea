package com.vktea.feed;

import android.app.Application;
import android.net.Uri;

import androidx.annotation.NonNull;
import androidx.lifecycle.AndroidViewModel;
import androidx.lifecycle.LiveData;
import androidx.lifecycle.MutableLiveData;

import java.io.File;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Retains one original-byte export through rotation without retaining the Activity. */
public final class PhotoExportViewModel extends AndroidViewModel {
    enum Kind { SAVED, SHARE, DOCUMENT, ERROR }
    static final class State {
        final boolean busy;
        final Kind kind;
        final PhotoExports.SavedPhoto photo;
        boolean handled;
        State(boolean busy, Kind kind, PhotoExports.SavedPhoto photo) {
            this.busy = busy; this.kind = kind; this.photo = photo;
        }
    }
    private final MutableLiveData<State> state = new MutableLiveData<>();
    private final ExecutorService worker = Executors.newSingleThreadExecutor();

    public PhotoExportViewModel(@NonNull Application app) { super(app); }
    LiveData<State> state() { return state; }
    boolean isBusy() { State value = state.getValue(); return value != null && value.busy; }

    void start(String source, Kind kind) {
        if (isBusy()) return;
        state.setValue(new State(true, kind, null));
        worker.execute(() -> {
            try {
                PhotoExports.SavedPhoto photo = null;
                if (kind == Kind.SAVED) PhotoExports.saveGallery(getApplication(), source);
                else photo = PhotoExports.prepareShare(getApplication(), source);
                state.postValue(new State(false, kind, photo));
            } catch (Exception ignored) { state.postValue(new State(false, Kind.ERROR, null)); }
        });
    }

    void copyDocument(String source, Uri target) {
        if (isBusy()) return;
        state.setValue(new State(true, Kind.SAVED, null));
        worker.execute(() -> {
            try {
                PhotoExports.copyTo(getApplication(), source, target);
                state.postValue(new State(false, Kind.SAVED, null));
            } catch (Exception ignored) { state.postValue(new State(false, Kind.ERROR, null)); }
            finally {
                String path = Uri.parse(source).getPath();
                if ("file".equals(Uri.parse(source).getScheme()) && path != null) new File(path).delete();
            }
        });
    }

    @Override protected void onCleared() { worker.shutdownNow(); }
}
