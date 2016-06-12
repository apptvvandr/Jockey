package com.marverenic.music.instances.section;

import android.support.annotation.NonNull;
import android.view.LayoutInflater;
import android.view.ViewGroup;

import com.marverenic.music.databinding.InstancePlaylistBinding;
import com.marverenic.music.instances.Playlist;
import com.marverenic.music.view.EnhancedAdapters.EnhancedViewHolder;
import com.marverenic.music.view.EnhancedAdapters.HeterogeneousAdapter;
import com.marverenic.music.viewmodel.PlaylistViewModel;

import java.util.List;

public class PlaylistSection extends HeterogeneousAdapter.ListSection<Playlist> {

    public static final int ID = 3574;

    public PlaylistSection(@NonNull List<Playlist> data) {
        super(ID, data);
    }

    @Override
    public EnhancedViewHolder<Playlist> createViewHolder(HeterogeneousAdapter adapter,
                                                                      ViewGroup parent) {
        return ViewHolder.createViewHolder(parent);
    }

    public static class ViewHolder extends EnhancedViewHolder<Playlist> {

        private InstancePlaylistBinding mBinding;

        public static ViewHolder createViewHolder(ViewGroup parent) {
            InstancePlaylistBinding binding = InstancePlaylistBinding.inflate(
                    LayoutInflater.from(parent.getContext()), parent, false);

            return new ViewHolder(binding);
        }

        public ViewHolder(InstancePlaylistBinding binding) {
            super(binding.getRoot());
            mBinding = binding;
            mBinding.setViewModel(new PlaylistViewModel(itemView.getContext()));
        }

        @Override
        public void update(Playlist item, int sectionPosition) {
            mBinding.getViewModel().setPlaylist(item);
        }
    }
}
