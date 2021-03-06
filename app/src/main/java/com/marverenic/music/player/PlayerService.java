package com.marverenic.music.player;

import android.app.Notification;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Intent;
import android.os.Build;
import android.os.IBinder;
import android.os.RemoteException;
import android.support.annotation.DrawableRes;
import android.support.annotation.StringRes;
import android.support.v4.media.session.MediaButtonReceiver;
import android.support.v4.media.session.MediaSessionCompat;
import android.support.v7.app.NotificationCompat;
import android.util.Log;
import android.view.KeyEvent;

import com.crashlytics.android.Crashlytics;
import com.marverenic.music.BuildConfig;
import com.marverenic.music.IPlayerService;
import com.marverenic.music.R;
import com.marverenic.music.data.store.RemotePreferencesStore;
import com.marverenic.music.instances.Song;
import com.marverenic.music.utils.MediaStyleHelper;

import java.io.IOException;
import java.util.List;

public class PlayerService extends Service implements MusicPlayer.OnPlaybackChangeListener {

    private static final String TAG = "PlayerService";
    private static final boolean DEBUG = BuildConfig.DEBUG;

    public static final String ACTION_STOP = "PlayerService.stop";

    public static final int NOTIFICATION_ID = 1;

    /**
     * The service instance in use (singleton)
     */
    private static PlayerService instance;

    /**
     * Used in binding and unbinding this service to the UI process
     */
    private static IBinder binder;

    // Instance variables
    /**
     * The media player for the service instance
     */
    private MusicPlayer musicPlayer;

    /**
     * Used to to prevent errors caused by freeing resources twice
     */
    private boolean finished;
    /**
     * Used to keep track of whether the main application window is open or not
     */
    private boolean mAppRunning;
    /**
     * Used to keep track of whether the notification has been dismissed or not
     */
    private boolean mStopped;

    @Override
    public IBinder onBind(Intent intent) {
        if (DEBUG) Log.i(TAG, "onBind called");

        if (binder == null) {
            binder = new Stub();
        }
        mAppRunning = true;
        return binder;
    }

    /**
     * @inheritDoc
     */
    @Override
    public void onCreate() {
        super.onCreate();
        if (DEBUG) Log.i(TAG, "onCreate() called");

        if (instance == null) {
            instance = this;
        } else {
            if (DEBUG) Log.w(TAG, "Attempted to create a second PlayerService");
            stopSelf();
            return;
        }

        if (musicPlayer == null) {
            musicPlayer = new MusicPlayer(this);
        }

        mStopped = false;
        finished = false;

        musicPlayer.setPlaybackChangeListener(this);
        musicPlayer.loadState();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (DEBUG) Log.i(TAG, "onStartCommand() called");
        super.onStartCommand(intent, flags, startId);

        if (intent != null) {
            if (intent.hasExtra(Intent.EXTRA_KEY_EVENT)) {
                MediaButtonReceiver.handleIntent(musicPlayer.getMediaSession(), intent);
                Log.i(TAG, intent.getParcelableExtra(Intent.EXTRA_KEY_EVENT).toString());
            } else if (ACTION_STOP.equals(intent.getAction())) {
                stop();
            }
        }
        return START_STICKY;
    }

    @Override
    public void onDestroy() {
        if (DEBUG) Log.i(TAG, "Called onDestroy()");
        finish();
        super.onDestroy();
    }

    @Override
    public void onTaskRemoved(Intent rootIntent) {
        if (DEBUG) Log.i(TAG, "onTaskRemoved() called");
        mAppRunning = false;

        /*
            When the application is removed from the overview page, we make the notification
            dismissible on Lollipop and higher devices if music is paused. To do this, we have to
            move the service out of the foreground state. As soon as this happens, ActivityManager
            will kill the service because it isn't in the foreground. Because the service is
            sticky, it will get queued to be restarted.

            Because our service has a chance of getting recreated as a result of this event in
            the lifecycle, we have to save the state of the media player under the assumption that
            we're about to be killed. If we are killed, this state will just be reloaded when the
            service is recreated, and all the user sees is their notification temporarily
            disappearing when they pause music and swipe Jockey out of their recent apps.

            There is no other way I'm aware of to implement a remote service that transitions
            between the foreground and background (as required by the platform's media style since
            it can't have a close button on L+ devices) without being recreated and requiring
            this workaround.
         */
        try {
            musicPlayer.saveState();
        } catch (IOException exception) {
            Log.e(TAG, "Failed to save music player state", exception);
            Crashlytics.logException(exception);
        }

        if (mStopped) {
            stop();
        } else {
            notifyNowPlaying();
        }
    }

    public static PlayerService getInstance() {
        return instance;
    }

    /**
     * Generate and post a notification for the current player status
     * Posts the notification by starting the service in the foreground
     */
    public void notifyNowPlaying() {
        if (DEBUG) Log.i(TAG, "notifyNowPlaying() called");

        if (musicPlayer.getNowPlaying() == null) {
            if (DEBUG) Log.i(TAG, "Not showing notification -- nothing is playing");
            return;
        }

        MediaSessionCompat mediaSession = musicPlayer.getMediaSession();
        NotificationCompat.Builder builder = MediaStyleHelper.from(this, mediaSession);

        setupNotificationActions(builder);

        builder.setSmallIcon(getNotificationIcon())
                .setDeleteIntent(getStopIntent())
                .setStyle(
                        new NotificationCompat.MediaStyle()
                                .setShowActionsInCompactView(1, 2)
                                .setShowCancelButton(true)
                                .setCancelButtonIntent(getStopIntent())
                                .setMediaSession(musicPlayer.getMediaSession().getSessionToken()));

        showNotification(builder.build());
    }

    @DrawableRes
    private int getNotificationIcon() {
        if (musicPlayer.isPlaying() || musicPlayer.isPreparing()) {
            return R.drawable.ic_play_arrow_24dp;
        } else {
            return R.drawable.ic_pause_24dp;
        }
    }

    private void setupNotificationActions(NotificationCompat.Builder builder) {
        addNotificationAction(builder, R.drawable.ic_skip_previous_36dp,
                R.string.action_previous, KeyEvent.KEYCODE_MEDIA_PREVIOUS);

        if (musicPlayer.isPlaying()) {
            addNotificationAction(builder, R.drawable.ic_pause_36dp,
                    R.string.action_pause, KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE);
        } else {
            addNotificationAction(builder, R.drawable.ic_play_arrow_36dp,
                    R.string.action_play, KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE);
        }

        addNotificationAction(builder, R.drawable.ic_skip_next_36dp,
                R.string.action_skip, KeyEvent.KEYCODE_MEDIA_NEXT);
    }

    private void addNotificationAction(NotificationCompat.Builder builder,
                                       @DrawableRes int icon, @StringRes int string,
                                       int keyEvent) {

        PendingIntent intent = MediaStyleHelper.getActionIntent(this, keyEvent);
        builder.addAction(new NotificationCompat.Action(icon, getString(string), intent));
    }

    private PendingIntent getStopIntent() {
        Intent intent = new Intent(this, PlayerService.class);
        intent.setAction(ACTION_STOP);

        return PendingIntent.getService(this, 0, intent, 0);
    }

    private void showNotification(Notification notification) {
        mStopped = false;

        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.LOLLIPOP) {
            startForeground(NOTIFICATION_ID, notification);
        } else if (!musicPlayer.isPlaying() && !mAppRunning) {
            stopForeground(false);

            NotificationManager mgr = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
            mgr.notify(NOTIFICATION_ID, notification);
        } else {
            startForeground(NOTIFICATION_ID, notification);
        }
    }

    public void stop() {
        if (DEBUG) Log.i(TAG, "stop() called");

        mStopped = true;

        // If the UI process is still running, don't kill the process, only remove its notification
        if (mAppRunning) {
            musicPlayer.pause();
            stopForeground(true);
            return;
        }

        // If the UI process has already ended, kill the service and close the player
        finish();
    }

    public void finish() {
        if (DEBUG) Log.i(TAG, "finish() called");
        if (!finished) {
            try {
                musicPlayer.saveState();
            } catch (IOException exception) {
                Log.e(TAG, "Failed to save player state", exception);
                Crashlytics.logException(exception);
            }

            musicPlayer.release();
            musicPlayer = null;
            stopForeground(true);
            instance = null;
            stopSelf();
            finished = true;
        }
    }

    @Override
    public void onPlaybackChange() {
        notifyNowPlaying();
    }

    public static class Stub extends IPlayerService.Stub {

        @Override
        public void stop() throws RemoteException {
            instance.stop();
        }

        @Override
        public void skip() throws RemoteException {
            instance.musicPlayer.skip();
        }

        @Override
        public void previous() throws RemoteException {
            instance.musicPlayer.skipPrevious();
        }

        @Override
        public void begin() throws RemoteException {
            instance.musicPlayer.prepare(true);
        }

        @Override
        public void togglePlay() throws RemoteException {
            instance.musicPlayer.togglePlay();
        }

        @Override
        public void play() throws RemoteException {
            instance.musicPlayer.play();
        }

        @Override
        public void pause() throws RemoteException {
            instance.musicPlayer.play();
        }

        @Override
        public void setPreferences(RemotePreferencesStore preferences) throws RemoteException {
            instance.musicPlayer.updatePreferences(preferences);
        }

        @Override
        public void setQueue(List<Song> newQueue, int newPosition) throws RemoteException {
            instance.musicPlayer.setQueue(newQueue, newPosition);
            if (newQueue.isEmpty()) {
                instance.stop();
            }
        }

        @Override
        public void changeSong(int position) throws RemoteException {
            instance.musicPlayer.changeSong(position);
        }

        @Override
        public void editQueue(List<Song> newQueue, int newPosition) throws RemoteException {
            instance.musicPlayer.editQueue(newQueue, newPosition);
        }

        @Override
        public void queueNext(Song song) throws RemoteException {
            instance.musicPlayer.queueNext(song);
        }

        @Override
        public void queueNextList(List<Song> songs) throws RemoteException {
            instance.musicPlayer.queueNext(songs);
        }

        @Override
        public void queueLast(Song song) throws RemoteException {
            instance.musicPlayer.queueLast(song);
        }

        @Override
        public void queueLastList(List<Song> songs) throws RemoteException {
            instance.musicPlayer.queueLast(songs);
        }

        @Override
        public void seekTo(int position) throws RemoteException {
            instance.musicPlayer.seekTo(position);
        }

        @Override
        public boolean isPlaying() throws RemoteException {
            return instance.musicPlayer.isPlaying();
        }

        @Override
        public Song getNowPlaying() throws RemoteException {
            return instance.musicPlayer.getNowPlaying();
        }

        @Override
        public List<Song> getQueue() throws RemoteException {
            return instance.musicPlayer.getQueue();
        }

        @Override
        public int getQueuePosition() throws RemoteException {
            return instance.musicPlayer.getQueuePosition();
        }

        @Override
        public int getQueueSize() throws RemoteException {
            return instance.musicPlayer.getQueueSize();
        }

        @Override
        public int getCurrentPosition() throws RemoteException {
            return instance.musicPlayer.getCurrentPosition();
        }

        @Override
        public int getDuration() throws RemoteException {
            return instance.musicPlayer.getDuration();
        }

        @Override
        public int getAudioSessionId() throws RemoteException {
            return instance.musicPlayer.getAudioSessionId();
        }
    }
}

