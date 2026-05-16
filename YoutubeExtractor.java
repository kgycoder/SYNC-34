package com.sync.app;

import android.util.Log;
import org.json.JSONArray;
import org.json.JSONObject;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;

/**
 * YouTube 영상에서 오디오 스트림 URL을 추출합니다.
 * InnerTube API + player.js 시그니처 디코드 없이
 * android 클라이언트를 사용 (시그니처 불필요)
 */
public class YoutubeExtractor {

    private static final String TAG = "YTExtractor";
    private final OkHttpClient http;

    public YoutubeExtractor(OkHttpClient http) {
        this.http = http;
    }

    public static class StreamInfo {
        public String url;
        public int    itag;
        public String mimeType;
        public long   bitrate;
        public StreamInfo(String url, int itag, String mimeType, long bitrate) {
            this.url = url; this.itag = itag;
            this.mimeType = mimeType; this.bitrate = bitrate;
        }
    }

    /**
     * videoId → 오디오 스트림 URL 반환
     * 실패 시 null
     */
    public String extractAudioUrl(String videoId) {
        // 1차: Android 클라이언트 (시그니처 없음)
        String url = tryAndroidClient(videoId);
        if (url != null) return url;

        // 2차: iOS 클라이언트
        url = tryIosClient(videoId);
        if (url != null) return url;

        // 3차: TV 클라이언트
        return tryTvClient(videoId);
    }

    // ── Android 클라이언트 ─────────────────────────────────────────
    private String tryAndroidClient(String videoId) {
        try {
            JSONObject client = new JSONObject();
            client.put("clientName", "ANDROID");
            client.put("clientVersion", "19.09.37");
            client.put("androidSdkVersion", 30);
            client.put("hl", "ko");
            client.put("gl", "KR");

            JSONObject context = new JSONObject();
            context.put("client", client);

            JSONObject body = new JSONObject();
            body.put("context", context);
            body.put("videoId", videoId);
            body.put("params", "2AMBCgIQBg=="); // 오디오 전용 힌트

            String KEY = "AIzaSyA8eiZmM1FaDVjRy-df2KTyQ_vz_yYM39w";
            Request req = new okhttp3.Request.Builder()
                .url("https://www.youtube.com/youtubei/v1/player?key=" + KEY)
                .post(okhttp3.RequestBody.create(
                    body.toString(),
                    okhttp3.MediaType.parse("application/json")))
                .addHeader("User-Agent",
                    "com.google.android.youtube/19.09.37 (Linux; U; Android 11) gzip")
                .addHeader("X-YouTube-Client-Name", "3")
                .addHeader("X-YouTube-Client-Version", "19.09.37")
                .addHeader("Origin", "https://www.youtube.com")
                .build();

            try (Response resp = http.newCall(req).execute()) {
                if (!resp.isSuccessful() || resp.body() == null) return null;
                String json = new String(resp.body().bytes(), StandardCharsets.UTF_8);
                return pickBestAudio(json);
            }
        } catch (Exception e) {
            Log.w(TAG, "android client failed: " + e.getMessage());
            return null;
        }
    }

    // ── iOS 클라이언트 ─────────────────────────────────────────────
    private String tryIosClient(String videoId) {
        try {
            JSONObject client = new JSONObject();
            client.put("clientName", "IOS");
            client.put("clientVersion", "19.09.3");
            client.put("deviceModel", "iPhone16,2");
            client.put("hl", "ko");
            client.put("gl", "KR");

            JSONObject context = new JSONObject();
            context.put("client", client);

            JSONObject body = new JSONObject();
            body.put("context", context);
            body.put("videoId", videoId);

            String KEY = "AIzaSyB-63vPrdThhKuerbB2N_l7Kwwcxj6yUAc";
            Request req = new okhttp3.Request.Builder()
                .url("https://www.youtube.com/youtubei/v1/player?key=" + KEY)
                .post(okhttp3.RequestBody.create(
                    body.toString(),
                    okhttp3.MediaType.parse("application/json")))
                .addHeader("User-Agent",
                    "com.google.ios.youtube/19.09.3 (iPhone16,2; U; CPU iOS 17_4 like Mac OS X)")
                .addHeader("X-YouTube-Client-Name", "5")
                .addHeader("X-YouTube-Client-Version", "19.09.3")
                .build();

            try (Response resp = http.newCall(req).execute()) {
                if (!resp.isSuccessful() || resp.body() == null) return null;
                String json = new String(resp.body().bytes(), StandardCharsets.UTF_8);
                return pickBestAudio(json);
            }
        } catch (Exception e) {
            Log.w(TAG, "ios client failed: " + e.getMessage());
            return null;
        }
    }

    // ── TV 클라이언트 ──────────────────────────────────────────────
    private String tryTvClient(String videoId) {
        try {
            JSONObject client = new JSONObject();
            client.put("clientName", "TVHTML5_SIMPLY_EMBEDDED_PLAYER");
            client.put("clientVersion", "2.0");
            client.put("hl", "ko");
            client.put("gl", "KR");

            JSONObject context = new JSONObject();
            context.put("client", client);

            JSONObject thirdParty = new JSONObject();
            thirdParty.put("embedUrl", "https://www.youtube.com/");
            context.put("thirdParty", thirdParty);

            JSONObject body = new JSONObject();
            body.put("context", context);
            body.put("videoId", videoId);

            Request req = new okhttp3.Request.Builder()
                .url("https://www.youtube.com/youtubei/v1/player" +
                     "?key=AIzaSyAO_FJ2SlqU8Q4STEHLGCilw_Y9_11qcW8")
                .post(okhttp3.RequestBody.create(
                    body.toString(),
                    okhttp3.MediaType.parse("application/json")))
                .addHeader("Origin", "https://www.youtube.com")
                .addHeader("Referer", "https://www.youtube.com/")
                .build();

            try (Response resp = http.newCall(req).execute()) {
                if (!resp.isSuccessful() || resp.body() == null) return null;
                String json = new String(resp.body().bytes(), StandardCharsets.UTF_8);
                return pickBestAudio(json);
            }
        } catch (Exception e) {
            Log.w(TAG, "tv client failed: " + e.getMessage());
            return null;
        }
    }

    // ── 최적 오디오 포맷 선택 ──────────────────────────────────────
    private String pickBestAudio(String json) {
        try {
            JSONObject doc = new JSONObject(json);

            // 재생 불가 체크
            JSONObject playability = doc.optJSONObject("playabilityStatus");
            if (playability != null) {
                String status = playability.optString("status","");
                if ("ERROR".equals(status) || "LOGIN_REQUIRED".equals(status) ||
                    "UNPLAYABLE".equals(status)) {
                    Log.w(TAG, "Unplayable: " + status);
                    return null;
                }
            }

            JSONObject streaming = doc
                .optJSONObject("streamingData");
            if (streaming == null) return null;

            JSONArray formats = streaming.optJSONArray("adaptiveFormats");
            if (formats == null) formats = streaming.optJSONArray("formats");
            if (formats == null) return null;

            // 오디오 전용 포맷 수집
            List<StreamInfo> audioStreams = new ArrayList<>();
            for (int i = 0; i < formats.length(); i++) {
                JSONObject f = formats.getJSONObject(i);
                String mime = f.optString("mimeType","");
                if (!mime.startsWith("audio/")) continue;

                // url 또는 signatureCipher/cipher
                String url = f.optString("url","");
                if (url.isEmpty()) {
                    String cipher = f.optString("signatureCipher","");
                    if (cipher.isEmpty()) cipher = f.optString("cipher","");
                    if (!cipher.isEmpty()) {
                        url = extractUrlFromCipher(cipher);
                    }
                }
                if (url.isEmpty()) continue;

                int  itag    = f.optInt("itag", 0);
                long bitrate = f.optLong("bitrate", 0);
                audioStreams.add(new StreamInfo(url, itag, mime, bitrate));
            }

            if (audioStreams.isEmpty()) {
                // adaptive 없으면 일반 formats에서 오디오 포함 포맷 사용
                JSONArray allFormats = streaming.optJSONArray("formats");
                if (allFormats != null) {
                    for (int i = 0; i < allFormats.length(); i++) {
                        JSONObject f = allFormats.getJSONObject(i);
                        String url = f.optString("url","");
                        if (!url.isEmpty()) return url;
                    }
                }
                return null;
            }

            // 우선순위: opus(webm) > m4a > 그 외, 비트레이트 높은 것
            // itag 251=opus 160kbps, 250=opus 70kbps, 249=opus 48kbps
            // itag 140=m4a 128kbps, 139=m4a 48kbps
            StreamInfo best = null;
            long bestScore = -1;
            for (StreamInfo s : audioStreams) {
                long score = s.bitrate;
                // m4a 선호 (Android MediaPlayer 호환성)
                if (s.mimeType.contains("mp4a") || s.mimeType.contains("m4a"))
                    score += 500000;
                if (s.itag == 140) score += 1000000; // m4a 128k 최우선
                if (score > bestScore) { bestScore = score; best = s; }
            }
            return best != null ? best.url : null;

        } catch (Exception e) {
            Log.e(TAG, "pickBestAudio error", e);
            return null;
        }
    }

    private String extractUrlFromCipher(String cipher) {
        try {
            String[] parts = cipher.split("&");
            for (String p : parts) {
                if (p.startsWith("url="))
                    return URLDecoder.decode(p.substring(4), "UTF-8");
            }
        } catch (Exception ignored) {}
        return "";
    }
}
