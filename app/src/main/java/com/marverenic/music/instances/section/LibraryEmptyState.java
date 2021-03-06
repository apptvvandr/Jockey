package com.marverenic.music.instances.section;

import android.app.Activity;
import android.content.Intent;
import android.net.Uri;
import android.provider.Settings;
import android.support.design.widget.Snackbar;

import com.marverenic.music.BuildConfig;
import com.marverenic.music.R;
import com.marverenic.music.data.store.MediaStoreUtil;
import com.marverenic.music.data.store.MusicStore;

public class LibraryEmptyState extends BasicEmptyState {

    private Activity mActivity;
    private MusicStore mMusicStore;

    public LibraryEmptyState(Activity activity, MusicStore musicStore) {
        mActivity = activity;
        mMusicStore = musicStore;
    }

    public String getEmptyMessage() {
        return mActivity.getString(R.string.empty);
    }

    @Override
    public final String getMessage() {
        if (MediaStoreUtil.hasPermission(mActivity)) {
            return getEmptyMessage();
        } else {
            return mActivity.getString(R.string.empty_no_permission);
        }
    }

    public String getEmptyMessageDetail() {
        return mActivity.getString(R.string.empty_detail);
    }

    @Override
    public final String getDetail() {
        if (MediaStoreUtil.hasPermission(mActivity)) {
            return getEmptyMessageDetail();
        } else {
            return mActivity.getString(R.string.empty_no_permission_detail);
        }
    }

    public String getEmptyAction1Label() {
        return mActivity.getString(R.string.action_refresh);
    }

    @Override
    public final String getAction1Label() {
        if (MediaStoreUtil.hasPermission(mActivity)) {
            return getEmptyAction1Label();
        } else {
            return mActivity.getString(R.string.action_try_again);
        }
    }

    public String getEmptyAction2Label() {
        return super.getAction2Label();
    }

    @Override
    public final String getAction2Label() {
        if (MediaStoreUtil.hasPermission(mActivity)) {
            return getEmptyAction2Label();
        } else {
            return mActivity.getString(R.string.action_open_settings);
        }
    }

    @Override
    public void onAction1() {
        mMusicStore.refresh().subscribe(successful -> {
            if (successful) {
                Snackbar
                        .make(
                                mActivity.findViewById(R.id.list),
                                R.string.confirm_refresh_library,
                                Snackbar.LENGTH_SHORT)
                        .show();
            }
        });
    }

    @Override
    public void onAction2() {
        if (!MediaStoreUtil.hasPermission(mActivity)) {
            Intent intent = new Intent();
            intent.setAction(Settings.ACTION_APPLICATION_DETAILS_SETTINGS);
            Uri uri = Uri.fromParts("package", BuildConfig.APPLICATION_ID, null);
            intent.setData(uri);
            mActivity.startActivity(intent);
        }
    }
}
