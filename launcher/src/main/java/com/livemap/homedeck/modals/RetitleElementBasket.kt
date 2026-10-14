package com.livemap.homedeck.modals

import android.app.Activity
import android.app.AlertDialog
import org.fossify.commons.extensions.*
import org.fossify.commons.helpers.ensureBackgroundThread
import com.livemap.homedeck.databinding.HdDialogRenameItemBinding
import com.livemap.homedeck.ext.homeScreenGridItemsDB
import com.livemap.homedeck.entities.MainDisplayLatticeElement

class RetitleElementBasket(val activity: Activity, val item: MainDisplayLatticeElement, val callback: () -> Unit) {

    init {
        val binding = HdDialogRenameItemBinding.inflate(activity.layoutInflater)
        val view = binding.root
        binding.lvRenameItemEdittextUi.setText(item.title)

        activity.getAlertDialogBuilder()
            .setPositiveButton(org.fossify.commons.R.string.ok, null)
            .setNegativeButton(org.fossify.commons.R.string.cancel, null)
            .apply {
                activity.setupDialogStuff(view, this, org.fossify.commons.R.string.rename) { alertDialog ->
                    alertDialog.showKeyboard(binding.lvRenameItemEdittextUi)
                    alertDialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                        val newTitle = binding.lvRenameItemEdittextUi.value
                        if (newTitle.isNotEmpty()) {
                            ensureBackgroundThread {
                                val result = activity.homeScreenGridItemsDB.updateItemTitle(newTitle, item.id!!)
                                if (result == 1) {
                                    callback()
                                    alertDialog.dismiss()
                                } else {
                                    activity.toast(org.fossify.commons.R.string.unknown_error_occurred)
                                }
                            }
                        } else {
                            activity.toast(org.fossify.commons.R.string.value_cannot_be_empty)
                        }
                    }
                }
            }
    }
}
