package com.zebra.sample.multiactivitysample1.ui.first

import android.util.Log
import android.widget.TextView
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.lifecycleScope
import com.zebra.sample.multiactivitysample1.data.models.DWOutputData
import com.zebra.sample.multiactivitysample1.databinding.ActivityMainBinding
import com.zebra.sample.multiactivitysample1.ui.adapter.ItemAdapter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * Handles all UI-related operations for MainActivity.
 * Separates View logic from Business logic and ensures thread safety for UI updates.
 */
class MainUIHandler(
    private val lifecycleOwner: LifecycleOwner,
    private val statusTextView: TextView?,
    private val itemAdapter: ItemAdapter,
    private val binding: ActivityMainBinding
) {

    sealed class UIAction {
        data class StatusUpdate(val message: String) : UIAction()
        object ClearTags : UIAction()
        data class RefreshTagList(val tagMap: Map<String, Int>) : UIAction()
        data class TotalCount(val count: Int) : UIAction()
    }

    fun perform(action: UIAction) {
        // Ensure all UI operations happen on the Main thread
        lifecycleOwner.lifecycleScope.launch(Dispatchers.Main) {
            when (action) {
                is UIAction.StatusUpdate -> {
                    statusTextView?.text = action.message
                }
                is UIAction.ClearTags -> {
                    // Assuming ItemAdapter has a method to clear, or we re-init
                    // itemAdapter.clear()
                }
                is UIAction.TotalCount -> {
                    statusTextView?.text = "RFID Engine Read Total = ${action.count}"
                }
                is UIAction.RefreshTagList -> {
//                    // Efficiently update the list from the snapshot
//                    action.tagMap.forEach { (epc, count) ->
//                        val rfidData = DWOutputData(epc, "Count: $count")
//                        itemAdapter.addItem(rfidData)
//                    }
//                    if (action.tagMap.isNotEmpty()) {
//                        binding.rvActivity1.smoothScrollToPosition(0)
//                    }

                    // Map the DB to the UI Model
                    val dataList = action.tagMap.map { (epc, count) ->
                        DWOutputData(epc, "Count: $count")
                    }

                    // Optimization: Update adapter in bulk
                    // Note: Ensure your ItemAdapter.addItem is efficient
                    // or implement itemAdapter.submitList(dataList)
                    dataList.forEach { itemAdapter.addItem(it) }

                    if (dataList.isNotEmpty()) {
                        binding.rvActivity1.scrollToPosition(0)
                    }
                }
            }
        }
    }
}