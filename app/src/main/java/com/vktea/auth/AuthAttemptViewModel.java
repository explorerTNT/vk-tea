package com.vktea.auth;

import android.os.Handler;
import android.os.Looper;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.lifecycle.LiveData;
import androidx.lifecycle.MutableLiveData;
import androidx.lifecycle.ViewModel;

import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;

/** Retains one explicit authentication attempt across configuration changes. */
public final class AuthAttemptViewModel extends ViewModel {
    public interface Request {
        AuthResponse execute() throws Exception;
    }

    // Clearing an Activity's ViewModel must not allow another Activity to overlap
    // the transport operation that the old owner already started.
    private static final AtomicBoolean PROCESS_REQUEST_RUNNING = new AtomicBoolean();

    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final MutableLiveData<Boolean> busy = new MutableLiveData<>(false);
    private final MutableLiveData<AuthResponse> response = new MutableLiveData<>();
    private volatile boolean requestBusy;
    private volatile boolean cleared;

    public LiveData<Boolean> getBusy() {
        return busy;
    }

    public LiveData<AuthResponse> getResponse() {
        return response;
    }

    public boolean isBusy() {
        return requestBusy;
    }

    /** Returns false without executing the request when this owner or process is busy. */
    public boolean submit(@NonNull AuthResponse.Method method, @NonNull Request request) {
        requireMainThread();
        Objects.requireNonNull(method);
        Objects.requireNonNull(request);
        if (cleared || requestBusy || !PROCESS_REQUEST_RUNNING.compareAndSet(false, true)) {
            return false;
        }
        requestBusy = true;
        response.setValue(null);
        busy.setValue(true);
        if (cleared) {
            requestBusy = false;
            PROCESS_REQUEST_RUNNING.set(false);
            return false;
        }
        try {
            new Thread(() -> execute(method, request), "vk-tea-auth").start();
        } catch (RuntimeException ignored) {
            // A rejected worker must not leave this process permanently locked.
            PROCESS_REQUEST_RUNNING.set(false);
            complete(AuthResponse.failure(method));
        }
        return true;
    }

    /** Removes the result before UI side effects, so recreation cannot handle it twice. */
    @Nullable
    public AuthResponse takeResponse() {
        requireMainThread();
        AuthResponse pending = response.getValue();
        if (pending != null) {
            response.setValue(null);
        }
        return pending;
    }

    private void execute(AuthResponse.Method method, Request request) {
        AuthResponse result;
        try {
            result = request.execute();
            if (result == null || result.method != method) {
                result = AuthResponse.failure(method);
            }
        } catch (Exception ignored) {
            result = AuthResponse.failure(method);
        } finally {
            // Only the worker releases the gate after a started request ends.
            PROCESS_REQUEST_RUNNING.set(false);
        }
        AuthResponse completed = result;
        mainHandler.post(() -> complete(completed));
    }

    private void complete(AuthResponse completed) {
        requestBusy = false;
        if (cleared) {
            return;
        }
        // A result observer may consume this response and submit the next explicit
        // attempt synchronously. Both gates must already permit that submission.
        busy.setValue(false);
        if (!cleared) {
            response.setValue(completed);
        }
    }

    @Override
    protected void onCleared() {
        cleared = true;
        if (Looper.myLooper() == Looper.getMainLooper()) {
            response.setValue(null);
        } else {
            mainHandler.post(() -> response.setValue(null));
        }
        super.onCleared();
    }

    private static void requireMainThread() {
        if (Looper.myLooper() != Looper.getMainLooper()) {
            throw new IllegalStateException("Authentication state must be changed on the main thread");
        }
    }
}
