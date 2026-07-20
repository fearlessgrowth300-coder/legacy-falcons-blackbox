package top.niunaijun.blackboxa.view.main

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.net.VpnService
import android.os.Bundle
import android.util.Log
import android.view.Menu
import android.view.MenuItem
import android.widget.LinearLayout
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.core.content.edit
import androidx.lifecycle.lifecycleScope
import androidx.viewpager2.widget.ViewPager2
import com.afollestad.materialdialogs.MaterialDialog
import com.afollestad.materialdialogs.input.input
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import top.niunaijun.blackbox.BlackBoxCore
import top.niunaijun.blackboxa.R
import top.niunaijun.blackboxa.app.App
import top.niunaijun.blackboxa.app.AppManager
import top.niunaijun.blackboxa.databinding.ActivityMainBinding
import top.niunaijun.blackboxa.util.Resolution
import top.niunaijun.blackboxa.util.inflate
import top.niunaijun.blackboxa.util.toast
import top.niunaijun.blackboxa.view.apps.AppsFragment
import top.niunaijun.blackboxa.view.base.LoadingActivity
import top.niunaijun.blackboxa.view.fake.FakeManagerActivity
import top.niunaijun.blackboxa.view.list.ListActivity
import top.niunaijun.blackboxa.view.setting.SettingActivity
import top.niunaijun.blackboxa.cloud.Supabase
import top.niunaijun.blackboxa.cloud.VaultKeyStore
import top.niunaijun.blackboxa.cloud.CloudSync
import top.niunaijun.blackboxa.cloud.BackupService
import top.niunaijun.blackboxa.cloud.DriveFolderStore
import top.niunaijun.blackboxa.view.auth.AuthActivity
import top.niunaijun.blackboxa.view.auth.AccountSettingsActivity

class MainActivity : LoadingActivity() {

    private val viewBinding: ActivityMainBinding by inflate()

    private lateinit var mViewPagerAdapter: ViewPagerAdapter

    private val fragmentList = mutableListOf<AppsFragment>()

    private var currentUser = 0

    private val driveFolderPicker =
        registerForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
            if (uri != null) {
                try {
                    DriveFolderStore.save(this, uri,
                        VaultKeyStore.ownerHash(this) ?: error("Account recovery is not ready"))
                    handleConnectedDrive()
                } catch (e: Exception) {
                    toast("Could not connect Google Drive: ${e.message}")
                    showDriveRequired()
                }
            } else if (!DriveFolderStore.isConnected(this)) showDriveRequired()
        }

    private val accountSettingsLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            if (result.resultCode != RESULT_OK) return@registerForActivityResult
            when (result.data?.getStringExtra(AccountSettingsActivity.EXTRA_ACTION)) {
                AccountSettingsActivity.ACTION_BACKUP ->
                    if (DriveFolderStore.isConnected(this)) confirmBackup() else driveFolderPicker.launch(null)
                AccountSettingsActivity.ACTION_RESTORE ->
                    if (DriveFolderStore.isConnected(this)) confirmRestore() else driveFolderPicker.launch(null)
                AccountSettingsActivity.ACTION_DRIVE -> driveFolderPicker.launch(null)
                AccountSettingsActivity.ACTION_LOGOUT -> confirmLogout()
            }
        }

    companion object {
        private const val TAG = "MainActivity"
        // Only prompt for an update once per app process.
        private var updateChecked = false
        private const val STORAGE_PERMISSION_REQUEST_CODE = 1001
        private const val VPN_PERMISSION_REQUEST_CODE = 1002

        fun start(context: Context) {
            val intent = Intent(context, MainActivity::class.java)
            context.startActivity(intent)
        }
    }

    override fun onResume() {
        super.onResume()
        if (!Supabase.isSignedIn(this) || !VaultKeyStore.isReady(this)) {
            startActivity(Intent(this, AuthActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP))
            finish()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        try {
            super.onCreate(savedInstanceState)

            if (!Supabase.isSignedIn(this) || !VaultKeyStore.isReady(this)) {
                startActivity(Intent(this, AuthActivity::class.java))
                finish()
                return
            }

            var blackBoxReady = false
            try {
                BlackBoxCore.get().onBeforeMainActivityOnCreate(this)
            } catch (e: Exception) {
                Log.e(TAG, "Error in onBeforeMainActivityOnCreate: ${e.message}")
            }

            setContentView(viewBinding.root)
            if (android.os.Build.VERSION.SDK_INT >= 33 &&
                androidx.core.content.ContextCompat.checkSelfPermission(
                    this, android.Manifest.permission.POST_NOTIFICATIONS
                ) != android.content.pm.PackageManager.PERMISSION_GRANTED
            ) {
                androidx.core.app.ActivityCompat.requestPermissions(
                    this, arrayOf(android.Manifest.permission.POST_NOTIFICATIONS), 7301
                )
            }
            initToolbar(viewBinding.toolbarLayout.toolbar, R.string.app_name)
            viewBinding.accountBackup.setOnClickListener { openAccountSettings() }
            updateAccountButton()

            // In-app update check (once per app session).
            if (!updateChecked) {
                updateChecked = true
                top.niunaijun.blackboxa.cloud.Updater.checkAsync(this) { showUpdateDialog(it) }
            }
            initViewPager()
            initFab()
            initUserManager()
            initToolbarSubTitle()

            if (!DriveFolderStore.isConnected(this)) {
                window.decorView.post { showDriveRequired() }
            }

            
            checkStoragePermission()


            checkVpnPermission()

            // Keep cloned apps' push services alive in the background: exempt from
            // Samsung's battery killer so the container process isn't put to sleep.
            requestIgnoreBatteryOptimizations()

            try {
                BlackBoxCore.get().onAfterMainActivityOnCreate(this)
                blackBoxReady = true
            } catch (e: Exception) {
                Log.e(TAG, "Error in onAfterMainActivityOnCreate: ${e.message}")
            }
            // Only discard the rollback tree after the restored container initialized cleanly.
            if (blackBoxReady) CloudSync.cleanupPreviousRestore(this)
        } catch (e: Exception) {
            Log.e(TAG, "Critical error in onCreate: ${e.message}")
            
            showErrorDialog("Failed to initialize app: ${e.message}")
        }
    }

    private fun requestIgnoreBatteryOptimizations() {
        try {
            val pm = getSystemService(android.os.PowerManager::class.java)
            if (pm != null && !pm.isIgnoringBatteryOptimizations(packageName)) {
                @android.annotation.SuppressLint("BatteryLife")
                val i = android.content.Intent(
                    android.provider.Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS
                ).setData(android.net.Uri.parse("package:$packageName"))
                startActivity(i)
            }
        } catch (e: Exception) {
            Log.e(TAG, "battery optimization request failed: ${e.message}")
        }
    }

    private fun checkStoragePermission() {
        try {
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.R) {
                
                if (!android.os.Environment.isExternalStorageManager()) {
                    Log.w(TAG, "MANAGE_EXTERNAL_STORAGE permission not granted")
                    showStoragePermissionDialog()
                }
            } else {
                
                if (androidx.core.content.ContextCompat.checkSelfPermission(
                                this,
                                android.Manifest.permission.WRITE_EXTERNAL_STORAGE
                        ) != android.content.pm.PackageManager.PERMISSION_GRANTED ||
                                androidx.core.content.ContextCompat.checkSelfPermission(
                                        this,
                                        android.Manifest.permission.READ_EXTERNAL_STORAGE
                                ) != android.content.pm.PackageManager.PERMISSION_GRANTED
                ) {
                    Log.w(
                            TAG,
                            "Storage permissions not granted on Android ${android.os.Build.VERSION.SDK_INT}"
                    )
                    requestLegacyStoragePermission()
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error checking storage permission: ${e.message}")
        }
    }

    private fun requestLegacyStoragePermission() {
        try {
            androidx.core.app.ActivityCompat.requestPermissions(
                    this,
                    arrayOf(
                            android.Manifest.permission.READ_EXTERNAL_STORAGE,
                            android.Manifest.permission.WRITE_EXTERNAL_STORAGE
                    ),
                    STORAGE_PERMISSION_REQUEST_CODE
            )
        } catch (e: Exception) {
            Log.e(TAG, "Error requesting storage permission: ${e.message}")
        }
    }

    override fun onRequestPermissionsResult(
            requestCode: Int,
            permissions: Array<out String>,
            grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == STORAGE_PERMISSION_REQUEST_CODE) {
            if (grantResults.isNotEmpty() &&
                            grantResults.all {
                                it == android.content.pm.PackageManager.PERMISSION_GRANTED
                            }
            ) {
                Log.d(TAG, "Storage permissions granted")
            } else {
                Log.w(TAG, "Storage permissions denied")
            }
        }
    }

    private fun showStoragePermissionDialog() {
        try {
            MaterialDialog(this).show {
                title(text = "Storage Permission Required")
                message(
                        text =
                                "This app needs 'All Files Access' permission to properly run sandboxed apps. Without this permission, some apps may not work correctly.\n\nPlease grant permission in the next screen."
                )
                positiveButton(text = "Grant Permission") { openAllFilesAccessSettings() }
                negativeButton(text = "Later") { Log.w(TAG, "User postponed storage permission") }
                cancelable(false)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error showing storage permission dialog: ${e.message}")
        }
    }

    private fun openAllFilesAccessSettings() {
        try {
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.R) {
                val intent =
                        Intent(
                                android.provider.Settings
                                        .ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION
                        )
                intent.data = Uri.parse("package:$packageName")
                storagePermissionResult.launch(intent)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error opening storage settings: ${e.message}")
            
            try {
                val intent =
                        Intent(android.provider.Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION)
                storagePermissionResult.launch(intent)
            } catch (e2: Exception) {
                Log.e(TAG, "Error opening fallback storage settings: ${e2.message}")
            }
        }
    }

    private val storagePermissionResult =
            registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
                try {
                    if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.R) {
                        if (android.os.Environment.isExternalStorageManager()) {
                            Log.d(TAG, "Storage permission granted!")
                        } else {
                            Log.w(TAG, "Storage permission still not granted")
                        }
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Error handling storage permission result: ${e.message}")
                }
            }

    
    private fun checkVpnPermission() {
        try {
            val vpnIntent = VpnService.prepare(this)
            if (vpnIntent != null) {
                
                Log.d(TAG, "VPN permission not granted, requesting...")
                vpnPermissionResult.launch(vpnIntent)
            } else {
                
                Log.d(TAG, "VPN permission already granted")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error checking VPN permission: ${e.message}")
        }
    }

    private val vpnPermissionResult =
            registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
                try {
                    if (result.resultCode == RESULT_OK) {
                        Log.d(TAG, "VPN permission granted!")
                        
                    } else {
                        Log.w(TAG, "VPN permission denied by user")
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Error handling VPN permission result: ${e.message}")
                }
            }

    private fun showErrorDialog(message: String) {
        try {
            MaterialDialog(this).show {
                title(text = "Error")
                message(text = message)
                positiveButton(text = "OK") { finish() }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error showing error dialog: ${e.message}")
            finish()
        }
    }

    private fun initToolbarSubTitle() {
        try {
            updateUserRemark(0)
            
            viewBinding.toolbarLayout.toolbar.getChildAt(1)?.setOnClickListener {
                try {
                    MaterialDialog(this).show {
                        title(res = R.string.userRemark)
                        input(
                                hintRes = R.string.userRemark,
                                prefill = viewBinding.toolbarLayout.toolbar.subtitle
                        ) { _, input ->
                            try {
                                AppManager.mRemarkSharedPreferences.edit {
                                    putString("Remark$currentUser", input.toString())
                                    viewBinding.toolbarLayout.toolbar.subtitle = input
                                }
                            } catch (e: Exception) {
                                Log.e(TAG, "Error saving user remark: ${e.message}")
                            }
                        }
                        positiveButton(res = R.string.done)
                        negativeButton(res = R.string.cancel)
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Error showing remark dialog: ${e.message}")
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error in initToolbarSubTitle: ${e.message}")
        }
    }

    private fun initViewPager() {
        try {
            if (BlackBoxCore.get().users.isEmpty()) {
                BlackBoxCore.get().createUser(0)
            }

            mViewPagerAdapter = ViewPagerAdapter(this)
            viewBinding.viewPager.adapter = mViewPagerAdapter
            viewBinding.viewPager.registerOnPageChangeCallback(
                    object : ViewPager2.OnPageChangeCallback() {
                        override fun onPageSelected(position: Int) {
                            try {
                                super.onPageSelected(position)
                                currentUser = fragmentList[position].userID
                                updateUserRemark(currentUser)
                                showFloatButton(true)
                            } catch (e: Exception) {
                                Log.e(TAG, "Error in onPageSelected: ${e.message}")
                            }
                        }
                    }
            )
            refreshUserPages(0)
        } catch (e: Exception) {
            Log.e(TAG, "Error in initViewPager: ${e.message}")
        }
    }

    private fun initUserManager() {
        viewBinding.manageUsers.setOnClickListener { showUserManagerDialog() }
    }

    private fun showUserManagerDialog() {
        val users = BlackBoxCore.get().users.sortedBy { it.id }
        val labels = ArrayList<String>()
        labels.add("＋ Create new user")
        users.forEach { user ->
            val name = AppManager.mRemarkSharedPreferences
                .getString("Remark${user.id}", "User ${user.id}")
                .orEmpty().ifBlank { "User ${user.id}" }
            val defaultName = "User ${user.id}"
            labels.add(if (name == defaultName) defaultName else "$name  •  $defaultName")
        }
        AlertDialog.Builder(this)
            .setTitle("Manage BlackBox users")
            .setItems(labels.toTypedArray()) { _, position ->
                if (position == 0) showCreateUserDialog()
                else showUserActions(users[position - 1].id)
            }
            .setNegativeButton("Close", null)
            .show()
    }

    private fun showCreateUserDialog() {
        val nextId = (BlackBoxCore.get().users.maxOfOrNull { it.id } ?: -1) + 1
        MaterialDialog(this).show {
            title(text = "Create BlackBox user")
            message(text = "A user is an isolated space for another app clone.")
            input(hint = "User name", prefill = "User $nextId") { _, value ->
                createUser(nextId, value.toString().trim().ifBlank { "User $nextId" })
            }
            positiveButton(text = "Create")
            negativeButton(text = "Cancel")
        }
    }

    private fun createUser(userId: Int, name: String) {
        showLoading()
        lifecycleScope.launch {
            val created = withContext(Dispatchers.IO) {
                runCatching { BlackBoxCore.get().createUser(userId) }.getOrNull()
            }
            hideLoading()
            if (created == null) {
                toast("Could not create User $userId")
                return@launch
            }
            AppManager.mRemarkSharedPreferences.edit {
                putString("Remark$userId", name)
            }
            refreshUserPages(userId)
            toast("$name created")
        }
    }

    private fun showUserActions(userId: Int) {
        val name = AppManager.mRemarkSharedPreferences
            .getString("Remark$userId", "User $userId")
            .orEmpty().ifBlank { "User $userId" }
        val actions = if (userId == 0) {
            arrayOf("Open user", "Rename user")
        } else {
            arrayOf("Open user", "Rename user", "Delete user")
        }
        AlertDialog.Builder(this)
            .setTitle("$name  •  User $userId")
            .setItems(actions) { _, which ->
                when (which) {
                    0 -> selectUser(userId)
                    1 -> showRenameUserDialog(userId, name)
                    2 -> showDeleteUserDialog(userId, name)
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun showRenameUserDialog(userId: Int, oldName: String) {
        MaterialDialog(this).show {
            title(text = "Rename User $userId")
            input(hint = "User name", prefill = oldName) { _, value ->
                val name = value.toString().trim().ifBlank { "User $userId" }
                AppManager.mRemarkSharedPreferences.edit { putString("Remark$userId", name) }
                if (currentUser == userId) updateUserRemark(userId)
                toast("User renamed")
            }
            positiveButton(text = "Save")
            negativeButton(text = "Cancel")
        }
    }

    private fun showDeleteUserDialog(userId: Int, name: String) {
        if (userId == 0) {
            toast("User 0 is the primary user and cannot be deleted")
            return
        }
        AlertDialog.Builder(this)
            .setTitle("Delete $name?")
            .setMessage(
                "This permanently deletes every cloned app and all account data inside User $userId. " +
                    "ShieldProxy lists assigned to this user will stop working. This cannot be undone."
            )
            .setPositiveButton("Delete user") { _, _ -> deleteUser(userId, name) }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun deleteUser(userId: Int, name: String) {
        showLoading()
        lifecycleScope.launch {
            val error = withContext(Dispatchers.IO) {
                runCatching { BlackBoxCore.get().deleteUser(userId) }.exceptionOrNull()
            }
            hideLoading()
            if (error != null) {
                Log.e(TAG, "Could not delete user $userId", error)
                toast("Could not delete $name")
                return@launch
            }
            AppManager.mRemarkSharedPreferences.edit {
                remove("Remark$userId")
                remove("AppList$userId")
                AppManager.mRemarkSharedPreferences.all.keys
                    .filter { it.startsWith("cloneName_${userId}_") }
                    .forEach { remove(it) }
            }
            val fallback = BlackBoxCore.get().users.minOfOrNull { it.id } ?: 0
            refreshUserPages(fallback)
            toast("$name deleted")
        }
    }

    private fun selectUser(userId: Int) {
        val index = fragmentList.indexOfFirst { it.userID == userId }
        if (index >= 0) viewBinding.viewPager.setCurrentItem(index, true)
    }

    private fun refreshUserPages(preferredUserId: Int = currentUser) {
        val users = BlackBoxCore.get().users.sortedBy { it.id }
        fragmentList.clear()
        users.forEach { fragmentList.add(AppsFragment.newInstance(it.id)) }
        mViewPagerAdapter.replaceData(fragmentList)
        viewBinding.dotsIndicator.setViewPager2(viewBinding.viewPager)

        viewBinding.viewPager.post {
            val index = fragmentList.indexOfFirst { it.userID == preferredUserId }
                .takeIf { it >= 0 } ?: 0
            if (fragmentList.isNotEmpty()) {
                viewBinding.viewPager.setCurrentItem(index, false)
                currentUser = fragmentList[index].userID
                updateUserRemark(currentUser)
            }
        }
    }

    private fun initFab() {
        try {
            viewBinding.fab.setOnClickListener {
                try {
                    val userId = fragmentList[viewBinding.viewPager.currentItem].userID
                    val intent = Intent(this, ListActivity::class.java)
                    intent.putExtra("userID", userId)
                    apkPathResult.launch(intent)
                } catch (e: Exception) {
                    Log.e(TAG, "Error launching ListActivity: ${e.message}")
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error in initFab: ${e.message}")
        }
    }

    fun showFloatButton(show: Boolean) {
        try {
            val tranY: Float = Resolution.convertDpToPixel(120F, App.getContext())
            val time = 200L
            if (show) {
                viewBinding.fab.animate().translationY(0f).alpha(1f).setDuration(time).start()
            } else {
                viewBinding.fab.animate().translationY(tranY).alpha(0f).setDuration(time).start()
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error in showFloatButton: ${e.message}")
        }
    }

    fun scanUser() {
        try {
            val realIds = BlackBoxCore.get().users.map { it.id }.sorted()
            val shownIds = fragmentList.map { it.userID }.sorted()
            if (realIds != shownIds) {
                refreshUserPages(currentUser)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error in scanUser: ${e.message}")
        }
    }

    private fun updateUserRemark(userId: Int) {
        try {
            var remark =
                    AppManager.mRemarkSharedPreferences.getString("Remark$userId", "User $userId")
            if (remark.isNullOrEmpty()) {
                remark = "User $userId"
            }

            viewBinding.toolbarLayout.toolbar.subtitle = remark
        } catch (e: Exception) {
            Log.e(TAG, "Error updating user remark: ${e.message}")
            viewBinding.toolbarLayout.toolbar.subtitle = "User $userId"
        }
    }

    private val apkPathResult =
            registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
                try {
                    if (it.resultCode == RESULT_OK) {
                        it.data?.let { data ->
                            val userId = data.getIntExtra("userID", 0)
                            val source = data.getStringExtra("source")
                            if (source != null) {
                                fragmentList.firstOrNull { it.userID == userId }?.installApk(source)
                            }
                        }
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Error handling APK path result: ${e.message}")
                }
            }

    private fun showDriveRequired() {
        if (isFinishing || DriveFolderStore.isConnected(this)) return
        AlertDialog.Builder(this)
            .setTitle("Connect Google Drive")
            .setMessage("Choose or create the same private Drive folder used by ShieldProxy. BlackBox encrypts every backup chunk before uploading it, and cloned apps never receive Drive access.")
            .setPositiveButton("Choose folder") { _, _ -> driveFolderPicker.launch(null) }
            .setNeutralButton("Open settings") { _, _ -> openAccountSettings() }
            .setNegativeButton("Later", null)
            .setCancelable(true)
            .show()
    }

    private fun handleConnectedDrive() {
        updateAccountButton()
        toast("Google Drive connected")
        Thread {
            val hasBackup = runCatching { CloudSync.hasBackup(this) }.getOrDefault(false)
            runOnUiThread {
                if (hasBackup) {
                    AlertDialog.Builder(this)
                        .setTitle("BlackBox backup found")
                        .setMessage("Restore the encrypted clone backup, or keep the data currently on this phone?")
                        .setPositiveButton("Restore") { _, _ -> confirmRestore() }
                        .setNegativeButton("Keep this phone") { _, _ -> confirmBackup() }
                        .setCancelable(false)
                        .show()
                } else confirmBackup()
            }
        }.start()
    }

    private fun showAccountDialog() {
        val email = Supabase.email(this) ?: "signed in"
        val drive = if (DriveFolderStore.isConnected(this)) "Connected" else "Not connected"
        AlertDialog.Builder(this)
            .setTitle("Account & encrypted backup")
            .setMessage("Signed in: $email\nGoogle Drive: $drive\n\nRestore brings back users, clone names, app data, and saved positions.")
            .setItems(arrayOf("Back up everything", "Restore everything", "Select/change Google Drive folder", "Log out")) { _, which ->
                when (which) {
                    0 -> if (DriveFolderStore.isConnected(this)) confirmBackup() else driveFolderPicker.launch(null)
                    1 -> if (DriveFolderStore.isConnected(this)) confirmRestore() else driveFolderPicker.launch(null)
                    2 -> driveFolderPicker.launch(null)
                    3 -> confirmLogout()
                }
            }
            .setNegativeButton("Close", null)
            .show()
    }

    private fun updateAccountButton() {
        viewBinding.accountBackup.text = if (DriveFolderStore.isConnected(this))
            "Account & encrypted backup  •  Protected"
        else "Account & encrypted backup  •  Connect Drive"
    }

    private fun showUpdateDialog(release: top.niunaijun.blackboxa.cloud.Updater.Release) {
        if (isFinishing) return
        AlertDialog.Builder(this)
            .setTitle("Update available — v${release.versionName}")
            .setMessage(release.notes.ifBlank { "A newer version of BlackBox is ready." })
            .setCancelable(false)
            .setPositiveButton("Update now") { _, _ -> startUpdate(release) }
            .setNegativeButton("Later", null)
            .show()
    }

    private fun startUpdate(release: top.niunaijun.blackboxa.cloud.Updater.Release) {
        val progress = AlertDialog.Builder(this)
            .setTitle("Downloading update…").setMessage("0%").setCancelable(false).create()
        progress.show()
        top.niunaijun.blackboxa.cloud.Updater.downloadAndInstall(this, release,
            onProgress = { pct -> if (!isFinishing) progress.setMessage("$pct%") },
            onError = { msg -> if (!isFinishing) { progress.dismiss()
                android.widget.Toast.makeText(this, "Update failed: $msg", android.widget.Toast.LENGTH_LONG).show() } }
        )
    }

    private fun openAccountSettings() {
        accountSettingsLauncher.launch(Intent(this, AccountSettingsActivity::class.java))
    }

    private fun confirmBackup() {
        AlertDialog.Builder(this)
            .setTitle("Back up all clones?")
            .setMessage("The current data is about 1.5 GB. All cloned apps will close briefly so databases and login files are captured consistently. Use Wi-Fi and keep the phone charging.")
            .setPositiveButton("Start backup") { _, _ -> backupNow() }
            .setNegativeButton("Later", null)
            .show()
    }

    private fun backupNow() {
        BackupService.startBackup(this)
        toast("Encrypted backup is running in the notification area")
    }

    private fun confirmRestore() {
        AlertDialog.Builder(this)
            .setTitle("Restore all BlackBox data?")
            .setMessage("This replaces the clones on this phone. Install the original apps such as Instagram from Play Store first. BlackBox will restart after the encrypted backup passes every integrity check.")
            .setPositiveButton("Restore") { _, _ -> restoreNow() }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun restoreNow() {
        BackupService.startRestore(this)
        toast("Encrypted restore is running in the notification area")
    }

    private fun restartAfterRestore() {
        val restart = Intent(this, WelcomeActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
        val pending = android.app.PendingIntent.getActivity(
            this, 9201, restart,
            android.app.PendingIntent.FLAG_CANCEL_CURRENT or android.app.PendingIntent.FLAG_IMMUTABLE
        )
        val alarm = getSystemService(android.app.AlarmManager::class.java)
        alarm?.set(android.app.AlarmManager.ELAPSED_REALTIME_WAKEUP,
            android.os.SystemClock.elapsedRealtime() + 1_000, pending)
        finishAffinity()
        android.os.Process.killProcess(android.os.Process.myPid())
    }

    private fun confirmLogout() {
        AlertDialog.Builder(this)
            .setTitle("Log out?")
            .setMessage("Running clones will close. The encrypted Drive backup is not deleted.")
            .setPositiveButton("Log out") { _, _ ->
                runCatching {
                    for (user in BlackBoxCore.get().users) {
                        for (app in BlackBoxCore.get().getInstalledApplications(0, user.id).orEmpty()) {
                            BlackBoxCore.get().stopPackage(app.packageName, user.id)
                        }
                    }
                }
                Supabase.signOut(this); VaultKeyStore.clear(this)
                startActivity(Intent(this, AuthActivity::class.java)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK))
                finish()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    override fun onCreateOptionsMenu(menu: Menu?): Boolean {
        try {
            menuInflater.inflate(R.menu.menu_main, menu)
            return true
        } catch (e: Exception) {
            Log.e(TAG, "Error creating options menu: ${e.message}")
            return false
        }
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        try {
            when (item.itemId) {
                R.id.main_account -> openAccountSettings()
                R.id.main_git -> {
                    val intent =
                            Intent(
                                    Intent.ACTION_VIEW,
                                    Uri.parse("https://github.com/ALEX5402/NewBlackbox")
                            )
                    startActivity(intent)
                }
                R.id.main_setting -> {
                    SettingActivity.start(this)
                }
                R.id.main_tg -> {
                    val intent = Intent(Intent.ACTION_VIEW, Uri.parse("https://t.me/newblackboxa"))
                    startActivity(intent)
                }
                R.id.fake_location -> {
                    
                    val intent = Intent(this, FakeManagerActivity::class.java)
                    intent.putExtra("userID", 0)
                    startActivity(intent)
                }
            }

            return true
        } catch (e: Exception) {
            Log.e(TAG, "Error handling menu item selection: ${e.message}")
            return false
        }
    }
}
