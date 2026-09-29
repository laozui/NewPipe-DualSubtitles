package org.schabi.newpipe.player.dualsubtitles;

import androidx.annotation.NonNull;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.regex.Pattern;

/**
 * 双语字幕对齐与断句缝合算法 (Stitching & Alignment Engine)
 * 移植自 YouTube Dual Subs Pro v6.0 核心逻辑
 */
public final class DualSubtitleAligner {

    private static final Pattern SENTENCE_END_PATTERN = Pattern.compile(".*[.?!][\"']?\\s*$");

    private DualSubtitleAligner() {
    }

    /**
     * 判断句子是否在语义或标点上结束
     */
    public static boolean isSentenceEnd(@NonNull final String text) {
        return SENTENCE_END_PATTERN.matcher(text.trim()).matches();
    }

    /**
     * 上下文断句合并算法 (Context Stitching)
     * 将断裂的 ASR 短句合并为具有完整语义的句子段落
     */
    @NonNull
    public static List<SubtitleItem> stitchTimeline(@NonNull final List<SubtitleItem> timeline) {
        if (timeline.isEmpty()) {
            return Collections.emptyList();
        }

        final List<SubtitleItem> result = new ArrayList<>();
        int i = 0;
        final int n = timeline.size();

        while (i < n) {
            int j = i + 1;
            while (j < n) {
                // 1. 检查已合并的内容是否已构成完整句子
                final StringBuilder currentText = new StringBuilder();
                for (int k = i; k < j; k++) {
                    if (currentText.length() > 0) {
                        currentText.append(" ");
                    }
                    currentText.append(timeline.get(k).getText());
                }

                if (isSentenceEnd(currentText.toString())) {
                    break;
                }

                // 2. 检查与下一条的停顿间隔，超过 2.5 秒视作自然停顿，不强制合并
                if (timeline.get(j).getStartMs() - timeline.get(j - 1).getEndMs() > 2500) {
                    break;
                }

                // 3. 合并后的字符长度限制（单屏通常不超过 250 个字符）
                int totalLen = 0;
                for (int k = i; k <= j; k++) {
                    totalLen += timeline.get(k).getText().length();
                }
                if (totalLen > 250) {
                    break;
                }

                j++;
            }

            final long mergedStartMs = timeline.get(i).getStartMs();
            final long mergedEndMs = timeline.get(j - 1).getEndMs();
            final StringBuilder mergedText = new StringBuilder();

            for (int k = i; k < j; k++) {
                if (mergedText.length() > 0) {
                    mergedText.append(" ");
                }
                mergedText.append(timeline.get(k).getText());
            }

            result.add(new SubtitleItem(mergedStartMs, mergedEndMs, mergedText.toString().trim()));
            i = j;
        }

        return result;
    }

    /**
     * 双语字幕条目，包含主语言与副语言
     */
    public static class MergedSubtitleItem {
        private final long startMs;
        private final long endMs;
        private final String primaryText;
        private final String secondaryText;

        public MergedSubtitleItem(final long startMs, final long endMs,
                                  @NonNull final String primaryText,
                                  @NonNull final String secondaryText) {
            this.startMs = startMs;
            this.endMs = Math.max(startMs, endMs);
            this.primaryText = primaryText;
            this.secondaryText = secondaryText;
        }

        public long getStartMs() {
            return startMs;
        }

        public long getEndMs() {
            return endMs;
        }

        @NonNull
        public String getPrimaryText() {
            return primaryText;
        }

        @NonNull
        public String getSecondaryText() {
            return secondaryText;
        }
    }

    /**
     * 将主字幕与副字幕对齐合并
     * 采用时间轴区间重叠算法（Overlap Score），完美兼容官方自制中英字幕及机器翻译字幕
     */
    @NonNull
    public static List<MergedSubtitleItem> alignDualSubtitles(
            @NonNull final List<SubtitleItem> primaryList,
            @NonNull final List<SubtitleItem> secondaryList) {

        final List<MergedSubtitleItem> mergedList = new ArrayList<>();
        if (primaryList.isEmpty()) {
            return mergedList;
        }

        if (secondaryList.isEmpty()) {
            for (final SubtitleItem item : primaryList) {
                mergedList.add(new MergedSubtitleItem(item.getStartMs(), item.getEndMs(), item.getText(), ""));
            }
            return mergedList;
        }

        int secIndex = 0;
        final int secSize = secondaryList.size();

        for (final SubtitleItem pItem : primaryList) {
            final long pStart = pItem.getStartMs();
            final long pEnd = pItem.getEndMs();

            final List<String> matchedTranslations = new ArrayList<>();

            // 移动 secIndex 到可能相交的位置
            while (secIndex < secSize && secondaryList.get(secIndex).getEndMs() < pStart - 500) {
                secIndex++;
            }

            // 寻找在 [pStart, pEnd] 时间范围内具有重叠的副字幕
            int scan = secIndex;
            while (scan < secSize) {
                final SubtitleItem sItem = secondaryList.get(scan);
                final long sStart = sItem.getStartMs();
                final long sEnd = sItem.getEndMs();

                // 超过重叠范围则终止探测
                if (sStart > pEnd + 500) {
                    break;
                }

                // 计算重叠时间
                final long overlap = Math.min(pEnd, sEnd) - Math.max(pStart, sStart);
                if (overlap > 0 || (Math.abs(pStart - sStart) < 300)) {
                    if (!matchedTranslations.contains(sItem.getText())) {
                        matchedTranslations.add(sItem.getText());
                    }
                }
                scan++;
            }

            final String combinedTranslation = String.join(" ", matchedTranslations);
            mergedList.add(new MergedSubtitleItem(pStart, pEnd, pItem.getText(), combinedTranslation));
        }

        return mergedList;
    }
}
