package com.uniguajira.signlanguagecollecto

import android.Manifest
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Intent
import android.content.pm.PackageManager
import android.location.LocationManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import com.uniguajira.signlanguagecollecto.databinding.ActivityMainBinding
import java.io.File
import java.io.FileWriter
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class MainActivity : AppCompatActivity(), BluetoothService.Listener {

    private lateinit var binding: ActivityMainBinding

    private var bluetoothAdapter: BluetoothAdapter? = null
    private var bluetoothService: BluetoothService? = null

    // Latest sensor reading — written from BLE callback thread, read from main thread
    @Volatile private var currentData: BluetoothService.SensorData? = null

    private var csvFile: File? = null
    private var sequenceCount = 0   // # distinct sequences in file (display only)
    private var maxSeqId = 0        // highest ID ever assigned; never decremented

    // ── Recognition mode ──────────────────────────────────────────────────────
    @Volatile private var recognizer: SignRecognizer? = null
    @Volatile private var isRecognitionMode = false
    private val wordHistory = mutableListOf<String>()
    private var lastConfirmedSign = ""

    // ── Sequence capture state ────────────────────────────────────────────────

    /** True only during the 1000 ms recording window. Written on main thread,
     *  read on BLE callback thread — must be @Volatile. */
    @Volatile private var isCapturing = false

    /** System.currentTimeMillis() when recording started; used to compute timestamp_ms. */
    @Volatile private var captureStartTime = 0L

    /** Accumulates (SensorData, timestamp_ms) pairs during the recording window.
     *  Accessed from both the BLE thread and the main thread — guard with synchronized. */
    private val captureBuffer = mutableListOf<Pair<BluetoothService.SensorData, Long>>()

    private val captureHandler = Handler(Looper.getMainLooper())

    private val quickLabels = listOf(
        "A","B","C","D","E","F","G","H","I","J","K","L","M",
        "N","Ñ","O","P","Q","R","S","T","U","V","W","X","Y","Z"
    )

    // ── BLE scanning state ───────────────────────────────────────────────────

    private var isScanning = false
    private var scanDialog: AlertDialog? = null
    private val foundDevices = mutableListOf<BluetoothDevice>()
    private var scanAdapter: ArrayAdapter<BluetoothDevice>? = null
    private val scanHandler = Handler(Looper.getMainLooper())

    /**
     * BLE scan callback. startScan() is called from the main thread so these
     * callbacks arrive on the main thread — no runOnUiThread needed, but it is
     * kept for safety (runOnUiThread is a no-op when already on the main thread).
     */
    private val scanCallback = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            val device = try { result.device } catch (ignored: SecurityException) { return }
            val name = try { device.name ?: "Unknown" } catch (ignored: SecurityException) { "Unknown" }
            Log.d(TAG, "BLE found: name=$name  addr=${device.address}  rssi=${result.rssi} dBm")
            // Deduplicate by MAC address before adding to the live list
            if (foundDevices.none { it.address == device.address }) {
                runOnUiThread {
                    foundDevices.add(device)
                    scanAdapter?.notifyDataSetChanged()
                }
            }
        }

        override fun onScanFailed(errorCode: Int) {
            Log.e(TAG, "BLE scan failed: errorCode=$errorCode")
            runOnUiThread {
                stopScan()
                scanDialog?.dismiss()
                Toast.makeText(
                    this@MainActivity,
                    "BLE scan failed (error $errorCode)",
                    Toast.LENGTH_LONG
                ).show()
            }
        }
    }

    // ── Permission launcher ──────────────────────────────────────────────────

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { results ->
        if (results.values.all { it }) {
            startBleScan()
        } else {
            Toast.makeText(
                this,
                "Permissions are required to scan for BLE devices.",
                Toast.LENGTH_LONG
            ).show()
        }
    }

    // ── Lifecycle ────────────────────────────────────────────────────────────

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        val manager = getSystemService(BluetoothManager::class.java)
        bluetoothAdapter = manager?.adapter

        if (bluetoothAdapter == null) {
            Toast.makeText(this, "Bluetooth is not supported on this device.", Toast.LENGTH_LONG).show()
            binding.btnSelectDevice.isEnabled = false
        }

        initCsvFile()
        setupQuickLabels()
        setupRecognizer()

        binding.btnSelectDevice.setOnClickListener { checkPermissionsAndStartScan() }
        binding.btnSave.setOnClickListener { startCapture() }
        binding.btnDelete.setOnClickListener { showDeleteDialog() }
        binding.btnExport.setOnClickListener { exportCsv() }
        binding.btnAddToHistory.setOnClickListener { addCurrentToHistory() }
        binding.btnClearHistory.setOnClickListener { clearHistory() }

        binding.bottomNav.setOnItemSelectedListener { item ->
            when (item.itemId) {
                R.id.navCapture    -> { switchMode(recognition = false); true }
                R.id.navRecognize  -> { switchMode(recognition = true);  true }
                else -> false
            }
        }

        updateConnectionStatus(connected = false, deviceName = null)
    }

    override fun onDestroy() {
        super.onDestroy()
        captureHandler.removeCallbacksAndMessages(null)
        isCapturing = false
        stopScan()
        scanDialog?.dismiss()
        bluetoothService?.disconnect()
        bluetoothService = null
        recognizer?.close()
        recognizer = null
    }

    // ── CSV initialisation ───────────────────────────────────────────────────

    private fun initCsvFile() {
        val ts = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(Date())
        // getExternalFilesDir requires no storage permission on API 29+;
        // falls back to internal files dir if external storage is unavailable.
        val dir = getExternalFilesDir(null) ?: filesDir
        csvFile = File(dir, "dataset_$ts.csv")
        try {
            FileWriter(csvFile, false).use { w ->
                w.write(
                    "flex1,flex2,flex3,flex4,flex5," +
                    "accelX,accelY,accelZ," +
                    "gyroX,gyroY,gyroZ," +
                    "roll,pitch,yaw,label,sequence_id,timestamp_ms\n"
                )
            }
        } catch (e: IOException) {
            Toast.makeText(this, "Could not create CSV file: ${e.message}", Toast.LENGTH_LONG).show()
        }
    }

    // ── Quick label buttons (created programmatically) ───────────────────────

    private fun setupQuickLabels() {
        val marginPx = (8 * resources.displayMetrics.density).toInt()
        quickLabels.forEach { label ->
            val btn = Button(this)
            btn.text = label
            btn.layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { marginEnd = marginPx }
            btn.setOnClickListener { binding.etLabel.setText(label) }
            binding.quickLabelsContainer.addView(btn)
        }
    }

    // ── Permission check ─────────────────────────────────────────────────────

    private fun checkPermissionsAndStartScan() {
        val adapter = bluetoothAdapter ?: run {
            Toast.makeText(this, "Bluetooth not available.", Toast.LENGTH_SHORT).show()
            return
        }
        if (!adapter.isEnabled) {
            Toast.makeText(this, "Please enable Bluetooth and try again.", Toast.LENGTH_LONG).show()
            return
        }

        val missing = mutableListOf<String>()
        // ACCESS_FINE_LOCATION is required on ALL API levels for BLE scanning.
        // On API 31+ it is also required unless BLUETOOTH_SCAN carries neverForLocation,
        // which our manifest does NOT declare, so we must always request it.
        if (!hasPermission(Manifest.permission.ACCESS_FINE_LOCATION))
            missing += Manifest.permission.ACCESS_FINE_LOCATION
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            // Android 12+ additionally needs runtime Bluetooth permissions
            if (!hasPermission(Manifest.permission.BLUETOOTH_SCAN))
                missing += Manifest.permission.BLUETOOTH_SCAN
            if (!hasPermission(Manifest.permission.BLUETOOTH_CONNECT))
                missing += Manifest.permission.BLUETOOTH_CONNECT
        }

        if (missing.isEmpty()) startBleScan() else permissionLauncher.launch(missing.toTypedArray())
    }

    private fun hasPermission(permission: String) =
        ContextCompat.checkSelfPermission(this, permission) == PackageManager.PERMISSION_GRANTED

    // ── BLE scan ─────────────────────────────────────────────────────────────

    private fun startBleScan() {
        // BLE scanning returns zero results if Location is off, with no error.
        // Check the Location toggle and prompt the user before wasting the 10-second window.
        if (!isLocationEnabled()) {
            Toast.makeText(
                this,
                "Location services are off — BLE scanning requires Location to be enabled.",
                Toast.LENGTH_LONG
            ).show()
            startActivity(Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS))
            return
        }

        val scanner = bluetoothAdapter?.bluetoothLeScanner ?: run {
            Toast.makeText(this, "BLE scanner not available.", Toast.LENGTH_SHORT).show()
            return
        }

        // Reset state for a fresh scan
        foundDevices.clear()
        isScanning = true

        // Build a live-updating ListView for the dialog
        val listView = ListView(this)
        scanAdapter = object : ArrayAdapter<BluetoothDevice>(this, 0, foundDevices) {
            override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
                val row = convertView
                    ?: LayoutInflater.from(context).inflate(R.layout.item_device, parent, false)
                val dev = getItem(position)!!
                val name = try { dev.name ?: "Unknown" } catch (ignored: SecurityException) { "Unknown" }
                row.findViewById<TextView>(R.id.tvDeviceName).text = name
                row.findViewById<TextView>(R.id.tvDeviceAddress).text = dev.address
                return row
            }
        }
        listView.adapter = scanAdapter
        listView.setOnItemClickListener { _, _, position, _ ->
            val device = foundDevices[position]
            stopScan()
            scanDialog?.dismiss()
            connectToDevice(device)
        }

        scanDialog = AlertDialog.Builder(this)
            .setTitle("Scanning for BLE devices…")
            .setView(listView)
            .setNegativeButton("Cancel") { _, _ -> stopScan() }
            // Covers back-press and any other dismiss path
            .setOnDismissListener { stopScan() }
            .show()

        Log.d(TAG, "BLE scan starting — null filters, SCAN_MODE_LOW_LATENCY")
        try {
            val settings = ScanSettings.Builder()
                .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
                .build()
            // null filter list = discover every advertising BLE device
            scanner.startScan(null, settings, scanCallback)
        } catch (e: SecurityException) {
            isScanning = false
            scanDialog?.dismiss()
            Toast.makeText(this, "Scan permission denied: ${e.message}", Toast.LENGTH_LONG).show()
            return
        }

        // Auto-stop after timeout; update title so the user sees the scan is done
        scanHandler.postDelayed({
            if (isScanning) {
                stopScan()
                scanDialog?.setTitle("Scan complete — ${foundDevices.size} device(s) found")
            }
        }, SCAN_TIMEOUT_MS)
    }

    /**
     * Stops an in-progress scan. Idempotent — safe to call multiple times.
     * Removes the pending timeout callback so it never fires after a manual stop.
     */
    private fun stopScan() {
        if (!isScanning) return
        isScanning = false
        scanHandler.removeCallbacksAndMessages(null)
        try {
            bluetoothAdapter?.bluetoothLeScanner?.stopScan(scanCallback)
        } catch (ignored: SecurityException) {
            // Permission revoked mid-scan — nothing to do
        }
    }

    // ── Connect ──────────────────────────────────────────────────────────────

    private fun connectToDevice(device: BluetoothDevice) {
        bluetoothService?.disconnect()
        bluetoothService = BluetoothService(device, this)
        bluetoothService!!.connect()

        val name = try { device.name ?: device.address } catch (ignored: SecurityException) { device.address }
        Toast.makeText(this, "Connecting to $name…", Toast.LENGTH_SHORT).show()
    }

    // ── BluetoothService.Listener (called on BLE GATT callback thread) ───────

    override fun onConnected(deviceName: String) {
        if (isDestroyed || isFinishing) return
        runOnUiThread { updateConnectionStatus(connected = true, deviceName = deviceName) }
    }

    override fun onSensorData(data: BluetoothService.SensorData) {
        currentData = data
        if (isCapturing) {
            val ts = System.currentTimeMillis() - captureStartTime
            synchronized(captureBuffer) { captureBuffer.add(Pair(data, ts)) }
        }
        // Run inference on BLE thread (fast, <5 ms) — update UI on main thread
        val result = if (isRecognitionMode) recognizer?.addFrame(data) else null
        if (isDestroyed || isFinishing) return
        runOnUiThread {
            updateSensorDisplay(data)
            if (result != null) updateRecognitionDisplay(result)
        }
    }

    override fun onDisconnected() {
        if (isDestroyed || isFinishing) return
        runOnUiThread {
            updateConnectionStatus(connected = false, deviceName = null)
            Toast.makeText(this, "Bluetooth disconnected.", Toast.LENGTH_SHORT).show()
        }
    }

    override fun onError(message: String) {
        if (isDestroyed || isFinishing) return
        runOnUiThread {
            updateConnectionStatus(connected = false, deviceName = null)
            Toast.makeText(this, "BT error: $message", Toast.LENGTH_LONG).show()
        }
    }

    // ── UI helpers ────────────────────────────────────────────────────────────

    private fun updateConnectionStatus(connected: Boolean, deviceName: String?) {
        val color = if (connected) R.color.connected else R.color.disconnected
        binding.statusBar.setBackgroundColor(ContextCompat.getColor(this, color))
        binding.tvStatus.text = if (connected) "● Connected: $deviceName" else "● Disconnected"
    }

    private fun updateSensorDisplay(d: BluetoothService.SensorData) {
        binding.tvFlex1.text  = d.flex1.toString()
        binding.tvFlex2.text  = d.flex2.toString()
        binding.tvFlex3.text  = d.flex3.toString()
        binding.tvFlex4.text  = d.flex4.toString()
        binding.tvFlex5.text  = d.flex5.toString()
        binding.tvAccelX.text = "%.3f".format(d.accelX)
        binding.tvAccelY.text = "%.3f".format(d.accelY)
        binding.tvAccelZ.text = "%.3f".format(d.accelZ)
        binding.tvGyroX.text  = "%.2f".format(d.gyroX)
        binding.tvGyroY.text  = "%.2f".format(d.gyroY)
        binding.tvGyroZ.text  = "%.2f".format(d.gyroZ)
        binding.tvRoll.text   = "%.1f".format(d.roll)
        binding.tvPitch.text  = "%.1f".format(d.pitch)
        binding.tvYaw.text    = "%.1f".format(d.yaw)
    }

    // ── Sequence capture ──────────────────────────────────────────────────────

    /** Entry point: validates state, then kicks off the 3-second countdown. */
    private fun startCapture() {
        if (isCapturing) return
        if (currentData == null) {
            Toast.makeText(this, "No hay datos del sensor — conecta el guante primero.", Toast.LENGTH_SHORT).show()
            return
        }
        val label = binding.etLabel.text.toString().trim()
        if (label.isEmpty()) {
            Toast.makeText(this, "Selecciona o escribe un label.", Toast.LENGTH_SHORT).show()
            return
        }

        binding.btnSave.isEnabled = false
        binding.tvCountdown.visibility = View.VISIBLE

        var count = 3
        val tick = object : Runnable {
            override fun run() {
                if (count > 0) {
                    binding.tvCountdown.text = "$count..."
                    count--
                    captureHandler.postDelayed(this, 1_000L)
                } else {
                    binding.tvCountdown.text = "¡YA!"
                    captureHandler.postDelayed({ startRecording(label) }, 300L)
                }
            }
        }
        captureHandler.post(tick)
    }

    /** Clears the buffer, opens the recording window, animates the progress bar. */
    private fun startRecording(label: String) {
        synchronized(captureBuffer) { captureBuffer.clear() }
        captureStartTime = System.currentTimeMillis()
        isCapturing = true

        binding.tvCountdown.visibility = View.GONE
        binding.progressCapture.progress = 0
        binding.progressCapture.visibility = View.VISIBLE

        val progressTick = object : Runnable {
            override fun run() {
                val elapsed = System.currentTimeMillis() - captureStartTime
                binding.progressCapture.progress =
                    (elapsed * 100L / CAPTURE_DURATION_MS).toInt().coerceAtMost(100)
                if (elapsed < CAPTURE_DURATION_MS) {
                    captureHandler.postDelayed(this, 16L) // ~60 fps
                } else {
                    finishCapture(label)
                }
            }
        }
        captureHandler.postDelayed(progressTick, 16L)
    }

    /** Closes the recording window, writes all buffered frames to CSV. */
    private fun finishCapture(label: String) {
        isCapturing = false
        binding.progressCapture.visibility = View.GONE
        binding.progressCapture.progress = 0
        binding.btnSave.isEnabled = true

        val samples = synchronized(captureBuffer) { captureBuffer.toList() }

        if (samples.isEmpty()) {
            Toast.makeText(this, "No se recibieron datos durante la grabación.", Toast.LENGTH_LONG).show()
            return
        }

        val file = csvFile ?: run {
            Toast.makeText(this, "CSV no inicializado.", Toast.LENGTH_SHORT).show()
            return
        }

        val seqId = ++maxSeqId
        try {
            FileWriter(file, true /* append */).use { w ->
                for ((data, ts) in samples) {
                    w.write(
                        "${data.flex1},${data.flex2},${data.flex3},${data.flex4},${data.flex5}," +
                        "${data.accelX},${data.accelY},${data.accelZ}," +
                        "${data.gyroX},${data.gyroY},${data.gyroZ}," +
                        "${data.roll},${data.pitch},${data.yaw},$label,$seqId,$ts\n"
                    )
                }
            }
            sequenceCount++
            binding.tvCounter.text = "Secuencias guardadas: $sequenceCount"
            Toast.makeText(
                this,
                "✓ Secuencia $seqId guardada (${samples.size} muestras en 1000 ms)",
                Toast.LENGTH_SHORT
            ).show()
        } catch (e: IOException) {
            maxSeqId-- // rollback — nothing was written
            Toast.makeText(this, "Error al guardar: ${e.message}", Toast.LENGTH_LONG).show()
        }
    }

    // ── Recognition mode ──────────────────────────────────────────────────────

    private fun setupRecognizer() {
        try {
            recognizer = SignRecognizer(this)
        } catch (e: Exception) {
            Toast.makeText(this, "No se pudo cargar el modelo: ${e.message}", Toast.LENGTH_LONG).show()
        }
    }

    private fun switchMode(recognition: Boolean) {
        isRecognitionMode = recognition
        if (recognition) {
            binding.scrollCapture.visibility     = View.GONE
            binding.scrollRecognition.visibility = View.VISIBLE
            recognizer?.reset()
            binding.tvPredictedSign.text = "—"
            binding.tvConfidence.text    = ""
            binding.progressWindow.progress = 0
            binding.tvWindowFill.text = "Acumulando frames: 0 / ${SignRecognizer.WINDOW_SIZE}"
        } else {
            binding.scrollCapture.visibility     = View.VISIBLE
            binding.scrollRecognition.visibility = View.GONE
        }
    }

    private fun updateRecognitionDisplay(result: SignRecognizer.Result) {
        binding.progressWindow.progress  = result.windowFill
        binding.tvWindowFill.text = "Frames: ${result.windowFill} / ${SignRecognizer.WINDOW_SIZE}"

        if (result.windowFill < SignRecognizer.WINDOW_SIZE) return

        if (result.confidence >= SignRecognizer.CONFIDENCE_THRESHOLD) {
            binding.tvPredictedSign.text = result.label
            binding.tvConfidence.text    = "${"%.1f".format(result.confidence * 100)}%"
            lastConfirmedSign = result.label
        } else {
            binding.tvPredictedSign.text = "?"
            binding.tvConfidence.text    = "${"%.1f".format(result.confidence * 100)}%"
        }
    }

    private fun addCurrentToHistory() {
        if (lastConfirmedSign.isEmpty()) {
            Toast.makeText(this, "No hay seña confirmada aún.", Toast.LENGTH_SHORT).show()
            return
        }
        wordHistory.add(lastConfirmedSign)
        binding.tvWordHistory.text = wordHistory.joinToString(" · ")
    }

    private fun clearHistory() {
        wordHistory.clear()
        lastConfirmedSign = ""
        binding.tvWordHistory.text = "—"
        binding.tvPredictedSign.text = "—"
        binding.tvConfidence.text = ""
    }

    // ── Delete sequence ───────────────────────────────────────────────────────

    private fun showDeleteDialog() {
        if (sequenceCount == 0) {
            Toast.makeText(this, "No hay secuencias guardadas.", Toast.LENGTH_SHORT).show()
            return
        }
        val input = android.widget.EditText(this).apply {
            inputType = android.text.InputType.TYPE_CLASS_NUMBER
            hint = "ID de la secuencia"
        }
        val padding = (16 * resources.displayMetrics.density).toInt()
        val container = android.widget.FrameLayout(this).apply {
            setPadding(padding, padding / 2, padding, 0)
            addView(input)
        }
        AlertDialog.Builder(this)
            .setTitle("Eliminar secuencia")
            .setMessage("Secuencias actuales: $sequenceCount\nIngresa el ID de la toma a eliminar:")
            .setView(container)
            .setPositiveButton("Eliminar") { _, _ ->
                val seqId = input.text.toString().trim().toIntOrNull()
                if (seqId == null || seqId < 1) {
                    Toast.makeText(this, "ID inválido.", Toast.LENGTH_SHORT).show()
                    return@setPositiveButton
                }
                deleteSequence(seqId)
            }
            .setNegativeButton("Cancelar", null)
            .show()
    }

    private fun deleteSequence(seqId: Int) {
        val file = csvFile ?: return
        if (!file.exists()) return

        val lines = try {
            file.readLines()
        } catch (e: IOException) {
            Toast.makeText(this, "Error al leer el CSV: ${e.message}", Toast.LENGTH_LONG).show()
            return
        }

        if (lines.isEmpty()) return
        val header = lines[0]

        // sequence_id is column index 15 in the CSV header
        val SEQ_ID_COL = 15

        val kept = lines.drop(1).filter { line ->
            val cols = line.split(",")
            // Keep the line if we can't parse sequence_id OR if it's a different sequence
            cols.getOrNull(SEQ_ID_COL)?.trim()?.toIntOrNull() != seqId
        }

        val removedRows = lines.size - 1 - kept.size
        if (removedRows == 0) {
            Toast.makeText(this, "No se encontró la secuencia $seqId.", Toast.LENGTH_SHORT).show()
            return
        }

        try {
            FileWriter(file, false).use { w ->
                w.write(header + "\n")
                kept.forEach { w.write(it + "\n") }
            }
        } catch (e: IOException) {
            Toast.makeText(this, "Error al reescribir el CSV: ${e.message}", Toast.LENGTH_LONG).show()
            return
        }

        // Recount distinct sequence IDs remaining in the file
        sequenceCount = kept.mapNotNull { line ->
            line.split(",").getOrNull(SEQ_ID_COL)?.trim()?.toIntOrNull()
        }.toSet().size

        binding.tvCounter.text = "Secuencias guardadas: $sequenceCount"
        Toast.makeText(
            this,
            "Secuencia $seqId eliminada ($removedRows muestras).",
            Toast.LENGTH_SHORT
        ).show()
    }

    // ── CSV export via share sheet ────────────────────────────────────────────

    private fun exportCsv() {
        val file = csvFile
        if (file == null || !file.exists()) {
            Toast.makeText(this, "No CSV file found.", Toast.LENGTH_SHORT).show()
            return
        }
        if (sequenceCount == 0) {
            Toast.makeText(this, "No hay secuencias guardadas aún.", Toast.LENGTH_SHORT).show()
            return
        }
        val uri = FileProvider.getUriForFile(this, "${packageName}.fileprovider", file)
        val shareIntent = Intent(Intent.ACTION_SEND).apply {
            type = "text/csv"
            putExtra(Intent.EXTRA_STREAM, uri)
            putExtra(Intent.EXTRA_SUBJECT, "Sign Language Dataset — ${file.name}")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        startActivity(Intent.createChooser(shareIntent, "Export CSV dataset via…"))
    }

    // ── Constants ─────────────────────────────────────────────────────────────

    private fun isLocationEnabled(): Boolean {
        val lm = getSystemService(LocationManager::class.java)
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            lm.isLocationEnabled
        } else {
            lm.isProviderEnabled(LocationManager.GPS_PROVIDER) ||
            lm.isProviderEnabled(LocationManager.NETWORK_PROVIDER)
        }
    }

    companion object {
        private const val TAG = "BLEScan"
        private const val SCAN_TIMEOUT_MS   = 10_000L
        private const val CAPTURE_DURATION_MS = 1_000L
    }
}
