package com.sync.app;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.media.AudioAttributes;
import android.media.AudioFocusRequest;
import android.media.AudioManager;
import android.media.MediaPlayer;
import android.os.Binder;
import android.os.Build;
import android.os.IBinder;
import android.support.v4.media.MediaMetadataCompat;
import android.support.v4.media.session.MediaSessionCompat;
import android.support.v4.media.session.PlaybackStateCompat;
import android.util.Log;

import androidx.core.app.NotificationCompat;
import androidx.media.app.NotificationCompat.MediaStyle;

import java.io.InputStream;
import java.net.URL;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class MusicService extends Service {

    private static final String TAG = "SYNCService";
    public static final String ACTION_PLAY   = "com.sync.app.PLAY";
    public static final String ACTION_PAUSE  = "com.sync.app.PAUSE";
    public static final String ACTION_NEXT   = "com.sync.app.NEXT";
    public static final String ACTION_PREV   = "com.sync.app.PREV";
    public static final String ACTION_STOP   = "com.sync.app.STOP";

    private static final String CH_ID   = "sync_music";
    private static final int    NOTIF_ID = 1;

    // MediaPlayer — WebView 대신 직접 재생
    private MediaPlayer mediaPlayer;
    private AudioManager audioManager;
    private AudioFocusRequest audioFocusRequest;

    private MediaSessionCompat mediaSession;
    private final IBinder binder = new MusicBinder();
    private final ExecutorService executor = Executors.newCachedThreadPool();

    // 트랙 상태
    private String currentTitle   = "SYNC";
    private String currentArtist  = "";
    private String currentThumbUrl = "";
    private boolean isPlaying     = false;
    private long    duration      = 0;
    private long    position      = 0;
    private String  currentStreamUrl = "";

    // MainActivity 콜백
    private Runnable onNextCallback;
    private Runnable onPrevCallback;
    // JS UI 동기화 콜백
    private PlaybackCallback playbackCallback;

    public interface PlaybackCallback {
        void onPlay();
        void onPause();
        void onEnded();
        void onProgress(long posMs, long durMs);
        void onError(String msg);
    }

    public class MusicBinder extends Binder {
        public MusicService getService() { return MusicService.this; }
    }

    @Override
    public void onCreate() {
        super.onCreate();
        audioManager = (AudioManager) getSystemService(AUDIO_SERVICE);
        createNotificationChannel();
        initMediaSession();
        startForeground(NOTIF_ID, buildNotification(null));
    }

    @Override
    public IBinder onBind(Intent intent) { return binder; }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null && intent.getAction() != null) {
            switch (intent.getAction()) {
                case ACTION_PLAY:  resumePlay(); break;
                case ACTION_PAUSE: pause();      break;
                case ACTION_NEXT:  if (onNextCallback != null) onNextCallback.run(); break;
                case ACTION_PREV:  if (onPrevCallback != null) onPrevCallback.run(); break;
                case ACTION_STOP:  fullStop();   break;
            }
        }
        return START_STICKY;
    }

    // ── 공개 API ────────────────────────────────────────────────────

    public void setCallbacks(Runnable next, Runnable prev,
                             PlaybackCallback cb) {
        onNextCallback   = next;
        onPrevCallback   = prev;
        playbackCallback = cb;
    }

    /** YouTube 스트림 URL을 받아 MediaPlayer로 재생 */
    public void playUrl(String streamUrl, String title, String artist,
                        String thumbUrl, long durationMs) {
        currentTitle     = title;
        currentArtist    = artist;
        currentThumbUrl  = thumbUrl;
        duration         = durationMs;
        currentStreamUrl = streamUrl;

        executor.submit(() -> {
            try {
                requestAudioFocus();

                if (mediaPlayer != null) {
                    mediaPlayer.stop();
                    mediaPlayer.release();
                    mediaPlayer = null;
                }

                mediaPlayer = new MediaPlayer();
                mediaPlayer.setAudioAttributes(new AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                    .build());

                mediaPlayer.setDataSource(streamUrl);
                mediaPlayer.prepareAsync();

                mediaPlayer.setOnPreparedListener(mp -> {
                    mp.start();
                    isPlaying = true;
                    updatePlaybackState();
                    updateNotification(null);
                    loadThumbAndNotify(thumbUrl);
                    if (playbackCallback != null) playbackCallback.onPlay();
                    startProgressUpdater();
                });

                mediaPlayer.setOnCompletionListener(mp -> {
                    isPlaying = false;
                    updatePlaybackState();
                    updateNotification(null);
                    if (playbackCallback != null) playbackCallback.onEnded();
                    if (onNextCallback != null) onNextCallback.run();
                });

                mediaPlayer.setOnErrorListener((mp, what, extra) -> {
                    Log.e(TAG, "MediaPlayer error: " + what + "/" + extra);
                    if (playbackCallback != null)
                        playbackCallback.onError("재생 오류: " + what);
                    return false;
                });

            } catch (Exception e) {
                Log.e(TAG, "playUrl error", e);
                if (playbackCallback != null)
                    playbackCallback.onError(e.getMessage());
            }
        });

        // 메타데이터 즉시 업데이트
        updateMetadata(null);
        updateNotification(null);
    }

    public void pause() {
        if (mediaPlayer != null && mediaPlayer.isPlaying()) {
            mediaPlayer.pause();
            isPlaying = false;
            updatePlaybackState();
            updateNotification(null);
            if (playbackCallback != null) playbackCallback.onPause();
        }
    }

    public void resumePlay() {
        if (mediaPlayer != null && !mediaPlayer.isPlaying()) {
            requestAudioFocus();
            mediaPlayer.start();
            isPlaying = true;
            updatePlaybackState();
            updateNotification(null);
            if (playbackCallback != null) playbackCallback.onPlay();
        }
    }

    public void seekTo(long posMs) {
        if (mediaPlayer != null) {
            mediaPlayer.seekTo((int) posMs);
            position = posMs;
            updatePlaybackState();
        }
    }

    public boolean isPlaying() { return isPlaying; }
    public long getCurrentPosition() {
        if (mediaPlayer != null) {
            try { return mediaPlayer.getCurrentPosition(); } catch (Exception e) {}
        }
        return 0;
    }
    public long getDuration() {
        if (mediaPlayer != null) {
            try { return mediaPlayer.getDuration(); } catch (Exception e) {}
        }
        return duration;
    }

    public void updateTrackMeta(String title, String artist,
                                String thumbUrl, long durationMs) {
        currentTitle    = title;
        currentArtist   = artist;
        currentThumbUrl = thumbUrl;
        duration        = durationMs;
        updateMetadata(null);
        updateNotification(null);
        loadThumbAndNotify(thumbUrl);
    }

    public MediaSessionCompat.Token getSessionToken() {
        return mediaSession.getSessionToken();
    }

    // ── 진행 상황 폴링 ───────────────────────────────────────────────
    private final android.os.Handler progressHandler =
        new android.os.Handler(android.os.Looper.getMainLooper());
    private final Runnable progressRunnable = new Runnable() {
        @Override public void run() {
            if (mediaPlayer != null && isPlaying) {
                try {
                    long pos = mediaPlayer.getCurrentPosition();
                    long dur = mediaPlayer.getDuration();
                    position = pos;
                    if (dur > 0) duration = dur;
                    updatePlaybackState();
                    if (playbackCallback != null)
                        playbackCallback.onProgress(pos, dur);
                } catch (Exception ignored) {}
                progressHandler.postDelayed(this, 500);
            }
        }
    };

    private void startProgressUpdater() {
        progressHandler.removeCallbacks(progressRunnable);
        progressHandler.postDelayed(progressRunnable, 500);
    }

    // ── 오디오 포커스 ────────────────────────────────────────────────
    private void requestAudioFocus() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            audioFocusRequest = new AudioFocusRequest.Builder(
                AudioManager.AUDIOFOCUS_GAIN)
                .setAudioAttributes(new AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                    .build())
                .setOnAudioFocusChangeListener(focus -> {
                    if (focus == AudioManager.AUDIOFOCUS_LOSS) pause();
                    else if (focus == AudioManager.AUDIOFOCUS_GAIN) resumePlay();
                })
                .build();
            audioManager.requestAudioFocus(audioFocusRequest);
        } else {
            //noinspection deprecation
            audioManager.requestAudioFocus(null,
                AudioManager.STREAM_MUSIC, AudioManager.AUDIOFOCUS_GAIN);
        }
    }

    // ── MediaSession ─────────────────────────────────────────────────
    private void initMediaSession() {
        mediaSession = new MediaSessionCompat(this, "SYNCSession");
        mediaSession.setFlags(
            MediaSessionCompat.FLAG_HANDLES_MEDIA_BUTTONS |
            MediaSessionCompat.FLAG_HANDLES_TRANSPORT_CONTROLS);
        mediaSession.setCallback(new MediaSessionCompat.Callback() {
            @Override public void onPlay()  { resumePlay(); }
            @Override public void onPause() { pause(); }
            @Override public void onSkipToNext()     {
                if (onNextCallback != null) onNextCallback.run(); }
            @Override public void onSkipToPrevious() {
                if (onPrevCallback != null) onPrevCallback.run(); }
            @Override public void onStop()  { fullStop(); }
            @Override public void onSeekTo(long pos) { seekTo(pos); }
        });
        mediaSession.setActive(true);
        updatePlaybackState();
    }

    private void updatePlaybackState() {
        int state = isPlaying
            ? PlaybackStateCompat.STATE_PLAYING
            : PlaybackStateCompat.STATE_PAUSED;
        PlaybackStateCompat ps = new PlaybackStateCompat.Builder()
            .setActions(
                PlaybackStateCompat.ACTION_PLAY |
                PlaybackStateCompat.ACTION_PAUSE |
                PlaybackStateCompat.ACTION_PLAY_PAUSE |
                PlaybackStateCompat.ACTION_SKIP_TO_NEXT |
                PlaybackStateCompat.ACTION_SKIP_TO_PREVIOUS |
                PlaybackStateCompat.ACTION_SEEK_TO)
            .setState(state, position, 1.0f)
            .build();
        mediaSession.setPlaybackState(ps);
    }

    private void updateMetadata(Bitmap art) {
        MediaMetadataCompat.Builder mb = new MediaMetadataCompat.Builder()
            .putString(MediaMetadataCompat.METADATA_KEY_TITLE,  currentTitle)
            .putString(MediaMetadataCompat.METADATA_KEY_ARTIST, currentArtist)
            .putLong(MediaMetadataCompat.METADATA_KEY_DURATION, duration);
        if (art != null)
            mb.putBitmap(MediaMetadataCompat.METADATA_KEY_ALBUM_ART, art);
        mediaSession.setMetadata(mb.build());
    }

    private void loadThumbAndNotify(String thumbUrl) {
        if (thumbUrl == null || thumbUrl.isEmpty()) return;
        executor.submit(() -> {
            Bitmap bmp = loadBitmap(thumbUrl);
            updateMetadata(bmp);
            updateNotification(bmp);
        });
    }

    // ── 알림 ─────────────────────────────────────────────────────────
    private void updateNotification(Bitmap art) {
        NotificationManager nm = getSystemService(NotificationManager.class);
        if (nm != null) nm.notify(NOTIF_ID, buildNotification(art));
    }

    private Notification buildNotification(Bitmap art) {
        Intent openIntent = new Intent(this, MainActivity.class);
        openIntent.setFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP);
        PendingIntent openPi = PendingIntent.getActivity(this, 0, openIntent,
            PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);

        PendingIntent prevPi  = actionPi(ACTION_PREV,  1);
        PendingIntent playPi  = actionPi(isPlaying ? ACTION_PAUSE : ACTION_PLAY, 2);
        PendingIntent nextPi  = actionPi(ACTION_NEXT,  3);

        int playIcon = isPlaying
            ? android.R.drawable.ic_media_pause
            : android.R.drawable.ic_media_play;

        NotificationCompat.Builder nb = new NotificationCompat.Builder(this, CH_ID)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle(currentTitle.isEmpty() ? "SYNC" : currentTitle)
            .setContentText(currentArtist.isEmpty() ? "음악 재생 중" : currentArtist)
            .setContentIntent(openPi)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setOnlyAlertOnce(true)
            .setOngoing(isPlaying)
            .addAction(android.R.drawable.ic_media_previous, "이전", prevPi)
            .addAction(playIcon, isPlaying ? "일시정지" : "재생", playPi)
            .addAction(android.R.drawable.ic_media_next, "다음", nextPi)
            .setStyle(new MediaStyle()
                .setMediaSession(mediaSession.getSessionToken())
                .setShowActionsInCompactView(0, 1, 2));

        if (art != null) nb.setLargeIcon(art);
        return nb.build();
    }

    private PendingIntent actionPi(String action, int reqCode) {
        Intent i = new Intent(this, MusicService.class);
        i.setAction(action);
        return PendingIntent.getService(this, reqCode, i,
            PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
    }

    private Bitmap loadBitmap(String url) {
        try (InputStream is = new URL(url).openStream()) {
            return BitmapFactory.decodeStream(is);
        } catch (Exception e) { return null; }
    }

    private void createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel ch = new NotificationChannel(
                CH_ID, "SYNC 음악 재생", NotificationManager.IMPORTANCE_LOW);
            ch.setDescription("백그라운드 음악 재생");
            ch.setShowBadge(false);
            getSystemService(NotificationManager.class).createNotificationChannel(ch);
        }
    }

    private void fullStop() {
        isPlaying = false;
        progressHandler.removeCallbacks(progressRunnable);
        if (mediaPlayer != null) {
            try { mediaPlayer.stop(); mediaPlayer.release(); } catch (Exception ignored) {}
            mediaPlayer = null;
        }
        mediaSession.setActive(false);
        stopForeground(true);
        stopSelf();
    }

    @Override
    public void onTaskRemoved(Intent rootIntent) {
        if (!isPlaying) fullStop();
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        progressHandler.removeCallbacks(progressRunnable);
        if (mediaPlayer != null) {
            try { mediaPlayer.release(); } catch (Exception ignored) {}
        }
        mediaSession.setActive(false);
        mediaSession.release();
        executor.shutdown();
    }
}
