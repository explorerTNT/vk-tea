package com.vktea;

import android.app.Application;

import com.vktea.ui.AppearanceSettings;

public class VKTeaApplication extends Application {
    @Override
    public void onCreate() {
        super.onCreate();
        AppearanceSettings.apply(this);
        registerActivityLifecycleCallbacks(new ActivityLifecycleCallbacks() {
            @Override public void onActivityCreated(android.app.Activity activity, android.os.Bundle state) { }
            @Override public void onActivityStarted(android.app.Activity activity) { }
            @Override public void onActivityResumed(android.app.Activity activity) {
                com.vktea.auth.TokenRevealActivity.redirectIfPending(activity);
            }
            @Override public void onActivityPaused(android.app.Activity activity) { }
            @Override public void onActivityStopped(android.app.Activity activity) { }
            @Override public void onActivitySaveInstanceState(android.app.Activity activity, android.os.Bundle state) { }
            @Override public void onActivityDestroyed(android.app.Activity activity) { }
        });
    }
}
