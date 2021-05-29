package com.impressions.videoencoding.fragment;

import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;

import com.impressions.videoencoding.R;
import com.nononsenseapps.filepicker.FilePickerFragment;

public class FastScrollerFilePickerFragment extends FilePickerFragment {
    @Override
    protected View inflateRootView(LayoutInflater inflater, ViewGroup container) {
        return inflater.inflate(R.layout.fragment_fastscrollerfilepicker, container, false);
    }
}