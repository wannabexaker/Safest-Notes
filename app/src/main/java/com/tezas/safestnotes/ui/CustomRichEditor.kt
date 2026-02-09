package com.tezas.safestnotes.ui

import com.tezas.safestnotes.R

import android.content.Context
import android.util.AttributeSet
import jp.wasabeef.richeditor.RichEditor

/**
 * A custom RichEditor that exposes the protected 'exec' method as public.
 * This is the proper workaround to call JavaScript functions directly when a specific
 * method (like setTodo) is problematic or unavailable in the library's public API.
 */
class CustomRichEditor @JvmOverloads constructor(
    context: Context, 
    attrs: AttributeSet? = null, 
    defStyleAttr: Int = 0
) : RichEditor(context, attrs, defStyleAttr) {

    public override fun exec(trigger: String?) {
        super.exec(trigger)
    }
}
