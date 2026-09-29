package org.schabi.newpipe.player.dualsubtitles;

import android.text.Html;
import android.util.Log;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.StringReader;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 强大且高效的字幕解析器，移植并增强自 YouTube Dual Subs Pro v6.0
 * 兼容 SRV3 JSON、XML Transcript、WebVTT
 */
public final class DualSubtitleParser {
    private static final String TAG = "DualSubtitleParser";

    private static final Pattern VTT_TIME_PATTERN = Pattern.compile(
            "((?:(\\d+):)?(\\d{2}):(\\d{2})[.,](\\d{3}))\\s*-->\\s*((?:(\\d+):)?(\\d{2}):(\\d{2})[.,](\\d{3}))");
    private static final Pattern XML_TEXT_PATTERN = Pattern.compile(
            "<text[^>]*?start=\"([^\"]+)\"[^>]*?(?:dur=\"([^\"]+)\")?[^>]*?>([\\s\\S]*?)</text>",
            Pattern.CASE_INSENSITIVE);

    private DualSubtitleParser() {
    }

    /**
     * 智能自动检测并解析字幕原始文本
     */
    @NonNull
    public static List<SubtitleItem> parse(@Nullable final String rawContent) {
        if (rawContent == null || rawContent.trim().isEmpty()) {
            return Collections.emptyList();
        }

        final String trimmed = rawContent.trim();

        // 1. YouTube SRV3 JSON 格式
        if (trimmed.startsWith("{") && trimmed.contains("\"events\"")) {
            final List<SubtitleItem> result = parseJson3(trimmed);
            if (!result.isEmpty()) {
                return result;
            }
        }

        // 2. YouTube XML 格式
        if (trimmed.contains("<text") || trimmed.contains("<transcript")) {
            final List<SubtitleItem> result = parseXml(trimmed);
            if (!result.isEmpty()) {
                return result;
            }
        }

        // 3. WebVTT 格式 (或标准 SRT 格式)
        if (trimmed.contains("-->")) {
            final List<SubtitleItem> result = parseVtt(trimmed);
            if (!result.isEmpty()) {
                return result;
            }
        }

        Log.w(TAG, "Unrecognized subtitle format. Length: " + rawContent.length());
        return Collections.emptyList();
    }

    /**
     * 解析 YouTube SRV3 (json3) 格式
     */
    @NonNull
    public static List<SubtitleItem> parseJson3(@NonNull final String jsonStr) {
        final List<SubtitleItem> items = new ArrayList<>();
        try {
            final JSONObject root = new JSONObject(jsonStr);
            final JSONArray events = root.optJSONArray("events");
            if (events == null) {
                return items;
            }

            for (int i = 0; i < events.length(); i++) {
                final JSONObject event = events.optJSONObject(i);
                if (event == null) {
                    continue;
                }

                if (!event.has("tStartMs")) {
                    continue;
                }

                final long startMs = event.optLong("tStartMs", 0);
                final long durMs = event.optLong("dDurationMs", 3000);
                final long endMs = startMs + durMs;

                final JSONArray segs = event.optJSONArray("segs");
                if (segs == null || segs.length() == 0) {
                    continue;
                }

                final StringBuilder textBuilder = new StringBuilder();
                for (int j = 0; j < segs.length(); j++) {
                    final JSONObject seg = segs.optJSONObject(j);
                    if (seg != null && seg.has("utf8")) {
                        textBuilder.append(seg.optString("utf8"));
                    }
                }

                final String cleanText = cleanSubtitleText(textBuilder.toString());
                if (!cleanText.isEmpty()) {
                    items.add(new SubtitleItem(startMs, endMs, cleanText));
                }
            }
        } catch (final Exception e) {
            Log.e(TAG, "Failed to parse json3 subtitles", e);
        }
        Collections.sort(items);
        return items;
    }

    /**
     * 解析 YouTube 经典 XML Transcript 格式
     */
    @NonNull
    public static List<SubtitleItem> parseXml(@NonNull final String xmlStr) {
        final List<SubtitleItem> items = new ArrayList<>();
        try {
            final Matcher matcher = XML_TEXT_PATTERN.matcher(xmlStr);
            while (matcher.find()) {
                final String startStr = matcher.group(1);
                final String durStr = matcher.group(2);
                final String rawText = matcher.group(3);

                final double startSec = startStr != null ? Double.parseDouble(startStr) : 0;
                final double durSec = durStr != null ? Double.parseDouble(durStr) : 3.0;

                final long startMs = Math.round(startSec * 1000);
                final long endMs = startMs + Math.round(durSec * 1000);

                final String cleanText = cleanSubtitleText(decodeHtml(rawText));
                if (!cleanText.isEmpty()) {
                    items.add(new SubtitleItem(startMs, endMs, cleanText));
                }
            }
        } catch (final Exception e) {
            Log.e(TAG, "Failed to parse XML subtitles", e);
        }
        Collections.sort(items);
        return items;
    }

    /**
     * 解析 WebVTT / SRT 格式
     */
    @NonNull
    public static List<SubtitleItem> parseVtt(@NonNull final String vttStr) {
        final List<SubtitleItem> items = new ArrayList<>();
        try (BufferedReader reader = new BufferedReader(new StringReader(vttStr))) {
            String line;
            long curStartMs = -1;
            long curEndMs = -1;
            final StringBuilder curText = new StringBuilder();

            while ((line = reader.readLine()) != null) {
                final String trimmed = line.trim();
                if (trimmed.isEmpty()) {
                    if (curStartMs >= 0 && curText.length() > 0) {
                        final String clean = cleanSubtitleText(curText.toString());
                        if (!clean.isEmpty()) {
                            items.add(new SubtitleItem(curStartMs, curEndMs, clean));
                        }
                        curStartMs = -1;
                        curEndMs = -1;
                        curText.setLength(0);
                    }
                    continue;
                }

                if (trimmed.startsWith("WEBVTT") || trimmed.startsWith("NOTE") || trimmed.startsWith("STYLE")) {
                    continue;
                }

                final Matcher timeMatcher = VTT_TIME_PATTERN.matcher(trimmed);
                if (timeMatcher.find()) {
                    if (curStartMs >= 0 && curText.length() > 0) {
                        final String clean = cleanSubtitleText(curText.toString());
                        if (!clean.isEmpty()) {
                            items.add(new SubtitleItem(curStartMs, curEndMs, clean));
                        }
                        curText.setLength(0);
                    }
                    curStartMs = parseVttTimestamp(timeMatcher.group(1));
                    curEndMs = parseVttTimestamp(timeMatcher.group(6));
                } else if (curStartMs >= 0) {
                    if (!trimmed.matches("^\\d+$")) { // 过滤纯序号行
                        if (curText.length() > 0) {
                            curText.append(" ");
                        }
                        curText.append(trimmed);
                    }
                }
            }

            // 处理最后一条
            if (curStartMs >= 0 && curText.length() > 0) {
                final String clean = cleanSubtitleText(curText.toString());
                if (!clean.isEmpty()) {
                    items.add(new SubtitleItem(curStartMs, curEndMs, clean));
                }
            }
        } catch (final Exception e) {
            Log.e(TAG, "Failed to parse VTT subtitles", e);
        }
        Collections.sort(items);
        return items;
    }

    private static long parseVttTimestamp(final String timestamp) {
        if (timestamp == null) {
            return 0;
        }
        final String normalized = timestamp.replace(',', '.').trim();
        final String[] parts = normalized.split(":");
        try {
            if (parts.length == 3) {
                final long hours = Long.parseLong(parts[0]);
                final long minutes = Long.parseLong(parts[1]);
                final double seconds = Double.parseDouble(parts[2]);
                return (hours * 3600 + minutes * 60) * 1000 + Math.round(seconds * 1000);
            } else if (parts.length == 2) {
                final long minutes = Long.parseLong(parts[0]);
                final double seconds = Double.parseDouble(parts[1]);
                return (minutes * 60) * 1000 + Math.round(seconds * 1000);
            }
        } catch (final Exception ignored) {
        }
        return 0;
    }

    private static String decodeHtml(final String text) {
        if (text == null) {
            return "";
        }
        return Html.fromHtml(text, Html.FROM_HTML_MODE_LEGACY).toString();
    }

    private static String cleanSubtitleText(final String text) {
        if (text == null) {
            return "";
        }
        return text.replaceAll("<[^>]*>", "") // 去除内联HTML标签
                .replace("\n", " ")
                .replaceAll("\\s+", " ")
                .trim();
    }
}
