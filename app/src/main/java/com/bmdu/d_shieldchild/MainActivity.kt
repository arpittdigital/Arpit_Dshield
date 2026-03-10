
package com.bmdu.d_shieldchild

import android.Manifest
import android.annotation.SuppressLint
import android.app.admin.DeviceAdminReceiver
import android.app.admin.DevicePolicyManager
import android.app.admin.FactoryResetProtectionPolicy
import android.content.ClipData
import android.content.ClipboardManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.UserManager
import android.telephony.SubscriptionManager
import android.telephony.TelephonyManager
import android.text.InputType
import android.util.Log
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import androidx.annotation.RequiresApi
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.lifecycleScope
import com.bmdu.d_shieldchild.databinding.ActivityMainBinding
import com.bmdu.d_shieldchild.managers.CommandManager
import com.bmdu.d_shieldchild.managers.EMIManager
import com.bmdu.d_shieldchild.managers.EMICheckWorker
import com.bmdu.d_shieldchild.managers.FirebaseManager
import com.bmdu.d_shieldchild.managers.RegisterViewModel
import com.bmdu.d_shieldchild.managers.RegisterViewModelFactory
import com.bmdu.d_shieldchild.receivers.DShieldAdminReceiver
import com.bmdu.d_shieldchild.receivers.SimChangeReceiver
import com.bmdu.d_shieldchild.services.MyForegroundService
import com.bmdu.d_shieldchild.utils.Constants
import com.bmdu.d_shieldchild.utils.OverlayHelper
import com.google.firebase.FirebaseApp
import com.google.firebase.messaging.FirebaseMessaging
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await

class MainActivity : AppCompatActivity() {

    private lateinit var statusTextView: TextView
    private lateinit var btnRefresh: Button
    private lateinit var btnEnableAdmin: Button
    private lateinit var btnRemoveApp: Button

    private lateinit var devicePolicyManager: DevicePolicyManager
    private lateinit var adminComponent: ComponentName
    private lateinit var simChangeReceiver: SimChangeReceiver
    private lateinit var binding: ActivityMainBinding
    private lateinit var registerViewModel: RegisterViewModel

    companion object {
        private const val TAG = "MainActivity"
        private const val REQUEST_CODE_ENABLE_ADMIN = 1001
        private const val REQUEST_OVERLAY_PERMISSION = 999
        private const val REQUEST_PHONE_PERMISSION = 1002
        private const val PERMISSION_REQUEST_CODE = 100
        private const val ACTION_FRP_CONFIG_CHANGED =
            "com.google.android.gms.auth.FRP_CONFIG_CHANGED"
        private const val GMSCORE_PACKAGE = "com.google.android.gms"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        handleProvisioningExtras()
        val factory = RegisterViewModelFactory(applicationContext)
        registerViewModel = ViewModelProvider(this, factory)
            .get(RegisterViewModel::class.java)

        handleDeepLink(intent)
        // Initialize Firebase
        try {
            FirebaseApp.initializeApp(this)
            Log.d(TAG, "✅ Firebase initialized")
        } catch (e: Exception) {
            Log.e(TAG, "❌ Firebase init failed: ${e.message}")
        }

        // Initialize managers
        FirebaseManager.init(this)
        EMIManager.init(this)

        devicePolicyManager = getSystemService(Context.DEVICE_POLICY_SERVICE) as DevicePolicyManager
        adminComponent = ComponentName(this, DShieldAdminReceiver::class.java)

        // Setup UI first
        setupUI()

        // Apply security
        applySecurityPolicies()
        grantPrivilegedPermissions()

        // Start services
        startForegroundService()

        // Check permissions
        checkAndRequestOverlayIfNeeded()

        // Register device to Firebase (Firebase registration only)
        FirebaseManager.registerDevice()

        // ✅ FIXED: Register device to API server with FCM token
        fetchFcmAndRegisterToServer()

        // Observe registration status
        observeRegistrationStatus()
        requestPhonePermissions()

        // Check EMI
        checkEMILockStatus()

        // Update status
        updateStatus()
        checkRemoveCommandStatus()

        // Schedule EMI worker after delay
        lifecycleScope.launch {
            kotlinx.coroutines.delay(5000)
            EMIManager.checkAndApplyLock()
        }

        handleIncomingRemoveCommand()
    }

    private fun handleDeepLink(intent: Intent?) {

        val data = intent?.data

        Log.d("DEEPLINK_TEST", "Received deep link: $data")

        if (data?.scheme == "hiddenapp" && data.host == "open") {

            Log.d("DEEPLINK_TEST", "Deep link triggered successfully")
        }
    }
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleDeepLink(intent)
    }

    override fun onResume() {
        super.onResume()
        updateStatus()

        checkRemoveCommandStatus()

        if (EMIManager.hasEMIData()) {
            EMIManager.checkAndApplyLock()
        }
    }


    private fun handleProvisioningExtras() {
        try {
            val extras = intent.getBundleExtra("android.app.extra.PROVISIONING_ADMIN_EXTRAS_BUNDLE")

            if (extras != null) {
                val deviceId = extras.getString("device_id")
                val pairToken = extras.getString("pair_token")
                val autoProvision = extras.getBoolean("auto_provision", false)

                Log.d(TAG, "========================================")
                Log.d(TAG, "📦 PROVISIONING EXTRAS RECEIVED:")
                Log.d(TAG, "   Device ID: $deviceId")
                Log.d(TAG, "   Pair Token: $pairToken")
                Log.d(TAG, "   Auto Provision: $autoProvision")
                Log.d(TAG, "========================================")

                // Save to SharedPreferences
                val prefs = getSharedPreferences("DShieldPrefs", MODE_PRIVATE)
                prefs.edit().apply {
                    putString("device_id", deviceId)
                    putString("pair_token", pairToken)
                    putBoolean("auto_provision", autoProvision)
                    putBoolean("provisioning_completed", true)
                    putLong("provisioning_time", System.currentTimeMillis())
                    apply()
                }

                Log.d(TAG, "✅ Provisioning data saved to SharedPreferences")

            } else {
                Log.w(TAG, "⚠️ No provisioning extras found in intent")

                // Check if already provisioned before
                val prefs = getSharedPreferences("DShieldPrefs", MODE_PRIVATE)
                val savedDeviceId = prefs.getString("device_id", null)
                val savedToken = prefs.getString("pair_token", null)

                if (savedDeviceId != null && savedToken != null) {
                    Log.d(TAG, "📋 Using saved provisioning data:")
                    Log.d(TAG, "   Device ID: $savedDeviceId")
                    Log.d(TAG, "   Pair Token: $savedToken")
                } else {
                    Log.w(TAG, "⚠️ No saved provisioning data found either")
                }
            }

        } catch (e: Exception) {
            Log.e(TAG, "❌ Error handling provisioning extras: ${e.message}", e)
        }
    }

    private fun setupUI() {
        statusTextView = findViewById(R.id.statusText)
        btnRefresh = findViewById(R.id.btnCheckStatus)
        btnEnableAdmin = findViewById(R.id.btnEnableAdmin)
        btnRemoveApp = findViewById(R.id.removedevice)

        btnRemoveApp.visibility = View.GONE

        btnRefresh.setOnClickListener {
            Toast.makeText(this, "🔄 Refreshing...", Toast.LENGTH_SHORT).show()

            FirebaseManager.registerDevice()
            fetchFcmAndRegisterToServer()
            if (EMIManager.hasEMIData()) {
                EMIManager.checkAndApplyLock()
            }

            updateStatus()
            checkRemoveCommandStatus()
        }

        btnEnableAdmin.setOnClickListener {
            enableDeviceAdmin()
        }

        btnRemoveApp.setOnClickListener {
            // ✅ Show password dialog
            showRemovalPasswordDialog()
        }
    }

    /**
     * ✅ Check if server sent remove command
     */
    private fun checkRemoveCommandStatus() {
        val prefs = getSharedPreferences(Constants.PREF_NAME, MODE_PRIVATE)
        val removeAllowed = prefs.getBoolean("remove_allowed", false)

        if (removeAllowed) {
            btnRemoveApp.visibility = View.VISIBLE
            Log.d(TAG, "🗑️ Remove button VISIBLE (server authorized)")
        } else {
            btnRemoveApp.visibility = View.GONE
            Log.d(TAG, "🔒 Remove button HIDDEN (not authorized)")
        }
    }

    /**
     * ✅ Handle remove command from FCM
     */
    private fun handleIncomingRemoveCommand() {
        if (intent.getBooleanExtra("do_remove", false)) {
            Log.w(TAG, "🗑️ Remove command received from FCM")

            // Set flag to show remove button
            setRemoveAllowed(true)
            checkRemoveCommandStatus()

            // Show password dialog
            showRemovalPasswordDialog()
        }
    }
    private fun requestPhonePermissions() {
        val permissionsNeeded = mutableListOf<String>()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            // Android 13+
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.READ_PHONE_NUMBERS)
                != PackageManager.PERMISSION_GRANTED) {
                permissionsNeeded.add(Manifest.permission.READ_PHONE_NUMBERS)
            }
        } else {
            // Android 12 and below
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.READ_PHONE_STATE)
                != PackageManager.PERMISSION_GRANTED) {
                permissionsNeeded.add(Manifest.permission.READ_PHONE_STATE)
            }
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.READ_SMS)
                != PackageManager.PERMISSION_GRANTED) {
                permissionsNeeded.add(Manifest.permission.READ_SMS)
            }
        }

        if (permissionsNeeded.isNotEmpty()) {
            Log.d(TAG, "📱 Requesting phone permissions...")
            ActivityCompat.requestPermissions(
                this,
                permissionsNeeded.toTypedArray(),
                PERMISSION_REQUEST_CODE
            )
        } else {
            Log.d(TAG, "✅ All phone permissions already granted")
        }
    }

    private fun showRemovalPasswordDialog() {
        val imeiList = FirebaseManager.getAllImei()

        if (imeiList.size < 2) {
            showRemovalConfirmationDialog()
            return
        }

        val imei2 = imeiList[1] // IMEI2 as password

        val input = EditText(this)
        input.inputType = InputType.TYPE_CLASS_NUMBER
        input.hint = "Enter IMEI2"

        AlertDialog.Builder(this)
            .setTitle("🔐 Verify Removal")
            .setMessage(
                "To remove DShield, enter your device's IMEI2.\n\n" +
                        "Find it: Settings → About Phone → Status → IMEI 2"
            )
            .setView(input)
            .setPositiveButton("Verify") { _, _ ->
                val enteredPassword = input.text.toString().trim()

                if (enteredPassword == imei2) {
                    Log.d(TAG, "✅ IMEI2 verified - proceeding with removal")
                    showRemovalConfirmationDialog()
                } else {
                    Toast.makeText(
                        this,
                        "❌ Wrong IMEI2! Contact retailer for help.",
                        Toast.LENGTH_LONG
                    ).show()
                    Log.w(TAG, "❌ IMEI2 verification failed")
                }
            }
            .setNegativeButton("Cancel", null)
            .setNeutralButton("Copy IMEI2") { _, _ ->
                // Copy IMEI2 to clipboard
                val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                val clip = ClipData.newPlainText("IMEI2", imei2)
                clipboard.setPrimaryClip(clip)
                Toast.makeText(this, "✅ IMEI2 copied to clipboard", Toast.LENGTH_SHORT).show()

                // Show dialog again
                showRemovalPasswordDialog()
            }
            .setCancelable(false)
            .show()
    }

    private fun showRemovalConfirmationDialog() {
        AlertDialog.Builder(this)
            .setTitle("⚠️ Remove DShield")
            .setMessage(
                "This will:\n" +
                        "• Disable all security policies\n" +
                        "• Remove device admin rights\n" +
                        "• Uninstall the app\n\n" +
                        "Are you sure?"
            )
            .setPositiveButton("Yes, Remove") { _, _ ->
                performRemoval()
            }
            .setNegativeButton("Cancel", null)
            .setCancelable(true)
            .show()
    }

    private fun setRemoveAllowed(allowed: Boolean) {
        val prefs = getSharedPreferences(Constants.PREF_NAME, MODE_PRIVATE)
        prefs.edit().putBoolean("remove_allowed", allowed).apply()

        checkRemoveCommandStatus()

        Log.d(TAG, "✅ Remove allowed set to: $allowed")
    }

    private fun isRemoveAllowed(): Boolean {
        val prefs = getSharedPreferences(Constants.PREF_NAME, MODE_PRIVATE)
        return prefs.getBoolean("remove_allowed", false)
    }

    private fun performRemoval() {
        Toast.makeText(this, "🗑️ Removing app...", Toast.LENGTH_SHORT).show()

        try {
            // Stop lock task if active
            try {
                stopLockTask()
            } catch (e: Exception) {
                Log.d(TAG, "Not in lock task: ${e.message}")
            }

            // Disable FRP
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                disableFactoryResetProtection()
            }

            // Clear device owner
            try {
                if (devicePolicyManager.isDeviceOwnerApp(packageName)) {
                    devicePolicyManager.clearDeviceOwnerApp(packageName)
                    Log.d(TAG, "✅ Device owner cleared")
                }
            } catch (e: Exception) {
                Log.e(TAG, "❌ Clear owner failed: ${e.message}")
            }

            // Remove admin
            try {
                if (devicePolicyManager.isAdminActive(adminComponent)) {
                    devicePolicyManager.removeActiveAdmin(adminComponent)
                    Log.d(TAG, "✅ Admin removed")
                }
            } catch (e: Exception) {
                Log.e(TAG, "❌ Remove admin failed: ${e.message}")
            }

            // ✅ Clear the remove_allowed flag
            setRemoveAllowed(false)

            // Uninstall
            val uninstallIntent = Intent(Intent.ACTION_DELETE)
            uninstallIntent.data = Uri.parse("package:$packageName")
            uninstallIntent.flags = Intent.FLAG_ACTIVITY_NEW_TASK
            startActivity(uninstallIntent)

        } catch (e: Exception) {
            Log.e(TAG, "❌ Removal failed: ${e.message}")
            Toast.makeText(this, "❌ Removal failed: ${e.message}", Toast.LENGTH_LONG).show()
        }
    }

    private fun enableDeviceAdmin() {
        if (devicePolicyManager.isAdminActive(adminComponent)) {
            Toast.makeText(this, "✅ Already enabled", Toast.LENGTH_SHORT).show()
            return
        }

        val intent = Intent(DevicePolicyManager.ACTION_ADD_DEVICE_ADMIN)
        intent.putExtra(DevicePolicyManager.EXTRA_DEVICE_ADMIN, adminComponent)
        intent.putExtra(
            DevicePolicyManager.EXTRA_ADD_EXPLANATION,
            "DShield needs Device Admin to secure the device"
        )
        startActivityForResult(intent, REQUEST_CODE_ENABLE_ADMIN)
    }

    private fun startForegroundService() {
        try {
            val serviceIntent = Intent(this, MyForegroundService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                startForegroundService(serviceIntent)
            } else {
                startService(serviceIntent)
            }
            Log.d(TAG, "✅ Foreground service started")
        } catch (e: Exception) {
            Log.e(TAG, "❌ Service start failed: ${e.message}")
        }
    }

    private fun fetchFcmAndRegisterToServer() {
        lifecycleScope.launch {
            try {

                val prefs = getSharedPreferences(Constants.PREF_NAME, Context.MODE_PRIVATE)
                val isRegistered = prefs.getBoolean("device_registered_to_server", false)

                if (isRegistered) {
                    Log.d(TAG, "✅ Device already registered to server")
                    return@launch
                }

                // Get FCM Token
                val fcmToken = FirebaseMessaging.getInstance().token.await()
                Log.d(TAG, "🔑 FCM Token retrieved: $fcmToken")

                // Get device info
                val firebaseDeviceId = FirebaseManager.getDeviceIdPublic()
                val imeiList = FirebaseManager.getAllImei()
                val phoneList = getAllPhoneNumbers(this@MainActivity)

                if (imeiList.isEmpty()) {
                    Log.e(TAG, "❌ No IMEI found")
                    Toast.makeText(this@MainActivity, "❌ IMEI not found", Toast.LENGTH_SHORT).show()
                    return@launch
                }

                val imei = imeiList[0]
                val phoneNumber = if (phoneList.isNotEmpty()) phoneList[0] else ""

                val manufacturer = Build.MANUFACTURER
                val deviceName = "${Build.MANUFACTURER} ${Build.MODEL}"
                val androidVersion = Build.VERSION.RELEASE

                Log.d(TAG, "📤 Registering device to server...")
                Log.d(TAG, "   Firebase ID: $firebaseDeviceId")
                Log.d(TAG, "   IMEI: $imei")
                Log.d(TAG, "   Phone: $phoneNumber")
                Log.d(TAG, "   FCM Token: ${fcmToken.take(20)}...")

                registerViewModel.registerDeviceToServer(
                    firebaseDeviceId = firebaseDeviceId,
                    imei = imei,
                    phoneNumber = phoneNumber,
                    androidVersion = androidVersion,
                    manufacturer = manufacturer,
                    deviceName = deviceName,
                    fcmToken = fcmToken
                )

            } catch (e: Exception) {
                Log.e(TAG, "❌ Failed to get FCM token: ${e.message}", e)
                Toast.makeText(this@MainActivity, "❌ FCM Token error: ${e.message}", Toast.LENGTH_SHORT).show()
            }
        }
    }


    private fun registerSimChangeReceiver() {
        try {
            simChangeReceiver = SimChangeReceiver()

            val filter = IntentFilter().apply {
                addAction("android.telephony.action.SIM_CARD_STATE_CHANGED")
                addAction("android.telephony.action.SIM_APPLICATION_STATE_CHANGED")
                addAction("android.telephony.action.CARRIER_CONFIG_CHANGED")
            }

            registerReceiver(simChangeReceiver, filter)
            Log.d(TAG, "✅ SimChangeReceiver registered successfully")

        } catch (e: Exception) {
            Log.e(TAG, "❌ Failed to register SimChangeReceiver", e)
        }
    }

    private fun observeRegistrationStatus() {
        registerViewModel.registrationStatus.observe(this) { state ->
            when (state) {
                is RegisterViewModel.RegistrationState.Idle -> {
                    Log.d(TAG, "⏸️ Registration idle")
                }
                is RegisterViewModel.RegistrationState.Loading -> {
                    Log.d(TAG, "⏳ Registering...")
                    Toast.makeText(this, "⏳ Registering device...", Toast.LENGTH_SHORT).show()
                }
                is RegisterViewModel.RegistrationState.Success -> {
                    Log.d(TAG, "✅ Registration successful!")
                    Toast.makeText(this, "✅ Device registered!", Toast.LENGTH_SHORT).show()

                    // Save registration status
                    val prefs = getSharedPreferences(Constants.PREF_NAME, Context.MODE_PRIVATE)
                    prefs.edit().apply {
                        putBoolean("device_registered_to_server", true)
                        putString("customer_name", state.response.device.customer_name ?: "N/A")
                        apply()
                    }

                    updateStatus()
                }
                is RegisterViewModel.RegistrationState.Error -> {
                    Log.e(TAG, "❌ Registration failed: ${state.message}")
                    Toast.makeText(this, "❌ Registration failed: ${state.message}", Toast.LENGTH_LONG).show()
                }
            }
        }
    }



    private fun checkEMILockStatus() {
        if (EMIManager.hasEMIData()) {
            EMIManager.checkAndApplyLock()
        } else {
            Log.d(TAG, "⏳ No EMI data configured yet")
        }
    }

    private fun applySecurityPolicies() {
        if (!devicePolicyManager.isDeviceOwnerApp(packageName)) {
            Log.w(TAG, "⚠️ Not device owner - security limited")
            return
        }

        try {
            val um = getSystemService(Context.USER_SERVICE) as UserManager
            devicePolicyManager.addUserRestriction(adminComponent, UserManager.DISALLOW_FACTORY_RESET)
            devicePolicyManager.addUserRestriction(adminComponent, UserManager.DISALLOW_SAFE_BOOT)

            devicePolicyManager.setLockTaskPackages(adminComponent, arrayOf(packageName))

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                enableFactoryResetProtection()
            }

            Log.d(TAG, "✅ Security policies applied")
        } catch (e: Exception) {
            Log.e(TAG, "❌ Security policy error: ${e.message}")
        }
    }

    @RequiresApi(Build.VERSION_CODES.R)
    private fun enableFactoryResetProtection() {
        try {
            val accounts = listOf("tarunanutilization@gmail.com")
            val policy = FactoryResetProtectionPolicy.Builder()
                .setFactoryResetProtectionAccounts(accounts)
                .setFactoryResetProtectionEnabled(true)
                .build()

            devicePolicyManager.setFactoryResetProtectionPolicy(adminComponent, policy)
            Log.d(TAG, "✅ FRP enabled")
        } catch (e: Exception) {
            Log.e(TAG, "❌ FRP enable failed: ${e.message}")
        }
    }

    @RequiresApi(Build.VERSION_CODES.R)
    private fun disableFactoryResetProtection() {
        try {
            devicePolicyManager.setFactoryResetProtectionPolicy(adminComponent, null)
            Log.d(TAG, "✅ FRP disabled")
        } catch (e: Exception) {
            Log.e(TAG, "❌ FRP disable failed: ${e.message}")
        }
    }

    private fun grantPrivilegedPermissions() {
        if (!devicePolicyManager.isDeviceOwnerApp(packageName)) {
            return
        }

        try {
            val permissions = arrayOf(
                android.Manifest.permission.READ_PHONE_STATE,
                android.Manifest.permission.READ_SMS,
                android.Manifest.permission.RECEIVE_SMS
            )

            permissions.forEach { permission ->
                try {
                    devicePolicyManager.setPermissionGrantState(
                        adminComponent,
                        packageName,
                        permission,
                        DevicePolicyManager.PERMISSION_GRANT_STATE_GRANTED
                    )
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to grant $permission: ${e.message}")
                }
            }

            Log.d(TAG, "✅ Permissions granted")
        } catch (e: Exception) {
            Log.e(TAG, "❌ Permission grant error: ${e.message}")
        }
    }

    private fun checkAndRequestOverlayIfNeeded() {
        if (FirebaseManager.needsPhoneNumbers()) {
            Log.w(TAG, "⚠️ Phone numbers not available")
            if (hasOverlayPermission()) {
                OverlayHelper.showNumberInputOverlay(this)
            } else {
                requestOverlayPermission()
            }
        }
    }

    private fun hasOverlayPermission(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            android.provider.Settings.canDrawOverlays(this)
        } else {
            true
        }
    }

    private fun requestOverlayPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            val intent = Intent(
                android.provider.Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                Uri.parse("package:$packageName")
            )
            startActivityForResult(intent, REQUEST_OVERLAY_PERMISSION)
        }
    }

    private fun updateStatus() {
        val deviceId = FirebaseManager.getDeviceIdPublic()
        val isAdmin = devicePolicyManager.isAdminActive(adminComponent)
        val isOwner = devicePolicyManager.isDeviceOwnerApp(packageName)

        val imeiList = FirebaseManager.getAllImei()
        val phoneList = getAllPhoneNumbers(this)

        val imei = if (imeiList.isNotEmpty()) imeiList.joinToString(", ") else "unknown"
        val currentno = if (phoneList.isNotEmpty()) phoneList.joinToString(", ") else "unknown"

        val hasPermission = checkSelfPermission(android.Manifest.permission.READ_PHONE_STATE) ==
                PackageManager.PERMISSION_GRANTED

        // Check server
        val prefs = getSharedPreferences(Constants.PREF_NAME, Context.MODE_PRIVATE)
        val isServerRegistered = prefs.getBoolean("device_registered_to_server", false)
        val customerName = prefs.getString("customer_name", "N/A") ?: "N/A"

        // ✅ Show remove status
        val removeAllowed = prefs.getBoolean("remove_allowed", false)

        // EMI Status
        val emiStatus = if (EMIManager.hasEMIData()) {
            val daysLeft = EMIManager.getDaysUntilLockDate()
            if (daysLeft >= 0) {
                "💰 EMI: ✅ Active (${daysLeft} days until lock)"
            } else {
                "💰 EMI: 🔒 Device should be locked!"
            }
        } else {
            "💰 EMI: Not configured"
        }

        val status = """
        📱 DShield Child App
        🆔 Device ID: $deviceId

        🔥 Firebase: Connected ✅
        🔧 Service: Running ✅
        🌐 Server: ${if (isServerRegistered) "✅ Registered" else "⏳ Pending"}
        ${if (isServerRegistered) "👤 Customer: $customerName" else ""}
        $emiStatus
        🗑️ Remove: ${if (removeAllowed) "✅ Authorized" else "🔒 Locked"}

        🔐 Device Admin: ${if (isAdmin) "✅ ENABLED" else "❌ DISABLED"}
        👑 Device Owner: ${if (isOwner) "✅ YES" else "❌ NO"}
        📞 Phone Permission: ${if (hasPermission) "✅ GRANTED" else "❌ DENIED"}

        📱 IMEI: $imei
        📱 Phone Numbers: $currentno

        ${
            when {
                !isOwner -> "⚠️ Set device owner via ADB for maximum protection!"
                !hasPermission -> "⚠️ Grant phone permission for IMEI access!"
                !isServerRegistered -> "⏳ Waiting for server registration..."
                else -> "✅ Maximum security active!"
            }
        }
    """.trimIndent()

        statusTextView.text = status
    }

    @SuppressLint("MissingPermission", "HardwareIds")
    private fun getAllPhoneNumbers(context: Context): List<String> {
        val list = mutableListOf<String>()

        if (checkSelfPermission(android.Manifest.permission.READ_PHONE_STATE)
            != PackageManager.PERMISSION_GRANTED
        ) {
            return list
        }

        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP_MR1) {
                val sm = context.getSystemService(Context.TELEPHONY_SUBSCRIPTION_SERVICE) as SubscriptionManager
                val subs = sm.activeSubscriptionInfoList

                subs?.forEach { info ->
                    val number = info.number
                    if (!number.isNullOrBlank()) {
                        list.add(number)
                    }
                }
            }

            if (list.isEmpty()) {
                val tm = context.getSystemService(Context.TELEPHONY_SERVICE) as TelephonyManager
                @Suppress("DEPRECATION")
                val line1Number = tm.line1Number
                if (!line1Number.isNullOrBlank()) {
                    list.add(line1Number)
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error getting phone numbers: ${e.message}")
        }

        return list
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)

        when (requestCode) {
            REQUEST_CODE_ENABLE_ADMIN -> {
                if (devicePolicyManager.isAdminActive(adminComponent)) {
                    Toast.makeText(this, "✅ Device Admin Enabled", Toast.LENGTH_SHORT).show()
                    applySecurityPolicies()
                    grantPrivilegedPermissions()
                } else {
                    Toast.makeText(this, "❌ Device Admin NOT Enabled", Toast.LENGTH_LONG).show()
                }
                updateStatus()
            }

            REQUEST_OVERLAY_PERMISSION -> {
                if (hasOverlayPermission()) {
                    Toast.makeText(this, "✅ Overlay Permission Granted", Toast.LENGTH_SHORT).show()
                    if (FirebaseManager.needsPhoneNumbers()) {
                        OverlayHelper.showNumberInputOverlay(this)
                    }
                } else {
                    Toast.makeText(this, "❌ Overlay Permission Required!", Toast.LENGTH_LONG).show()
                }
            }
        }
    }
}


