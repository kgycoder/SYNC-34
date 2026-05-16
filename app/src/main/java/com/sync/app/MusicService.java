package com.sync.app;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.os.Binder;
import android.os.Build;
import android.os.IBinder;
import android.support.v4.media.MediaMetadataCompat;
import android.support.v4.media.session.MediaSessionCompat;
import android.support.v4.media.session.PlaybackStateCompat;

import androidx.core.app.NotificationCompat;
import androidx.media.app.NotificationCompat.MediaStyle;

import java.io.InputStream;
import java.net.URL;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class MusicService extends Service {

    public static final String ACTION_PLAY   = "com.sync.app.PLAY";
    public static final String ACTION_PAUSE  = "com.sync.app.PAUSE";
    public static final String ACTION_NEXT   = "com.sync.app.NEXT";
    public static final String ACTION_PREV   = "com.sync.app.PREV";
    public static final String ACTION_STOP   = "com.sync.app.STOP";

    private static final String CH_ID   = "sync_music";
    private static final int    NOTIF_ID = 1;

    private MediaSessionCompat mediaSession;
    private final IBinder binder = new MusicBinder();
    private final ExecutorService executor = Executors.newSingleThreadExecutor();

    // 현재 상태
    private String currentTitle   = "SYNC";
    private String currentArtist  = "";
    private String currentThumbUrl = "";
    private boolean isPlaying     = false;
    private long    duration      = 0;
    private long    position      = 0;

    // MainActivity 콜백
    private Runnable onPlayCallback;
    private Runnable onPauseCallback;
    private Runnable onNextCallback;
    private Runnable onPrevCallback;

    public class MusicBinder extends Binder {
        public MusicService getService() { return MusicService.this; }
    }

    @Override
    public void onCreate() {
        super.onCreate();
        createNotificationChannel();
        initMediaSession();
        startForeground(NOTIF_ID, buildNotification());
    }

    @Override
    public IBinder onBind(Intent intent) { return binder; }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent == null) return START_STICKY;
        String action = intent.getAction();
        if (action == null) return START_STICKY;
        switch (action) {
            case ACTION_PLAY:  if (onPlayCallback  != null) onPlayCallback.run();  break;
            case ACTION_PAUSE: if (onPauseCallback != null) onPauseCallback.run(); break;
            case ACTION_NEXT:  if (onNextCallback  != null) onNextCallback.run();  break;
            case ACTION_PREV:  if (onPrevCallback  != null) onPrevCallback.run();  break;
            case ACTION_STOP:  stopSelf(); break;
        }
        return START_STICKY;
    }

    // ── MediaSession 초기화 ──────────────────────────────
    private void initMediaSession() {
        mediaSession = new MediaSessionCompat(this, "SYNCSession");
        mediaSession.setFlags(
            MediaSessionCompat.FLAG_HANDLES_MEDIA_BUTTONS |
            MediaSessionCompat.FLAG_HANDLES_TRANSPORT_CONTROLS);

        mediaSession.setCallback(new MediaSessionCompat.Callback() {
            @Override public void onPlay()     { if (onPlayCallback  != null) onPlayCallback.run(); }
            @Override public void onPause()    { if (onPauseCallback != null) onPauseCallback.run(); }
            @Override public void onSkipToNext()     { if (onNextCallback != null) onNextCallback.run(); }
            @Override public void onSkipToPrevious() { if (onPrevCallback != null) onPrevCallback.run(); }
            @Override public void onStop()     { stopSelf(); }
            @Override public void onSeekTo(long pos) { /* WebView seek은 MainActivity에서 처리 */ }
        });
        mediaSession.setActive(true);
        updatePlaybackState();
    }

    // ── 공개 API (MainActivity에서 호출) ─────────────────
    public void setCallbacks(Runnable play, Runnable pause,
                             Runnable next, Runnable prev) {
        onPlayCallback  = play;
        onPauseCallback = pause;
        onNextCallback  = next;
        onPrevCallback  = prev;
    }

    public void updateTrackInfo(String title, String artist,
                                String thumbUrl, boolean playing,
                                long durationMs, long positionMs) {
        currentTitle    = title;
        currentArtist   = artist;
        currentThumbUrl = thumbUrl;
        isPlaying       = playing;
        duration        = durationMs;
        position        = positionMs;
        updatePlaybackState();
        updateMetadata(null);   // 텍스트만 먼저 업데이트
        updateNotification(null);

        // 썸네일 비동기 로드
        if (thumbUrl != null && !thumbUrl.isEmpty()) {
            executor.submit(() -> {
                Bitmap bmp = loadBitmap(thumbUrl);
                updateMetadata(bmp);
                updateNotification(bmp);
            });
        }
    }

    public void updatePosition(long positionMs) {
        position = positionMs;
        updatePlaybackState();
    }

    public MediaSessionCompat.Token getSessionToken() {
        return mediaSession.getSessionToken();
    }

    // ── 내부 업데이트 ────────────────────────────────────
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

    private void updateNotification(Bitmap art) {
        NotificationManager nm = getSystemService(NotificationManager.class);
        if (nm != null) nm.notify(NOTIF_ID, buildNotification(art));
    }

    private Notification buildNotification() {
        return buildNotification(null);
    }

    private Notification buildNotification(Bitmap art) {
        // 앱 열기 인텐트
        Intent openIntent = new Intent(this, MainActivity.class);
        openIntent.setFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP);
        PendingIntent openPi = PendingIntent.getActivity(
            this, 0, openIntent,
            PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);

        // 액션 인텐트들
        PendingIntent prevPi  = actionPi(ACTION_PREV,  1);
        PendingIntent playPi  = actionPi(isPlaying ? ACTION_PAUSE : ACTION_PLAY, 2);
        PendingIntent nextPi  = actionPi(ACTION_NEXT,  3);

        int playIcon = isPlaying
            ? android.R.drawable.ic_media_pause
            : android.R.drawable.ic_media_play;

        NotificationCompat.Builder nb = new NotificationCompat.Builder(this, CH_ID)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle(currentTitle.isEmpty() ? "SYNC" : currentTitle)
            .setContentText(currentArtist.isEmpty() ? "음악 앱" : currentArtist)
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
        return PendingIntent.getService(
            this, reqCode, i,
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
                CH_ID, "SYNC 음악 재생",
                NotificationManager.IMPORTANCE_LOW);
            ch.setDescription("백그라운드 음악 재생");
            ch.setShowBadge(false);
            getSystemService(NotificationManager.class).createNotificationChannel(ch);
        }
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        mediaSession.setActive(false);
        mediaSession.release();
        executor.shutdown();
    }

    @Override
    public void onTaskRemoved(Intent rootIntent) {
    // 재생 중이면 서비스 절대 종료하지 않음
    // 재생 중이 아닐 때만 종료
    if (!isPlaying) {
        stopSelf();
    }
    // isPlaying 이면 START_STICKY 로 서비스 유지됨
    }
}
