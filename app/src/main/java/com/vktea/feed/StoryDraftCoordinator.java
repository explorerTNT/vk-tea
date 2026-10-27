package com.vktea.feed;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Orders draft operations across the viewer and the recovery screen. */
final class StoryDraftCoordinator {
    private static final ExecutorService EXECUTOR = Executors.newSingleThreadExecutor();

    private StoryDraftCoordinator() { }

    static void execute(Runnable operation) { EXECUTOR.execute(operation); }
}
