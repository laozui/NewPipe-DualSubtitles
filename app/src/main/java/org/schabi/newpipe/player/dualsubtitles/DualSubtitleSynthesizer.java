package org.schabi.newpipe.player.dualsubtitles;

import android.content.Context;
import android.net.Uri;
import android.util.Log;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import org.json.JSONArray;
import org.schabi.newpipe.DownloaderImpl;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStreamWriter;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;

/**
 * 双语字幕合成与本地 WebVTT 生成引擎 (DualSubtitles Pro 引擎)
 * 支持原生副字幕提取、YouTube Timedtext 转译、以及 Google 极速翻译兜底
 */
public final class DualSubtitleSynthesizer {
    private static final String TAG = "DualSubtitleSynth";

    private static final Map<String, String> GOOGLE_LANG_MAP = new HashMap<>();
    static {
        GOOGLE_LANG_MAP.put("zh-Hans", "zh-CN");
        GOOGLE_LANG_MAP.put("zh-Hant", "zh-TW");
        GOOGLE_LANG_MAP.put("zh", "zh-CN");
        GOOGLE_LANG_MAP.put("en", "en");
        GOOGLE_LANG_MAP.put("ja", "ja");
        GOOGLE_LANG_MAP.put("ko", "ko");
        GOOGLE_LANG_MAP.put("es", "es");
        GOOGLE_LANG_MAP.put("fr", "fr");
        GOOGLE_LANG_MAP.put("de", "de");
        GOOGLE_LANG_MAP.put("ru", "ru");
        GOOGLE_LANG_MAP.put("vi", "vi");
    }

    private DualSubtitleSynthesizer() {
    }

    /**
     * 根据主字幕 URL 和目标副语言生成本地双语 WebVTT 文件
     *
     * @param context          上下文
     * @param videoId          视频 ID (如 YouTube videoId)
     * @param primaryUrl       主字幕 URL
     * @param primaryLangCode  主语言代号 (如 en)
     * @param secondaryUrl     现成副字幕 URL (若无现成中文，可传 null，将自动通过 &tlang= 或 Google 翻译申请)
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
            Log.d(TAG, "Reusing cached dual subtitle: " + outputFile.getAbsolutePath()
                    + " (" + outputFile.length() + " bytes)");
            return outputFile;
        }

        try {
            final OkHttpClient client = DownloaderImpl.getInstance().getClient();

            // 1. 下载主字幕
            Log.d(TAG, "Fetching primary subtitle from: " + primaryUrl);
            final String primaryRaw = fetchString(client, primaryUrl);
            if (primaryRaw == null || primaryRaw.isEmpty()) {
                Log.w(TAG, "Primary subtitle content is empty for video: " + videoId);
                return null;
            }

            // 解析为主字幕结构体
            List<SubtitleItem> primaryItems = DualSubtitleParser.parse(primaryRaw);
            if (primaryItems.isEmpty()) {
                Log.w(TAG, "Parsed primary subtitle items is empty");
                return null;
            }

            // 若开启上下文合并且主字幕是单句碎词，执行上下文缝合对齐 (Stitching)
            if (DualSubtitleConfig.isStitchingEnabled(context)) {
                primaryItems = DualSubtitleAligner.stitchTimeline(primaryItems);
            }

            // 2. 准备副字幕
            List<SubtitleItem> secondaryItems = Collections.emptyList();

            // 2.1 若有现成副字幕 URL，优先下载
            if (secondaryUrl != null && !secondaryUrl.isEmpty()) {
                Log.d(TAG, "Fetching existing secondary subtitle from: " + secondaryUrl);
                final String secondaryRaw = fetchString(client, secondaryUrl);
                if (secondaryRaw != null && !secondaryRaw.isEmpty()) {
                    secondaryItems = DualSubtitleParser.parse(secondaryRaw);
                }
            }

            // 2.2 若无现成副字幕或下载失败，尝试通过 YouTube 原生 timedtext &tlang=
            if (secondaryItems.isEmpty()) {
                final String ytTransUrl = buildYoutubeTranslatedUrl(primaryUrl, targetLangCode);
                Log.d(TAG, "Attempting YouTube native timedtext translation: " + ytTransUrl);
                final String ytTransRaw = fetchString(client, ytTransUrl);
                if (ytTransRaw != null && !ytTransRaw.isEmpty()) {
                    secondaryItems = DualSubtitleParser.parse(ytTransRaw);
                }
            }

            // 2.3 🌟 核心保底策略：若依然无副字幕（YouTube 429/无翻译），调用 Google 极速翻译引擎
            if (secondaryItems.isEmpty()) {
                Log.i(TAG, "Activating Google Translate fallback engine for " + primaryItems.size() + " items");
                secondaryItems = translateViaGoogle(client, primaryItems, targetLangCode);
            }

            // 3. 对齐合并双语字幕
            final List<DualSubtitleAligner.MergedSubtitleItem> mergedItems =
                    DualSubtitleAligner.alignDualSubtitles(primaryItems, secondaryItems);

            if (mergedItems.isEmpty()) {
                Log.w(TAG, "Merged subtitle items is empty");
                return null;
            }

            // 4. 生成标准高保真 WebVTT
            final String primaryColor = DualSubtitleConfig.getPrimaryColor(context);
            final String secondaryColor = DualSubtitleConfig.getSecondaryColor(context);
            final String vttContent = generateWebVttString(mergedItems, primaryColor, secondaryColor);

            // 5. 写入缓存文件
            try (FileOutputStream fos = new FileOutputStream(outputFile);
                 OutputStreamWriter writer = new OutputStreamWriter(fos, StandardCharsets.UTF_8)) {
                writer.write(vttContent);
                writer.flush();
            }

            Log.i(TAG, "Successfully generated dual subtitles WebVTT: " + outputFile.length()
                    + " bytes, items=" + mergedItems.size());
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
     * 🌟 逐字稿 (Transcript) 通道：面向 PBS 等无 CC 字幕轨、仅有交互式逐字稿的视频
     *
     * <p>不再依赖 timedtext，而是通过 {@link YouTubeTranscriptFetcher} 拉取逐字稿段落作为主字幕，
     * 再用 Google 翻译生成副语言，最后合成双语 WebVTT。</p>
     *
     * @param context        上下文
     * @param videoId        视频 ID
     * @param targetLangCode 目标副语言代号（如 zh-Hans）
     * @return 合成后的本地 WebVTT 文件，失败返回 null
     */
    @Nullable
    public static File buildDualSubtitleFileFromTranscript(
            @NonNull final Context context,
            @NonNull final String videoId,
            @NonNull final String targetLangCode) {

        final File cacheDir = new File(context.getCacheDir(), "dualsub_cache");
        if (!cacheDir.exists()) {
            //noinspection ResultOfMethodCallIgnored
            cacheDir.mkdirs();
        }

        final String fileName = "dual_" + sanitizeFileName(videoId)
                + "_tr_" + sanitizeFileName(targetLangCode) + ".vtt";
        final File outputFile = new File(cacheDir, fileName);

        if (outputFile.exists() && outputFile.length() > 100) {
            Log.d(TAG, "Reusing cached transcript dual subtitle: " + outputFile.getAbsolutePath());
            return outputFile;
        }

        try {
            final OkHttpClient client = DownloaderImpl.getInstance().getClient();

            // 1. 抓取逐字稿作为主字幕
            final List<SubtitleItem> primaryItems =
                    YouTubeTranscriptFetcher.fetchTranscript(client, videoId);
            if (primaryItems.isEmpty()) {
                Log.w(TAG, "Transcript unavailable for video: " + videoId);
                return null;
            }

            // 2. 逐字稿段落普遍较长，采用较小的翻译批次避免 URL 过长
            Log.i(TAG, "Translating " + primaryItems.size()
                    + " transcript segments to " + targetLangCode);
            final List<SubtitleItem> secondaryItems =
                    translateViaGoogle(client, primaryItems, targetLangCode, 10);
            if (secondaryItems.isEmpty()) {
                Log.w(TAG, "Transcript translation failed for video: " + videoId);
                return null;
            }

            // 3. 对齐合并 + 生成 WebVTT
            final List<DualSubtitleAligner.MergedSubtitleItem> mergedItems =
                    DualSubtitleAligner.alignDualSubtitles(primaryItems, secondaryItems);
            if (mergedItems.isEmpty()) {
                return null;
            }

            final String vttContent = generateWebVttString(
                    mergedItems,
                    DualSubtitleConfig.getPrimaryColor(context),
                    DualSubtitleConfig.getSecondaryColor(context));

            try (FileOutputStream fos = new FileOutputStream(outputFile);
                 OutputStreamWriter writer = new OutputStreamWriter(fos, StandardCharsets.UTF_8)) {
                writer.write(vttContent);
                writer.flush();
            }

            Log.i(TAG, "Transcript dual subtitle generated: " + outputFile.length()
                    + " bytes, items=" + mergedItems.size());
            return outputFile;

        } catch (final Exception e) {
            Log.e(TAG, "Error while synthesizing transcript dual subtitles", e);
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

        builder.appendQueryParameter("tlang", targetLang);
        if (uri.getQueryParameter("fmt") == null) {
            builder.appendQueryParameter("fmt", "vtt");
        }

        return builder.build().toString();
    }

    /**
     * 使用 Google 翻译公开接口对字幕条目进行分批并发翻译
     */
    @NonNull
    private static List<SubtitleItem> translateViaGoogle(
            @NonNull final OkHttpClient client,
            @NonNull final List<SubtitleItem> sourceItems,
            @NonNull final String targetLangCode) {
        return translateViaGoogle(client, sourceItems, targetLangCode, 25);
    }

    /**
     * 使用 Google 翻译公开接口对字幕条目进行分批并发翻译
     *
     * @param batchSize 每批合并的行数（逐字稿文本较长时应调小）
     */
    @NonNull
    private static List<SubtitleItem> translateViaGoogle(
            @NonNull final OkHttpClient client,
            @NonNull final List<SubtitleItem> sourceItems,
            @NonNull final String targetLangCode,
            final int batchSize) {

        final String googleLang = GOOGLE_LANG_MAP.getOrDefault(targetLangCode, targetLangCode);
        final List<SubtitleItem> translatedList = new ArrayList<>(sourceItems.size());

        for (int i = 0; i < sourceItems.size(); i += batchSize) {
            final int end = Math.min(i + batchSize, sourceItems.size());
            final List<SubtitleItem> batch = sourceItems.subList(i, end);

            final StringBuilder batchText = new StringBuilder();
            for (int j = 0; j < batch.size(); j++) {
                if (j > 0) {
                    batchText.append("\n");
                }
                // 单行内部的换行替换为空格，确保行数一一对应
                batchText.append(batch.get(j).getText().replace("\n", " ").trim());
            }

            final List<String> translatedLines = translateBatch(client, batchText.toString(), googleLang);

            for (int j = 0; j < batch.size(); j++) {
                final SubtitleItem orig = batch.get(j);
                final String transText = (j < translatedLines.size() && !translatedLines.get(j).isEmpty())
                        ? translatedLines.get(j)
                        : orig.getText();
                translatedList.add(new SubtitleItem(orig.getStartMs(), orig.getEndMs(), transText));
            }
        }

        return translatedList;
    }

    @NonNull
    private static List<String> translateBatch(
            @NonNull final OkHttpClient client,
            @NonNull final String combinedText,
            @NonNull final String targetLang) {

        // 🛡 双端点容灾：dict-chrome-ex 抗限流更稳（gtx 在部分出口 IP 上会 429）
        for (final String clientName : new String[] {"dict-chrome-ex", "gtx"}) {
            final List<String> lines = translateBatchWithClient(
                    client, combinedText, targetLang, clientName);
            if (!lines.isEmpty()) {
                return lines;
            }
        }
        return Collections.emptyList();
    }

    @NonNull
    private static List<String> translateBatchWithClient(
            @NonNull final OkHttpClient client,
            @NonNull final String combinedText,
            @NonNull final String targetLang,
            @NonNull final String googleClient) {

        final List<String> lines = new ArrayList<>();
        try {
            final String encoded = URLEncoder.encode(combinedText, "UTF-8");
            final String url = "https://translate.googleapis.com/translate_a/single?client="
                    + googleClient + "&sl=auto&tl=" + targetLang + "&dt=t&q=" + encoded;

            final String respJson = fetchString(client, url);
            if (respJson != null && respJson.startsWith("[")) {
                final JSONArray rootArray = new JSONArray(respJson);
                final JSONArray sentencesArray = rootArray.optJSONArray(0);
                if (sentencesArray != null) {
                    final StringBuilder fullTrans = new StringBuilder();
                    for (int k = 0; k < sentencesArray.length(); k++) {
                        final JSONArray item = sentencesArray.optJSONArray(k);
                        if (item != null && item.length() > 0) {
                            fullTrans.append(item.optString(0, ""));
                        }
                    }
                    final String[] splitLines = fullTrans.toString().split("\n");
                    Collections.addAll(lines, splitLines);
                }
            }
        } catch (final Exception e) {
            Log.w(TAG, "Google Translate batch failed (" + googleClient + "): " + e.getMessage());
        }
        return lines;
    }

    @Nullable
    private static String fetchString(@NonNull final OkHttpClient client, @NonNull final String url) {
        final Request request = new Request.Builder()
                .url(url)
                .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36")
                .header("Referer", "https://www.youtube.com/")
                .build();
        try (Response response = client.newCall(request).execute()) {
            if (response.isSuccessful() && response.body() != null) {
                return response.body().string();
            } else {
                Log.w(TAG, "Failed response code " + response.code() + " for " + url);
            }
        } catch (final IOException e) {
            Log.e(TAG, "Network error fetching " + url + ": " + e.getMessage());
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
