package com.marverenic.music.player;

import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.media.AudioManager;
import android.media.MediaPlayer;
import android.media.audiofx.AudioEffect;
import android.media.audiofx.Equalizer;
import android.os.Handler;
import android.os.PowerManager;
import android.support.annotation.NonNull;
import android.support.v4.media.MediaMetadataCompat;
import android.support.v4.media.session.MediaSessionCompat;
import android.support.v4.media.session.PlaybackStateCompat;
import android.view.KeyEvent;

import com.crashlytics.android.Crashlytics;
import com.marverenic.music.JockeyApplication;
import com.marverenic.music.R;
import com.marverenic.music.activity.NowPlayingActivity;
import com.marverenic.music.data.store.MediaStoreUtil;
import com.marverenic.music.data.store.PlayCountStore;
import com.marverenic.music.data.store.PreferencesStore;
import com.marverenic.music.data.store.ReadOnlyPreferencesStore;
import com.marverenic.music.data.store.SharedPreferencesStore;
import com.marverenic.music.instances.Song;
import com.marverenic.music.utils.Util;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.Scanner;

import javax.inject.Inject;

import static android.content.Intent.ACTION_HEADSET_PLUG;
import static android.media.AudioManager.ACTION_AUDIO_BECOMING_NOISY;

/**
 * High level implementation for a MediaPlayer. MusicPlayer is backed by a {@link QueuedMediaPlayer}
 * and provides high-level behavior definitions (for actions like {@link #skip()},
 * {@link #skipPrevious()} and {@link #togglePlay()}) as well as system integration.
 *
 * MediaPlayer provides shuffle and repeat with {@link #setShuffle(boolean)} and
 * {@link #setRepeat(int)}, respectively.
 *
 * MusicPlayer also provides play count logging and state reloading.
 * See {@link #logPlayCount(Song, boolean)}, {@link #loadState()} and {@link #saveState()}
 *
 * System integration is implemented by handling Audio Focus through {@link AudioManager}, attaching
 * a {@link MediaSessionCompat}, and with a {@link HeadsetListener} -- an implementation of
 * {@link BroadcastReceiver} that pauses playback when headphones are disconnected.
 */
public class MusicPlayer implements AudioManager.OnAudioFocusChangeListener,
        QueuedMediaPlayer.PlaybackEventListener {

    private static final String TAG = "MusicPlayer";

    /**
     * The filename of the queue state used to load and save previous configurations.
     * This file will be stored in the directory defined by
     * {@link Context#getExternalFilesDir(String)}
     */
    private static final String QUEUE_FILE = ".queue";

    /**
     * An {@link Intent} action broadcasted when a MusicPlayer has changed its state automatically
     */
    public static final String UPDATE_BROADCAST = "marverenic.jockey.player.REFRESH";

    /**
     * An {@link Intent} action broadcasted when a MusicPlayer has encountered an error when
     * setting the current playback source
     * @see #ERROR_EXTRA_MSG
     */
    public static final String ERROR_BROADCAST = "marverenic.jockey.player.ERROR";

    /**
     * An {@link Intent} extra sent with {@link #ERROR_BROADCAST} intents which maps to a
     * user-friendly error message
     */
    public static final String ERROR_EXTRA_MSG = "marverenic.jockey.player.ERROR:MSG";

    /**
     * Repeat value that corresponds to repeat none. Playback will continue as normal until and will
     * end after the last song finishes
     * @see #setRepeat(int)
     */
    public static final int REPEAT_NONE = 0;

    /**
     * Repeat value that corresponds to repeat all. Playback will continue as normal, but the queue
     * will restart from the beginning once the last song finishes
     * @see #setRepeat(int)
     */
    public static final int REPEAT_ALL = -1;

    /**
     * Repeat value that corresponds to repeat one. When the current song is finished, it will be
     * repeated. The MusicPlayer will never progress to the next track until the user manually
     * changes the song.
     * @see #setRepeat(int)
     */
    public static final int REPEAT_ONE = -2;

    /**
     * Defines the threshold for skip previous behavior. If the current seek position in the song is
     * greater than this value, {@link #skipPrevious()} will seek to the beginning of the song.
     * If the current seek position is less than this threshold, then the queue index will be
     * decremented and the previous song in the queue will be played.
     * This value is measured in milliseconds and is currently set to 5 seconds
     * @see #skipPrevious()
     */
    private static final int SKIP_PREVIOUS_THRESHOLD = 5000;

    /**
     * Defines the minimum duration that must be passed for a song to be considered "played" when
     * logging play counts
     * This value is measured in milliseconds and is currently set to 24 seconds
     */
    private static final int PLAY_COUNT_THRESHOLD = 24000;

    /**
     * Defines the maximum duration that a song can reach to be considered "skipped" when logging
     * play counts
     * This value is measured in milliseconds and is currently set to 20 seconds
     */
    private static final int SKIP_COUNT_THRESHOLD = 20000;

    /**
     * The volume scalar to set when {@link AudioManager} causes a MusicPlayer instance to duck
     */
    private static final float DUCK_VOLUME = 0.5f;

    private QueuedMediaPlayer mMediaPlayer;
    private Equalizer mEqualizer;
    private Context mContext;
    private MediaSessionCompat mMediaSession;
    private HeadsetListener mHeadphoneListener;
    private OnPlaybackChangeListener mCallback;

    private List<Song> mQueue;
    private List<Song> mQueueShuffled;

    private boolean mShuffle;
    private int mRepeat;

    /**
     * Whether this MusicPlayer has focus from {@link AudioManager} to play audio
     * @see #getFocus()
     */
    private boolean mFocused = false;
    /**
     * Whether playback should continue once {@link AudioManager} returns focus to this MusicPlayer
     * @see #onAudioFocusChange(int)
     */
    private boolean mResumeOnFocusGain = false;

    /**
     * The album artwork of the current song
     */
    private Bitmap mArtwork;

    @Inject PlayCountStore mPlayCountStore;

    /**
     * Creates a new MusicPlayer with an empty queue. The backing {@link android.media.MediaPlayer}
     * will create a wakelock (specified by {@link PowerManager#PARTIAL_WAKE_LOCK}), and all
     * system integration will be initialized
     * @param context A Context used to interact with other components of the OS and used to
     *                load songs. This Context will be kept for the lifetime of this Object.
     */
    public MusicPlayer(Context context) {
        mContext = context;
        JockeyApplication.getComponent(mContext).inject(this);

        // Initialize the media player
        mMediaPlayer = new QueuedMediaPlayer(context);

        mMediaPlayer.setAudioStreamType(AudioManager.STREAM_MUSIC);
        mMediaPlayer.setWakeMode(PowerManager.PARTIAL_WAKE_LOCK);
        mMediaPlayer.setPlaybackEventListener(this);

        mQueue = new ArrayList<>();
        mQueueShuffled = new ArrayList<>();

        // Attach a HeadsetListener to respond to headphone events
        mHeadphoneListener = new HeadsetListener(this);
        IntentFilter filter = new IntentFilter();
        filter.addAction(ACTION_HEADSET_PLUG);
        filter.addAction(ACTION_AUDIO_BECOMING_NOISY);
        context.registerReceiver(mHeadphoneListener, filter);

        loadPrefs();
        initMediaSession();
    }

    /**
     * Reloads shuffle and repeat preferences from {@link SharedPreferences}
     */
    private void loadPrefs() {
        // SharedPreferencesStore is backed by an instance of SharedPreferences. Because
        // SharedPreferences isn't safe to use across processes, the only time we can get valid
        // data is right after we open the SharedPreferences for the first time in this process.
        //
        // We're going to take advantage of that here so that we can load the latest preferences
        // as soon as the MusicPlayer is started (which should be the same time that this process
        // is started). To update these preferences, see updatePreferences(preferencesStore)
        PreferencesStore preferencesStore = new SharedPreferencesStore(mContext);

        mShuffle = preferencesStore.isShuffled();
        mRepeat = preferencesStore.getRepeatMode();

        initEqualizer(preferencesStore);
    }

    /**
     * Updates shuffle and repeat preferences from a Preference Store
     * @param preferencesStore The preference store to read values from
     */
    public void updatePreferences(ReadOnlyPreferencesStore preferencesStore) {
        if (preferencesStore.isShuffled() != mShuffle) {
            setShuffle(preferencesStore.isShuffled());
        }

        setRepeat(preferencesStore.getRepeatMode());
    }

    /**
     * Initiate a MediaSession to allow the Android system to interact with the player
     */
    private void initMediaSession() {
        mMediaSession = new MediaSessionCompat(mContext, TAG, null, null);

        mMediaSession.setCallback(new MediaSessionCallback(this));
        mMediaSession.setSessionActivity(
                PendingIntent.getActivity(
                        mContext, 0,
                        new Intent(mContext, NowPlayingActivity.class)
                                .setFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP),
                        PendingIntent.FLAG_CANCEL_CURRENT));

        mMediaSession.setFlags(MediaSessionCompat.FLAG_HANDLES_MEDIA_BUTTONS
                | MediaSessionCompat.FLAG_HANDLES_TRANSPORT_CONTROLS);

        PlaybackStateCompat.Builder state = new PlaybackStateCompat.Builder()
                .setActions(PlaybackStateCompat.ACTION_PLAY
                        | PlaybackStateCompat.ACTION_PLAY_PAUSE
                        | PlaybackStateCompat.ACTION_SEEK_TO
                        | PlaybackStateCompat.ACTION_PAUSE
                        | PlaybackStateCompat.ACTION_SKIP_TO_NEXT
                        | PlaybackStateCompat.ACTION_SKIP_TO_PREVIOUS
                        | PlaybackStateCompat.ACTION_STOP)
                .setState(PlaybackStateCompat.STATE_NONE, 0, 0f);

        mMediaSession.setPlaybackState(state.build());
        mMediaSession.setActive(true);
    }

    /**
     * Reload all equalizer settings from SharedPreferences
     */
    private void initEqualizer(ReadOnlyPreferencesStore preferencesStore) {
        Equalizer.Settings eqSettings = preferencesStore.getEqualizerSettings();

        mEqualizer = new Equalizer(0, mMediaPlayer.getAudioSessionId());
        if (eqSettings != null) {
            try {
                mEqualizer.setProperties(eqSettings);
            } catch (IllegalArgumentException | UnsupportedOperationException e) {
                Crashlytics.logException(new RuntimeException(
                        "Failed to load equalizer settings: " + eqSettings, e));
            }
        }
        mEqualizer.setEnabled(preferencesStore.getEqualizerEnabled());

        // If the built in equalizer is off, bind to the system equalizer if one is available
        if (!preferencesStore.getEqualizerEnabled()) {
            final Intent intent = new Intent(AudioEffect.ACTION_OPEN_AUDIO_EFFECT_CONTROL_SESSION);
            intent.putExtra(AudioEffect.EXTRA_AUDIO_SESSION, getAudioSessionId());
            intent.putExtra(AudioEffect.EXTRA_PACKAGE_NAME, mContext.getPackageName());
            mContext.sendBroadcast(intent);
        }
    }

    /**
     * Saves the player's current state to a file with the name {@link #QUEUE_FILE} in
     * the app's external files directory specified by {@link Context#getExternalFilesDir(String)}
     * @throws IOException
     * @see #loadState()
     */
    public void saveState() throws IOException {
        // Anticipate the outcome of a command so that if we're killed right after it executes,
        // we can restore to the proper state
        int reloadSeekPosition = mMediaPlayer.getCurrentPosition();
        int reloadQueuePosition = mMediaPlayer.getQueueIndex();

        final String currentPosition = Integer.toString(reloadSeekPosition);
        final String queuePosition = Integer.toString(reloadQueuePosition);
        final String queueLength = Integer.toString(mQueue.size());

        StringBuilder queue = new StringBuilder();
        for (Song s : mQueue) {
            queue.append(' ').append(s.getSongId());
        }

        StringBuilder queueShuffled = new StringBuilder();
        for (Song s : mQueueShuffled) {
            queueShuffled.append(' ').append(s.getSongId());
        }

        String output = currentPosition + " " + queuePosition + " "
                + queueLength + queue + queueShuffled;

        File save = new File(mContext.getExternalFilesDir(null), QUEUE_FILE);
        FileOutputStream stream = new FileOutputStream(save);
        stream.write(output.getBytes());
        stream.close();
    }

    /**
     * Reloads a saved state
     * @see #saveState()
     */
    public void loadState() {
        try {
            File save = new File(mContext.getExternalFilesDir(null), QUEUE_FILE);
            Scanner scanner = new Scanner(save);

            int currentPosition = scanner.nextInt();
            int queuePosition = scanner.nextInt();

            int queueLength = scanner.nextInt();
            long[] queueIDs = new long[queueLength];
            for (int i = 0; i < queueLength; i++) {
                queueIDs[i] = scanner.nextInt();
            }
            mQueue = MediaStoreUtil.buildSongListFromIds(queueIDs, mContext);

            long[] shuffleQueueIDs;
            if (scanner.hasNextInt()) {
                shuffleQueueIDs = new long[queueLength];
                for (int i = 0; i < queueLength; i++) {
                    shuffleQueueIDs[i] = scanner.nextInt();
                }
                mQueueShuffled = MediaStoreUtil.buildSongListFromIds(shuffleQueueIDs, mContext);
            } else if (mShuffle) {
                shuffleQueue(queuePosition);
            }

            mMediaPlayer.seekTo(currentPosition);

            setBackingQueue(queuePosition);
            mArtwork = Util.fetchFullArt(getNowPlaying());
        } catch(FileNotFoundException ignored) {
            // If there's no queue file, just restore to an empty state
        } catch (NoSuchElementException e) {
            mQueue.clear();
            mQueueShuffled.clear();
            setBackingQueue(0);
        }
    }

    public void setPlaybackChangeListener(OnPlaybackChangeListener listener) {
        mCallback = listener;
    }

    /**
     * @return The audio session of the backing {@link android.media.MediaPlayer}
     * @see MediaPlayer#getAudioSessionId()
     */
    public int getAudioSessionId() {
        return mMediaPlayer.getAudioSessionId();
    }

    /**
     * Updates the metadata in the attached {@link MediaSessionCompat}
     */
    private void updateMediaSession() {
        if (getNowPlaying() != null) {
            Song nowPlaying = getNowPlaying();
            MediaMetadataCompat.Builder metadataBuilder = new MediaMetadataCompat.Builder()
                    .putString(MediaMetadataCompat.METADATA_KEY_DISPLAY_TITLE,
                            nowPlaying.getSongName())
                    .putString(MediaMetadataCompat.METADATA_KEY_TITLE,
                            nowPlaying.getSongName())
                    .putString(MediaMetadataCompat.METADATA_KEY_ALBUM,
                            nowPlaying.getAlbumName())
                    .putString(MediaMetadataCompat.METADATA_KEY_DISPLAY_DESCRIPTION,
                            nowPlaying.getAlbumName())
                    .putString(MediaMetadataCompat.METADATA_KEY_ARTIST,
                            nowPlaying.getArtistName())
                    .putString(MediaMetadataCompat.METADATA_KEY_DISPLAY_SUBTITLE,
                            nowPlaying.getArtistName())
                    .putLong(MediaMetadataCompat.METADATA_KEY_DURATION, getDuration())
                    .putBitmap(MediaMetadataCompat.METADATA_KEY_ALBUM_ART, mArtwork);
            mMediaSession.setMetadata(metadataBuilder.build());

            PlaybackStateCompat.Builder state = new PlaybackStateCompat.Builder().setActions(
                    PlaybackStateCompat.ACTION_PLAY
                            | PlaybackStateCompat.ACTION_PLAY_PAUSE
                            | PlaybackStateCompat.ACTION_SEEK_TO
                            | PlaybackStateCompat.ACTION_STOP
                            | PlaybackStateCompat.ACTION_PAUSE
                            | PlaybackStateCompat.ACTION_SKIP_TO_NEXT
                            | PlaybackStateCompat.ACTION_SKIP_TO_PREVIOUS);

            switch (mMediaPlayer.getState()) {
                case STARTED:
                    state.setState(PlaybackStateCompat.STATE_PLAYING, getCurrentPosition(), 1f);
                    break;
                case PAUSED:
                    state.setState(PlaybackStateCompat.STATE_PAUSED, getCurrentPosition(), 1f);
                    break;
                case STOPPED:
                    state.setState(PlaybackStateCompat.STATE_STOPPED, getCurrentPosition(), 1f);
                    break;
                default:
                    state.setState(PlaybackStateCompat.STATE_NONE, getCurrentPosition(), 1f);
            }
            mMediaSession.setPlaybackState(state.build());
            mMediaSession.setActive(mFocused);
        }
    }

    @Override
    public void onAudioFocusChange(int focusChange) {
        switch (focusChange) {
            case AudioManager.AUDIOFOCUS_LOSS_TRANSIENT:
                mResumeOnFocusGain = isPlaying() || mResumeOnFocusGain;
            case AudioManager.AUDIOFOCUS_LOSS:
                mFocused = false;
                pause();
                break;
            case AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK:
                mMediaPlayer.setVolume(DUCK_VOLUME, DUCK_VOLUME);
                break;
            case AudioManager.AUDIOFOCUS_GAIN:
                mMediaPlayer.setVolume(1f, 1f);
                if (mResumeOnFocusGain) play();
                mResumeOnFocusGain = false;
                break;
            default:
                break;
        }
        updateNowPlaying();
        updateUi();
    }

    /**
     * Notifies the attached {@link MusicPlayer.OnPlaybackChangeListener} that a playback change
     * has occurred, and updates the attached {@link MediaSessionCompat}
     */
    private void updateNowPlaying() {
        updateMediaSession();
        if (mCallback != null) {
            mCallback.onPlaybackChange();
        }
    }

    /**
     * Called to notify the UI thread to refresh any player data when the player changes states
     * on its own (Like when a song finishes)
     */
    protected void updateUi() {
        mContext.sendBroadcast(new Intent(UPDATE_BROADCAST), null);
    }

    /**
     * Called to notify the UI thread that an error has occurred. The typical listener will show the
     * message passed in to the user.
     * @param message A user-friendly message associated with this error that may be shown in the UI
     */
    protected void postError(String message) {
        mContext.sendBroadcast(
                new Intent(ERROR_BROADCAST).putExtra(ERROR_EXTRA_MSG, message), null);
    }

    /**
     * Gain Audio focus from the system if we don't already have it
     * @return whether we have gained focus (or already had it)
     */
    private boolean getFocus() {
        if (!mFocused) {
            AudioManager audioManager =
                    (AudioManager) mContext.getSystemService(Context.AUDIO_SERVICE);

            int response = audioManager.requestAudioFocus(this, AudioManager.STREAM_MUSIC,
                    AudioManager.AUDIOFOCUS_GAIN);
            mFocused = response == AudioManager.AUDIOFOCUS_REQUEST_GRANTED;
        }
        return mFocused;
    }

    /**
     * Generates a new random permutation of the queue, and sets it as the backing
     * {@link QueuedMediaPlayer}'s queue
     * @param currentIndex The index of the current song which will be moved to the top of the
     *                     shuffled queue
     */
    private void shuffleQueue(int currentIndex) {
        if (mQueueShuffled == null) {
            mQueueShuffled = new ArrayList<>(mQueue);
        } else {
            mQueueShuffled.clear();
            mQueueShuffled.addAll(mQueue);
        }

        if (!mQueueShuffled.isEmpty()) {
            Song first = mQueueShuffled.remove(currentIndex);

            Collections.shuffle(mQueueShuffled);
            mQueueShuffled.add(0, first);
        }
    }

    /**
     * Prepares the backing {@link QueuedMediaPlayer} for playback
     * @param playWhenReady Whether playback will begin when the current song has been prepared
     * @see QueuedMediaPlayer#prepare(boolean)
     */
    public void prepare(boolean playWhenReady) {
        mMediaPlayer.prepare(playWhenReady && getFocus());
    }

    /**
     * Toggles playback between playing and paused states
     * @see #play()
     * @see #pause()
     */
    public void togglePlay() {
        if (isPlaying()) {
            pause();
        } else {
            play();
        }
    }

    /**
     * Pauses music playback
     */
    public void pause() {
        if (isPlaying()) {
            mMediaPlayer.pause();
            updateNowPlaying();
        }
        mResumeOnFocusGain = false;
    }

    /**
     * Starts or resumes music playback
     */
    public void play() {
        if (!isPlaying() && getFocus()) {
            mMediaPlayer.play();
            updateNowPlaying();
        }
    }

    /**
     * Skips to the next song in the queue and logs a play count or skip count
     * If repeat all is enabled, the queue will loop from the beginning when it it is finished.
     * Otherwise, calling this method when the last item in the queue is being played will stop
     * playback
     * @see #setRepeat(int) to set the current repeat mode
     */
    public void skip() {
        if (!mMediaPlayer.isComplete()) {
            logPlay();
        }

        if (mRepeat > 0) {
            // TODO Reset Multi-Repeat. See #setRepeat(int) for more details
            mRepeat = REPEAT_NONE;
        }

        if (mMediaPlayer.getQueueIndex() < mQueue.size() - 1
                || mRepeat == REPEAT_ALL) {
            // If we're in the middle of the queue, or repeat all is on, start the next song
            mMediaPlayer.skip();
        }
    }

    /**
     * Records a play or skip for the current song based on the current time of the backing
     * {@link MediaPlayer} as returned by {@link #getCurrentPosition()}
     */
    private void logPlay() {
        if (getNowPlaying() != null) {
            if (getCurrentPosition() > PLAY_COUNT_THRESHOLD
                    || getCurrentPosition() > getDuration() / 2) {
                // Log a play if we're passed a certain threshold or more than 50% in a song
                // (whichever is smaller)
                logPlayCount(getNowPlaying(), false);
            } else if (getCurrentPosition() < SKIP_COUNT_THRESHOLD) {
                // If we're not very far into this song, log a skip
                logPlayCount(getNowPlaying(), true);
            }
        }
    }

    /**
     * Record a play or skip for a certain song
     * @param song the song to change the play count of
     * @param skip Whether the song was skipped (true if skipped, false if played)
     */
    private void logPlayCount(Song song, boolean skip) {
        if (skip) {
            mPlayCountStore.incrementSkipCount(song);
        } else {
            mPlayCountStore.incrementPlayCount(song);
            mPlayCountStore.setPlayDateToNow(song);
        }
        mPlayCountStore.save();
    }

    /**
     * Skips to the previous song in the queue
     * If the song's current position is more than 5 seconds or 50% of the song (whichever is
     * smaller), then the song will be restarted from the beginning instead.
     * If this is called when the first item in the queue is being played, it will loop to the last
     * song if repeat all is enabled, otherwise the current song will always be restarted
     * @see #setRepeat(int) to set the current repeat mode
     */
    public void skipPrevious() {
        if (getQueuePosition() == 0 || getCurrentPosition() > SKIP_PREVIOUS_THRESHOLD
                || getCurrentPosition() > getDuration() / 2) {
            mMediaPlayer.seekTo(0);
            updateNowPlaying();
        } else {
            mMediaPlayer.skipPrevious();
        }
    }

    /**
     * Stops music playback
     */
    public void stop() {
        pause();
        seekTo(0);
    }

    /**
     * Seek to a specified position in the current song
     * @param mSec The time (in milliseconds) to seek to
     * @see MediaPlayer#seekTo(int)
     */
    public void seekTo(int mSec) {
        mMediaPlayer.seekTo(mSec);
    }

    /**
     * @return The {@link Song} that is currently being played
     */
    public Song getNowPlaying() {
        return mMediaPlayer.getNowPlaying();
    }

    /**
     * @return Whether music is being played or not
     * @see MediaPlayer#isPlaying()
     */
    public boolean isPlaying() {
        return mMediaPlayer.isPlaying();
    }

    /**
     * @return Whether the current song is getting ready to be played
     */
    public boolean isPreparing() {
        return getState() == ManagedMediaPlayer.Status.PREPARING;
    }

    /**
     * @return The current queue. If shuffle is enabled, then the shuffled queue will be returned,
     *         otherwise the regular queue will be returned
     */
    public List<Song> getQueue() {
        // If you're using this method on the UI thread, consider replacing this method with
        // return new ArrayList<>(mMediaPlayer.getQueue());
        // to prevent components from accidentally changing the backing queue
        return mMediaPlayer.getQueue();
    }

    /**
     * @return The current index in the queue that is being played
     */
    public int getQueuePosition() {
        return mMediaPlayer.getQueueIndex();
    }

    /**
     * @return The number of items in the current queue
     */
    public int getQueueSize() {
        return mMediaPlayer.getQueueSize();
    }

    /**
     * @return The current seek position of the song that is playing
     * @see MediaPlayer#getCurrentPosition()
     */
    public int getCurrentPosition() {
        return mMediaPlayer.getCurrentPosition();
    }

    /**
     * @return The length of the current song in milliseconds
     * @see MediaPlayer#getDuration()
     */
    public int getDuration() {
        return mMediaPlayer.getDuration();
    }

    /**
     * Changes the current index of the queue and starts playback from this new position
     * @param position The index in the queue to skip to
     * @throws IllegalArgumentException if {@code position} is not between 0 and the queue length
     */
    public void changeSong(int position) {
        mMediaPlayer.setQueueIndex(position);
        prepare(true);
    }

    /**
     * Changes the current queue and starts playback from the current index
     * @param queue The replacement queue
     * @see #setQueue(List, int) to change the current index simultaneously
     * @throws IllegalArgumentException if the current index cannot be applied to the updated queue
     */
    public void setQueue(@NonNull List<Song> queue) {
        setQueue(queue, mMediaPlayer.getQueueIndex());
    }

    /**
     * Changes the current queue and starts playback from the specified index
     * @param queue The replacement queue
     * @param index The index to start playback from
     * @throws IllegalArgumentException if {@code index} is not between 0 and the queue length
     */
    public void setQueue(@NonNull List<Song> queue, int index) {
        // If you're using this method on the UI thread, consider replacing the first line in this
        // method with "mQueue = new ArrayList<>(queue);"
        // to prevent components from accidentally changing the backing queue
        mQueue = queue;
        if (mShuffle) {
            shuffleQueue(index);
            setBackingQueue(0);
        } else {
            setBackingQueue(index);
        }
    }

    /**
     * Changes the order of the current queue without interrupting playback
     * @param queue The modified queue. This List should contain all of the songs currently in the
     *              queue, but in a different order to prevent discrepancies between the shuffle
     *              and non-shuffled queue.
     * @param index The index of the song that is currently playing in the modified queue
     */
    public void editQueue(@NonNull List<Song> queue, int index) {
        if (mShuffle) {
            mQueueShuffled = queue;
        } else {
            mQueue = queue;
        }
        setBackingQueue(index);
    }

    /**
     * Helper method to push changes in the queue to the backing {@link QueuedMediaPlayer}
     * @see #setBackingQueue(int)
     */
    private void setBackingQueue() {
        setBackingQueue(mMediaPlayer.getQueueIndex());
    }

    /**
     * Helper method to push changes in the queue to the backing {@link QueuedMediaPlayer}. This
     * method will set the queue to the appropriate shuffled or ordered list and apply the
     * specified index as the replacement queue position
     * @param index The new queue index to send to the backing {@link QueuedMediaPlayer}.
     */
    private void setBackingQueue(int index) {
        if (mShuffle) {
            mMediaPlayer.setQueue(mQueueShuffled, index);
        } else {
            mMediaPlayer.setQueue(mQueue, index);
        }
    }

    /**
     * Sets the repeat option to control what happens when a track finishes.
     * @param repeat An integer representation of the repeat option. May be one of either
     *               {@link #REPEAT_NONE}, {@link #REPEAT_ALL}, {@link #REPEAT_ONE}, or a positive
     *               integer for multi-repeat. When multi-repeat is enabled, the current song will
     *               be played back-to-back for the specified number of loops (which is equal to the
     *               value passed into this method). Once this counter decrements to 0, playback
     *               will resume as it was before and the previous repeat option will be restored.
     */
    public void setRepeat(int repeat) {
        mRepeat = repeat;
    }

    /**
     * Sets the shuffle option and immediately applies it to the queue
     * @param shuffle The new shuffle option. {@code true} will switch the current playback to a
     *                copy of the current queue in a randomized order. {@code false} will restore
     *                the queue to its original order.
     */
    public void setShuffle(boolean shuffle) {
        if (shuffle) {
            shuffleQueue(getQueuePosition());
            mMediaPlayer.setQueue(mQueueShuffled, 0);
        } else {
            int position = mQueue.indexOf(mQueueShuffled.get(mMediaPlayer.getQueueIndex()));
            mMediaPlayer.setQueue(mQueue, position);
        }
        mShuffle = shuffle;
        updateNowPlaying();
    }

    /**
     * Adds a {@link Song} to the queue to be played after the current song
     * @param song the song to enqueue
     */
    public void queueNext(Song song) {
        int index = mQueue.isEmpty() ? 0 : mMediaPlayer.getQueueIndex() + 1;
        if (mShuffle) {
            mQueueShuffled.add(index, song);
            mQueue.add(song);
        } else {
            mQueue.add(index, song);
        }
        setBackingQueue();
    }

    /**
     * Adds a {@link List} of {@link Song}s to the queue to be played after the current song
     * @param songs The songs to enqueue
     */
    public void queueNext(List<Song> songs) {
        int index = mQueue.isEmpty() ? 0 : mMediaPlayer.getQueueIndex() + 1;
        if (mShuffle) {
            mQueueShuffled.addAll(index, songs);
            mQueue.addAll(songs);
        } else {
            mQueue.addAll(index, songs);
        }
        setBackingQueue();
    }

    /**
     * Adds a {@link Song} to the end of the queue
     * @param song The song to enqueue
     */
    public void queueLast(Song song) {
        if (mShuffle) {
            mQueueShuffled.add(song);
            mQueue.add(song);
        } else {
            mQueue.add(song);
        }
        setBackingQueue();
    }

    /**
     * Adds a {@link List} of {@link Song}s to the end of the queue
     * @param songs The songs to enqueue
     */
    public void queueLast(List<Song> songs) {
        if (mShuffle) {
            mQueueShuffled.addAll(songs);
            mQueue.addAll(songs);
        } else {
            mQueue.addAll(songs);
        }
        setBackingQueue();
    }

    /**
     * Releases all resources and bindings associated with this MusicPlayer.
     * Once this is called, this MusicPlayer can no longer be used.
     */
    public void release() {
        ((AudioManager) mContext.getSystemService(Context.AUDIO_SERVICE)).abandonAudioFocus(this);
        mContext.unregisterReceiver(mHeadphoneListener);

        // Unbind from the system audio effects
        final Intent intent = new Intent(AudioEffect.ACTION_CLOSE_AUDIO_EFFECT_CONTROL_SESSION);
        intent.putExtra(AudioEffect.EXTRA_AUDIO_SESSION, getAudioSessionId());
        intent.putExtra(AudioEffect.EXTRA_PACKAGE_NAME, mContext.getPackageName());
        mContext.sendBroadcast(intent);

        if (mEqualizer != null) {
            mEqualizer.release();
        }
        mFocused = false;
        mCallback = null;
        mMediaPlayer.stop();
        mMediaPlayer.release();
        mMediaSession.release();
        mMediaPlayer = null;
        mContext = null;
    }

    /**
     * @return The album artwork embedded in the current song
     */
    public Bitmap getArtwork() {
        return mArtwork;
    }

    /**
     * @return The state that the backing {@link QueuedMediaPlayer is in}
     * @see QueuedMediaPlayer#getState()
     */
    public ManagedMediaPlayer.Status getState() {
        return mMediaPlayer.getState();
    }

    protected MediaSessionCompat getMediaSession() {
        return mMediaSession;
    }

    @Override
    public void onCompletion() {
        logPlay();

        if (mRepeat == REPEAT_NONE) {
            if (mMediaPlayer.getQueueIndex() < mMediaPlayer.getQueue().size()) {
                skip();
            }
        } else if (mRepeat == REPEAT_ALL) {
            skip();
        } else if (mRepeat == REPEAT_ONE) {
            mMediaPlayer.play();
        } else if (mRepeat > 0) {
            mRepeat--;
            mMediaPlayer.play();
        }

        updateNowPlaying();
        updateUi();
    }

    @Override
    public void onSongStart() {
        mArtwork = Util.fetchFullArt(getNowPlaying());
        updateNowPlaying();
        updateUi();
    }

    @Override
    public boolean onError(int what, int extra) {
        postError(mContext.getString(
                R.string.message_play_error_io_exception,
                getNowPlaying().getSongName()));
        return false;
    }

    @Override
    public void onSetDataSourceException(IOException e) {
        Crashlytics.logException(
                new IOException("Failed to play song " + getNowPlaying().getLocation(), e));

        postError(mContext.getString(
                (e instanceof FileNotFoundException)
                        ? R.string.message_play_error_not_found
                        : R.string.message_play_error_io_exception,
                getNowPlaying().getSongName()));
    }

    /**
     * A callback for receiving information about song changes -- useful for integrating
     * {@link MusicPlayer} with other components
     */
    public interface OnPlaybackChangeListener {
        /**
         * Called when a MusicPlayer changes songs. This method will always be called, even if the
         * event was caused by an external source. Implement and attach this callback to provide
         * more integration with external sources which requires up-to-date song information
         * (i.e. to post a notification)
         *
         * This method will only be called after the current song changes -- not when the
         * {@link MediaPlayer} changes states.
         */
        void onPlaybackChange();
    }

    private static class MediaSessionCallback extends MediaSessionCompat.Callback {

        /**
         * A period of time added after a remote button press to delay handling the event. This
         * delay allows the user to press the remote button multiple times to execute different
         * actions
         */
        private static final int REMOTE_CLICK_SLEEP_TIME_MS = 300;

        private int mClickCount;

        private MusicPlayer mMusicPlayer;
        private Handler mHandler;

        MediaSessionCallback(MusicPlayer musicPlayer) {
            mHandler = new Handler();
            mMusicPlayer = musicPlayer;
        }

        private final Runnable mButtonHandler = () -> {
            if (mClickCount == 1) {
                mMusicPlayer.togglePlay();
                mMusicPlayer.updateUi();
            } else if (mClickCount == 2) {
                onSkipToNext();
            } else {
                onSkipToPrevious();
            }
            mClickCount = 0;
        };

        @Override
        public boolean onMediaButtonEvent(Intent mediaButtonEvent) {
            KeyEvent keyEvent = mediaButtonEvent.getParcelableExtra(Intent.EXTRA_KEY_EVENT);
            if (keyEvent.getKeyCode() == KeyEvent.KEYCODE_HEADSETHOOK) {
                if (keyEvent.getAction() == KeyEvent.ACTION_UP && !keyEvent.isLongPress()) {
                    onRemoteClick();
                }
                return true;
            } else {
                return super.onMediaButtonEvent(mediaButtonEvent);
            }
        }

        private void onRemoteClick() {
            mClickCount++;
            mHandler.removeCallbacks(mButtonHandler);
            mHandler.postDelayed(mButtonHandler, REMOTE_CLICK_SLEEP_TIME_MS);
        }

        @Override
        public void onPlay() {
            mMusicPlayer.play();
            mMusicPlayer.updateUi();
        }

        @Override
        public void onSkipToQueueItem(long id) {
            mMusicPlayer.changeSong((int) id);
            mMusicPlayer.updateUi();
        }

        @Override
        public void onPause() {
            mMusicPlayer.pause();
            mMusicPlayer.updateUi();
        }

        @Override
        public void onSkipToNext() {
            mMusicPlayer.skip();
            mMusicPlayer.updateUi();
        }

        @Override
        public void onSkipToPrevious() {
            mMusicPlayer.skipPrevious();
            mMusicPlayer.updateUi();
        }

        @Override
        public void onStop() {
            mMusicPlayer.stop();
            // Don't update the UI if this object has been released
            if (mMusicPlayer.mContext != null) {
                mMusicPlayer.updateUi();
            }
        }

        @Override
        public void onSeekTo(long pos) {
            mMusicPlayer.seekTo((int) pos);
            mMusicPlayer.updateUi();
        }
    }

    /**
     * Receives headphone connect and disconnect intents so that music may be paused when headphones
     * are disconnected
     */
    public static class HeadsetListener extends BroadcastReceiver {

        private MusicPlayer mInstance;

        public HeadsetListener(MusicPlayer instance) {
            mInstance = instance;
        }

        @Override
        public void onReceive(Context context, Intent intent) {
            if (!mInstance.isPlaying()) {
                return;
            }

            boolean unplugged = ACTION_HEADSET_PLUG.equals(intent.getAction())
                    && intent.getIntExtra("state", -1) == 0;

            boolean becomingNoisy = ACTION_AUDIO_BECOMING_NOISY.equals(intent.getAction());

            if (unplugged || becomingNoisy) {
                mInstance.pause();
                mInstance.updateUi();
            }
        }
    }

}
