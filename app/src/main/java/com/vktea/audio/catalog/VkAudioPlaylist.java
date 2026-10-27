package com.vktea.audio.catalog;

/** Playlist identity and display metadata from VK. */
public final class VkAudioPlaylist {
    public final long ownerId;
    public final long id;
    public final String title;
    public final String accessKey;
    public VkAudioPlaylist(long ownerId, long id, String title, String accessKey) {
        if (ownerId == 0 || id <= 0) throw new IllegalArgumentException("Invalid playlist");
        this.ownerId = ownerId;
        this.id = id;
        this.title = title == null ? "" : title;
        this.accessKey = accessKey != null && accessKey.matches("[A-Za-z0-9_-]{1,256}") ? accessKey : "";
    }
}
