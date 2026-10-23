package com.vktea.audio.catalog;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** consumedCount counts server rows, including malformed rows skipped by the parser. */
public final class VkAudioPage {
    public final int totalCount;
    public final int consumedCount;
    public final List<VkAudioTrack> items;

    public VkAudioPage(int totalCount, List<VkAudioTrack> items, int consumedCount) {
        this.totalCount = Math.max(0, totalCount);
        this.items = Collections.unmodifiableList(new ArrayList<>(items));
        this.consumedCount = Math.max(0, consumedCount);
    }
}
