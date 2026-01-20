package com.zebra.sample.multiactivitysample1.ui.first

import android.annotation.SuppressLint
import android.widget.TextView
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.lifecycleScope
import com.zebra.sample.multiactivitysample1.data.models.DWOutputData
import com.zebra.sample.multiactivitysample1.databinding.ActivityMainBinding
import com.zebra.sample.multiactivitysample1.ui.adapter.ItemAdapter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * MainUIHandler: Responsible for decoupling UI updates from the Activity logic.
 * This class ensures all UI modifications happen on the Main thread and optimizes
 * list rendering to prevent UI stutter during high-speed RFID scanning.
 */
class MainUIHandler(
    private val lifecycleOwner: LifecycleOwner,
    private val statusTextView: TextView?,
    private val itemAdapter: ItemAdapter,
    private val binding: ActivityMainBinding
) {

    /**
     * Defines the set of possible UI updates that can be requested by the Activity.
     */
    sealed class UIAction {
        data class StatusUpdate(val message: String) : UIAction()
        data class RefreshTagList(val tagMap: Map<String, Int>) : UIAction()
        data class TotalCount(val count: Int) : UIAction()
    }

    /**
     * Executes the requested [UIAction] on the Main thread.
     *
     * @param action The specific UI update operation to perform.
     */
    @SuppressLint("SetTextI18n")
    fun perform(action: UIAction) {
        // Ensure all UI operations happen on the Main thread
        lifecycleOwner.lifecycleScope.launch(Dispatchers.Main) {
            when (action) {
                /**
                 * Updates the RFID status label (usually the connection or operation state).
                 */
                is UIAction.StatusUpdate -> {
                    statusTextView?.text = action.message
                }
                /**
                 * Updates the status label with the total summary count from the RFID engine.
                 */
                is UIAction.TotalCount -> {
                    statusTextView?.text = "RFID Engine Read Total = ${action.count}"
                }
                /**
                 * Optimized batch update for the RecyclerView.
                 * Converts the tag database map into a list of UI models and refreshes the adapter.
                 */
                is UIAction.RefreshTagList -> {

                    // Batch conversion from Map entries to the UI Data Model
                    val dataList = action.tagMap.map { (epc, count) ->
                        DWOutputData(epc, "EPC Read Count: $count")
                    }

                    // Optimization: Update adapter in bulk
                    // Note: Ensure your ItemAdapter.addItem is efficient
                    // or implement itemAdapter.submitList(dataList)
                    dataList.forEach { itemAdapter.addItem(it) }

                    // Auto-scroll to top to show latest activity
                    if (dataList.isNotEmpty()) {
                        binding.rvActivity1.scrollToPosition(0)
                    }
                }
            }
        }
    }
}