package com.mfc.recentaudiobuffer

object ViewModelHolder {
    private lateinit var sharedViewModel: SharedViewModel

    fun setSharedViewModel(viewModel: SharedViewModel) {
        sharedViewModel = viewModel
    }

    fun getSharedViewModel(): SharedViewModel {
        return sharedViewModel
    }
}