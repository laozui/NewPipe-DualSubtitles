package org.schabi.newpipe.player.dualsubtitles;

import android.content.Context;
import android.net.Uri;
import android.util.Log;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import org.schabi.newpipe.DownloaderImpl;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Locale;

import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;

/**
 * 双语字幕合成与本地 WebVTT 生成引擎
 */
public final class DualSubtitleSynthesizer {
    private static final String TAG = "DualSubtitleSynth";

    private DualSubtitleSynthesizer() {
    }

    /**
     * 根据主字幕 URL 和目标副语言生成本地双语 WebVTT 文件
     *
     * @param context          上下文
     * @param videoId          视频 ID (如 YouTube videoId)
     * @param primaryUrl       主字幕 URL
     * @param primaryLangCode  主语言代号 (如 en)
     * @param secondaryUrl     现成副字幕 URL (若无现成中文，可传 null，将自动通过 &tlang= 向 YouTube 申请)
     * @param targetLangCode   目标副语言代号 (如 zh-Hans)
     * @return 合成后的本地 WebVTT 文件，若失败返回 null
     */
    @Nullable
    public static File buildDualSubtitleFile(
            @NonNull final Context context,
            @NonNull final String videoId,
            @NonNull final String primaryUrl,
            @NonNull final String primaryLangCode,
            @Nullable final String secondaryUrl,
            @NonNull final String targetLangCode) {

        final File cacheDir = new File(context.getCacheDir(), "dualsub_cache");
        if (!cacheDir.exists()) {
            //noinspection ResultOfMethodCallIgnored
            cacheDir.mkdirs();
        }

        final String fileName = "dual_" + sanitizeFileName(videoId) + "_"
                + sanitizeFileName(primaryLangCode) + "_"
                + sanitizeFileName(targetLangCode) + ".vtt";
        final File outputFile = new File(cacheDir, fileName);

        // 如果本地已存在且大小大于 100 字节，直接复用秒开
        if (outputFile.exists() && outputFile.length() > 100) {
            Log.d(TAG, "Reusing cached dual subtitle: " + outputFile.getAbsolutePath());
            return outputFile;
        }

        try {
            final OkHttpClient client = DownloaderImpl.getInstance().getClient();

            // 1. 下载主字幕
            Log.d(TAG, "Fetching primary subtitle from: " + primaryUrl);
            final String primaryRaw = fetchString(client, primaryUrl);
            if (primaryRaw == null || primaryRaw.isEmpty()) {
                Log.w(TAG, "Primary subtitle content is empty");
                return null;
            }

            // 2. 准备副字幕下载链接
            final String finalSecUrl;
            if (secondaryUrl != null && !secondaryUrl.isEmpty()) {
                finalSecUrl = secondaryUrl;
            } else {
                // 激活 YouTube Timedtext 原生机器翻译能力
                finalSecUrl = buildYoutubeTranslatedUrl(primaryUrl, targetLangCode);
            }

            Log.d(TAG, "Fetching secondary subtitle from: " + finalSecUrl);
            final String secondaryRaw = fetchString(client, finalSecUrl);

            // 3. 解析为结构化模型
            List<SubtitleItem> primaryItems = DualSubtitleParser.parse(primaryRaw);
            final List<SubtitleItem> secondaryItems = DualSubtitleParser.parse(secondaryRaw);

            if (primaryItems.isEmpty()) {
                Log.w(TAG, "Parsed primary subtitle items is empty");
                return null;
            }

            // 4. 若开启上下文合并且主字幕是单句碎词，执行 Stitching
            if (DualSubtitleConfig.isStitchingEnabled(context)) {
                primaryItems = DualSubtitleAligner.stitchTimeline(primaryItems);
            }

            // 5. 对齐合并
            final List<DualSubtitleAligner.MergedSubtitleItem> mergedItems =
                    DualSubtitleAligner.alignDualSubtitles(primaryItems, secondaryItems);

            // 6. 生成标准 WebVTT
            final String primaryColor = DualSubtitleConfig.getPrimaryColor(context);
            final String secondaryColor = DualSubtitleConfig.getSecondaryColor(context);
            final String vttContent = generateWebVttString(mergedItems, primaryColor, secondaryColor);

            // 7. 写入缓存文件
            try (FileOutputStream fos = new FileOutputStream(outputFile);
                 OutputStreamWriter writer = new OutputStreamWriter(fos, StandardCharsets.UTF_8)) {
                writer.write(vttContent);
                writer.flush();
            }

            Log.i(TAG, "Successfully generated dual subtitles WebVTT: " + outputFile.length() + " bytes");
            return outputFile;

        } catch (final Exception e) {
            Log.e(TAG, "Error while synthesizing dual subtitles", e);
            if (outputFile.exists()) {
                //noinspection ResultOfMethodCallIgnored
                outputFile.delete();
            }
            return null;
        }
    }

    /**
     * 将主字幕 YouTube timedtext 链接注入目标语言翻译参数 &tlang=
     */
    @NonNull
    public static String buildYoutubeTranslatedUrl(@NonNull final String originalUrl,
                                                   @NonNull final String targetLang) {
        if (!originalUrl.contains("timedtext")) {
            return originalUrl;
        }

        final Uri uri = Uri.parse(originalUrl);
        final Uri.Builder builder = uri.buildUpon();

        // 确保使用 WebVTT 或 json3 格式以获得最干净的时间轴
        builder.appendQueryParameter("tlang", targetLang);
        if (uri.getQueryParameter("fmt") == null) {
            builder.appendQueryParameter("fmt", "vtt");
        }

        return builder.build().toString();
    }

    @Nullable
    private static String fetchString(@NonNull final OkHttpClient client, @NonNull final String url) {
        final Request request = new Request.Builder()
                .url(url)
                .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36")
                .build();
        try (Response response = client.newCall(request).execute()) {
            if (response.isSuccessful() && response.body() != null) {
                return response.body().string();
            } else {
                Log.w(TAG, "Failed response code " + response.code() + " for " + url);
            }
        } catch (final IOException e) {
            Log.e(TAG, "Network error fetching " + url, e);
        }
        return null;
    }

    @NonNull
    private static String generateWebVttString(
            @NonNull final List<DualSubtitleAligner.MergedSubtitleItem> items,
            @NonNull final String primaryColor,
            @NonNull final String secondaryColor) {

        final StringBuilder sb = new StringBuilder();
        sb.append("WEBVTT\n\n");

        int index = 1;
        for (final DualSubtitleAligner.MergedSubtitleItem item : items) {
            sb.append(index++).append("\n");
            sb.append(formatVttTimestamp(item.getStartMs()))
                    .append(" --> ")
                    .append(formatVttTimestamp(item.getEndMs()))
                    .append("\n");

            // 主字幕（通常为原文）
            sb.append("<font color=\"").append(primaryColor).append("\"><b>")
                    .append(escapeHtml(item.getPrimaryText()))
                    .append("</b></font>");

            // 副字幕（译文）
            if (!item.getSecondaryText().isEmpty()) {
                sb.append("\n<font color=\"").append(secondaryColor).append("\"><small>")
                        .append(escapeHtml(item.getSecondaryText()))
                        .append("</small></font>");
            }

            sb.append("\n\n");
        }

        return sb.toString();
    }

    @NonNull
    private static String formatVttTimestamp(final long timeMs) {
        final long hours = timeMs / 3600000;
        final long minutes = (timeMs % 3600000) / 60000;
        final long seconds = (timeMs % 60000) / 1000;
        final long millis = timeMs % 1000;

        return String.format(Locale.US, "%02d:%02d:%02d.%03d", hours, minutes, seconds, millis);
    }

    private static String escapeHtml(final String text) {
        if (text == null) {
            return "";
        }
        return text.replace("&", "&amp;")
                .replace("<", "&lt;")
                .replace(">", "&gt;");
    }

    private static String sanitizeFileName(final String name) {
        return name.replaceAll("[^a-zA-Z0-9_-]", "_");
    }
}
