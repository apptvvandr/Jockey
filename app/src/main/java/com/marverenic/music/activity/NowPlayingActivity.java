package com.marverenic.music.activity;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.graphics.drawable.ColorDrawable;
import android.os.Build;
import android.os.Bundle;
import android.support.annotation.StringRes;
import android.support.design.widget.Snackbar;
import android.support.v7.app.ActionBar;
import android.util.DisplayMetrics;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import android.widget.ImageView;
import android.widget.Toast;

import com.marverenic.music.JockeyApplication;
import com.marverenic.music.R;
import com.marverenic.music.data.store.MediaStoreUtil;
import com.marverenic.music.data.store.PreferencesStore;
import com.marverenic.music.dialog.AppendPlaylistDialogFragment;
import com.marverenic.music.dialog.CreatePlaylistDialogFragment;
import com.marverenic.music.fragments.QueueFragment;
import com.marverenic.music.instances.Song;
import com.marverenic.music.player.MusicPlayer;
import com.marverenic.music.player.PlayerController;
import com.marverenic.music.view.GestureView;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

import javax.inject.Inject;

import static android.content.res.Configuration.ORIENTATION_LANDSCAPE;
import static android.support.design.widget.Snackbar.LENGTH_LONG;
import static android.support.design.widget.Snackbar.LENGTH_SHORT;

public class NowPlayingActivity extends BaseActivity implements GestureView.OnGestureListener {

    private static final String TAG_MAKE_PLAYLIST = "CreatePlaylistDialog";
    private static final String TAG_APPEND_PLAYLIST = "AppendPlaylistDialog";

    @Inject PreferencesStore mPrefStore;

    private ImageView artwork;
    private GestureView artworkWrapper;
    private Song lastPlaying;
    private QueueFragment queueFragment;

    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        onNewIntent(getIntent());
        setContentView(R.layout.activity_now_playing);

        JockeyApplication.getComponent(this).inject(this);

        boolean landscape = getResources().getConfiguration().orientation == ORIENTATION_LANDSCAPE;

        if (!landscape) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                getWindow().getDecorView().setSystemUiVisibility(
                        View.SYSTEM_UI_FLAG_LAYOUT_STABLE | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN);
                getWindow().setStatusBarColor(Color.TRANSPARENT);
            }
            findViewById(R.id.artworkSwipeFrame).getLayoutParams().height = getArtworkHeight();
        } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            getWindow().getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_VISIBLE);
        }

        artwork = (ImageView) findViewById(R.id.imageArtwork);
        queueFragment =
                (QueueFragment) getSupportFragmentManager().findFragmentById(R.id.listFragment);

        ActionBar actionBar = getSupportActionBar();
        if (actionBar != null) {
            if (!landscape) {
                actionBar.setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
            } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                actionBar.setElevation(getResources().getDimension(R.dimen.header_elevation));
            }
            actionBar.setTitle("");
            actionBar.setHomeAsUpIndicator(R.drawable.ic_clear_24dp);
        }

        artworkWrapper = (GestureView) findViewById(R.id.artworkSwipeFrame);
        if (artworkWrapper != null) {
            artworkWrapper.setGestureListener(this);
            artworkWrapper.setGesturesEnabled(mPrefStore.enableNowPlayingGestures());
        }

        onUpdate();
    }

    private int getArtworkHeight() {
        int reservedHeight = (int) getResources().getDimension(R.dimen.player_frame_peek);

        DisplayMetrics metrics = new DisplayMetrics();
        getWindowManager().getDefaultDisplay().getMetrics(metrics);

        // Default to a square view, so set the height equal to the width
        //noinspection SuspiciousNameCombination
        int preferredHeight = metrics.widthPixels;
        int maxHeight = metrics.heightPixels - reservedHeight;

        return Math.min(preferredHeight, maxHeight);
    }

    @Override
    public void onNewIntent(Intent intent) {
        // Handle incoming requests to play media from other applications
        if (intent.getData() == null) return;

        // If this intent is a music intent, process it
        if (intent.getType().contains("audio") || intent.getType().contains("application/ogg")
                || intent.getType().contains("application/x-ogg")
                || intent.getType().contains("application/itunes")) {

            // The queue to be passed to the player service
            ArrayList<Song> queue = new ArrayList<>();
            int position = 0;

            try {
                position = MediaStoreUtil.getSongListFromFile(this,
                        new File(intent.getData().getPath()), intent.getType(), queue);
            } catch (Exception e) {
                e.printStackTrace();
                queue = new ArrayList<>();
            }

            if (queue.isEmpty()) {
                // No music was found
                Toast toast = Toast.makeText(this, R.string.message_play_error_not_found,
                        Toast.LENGTH_SHORT);
                toast.show();
                finish();
            } else {
                if (PlayerController.isServiceStarted()) {
                    PlayerController.setQueue(queue, position);
                    PlayerController.begin();
                } else {
                    // If the service hasn't been bound yet, then we need to wait for the service to
                    // start before we can pass data to it. This code will bind a short-lived
                    // BroadcastReceiver to wait for the initial UPDATE broadcast to be sent before
                    // sending data. Once it has fulfilled its purpose it will unbind itself to
                    // avoid a lot of problems later on.

                    final ArrayList<Song> pendingQueue = queue;
                    final int pendingPosition = position;

                    final BroadcastReceiver binderWaiter = new BroadcastReceiver() {
                        @Override
                        public void onReceive(Context context, Intent intent) {
                            PlayerController.setQueue(pendingQueue, pendingPosition);
                            PlayerController.begin();
                            NowPlayingActivity.this.unregisterReceiver(this);
                        }
                    };
                    registerReceiver(binderWaiter, new IntentFilter(MusicPlayer.UPDATE_BROADCAST));
                }
            }
        }
    }

    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        // Inflate the menu; this adds items to the action bar if it is present.
        getMenuInflater().inflate(R.menu.activity_now_playing, menu);

        updateShuffleIcon(menu.findItem(R.id.action_shuffle));
        updateRepeatIcon(menu.findItem(R.id.action_repeat));

        return true;
    }

    private void updateShuffleIcon(MenuItem shuffleMenuItem) {
        if (mPrefStore.isShuffled()) {
            shuffleMenuItem.getIcon().setAlpha(255);
            shuffleMenuItem.setTitle(getResources().getString(R.string.action_disable_shuffle));
        } else {
            shuffleMenuItem.getIcon().setAlpha(128);
            shuffleMenuItem.setTitle(getResources().getString(R.string.action_enable_shuffle));
        }
    }

    private void updateRepeatIcon(MenuItem repeatMenuItem) {
        if (mPrefStore.getRepeatMode() == MusicPlayer.REPEAT_ALL) {
            repeatMenuItem.getIcon().setAlpha(255);
            repeatMenuItem.setTitle(getResources().getString(R.string.action_enable_repeat_one));
        } else if (mPrefStore.getRepeatMode() == MusicPlayer.REPEAT_ONE) {
            repeatMenuItem.setIcon(R.drawable.ic_repeat_one_24dp);
            repeatMenuItem.setTitle(getResources().getString(R.string.action_disable_repeat));
        } else {
            repeatMenuItem.setIcon(R.drawable.ic_repeat_24dp);
            repeatMenuItem.getIcon().setAlpha(128);
            repeatMenuItem.setTitle(getResources().getString(R.string.action_enable_repeat));
        }
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        switch (item.getItemId()) {
            case R.id.action_shuffle:
                toggleShuffle(item);
                return true;
            case R.id.action_repeat:
                toggleRepeat(item);
                return true;
            case R.id.save:
                saveQueueAsPlaylist();
                return true;
            case R.id.add_to_playlist:
                addQueueToPlaylist();
                return true;
            case R.id.clear_queue:
                clearQueue();
                return true;
        }
        return super.onOptionsItemSelected(item);
    }

    private void toggleShuffle(MenuItem shuffleMenuItem) {
        mPrefStore.toggleShuffle();
        PlayerController.updatePlayerPreferences(mPrefStore);

        if (mPrefStore.isShuffled()) {
            showSnackbar(R.string.confirm_enable_shuffle);
        } else {
            showSnackbar(R.string.confirm_disable_shuffle);
        }

        updateShuffleIcon(shuffleMenuItem);
        queueFragment.updateShuffle();
    }

    private void toggleRepeat(MenuItem repeatMenuItem) {
        mPrefStore.cycleRepeatMode();
        PlayerController.updatePlayerPreferences(mPrefStore);

        if (mPrefStore.getRepeatMode() == MusicPlayer.REPEAT_ALL) {
            showSnackbar(R.string.confirm_enable_repeat);
        } else if (mPrefStore.getRepeatMode() == MusicPlayer.REPEAT_ONE) {
            showSnackbar(R.string.confirm_enable_repeat_one);
        } else {
            showSnackbar(R.string.confirm_disable_repeat);
        }

        updateRepeatIcon(repeatMenuItem);
    }

    private void saveQueueAsPlaylist() {
        CreatePlaylistDialogFragment.newInstance()
                .setSongs(PlayerController.getQueue())
                .showSnackbarIn(R.id.imageArtwork)
                .show(getSupportFragmentManager(), TAG_MAKE_PLAYLIST);
    }

    private void addQueueToPlaylist() {
        AppendPlaylistDialogFragment.newInstance()
                .setTitle(getString(R.string.header_add_queue_to_playlist))
                .setSongs(PlayerController.getQueue())
                .showSnackbarIn(R.id.imageArtwork)
                .show(getSupportFragmentManager(), TAG_APPEND_PLAYLIST);
    }

    private void clearQueue() {
        List<Song> previousQueue = PlayerController.getQueue();
        int previousQueueIndex = PlayerController.getQueuePosition();

        int previousSeekPosition = PlayerController.getCurrentPosition();
        boolean wasPlaying = PlayerController.isPlaying();

        PlayerController.clearQueue();

        Snackbar.make(findViewById(R.id.imageArtwork), R.string.confirm_clear_queue, LENGTH_LONG)
                .setAction(R.string.action_undo, view -> {
                    PlayerController.editQueue(previousQueue, previousQueueIndex);
                    PlayerController.seek(previousSeekPosition);

                    if (wasPlaying) {
                        PlayerController.begin();
                    }
                })
                .show();
    }

    @Override
    public void onUpdate() {
        super.onUpdate();
        final Song nowPlaying = PlayerController.getNowPlaying();
        if (lastPlaying == null || !lastPlaying.equals(nowPlaying)) {
            Bitmap image = PlayerController.getArtwork();
            if (image == null) {
                artwork.setImageResource(R.drawable.art_default_xl);
            } else {
                artwork.setImageBitmap(image);
            }

            lastPlaying = nowPlaying;
        }
    }

    private void showSnackbar(@StringRes int stringId) {
        showSnackbar(getString(stringId));
    }

    @Override
    protected void showSnackbar(String message) {
        Snackbar.make(findViewById(R.id.imageArtwork), message, LENGTH_SHORT).show();
    }

    @Override
    public void onLeftSwipe() {
        PlayerController.skip();
    }

    @Override
    public void onRightSwipe() {
        int queuePosition = PlayerController.getQueuePosition() - 1;
        if (queuePosition < 0 && mPrefStore.getRepeatMode() == MusicPlayer.REPEAT_ALL) {
            queuePosition += PlayerController.getQueueSize();
        }

        if (queuePosition >= 0) {
            PlayerController.changeSong(queuePosition);
        } else {
            PlayerController.seek(0);
        }

    }

    @Override
    public void onTap() {
        PlayerController.togglePlay();

        //noinspection deprecation
        artworkWrapper.setTapIndicator(getResources().getDrawable(
                (PlayerController.isPlaying())
                        ? R.drawable.ic_play_arrow_36dp
                        : R.drawable.ic_pause_36dp));
    }
}
