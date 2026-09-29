package org.schabi.newpipe.player.dualsubtitles;

import android.util.Base64;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import org.json.JSONArray;
import org.json.JSONObject;
import org.schabi.newpipe.DownloaderImpl;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;

/**
 * YouTube 交互式逐字稿 (Transcript) 抓取器
 *
 * <p>面向 PBS 等「创作者的常规 CC 字幕轨被关闭，但网页侧边栏提供交互式逐字稿」的视频。
 * 此类视频既没有 captionTracks，也没有 timedtext 接口可用，必须走新版
 * {@code youtubei/v1/get_panel} 面板接口：</p>
 *
 * <pre>
 * POST https://www.youtube.com/youtubei/v1/get_panel?prettyPrint=false
 * body = {
 *   "context": { "client": { clientName: "WEB", clientVersion, visitorData, ... } },
 *   "panelId": "PAmodern_transcript_view",
 *   "params":  protobuf{ field149 { field1: videoId, field3: 2 } } -> base64url(no padding)
 * }
 * </pre>
 *
 * <p>响应里包含一串 {@code transcriptSegmentViewModel}，形如
 * {@code {"simpleText":"...","timestamp":"0:18"}}，即带时间戳的逐字稿段落。</p>
 */
public final class YouTubeTranscriptFetcher {
    private static final String TAG = "YTTranscriptFetcher";

    private static final String WATCH_URL = "https://www.youtube.com/watch?v=";
    private static final String GET_PANEL_URL =
            "https://www.youtube.com/youtubei/v1/get_panel?prettyPrint=false";
    private static final String PANEL_ID = "PAmodern_transcript_view";
    private static final String DESKTOP_UA =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 "
                    + "(KHTML, like Gecko) Chrome/126.0.0.0 Safari/537.36";

    private static final Pattern VISITOR_PATTERN =
            Pattern.compile("\"VISITOR_DATA\":\"([^\"]+)\"");
    private static final Pattern CLIENT_VERSION_PATTERN =
            Pattern.compile("\"INNERTUBE_CLIENT_VERSION\":\"([^\"]+)\"");
    /** 形如 0:18 / 12:05 / 1:02:33 */
    private static final Pattern TS_PATTERN = Pattern.compile("(\\d+):(\\d{2})(?::(\\d{2}))?");

    private YouTubeTranscriptFetcher() {
    }

    /**
     * 抓取指定视频的逐字稿并按时间轴换算为字幕条目
     *
     * @param client  OkHttp 客户端（复用 NewPipe DownloaderImpl，天然继承代理与 Cookie 配置）
     * @param videoId YouTube 视频 ID
     * @return 逐字稿字幕条目；不可用或失败时返回空列表
     */
    @NonNull
    public static List<SubtitleItem> fetchTranscript(
            @NonNull final OkHttpClient client,
            @NonNull final String videoId) {

        final List<SubtitleItem> result = new ArrayList<>();
        try {
            final String watchUrl = WATCH_URL + videoId;

            // 1. 拉取 watch 页面，取出 visitorData 与 Innertube clientVersion
            final String html = getString(client, new Request.Builder()
                    .url(watchUrl)
                    .header("User-Agent", DESKTOP_UA)
                    .header("Accept-Language", "en-US,en;q=0.9")
                    .header("Cookie", "CONSENT=YES+1; SOCS=CAI")
                    .build());
            if (html == null || html.isEmpty()) {
                Log.w(TAG, "Watch page empty for " + videoId);
                return result;
            }

            final String visitorData = firstGroup(VISITOR_PATTERN, html);
            final String clientVersion = firstGroup(CLIENT_VERSION_PATTERN, html);
            if (visitorData == null || clientVersion == null) {
                Log.w(TAG, "Missing visitorData/clientVersion for " + videoId);
                return result;
            }

            // 2. 构造 protobuf params 并请求面板接口
            final String params = buildPanelParams(videoId);
            final JSONObject clientContext = new JSONObject()
                    .put("hl", "en")
                    .put("gl", "US")
                    .put("clientName", "WEB")
                    .put("clientVersion", clientVersion)
                    .put("visitorData", visitorData)
                    .put("userAgent", DESKTOP_UA + ",gzip(gfe)")
                    .put("originalUrl", watchUrl)
                    .put("platform", "DESKTOP")
                    .put("clientFormFactor", "UNKNOWN_FORM_FACTOR");

            final JSONObject payload = new JSONObject()
                    .put("context", new JSONObject()
                            .put("client", clientContext)
                            .put("user", new JSONObject().put("lockedSafetyMode", false))
                            .put("request", new JSONObject().put("useSsl", true)))
                    .put("panelId", PANEL_ID)
                    .put("params", params);

            final String body = getString(client, new Request.Builder()
                    .url(GET_PANEL_URL)
                    .header("User-Agent", DESKTOP_UA)
                    .header("Content-Type", "application/json")
                    .header("X-Goog-Visitor-Id", visitorData)
                    .header("X-Youtube-Client-Name", "1")
                    .header("X-Youtube-Client-Version", clientVersion)
                    .header("Origin", "https://www.youtube.com")
                    .header("Referer", watchUrl)
                    .header("Cookie", "CONSENT=YES+1; SOCS=CAI")
                    .post(RequestBody.create(body(payload),
                            MediaType.parse("application/json; charset=utf-8")))
                    .build());
            if (body == null || body.isEmpty()) {
                Log.w(TAG, "Empty get_panel response for " + videoId);
                return result;
            }

            // 3. 解析 transcriptSegmentViewModel 段落
            final List<long[]> starts = new ArrayList<>();
            final List<String> texts = new ArrayList<>();
            collectSegments(new JSONObject(body), starts, texts);
            if (texts.isEmpty()) {
                Log.i(TAG, "No transcript segments found for " + videoId);
                return result;
            }

            // 4. 每段结束时间 = 下一段开始时间（末段给 5 秒余量）
            for (int i = 0; i < texts.size(); i++) {
                final long startMs = starts.get(i)[0];
                final long endMs = (i + 1 < texts.size() && starts.get(i + 1)[0] > startMs)
                        ? starts.get(i + 1)[0]
                        : startMs + 5000L;
                result.add(new SubtitleItem(startMs, endMs, texts.get(i)));
            }

            Log.i(TAG, "Transcript fetched for " + videoId + ": " + result.size() + " segments");
        } catch (final Exception e) {
            Log.e(TAG, "Failed to fetch transcript for " + videoId, e);
        }
        return result;
    }

    /**
     * 递归扫描 JSON 树，抽取所有 transcriptSegmentViewModel 的时间戳与文本
     */
    private static void collectSegments(@NonNull final Object node,
                                        @NonNull final List<long[]> starts,
                                        @NonNull final List<String> texts) {
        if (node instanceof JSONObject) {
            final JSONObject obj = (JSONObject) node;
            final JSONObject seg = obj.optJSONObject("transcriptSegmentViewModel");
            if (seg != null) {
                final String text = seg.optString("simpleText", "").trim();
                final long startMs = parseTimestampMs(seg.optString("timestamp", ""));
                if (!text.isEmpty() && startMs >= 0) {
                    starts.add(new long[] {startMs});
                    texts.add(text);
                }
            }
            final JSONArray names = obj.names();
            if (names != null) {
                for (int i = 0; i < names.length(); i++) {
                    collectSegments(obj.opt(names.optString(i)), starts, texts);
                }
            }
        } else if (node instanceof JSONArray) {
            final JSONArray arr = (JSONArray) node;
            for (int i = 0; i < arr.length(); i++) {
                collectSegments(arr.opt(i), starts, texts);
            }
        }
    }

    /**
     * "0:18" -> 18000 / "1:02:33" -> 3753000
     */
    private static long parseTimestampMs(@NonNull final String raw) {
        final Matcher m = TS_PATTERN.matcher(raw.trim());
        if (!m.find()) {
            return -1L;
        }
        final long first = Long.parseLong(m.group(1));
        final long second = Long.parseLong(m.group(2));
        final String third = m.group(3);
        long seconds;
        if (third != null) {
            seconds = first * 3600L + second * 60L + Long.parseLong(third);
        } else {
            seconds = first * 60L + second;
        }
        return seconds * 1000L;
    }

    /**
     * 构造面板 params：protobuf{ field149 { field1: videoId(string), field3: 2(varint) } }
     * 再以 URL-Safe Base64（无填充）编码
     */
    @NonNull
    private static String buildPanelParams(@NonNull final String videoId) {
        final byte[] videoIdBytes = videoId.getBytes(StandardCharsets.UTF_8);
        final ByteArrayOutputStream inner = new ByteArrayOutputStream();
        inner.write(0x0A); // field 1, wire type 2
        writeVarint(inner, videoIdBytes.length);
        inner.write(videoIdBytes, 0, videoIdBytes.length);
        inner.write(0x18); // field 3, wire type 0
        inner.write(0x02);

        final ByteArrayOutputStream outer = new ByteArrayOutputStream();
        // field 149, wire type 2 -> key = (149 << 3) | 2
        writeVarint(outer, (149 << 3) | 2);
        writeVarint(outer, inner.size());
        outer.write(inner.toByteArray(), 0, inner.size());

        return Base64.encodeToString(outer.toByteArray(),
                Base64.URL_SAFE | Base64.NO_WRAP | Base64.NO_PADDING);
    }

    private static void writeVarint(@NonNull final ByteArrayOutputStream out, int value) {
        while (true) {
            if ((value & ~0x7F) == 0) {
                out.write(value);
                return;
            }
            out.write((value & 0x7F) | 0x80);
            value >>>= 7;
        }
    }

    @NonNull
    private static byte[] body(@NonNull final JSONObject json) {
        return json.toString().getBytes(StandardCharsets.UTF_8);
    }

    @Nullable
    private static String firstGroup(@NonNull final Pattern pattern, @NonNull final String text) {
        final Matcher m = pattern.matcher(text);
        return m.find() ? m.group(1) : null;
    }

    @Nullable
    private static String getString(@NonNull final OkHttpClient client,
                                    @NonNull final Request request) {
        try (Response response = client.newCall(request).execute()) {
            if (response.isSuccessful() && response.body() != null) {
                return response.body().string();
            }
            Log.w(TAG, "HTTP " + response.code() + " for " + request.url());
        } catch (final Exception e) {
            Log.w(TAG, "Request failed: " + e.getMessage());
        }
        return null;
    }

    /** 便捷入口：内部自行获取全局 OkHttp 客户端 */
    @NonNull
    public static List<SubtitleItem> fetch(@NonNull final String videoId) {
        return fetchTranscript(DownloaderImpl.getInstance().getClient(), videoId);
    }
}
