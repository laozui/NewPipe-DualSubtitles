package org.schabi.newpipe.player.dualsubtitles;

import androidx.annotation.NonNull;

/**
 * 单条字幕数据模型
 */
public class SubtitleItem implements Comparable<SubtitleItem> {
    private final long startMs;
    private final long endMs;
    private final String text;

    public SubtitleItem(final long startMs, final long endMs, @NonNull final String text) {
        this.startMs = startMs;
        this.endMs = Math.max(startMs, endMs);
        this.text = text;
    }

    public long getStartMs() {
        return startMs;
    }

    public long getEndMs() {
        return endMs;
    }

    @NonNull
    public String getText() {
        return text;
    }

    public long getDurationMs() {
        return endMs - startMs;
    }

    @Override
    public int compareTo(final SubtitleItem other) {
        return Long.compare(this.startMs, other.startMs);
    }

    @NonNull
    @Override
    public String toString() {
        return "[" + startMs + " -> " + endMs + "] " + text;
    }
}
