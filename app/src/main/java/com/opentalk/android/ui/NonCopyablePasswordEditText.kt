package com.opentalk.android.ui

import android.content.Context
import android.os.Build
import android.text.method.PasswordTransformationMethod
import android.util.AttributeSet
import android.view.ActionMode
import android.view.Menu
import android.view.MenuItem
import android.view.View
import androidx.appcompat.widget.AppCompatEditText

/**
 * Strictly masked password field that blocks copying, cutting, sharing, and autofill
 * extraction while preserving normal typing, editing, and pasting of credentials.
 */
class NonCopyablePasswordEditText @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = androidx.appcompat.R.attr.editTextStyle
) : AppCompatEditText(context, attrs, defStyleAttr) {

    init {
        val stripCopyActionModeCallback = object : ActionMode.Callback {
            private fun stripForbiddenItems(menu: Menu?) {
                menu?.removeItem(android.R.id.copy)
                menu?.removeItem(android.R.id.cut)
                menu?.removeItem(android.R.id.shareText)
                menu?.removeItem(android.R.id.copyUrl)
            }
            override fun onCreateActionMode(mode: ActionMode?, menu: Menu?): Boolean {
                stripForbiddenItems(menu)
                return true
            }
            override fun onPrepareActionMode(mode: ActionMode?, menu: Menu?): Boolean {
                stripForbiddenItems(menu)
                return true
            }
            override fun onActionItemClicked(mode: ActionMode?, item: MenuItem?): Boolean = false
            override fun onDestroyActionMode(mode: ActionMode?) {}
        }
        transformationMethod = PasswordTransformationMethod.getInstance()
        customSelectionActionModeCallback = stripCopyActionModeCallback
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            customInsertionActionModeCallback = stripCopyActionModeCallback
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            importantForAutofill = View.IMPORTANT_FOR_AUTOFILL_NO
        }
    }

    override fun onTextContextMenuItem(id: Int): Boolean {
        if (id == android.R.id.copy ||
            id == android.R.id.cut ||
            id == android.R.id.shareText ||
            id == android.R.id.copyUrl
        ) {
            return false
        }
        return super.onTextContextMenuItem(id)
    }
}
