package com.vktea.videos;

/** Keeps monitoring through buffering until playback reaches the selected end or is stopped. */
final class VideoPreviewBoundary {
    private boolean active;
    private long end;
    void start(long endMs) { end = endMs; active = true; }
    void stop() { active = false; }
    boolean isActive() { return active; }
    boolean shouldPause(long position) {
        if (active && position >= end) { active = false; return true; }
        return false;
    }
}
