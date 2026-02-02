package com.tezas.safestnotes

import android.os.Bundle

class FolderNotesFragment : NotesFragment() {

    companion object {
        fun newInstance(folderId: Int): FolderNotesFragment {
            val fragment = FolderNotesFragment()
            val args = Bundle()
            args.putInt(NotesFragment.ARG_FOLDER_ID, folderId)
            fragment.arguments = args
            return fragment
        }
    }
}
