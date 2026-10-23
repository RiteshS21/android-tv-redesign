package com.mihir.tvhome;

import android.content.ComponentName;
import android.content.Context;
import android.graphics.Bitmap;
import android.media.MediaMetadata;
import android.media.session.MediaController;
import android.media.session.MediaSessionManager;
import android.media.session.PlaybackState;
import android.os.Handler;
import android.os.Looper;

import java.util.ArrayList;
import java.util.List;

/**
 * What is playing (YouTube Music, YouTube, Netflix...), for the Home card, Control Center and the
 * screensaver. Calls {@code onChange} on the main thread whenever a session starts, stops, changes
 * track or pauses. Without listener access (see MediaListener) it simply reports nothing.
 */
final class NowPlaying {
    private final Context ctx;
    private final Runnable onChange;
    private final ComponentName listener;
    private final MediaSessionManager msm;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final List<MediaController> watched = new ArrayList<>();

    private final MediaController.Callback callback = new MediaController.Callback() {
        @Override public void onPlaybackStateChanged(PlaybackState s) { onChange.run(); }
        @Override public void onMetadataChanged(MediaMetadata m) { onChange.run(); }
        @Override public void onSessionDestroyed() { onChange.run(); }
    };
    private final MediaSessionManager.OnActiveSessionsChangedListener sessions = this::sessionsChanged;

    private void sessionsChanged(List<MediaController> list) {
        watch(list);
        onChange.run();
    }

    NowPlaying(Context ctx, Runnable onChange) {
        this.ctx = ctx;
        this.onChange = onChange;
        listener = new ComponentName(ctx, MediaListener.class);
        msm = (MediaSessionManager) ctx.getSystemService(Context.MEDIA_SESSION_SERVICE);
    }

    void start() {
        try {
            msm.addOnActiveSessionsChangedListener(sessions, listener, handler);
            watch(msm.getActiveSessions(listener));
        } catch (SecurityException | NullPointerException ignored) { } // no listener access
        onChange.run();
    }

    void stop() {
        try { msm.removeOnActiveSessionsChangedListener(sessions); } catch (RuntimeException ignored) { }
        watch(null);
    }

    /** The playing session, else the most recent paused one; null when nothing has a title. */
    MediaController current() {
        MediaController paused = null;
        for (MediaController c : watched) {
            if (title(c) == null) continue;
            int s = c.getPlaybackState() == null ? PlaybackState.STATE_NONE : c.getPlaybackState().getState();
            if (s == PlaybackState.STATE_PLAYING || s == PlaybackState.STATE_BUFFERING) return c;
            if (paused == null && s == PlaybackState.STATE_PAUSED) paused = c;
        }
        return paused;
    }

    static boolean isPlaying(MediaController c) {
        PlaybackState s = c.getPlaybackState();
        return s != null && (s.getState() == PlaybackState.STATE_PLAYING || s.getState() == PlaybackState.STATE_BUFFERING);
    }

    static void playPause(MediaController c) {
        if (isPlaying(c)) c.getTransportControls().pause();
        else c.getTransportControls().play();
    }

    static String title(MediaController c) {
        MediaMetadata m = c.getMetadata();
        if (m == null) return null;
        String t = m.getString(MediaMetadata.METADATA_KEY_TITLE);
        if (t == null || t.isEmpty()) t = m.getString(MediaMetadata.METADATA_KEY_DISPLAY_TITLE);
        return t == null || t.isEmpty() ? null : t;
    }

    /** Artist, else subtitle, else the app's name. */
    static String subtitle(Context ctx, MediaController c) {
        MediaMetadata m = c.getMetadata();
        String s = m == null ? null : m.getString(MediaMetadata.METADATA_KEY_ARTIST);
        if ((s == null || s.isEmpty()) && m != null) s = m.getString(MediaMetadata.METADATA_KEY_DISPLAY_SUBTITLE);
        if ((s == null || s.isEmpty()) && m != null) s = m.getString(MediaMetadata.METADATA_KEY_ALBUM_ARTIST);
        if (s == null || s.isEmpty()) {
            try {
                s = String.valueOf(ctx.getPackageManager().getApplicationLabel(
                        ctx.getPackageManager().getApplicationInfo(c.getPackageName(), 0)));
            } catch (Exception e) {
                s = c.getPackageName();
            }
        }
        return s;
    }

    static Bitmap art(MediaController c) {
        MediaMetadata m = c.getMetadata();
        if (m == null) return null;
        for (String k : new String[]{MediaMetadata.METADATA_KEY_ART, MediaMetadata.METADATA_KEY_ALBUM_ART,
                MediaMetadata.METADATA_KEY_DISPLAY_ICON}) {
            Bitmap b = m.getBitmap(k);
            if (b != null) return b;
        }
        return null;
    }

    private void watch(List<MediaController> list) {
        for (MediaController c : watched) c.unregisterCallback(callback);
        watched.clear();
        if (list == null) return;
        for (MediaController c : list) {
            c.registerCallback(callback, handler);
            watched.add(c);
        }
    }
}
