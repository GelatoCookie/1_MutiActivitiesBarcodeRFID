package com.zebra.sample.multiactivitysample1.ui.first

import android.Manifest
import android.content.Intent
import android.content.pm.ActivityInfo
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.view.View
import android.widget.TextView
import android.widget.Toast
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import com.zebra.rfid.api3.ENUM_TRANSPORT
import com.zebra.rfid.api3.HANDHELD_TRIGGER_EVENT_TYPE
import com.zebra.rfid.api3.RFIDReader
import com.zebra.rfid.api3.ReaderDevice
import com.zebra.rfid.api3.Readers
import com.zebra.rfid.api3.RfidEventsListener
import com.zebra.rfid.api3.RfidReadEvents
import com.zebra.rfid.api3.RfidStatusEvents
import com.zebra.rfid.api3.STATUS_EVENT_TYPE
import com.zebra.rfid.api3.TagData
import com.zebra.sample.multiactivitysample1.R
import com.zebra.sample.multiactivitysample1.data.models.DWOutputData
import com.zebra.sample.multiactivitysample1.databinding.ActivityMainBinding
import com.zebra.sample.multiactivitysample1.scanner.DWCommunicationWrapper
import com.zebra.sample.multiactivitysample1.ui.adapter.ItemAdapter
import com.zebra.sample.multiactivitysample1.ui.second.SecondActivity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

// Main activity that handles UI initialization, observes ViewModel state, and interacts with DataWedge.
class MainActivity : AppCompatActivity(), Readers.RFIDReaderEventHandler, RfidEventsListener {

    // Binding for accessing views in the activity layout.
    private lateinit var binding: ActivityMainBinding

    // ViewModel instance scoped to the activity lifecycle.
    private val viewModel by viewModels<MainViewModel>()

    // Flags to track profile creation and initial configuration progression.
    private var isProfileCreated = false
    private var initialConfigInProgression = false

    // Adapter for managing RecyclerView items.
    private val itemAdapter = ItemAdapter()

    /* RFID reader */
    private lateinit var readers: Readers
    private lateinit var availableRFIDReaderList: ArrayList<ReaderDevice>
    private lateinit var readerDevice: ReaderDevice
    private lateinit var reader: RFIDReader
    private var lblRfidData: TextView? = null

    private var tagDB: HashMap<String, Int> = HashMap()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // 1. Set Portrait Mode
        requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_PORTRAIT

        // Mark initial configuration as in progress.
        initialConfigInProgression = true

        // Initialize the view components and layout.
        initView()

        // Register observers to listen for changes in ViewModel state.
        registerObservers()

        // Register broadcast receivers for DataWedge communication.
        DWCommunicationWrapper.registerReceivers()

        // Query DataWedge status to initialize the profile and settings.
        viewModel.getStatus()

        // RFID
        lblRfidData = findViewById(R.id.textrfid)
        Log.d(TAG, "#0 Checking Permission")
        checkAndInitRFID()
        tagDB.clear()
    }

    // Sets up LiveData observers to update the UI based on ViewModel changes.
    private fun registerObservers() {
        viewModel.isLoading.observe(this) {
            // Show or hide loading indicator based on loading state.
            binding.clLoading.visibility = if (it) View.VISIBLE else View.GONE
        }

        viewModel.scanViewStatus.observe(this) { scanViewState ->
            // Handle profile creation status.
            scanViewState.dwProfileCreate?.let { dwProfileCreate ->
                if (dwProfileCreate.isProfileCreated) {
                    viewModel.setConfig()
                } else {
                    Toast.makeText(
                        this,
                        getString(R.string.profile_creation_failed),
                        Toast.LENGTH_LONG
                    ).show()
                }
            }

            // Handle profile update status.
            scanViewState.dwProfileUpdate?.let { dwProfileUpdate ->
                if (dwProfileUpdate.isProfileUpdated) {
                    initialConfigInProgression = false
                }
            }

            // Handle DataWedge status and create profile if necessary.
            scanViewState.dwStatus?.let { dwStatus ->
                if (dwStatus.isEnable) {
                    if (!isProfileCreated) {
                        viewModel.createProfile()
                        isProfileCreated = true
                    }
                } else {
                    Toast.makeText(
                        this,
                        getString(R.string.datawedge_is, dwStatus.statusString),
                        Toast.LENGTH_LONG
                    ).show()
                }
            }

            // Add scanned output data to the RecyclerView and scroll to the top.
            scanViewState.dwOutputData?.let { dwOutputData ->
                itemAdapter.addItem(dwOutputData)
                binding.rvActivity1.smoothScrollToPosition(0)
            }

            // Update scanner state information in the UI.
            scanViewState.dwScannerState?.let { dwScannerState ->
                binding.tvProfile.text = dwScannerState.profileName
                binding.tvScannerStatus.text = dwScannerState.statusStr
            }
        }
    }

    // Initializes the activity view components and sets up UI elements.
    private fun initView() {
        binding = ActivityMainBinding.inflate(layoutInflater)
        binding.viewModel = viewModel
        enableEdgeToEdge()
        setContentView(binding.root)

        // Set the action bar title.
        supportActionBar?.title = getString(R.string.activity_1)

        // Apply window insets to adjust layout for system bars.
        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.main)) { v, insets ->
            val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(systemBars.left, systemBars.top, systemBars.right, systemBars.bottom)
            insets
        }

        // Set up RecyclerView with adapter and layout manager.
        with(binding.rvActivity1) {
            adapter = itemAdapter
            layoutManager = LinearLayoutManager(this@MainActivity, LinearLayoutManager.VERTICAL, false)
        }

        // Launch SecondActivity when the button is clicked.
        binding.buttonFirst.setOnClickListener {
            startActivity(Intent(this, SecondActivity::class.java))
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        // Unregister broadcast receivers and notifications.
        DWCommunicationWrapper.unregisterReceivers()
        viewModel.unregisterNotifications()
        disconnect();
    }

    override fun onResume() {
        super.onResume()
        // Set configuration if initial setup is complete.
        if (!initialConfigInProgression) viewModel.setConfig()
    }

    ///////////////////////////////////////////////////
    private fun checkAndInitRFID() {
        val permissions = mutableListOf(
            Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.ACCESS_COARSE_LOCATION
        )

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            permissions.add(Manifest.permission.BLUETOOTH_CONNECT)
            permissions.add(Manifest.permission.BLUETOOTH_SCAN)
        }

        val missingPermissions = permissions.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }

        if (missingPermissions.isNotEmpty()) {
            ActivityCompat.requestPermissions(this, missingPermissions.toTypedArray(), 600)
        } else {
            initRFID()
        }
    }

    private fun initRFID() {
        try {
            readers = Readers(this, ENUM_TRANSPORT.ALL)
            InitReaderConnection()
        } catch (e: Exception) {
            Log.e(TAG, "Failed to initialize Readers SDK", e)
        }
    }

    private fun InitReaderConnection() {
        lifecycleScope.launch {
            val isConnected = withContext(Dispatchers.IO) {
                try {
                    if (::readers.isInitialized) {
                        val availableReaders = readers.GetAvailableRFIDReaderList()
                        if (!availableReaders.isNullOrEmpty()) {
                            availableRFIDReaderList = availableReaders
                            readerDevice = availableRFIDReaderList[0]

                            Readers.attach(this@MainActivity)
                            reader = readerDevice?.rfidReader!!!!

                            if (reader?.isConnected == false) {

                                runOnUiThread {
                                    lblRfidData!!?.setText("Connecnting...")
                                }
                                reader?.connect()
                                runOnUiThread {
                                    lblRfidData!!?.setText("Connnected to " + reader?.hostName)
                                }
                                ConfigureReader()
                                return@withContext true
                            }
                        }
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "RFID Connection Error", e)
                }
                false
            }

            if (isConnected) {
                Log.d(TAG, "#6 Connected OK, Reader=${reader?.hostName}")
                testRead()
            }
        }
    }

    private fun testRead() {
        lifecycleScope.launch {
            if (reader?.isConnected == true) {
                Log.d(TAG, "Reader connected, starting inventory test...")
                startInventory()
                delay(2000) // Non-blocking delay (optimized)
                stopInventory()
            }
        }
    }

    private fun ConfigureReader() {
        reader?.let { r ->
            try {
                if (r.isConnected) {
                    r.Events.apply {
                        addEventsListener(this@MainActivity)
                        setHandheldEvent(true)
                        setTagReadEvent(true)
                        setAttachTagDataWithReadEvent(false)
                        setInventoryStartEvent(true)
                        setInventoryStopEvent(true)
                        setOperationEndSummaryEvent(true)
                        setReaderDisconnectEvent(true)
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Configuration Error", e)
            }
        }
    }

    private fun startInventory() {
        try {
            tagDB.clear();
            reader?.Actions?.Inventory?.perform()
        } catch (e: Exception) {
            Log.e(TAG, "Start Inventory Failed", e)
        }
    }

    private fun stopInventory() {
        try {
            if (reader?.isConnected == true) {
                reader?.Actions?.Inventory?.stop()
            }
        } catch (e: Exception) {
            Log.e(TAG, "Stop Inventory Failed", e)
        }
    }

    private fun disconnect() {
        try {
            reader?.let {
                if (it.isConnected) {
                    it.Events.removeEventsListener(this)
                    it.disconnect()
                }
            }
            if (::readers.isInitialized) {
                readers.Dispose()
            }
        } catch (e: Exception) {
            Log.e(TAG, "Disconnect Error", e)
        }
    }

    ////////////////////////////////////////////////////////////////////
    // RFID API Events
    override fun RFIDReaderAppeared(p0: ReaderDevice?) {
        Log.d(TAG, "ECRT: +RFIDReaderAppeared")
    }

    override fun RFIDReaderDisappeared(p0: ReaderDevice?) {
        Log.d(TAG, "ECRT: -RFIDReaderDisappeared")
    }

    override fun eventReadNotify(rfidReadEvents: RfidReadEvents?) {
        val scannedTags: Array<TagData>? = reader?.Actions?.getReadTags(100)
        scannedTags?.forEach { tag ->
            tag.tagID?.let { epc ->
                val iSeenCount = tag.tagSeenCount
                Log.d(TAG, "Read EPC: $epc, count: $iSeenCount")
                if (!tagDB.containsKey(epc))
                {
                    tagDB .put(epc, iSeenCount)
                    val rfidData : DWOutputData = DWOutputData(epc, "EPC")
                    itemAdapter.addItem(rfidData)
                    binding.rvActivity1.smoothScrollToPosition(0)
                }
                else{
                    var iUpdatedCount = iSeenCount + tagDB .get(epc)!!
                    tagDB .put(epc, iUpdatedCount)
                }
            }
        }
    }

    override fun eventStatusNotify(rfidStatusEvents: RfidStatusEvents?) {
        if (rfidStatusEvents?.StatusEventData?.statusEventType == STATUS_EVENT_TYPE.DISCONNECTION_EVENT) {
            Log.d(TAG, "ECRT: TO DO Disconnect Event")
        }
        if (rfidStatusEvents?.StatusEventData?.statusEventType == STATUS_EVENT_TYPE.INVENTORY_START_EVENT) {
            //uiHandler.perform(MainUIHandler.UIAction.StatusUpdate("RFID Status:    Unoqie Tag Count = " + tagDB.size))
            Log.d(TAG, "ECRT: INVENTORY_START_EVENT")
        }
        if (rfidStatusEvents?.StatusEventData?.statusEventType == STATUS_EVENT_TYPE.INVENTORY_STOP_EVENT) {
            Log.d(TAG, "ECRT: INVENTORY_STOP_EVENT")
            Log.d(TAG, "Unique Tag Count: " + tagDB.size)
            runOnUiThread {
                //lblRfidData!!?.setText("Unique Tag count = " + tagDB.size)
            }
        }
        if (rfidStatusEvents?.StatusEventData?.statusEventType == STATUS_EVENT_TYPE.OPERATION_END_SUMMARY_EVENT) {
            Log.d(TAG, "ECRT: Operation End Summary")
            var iCount = rfidStatusEvents?.StatusEventData?.OperationEndSummaryData?.totalTags
            if (rfidStatusEvents != null) {
                Log.d(TAG, "RFID Engine Total = " + iCount)
                runOnUiThread {
                    lblRfidData!!?.setText("Total Tag " + iCount)
                }
            }
        }
        if (rfidStatusEvents!!.StatusEventData.statusEventType === STATUS_EVENT_TYPE.HANDHELD_TRIGGER_EVENT) {
            if (rfidStatusEvents!!.StatusEventData.HandheldTriggerEventData.handheldEvent === HANDHELD_TRIGGER_EVENT_TYPE.HANDHELD_TRIGGER_PRESSED) {
                Log.d(TAG, "ECRT: HANDHELD_TRIGGER_PRESSED")
                startInventory()
            }
            else if (rfidStatusEvents!!.StatusEventData.HandheldTriggerEventData.handheldEvent === HANDHELD_TRIGGER_EVENT_TYPE.HANDHELD_TRIGGER_RELEASED) {
                Log.d(TAG, "ECRT: HANDHELD_TRIGGER_RELEASED")
                stopInventory()
            }
        }
    }

    companion object {
        private const val TAG = "RFID"
        private const val RFID_VID = 1504
    }

}
