package com.marverenic.music.viewmodel;

import android.content.Context;
import android.databinding.BaseObservable;
import android.support.v7.widget.PopupMenu;
import android.view.Gravity;
import android.view.Menu;
import android.view.View;

import com.marverenic.music.R;
import com.marverenic.music.activity.instance.GenreActivity;
import com.marverenic.music.instances.Genre;
import com.marverenic.music.instances.Library;
import com.marverenic.music.instances.PlaylistDialog;
import com.marverenic.music.player.PlayerController;
import com.marverenic.music.utils.Navigate;

import static com.marverenic.music.activity.instance.GenreActivity.GENRE_EXTRA;

public class GenreViewModel extends BaseObservable {

    private Context mContext;
    private Genre mGenre;

    public GenreViewModel(Context context) {
        mContext = context;
    }

    public void setGenre(Genre genre) {
        mGenre = genre;
        notifyChange();
    }

    public String getName() {
        return mGenre.getGenreName();
    }

    public View.OnClickListener onClickGenre() {
        return v -> Navigate.to(mContext, GenreActivity.class, GENRE_EXTRA, mGenre);
    }

    public View.OnClickListener onClickMenu() {
        return v -> {
            final PopupMenu menu = new PopupMenu(mContext, v, Gravity.END);
            String[] options = mContext.getResources().getStringArray(R.array.queue_options_genre);

            for (int i = 0; i < options.length;  i++) {
                menu.getMenu().add(Menu.NONE, i, i, options[i]);
            }
            menu.setOnMenuItemClickListener(onMenuItemClick(v));
            menu.show();
        };
    }

    private PopupMenu.OnMenuItemClickListener onMenuItemClick(View view) {
        return menuItem -> {
            switch (menuItem.getItemId()) {
                case 0: //Queue this genre next
                    PlayerController.queueNext(Library.getGenreEntries(mGenre));
                    return true;
                case 1: //Queue this genre last
                    PlayerController.queueLast(Library.getGenreEntries(mGenre));
                    return true;
                case 2: //Add to playlist
                    PlaylistDialog.AddToNormal.alert(view, Library.getGenreEntries(mGenre),
                            mContext.getString(R.string.header_add_song_name_to_playlist, mGenre));
                    return true;
            }
            return false;
        };
    }

}
