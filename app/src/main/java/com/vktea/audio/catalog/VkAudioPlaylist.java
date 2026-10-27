package com.vktea.audio.catalog;

/** Playlist identity and display metadata from VK. */
public final class VkAudioPlaylist {
    public final long ownerId;
    public final long id;
    public final String title;
    public final String accessKey;
    /** Null means VK did not supply a usable description; an empty string is an explicit value. */
    public final String description;
    /** Null means VK did not supply a boolean permission. */
    public final Boolean canEdit;
    public final Boolean canDelete;
    /** Null means the list response did not supply the visibility setting. */
    public final Boolean noDiscover;

    public VkAudioPlaylist(long ownerId, long id, String title, String accessKey) {
        this(ownerId, id, title, accessKey, null, null, null);
    }

    public VkAudioPlaylist(long ownerId, long id, String title, String accessKey,
                           String description, Boolean canEdit, Boolean canDelete) {
        this(ownerId, id, title, accessKey, description, canEdit, canDelete, null);
    }

    public VkAudioPlaylist(long ownerId, long id, String title, String accessKey,
                           String description, Boolean canEdit, Boolean canDelete, Boolean noDiscover) {
        if (ownerId == 0 || id <= 0) throw new IllegalArgumentException("Invalid playlist");
        this.ownerId = ownerId;
        this.id = id;
        this.title = title == null ? "" : title;
        this.accessKey = accessKey != null && accessKey.matches("[A-Za-z0-9_-]{1,256}") ? accessKey : "";
        this.description = description;
        this.canEdit = canEdit;
        this.canDelete = canDelete;
        this.noDiscover = noDiscover;
    }

    /** VK's playlist attachment route; access keys have already been restricted to safe characters. */
    public String webUrl() {
        return "https://m.vk.com/audio?z=audio_playlist" + ownerId + "_" + id
                + (accessKey.isEmpty() ? "" : "/" + accessKey);
    }
}
