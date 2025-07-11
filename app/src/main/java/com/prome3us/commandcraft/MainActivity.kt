package com.prome3us.commandcraft

import android.app.Activity
import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.net.wifi.WifiManager
import android.os.Bundle
import android.provider.Settings
import android.util.Base64
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.widget.*
import com.tananaev.adblib.*
import java.io.File
import java.lang.ref.WeakReference
import java.net.*
import java.nio.ByteOrder
import java.util.*

class MainActivity : Activity() {

    private var tvIP: TextView? = null
    private var connection: AdbConnection? = null
    private var stream: AdbStream? = null
    private var myAsyncTask: MyAsyncTask? = null

    private lateinit var customGrid: GridLayout
    private lateinit var prefs: SharedPreferences

    private val publicKeyName = "public.key"
    private val privateKeyName = "private.key"
    private var isEditingLayout = false
    private var selectedButton: Button? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_commandcraft)

        prefs = getSharedPreferences("commands", Context.MODE_PRIVATE)

        if (!isUsbDebuggingEnabled()) {
            Toast.makeText(this, getString(R.string.enable_usb_debugging_first), Toast.LENGTH_LONG)
                .show()
            openDeveloperSettings()
            finish()
            return
        }

        tvIP = findViewById(R.id.ip)
        tvIP?.text = getGateWayIp(this)

        customGrid = findViewById(R.id.custom_grid)

        restoreDefaultButtons()
        restoreCustomButtons()
        restoreButtonOrder()
        setupAddCommandButton()
        setupLayoutEditButton()

        if (customGrid.childCount > 0) {
            customGrid.getChildAt(0).requestFocus()
        }
    }

    private fun restoreDefaultButtons() {
        val defaultConfigs = mapOf(
            "btnCoreELEC" to ("CoreELEC" to "su -c \"echo '0xff6345d0 0x2' > /sys/kernel/debug/aml_reg/paddr && reboot\""),
            "btnRecovery" to ("TWRP/Recovery" to "reboot recovery"),
            "btnShutdown" to ("Power Off" to "reboot -p"),
            "btnFireOS" to ("FireOS" to "reboot quiescent"),
            "btnFastboot" to ("Fastboot" to "reboot fastboot"),
            "btnSoftReboot" to ("Soft Reboot" to "su -c \"stop; sleep 0.2; start\""),
            "btnUSB" to ("USB Boot" to "su -c \"echo '0xff6345d0 0x1' > /sys/kernel/debug/aml_reg/paddr && reboot\""),
            "btnUpdate" to ("AML Update" to "reboot update")

        )

        for ((idName, pair) in defaultConfigs) {
            val resId = resources.getIdentifier(idName, "id", packageName)
            val button = findViewById<Button>(resId)
            val (label, command) = pair
            val labelKey = "label_$resId"
            val cmdKey = "cmd_$resId"
            val savedLabel = prefs.getString(labelKey, label) ?: label

            button.text = savedLabel
            button.tag = resId.toString()

            button.setOnClickListener {
                if (isEditingLayout) {
                    selectButtonForMove(button)
                } else {
                    val cmd = prefs.getString(cmdKey, command) ?: command
                    sendCommand(cmd)
                }
            }

            button.setOnLongClickListener {
                if (!isEditingLayout) {
                    showEditDialog(button, cmdKey, labelKey, command, label, resettable = false)
                }
                true
            }
            customGrid.removeView(button) // Safely remove before re-adding
            customGrid.addView(button)
        }
    }

    private fun restoreCustomButtons() {
        val ids = prefs.getString("button_order", "")!!.split(",").filter { it.startsWith("uuid_") }
        for (id in ids) {
            val label = prefs.getString("label_$id", null)
            val cmd = prefs.getString("cmd_$id", null)
            if (label != null && cmd != null) {
                val btn = createButton(label, cmd, id)
                customGrid.addView(btn)
            }
        }
    }

    private fun restoreButtonOrder() {
        val orderedIds = prefs.getString("button_order", "")!!
            .split(",")
            .filter { it.isNotBlank() }

        if (orderedIds.isEmpty()) return // ✅ Skip if no saved order

        val allButtons = mutableMapOf<String, View>()
        for (i in 0 until customGrid.childCount) {
            val view = customGrid.getChildAt(i)
            val key = view.tag as? String ?: view.id.toString()
            allButtons[key] = view
        }

        customGrid.removeAllViews()

        for (key in orderedIds) {
            allButtons[key]?.let { customGrid.addView(it) }
        }
    }

    private fun saveButtonOrder() {
        val ids = (0 until customGrid.childCount)
            .mapNotNull {
                customGrid.getChildAt(it).tag as? String ?: customGrid.getChildAt(it).id.toString()
            }
        prefs.edit().putString("button_order", ids.joinToString(",")).apply()
    }

    private fun setupAddCommandButton() {
        val addButton = findViewById<Button>(R.id.btnAddCommand)
        val buttonWidth = (216 * resources.displayMetrics.density).toInt()

        addButton.setOnClickListener {
            val layout = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(40, 20, 40, 10)
            }
            val labelInput = EditText(this).apply { hint = "Button label" }
            val cmdInput = EditText(this).apply { hint = "Shell command" }
            layout.addView(labelInput)
            layout.addView(cmdInput)

            AlertDialog.Builder(this)
                .setTitle("New Command")
                .setView(layout)
                .setPositiveButton("Create") { _, _ ->
                    val label = labelInput.text.toString()
                    val command = cmdInput.text.toString()
                    if (label.isNotBlank() && command.isNotBlank()) {
                        val id = "uuid_${UUID.randomUUID()}"
                        val button = createButton(label, command, id)
                        prefs.edit().putString("label_$id", label).putString("cmd_$id", command)
                            .apply()
                        customGrid.addView(button)
                        saveButtonOrder()
                    }
                }
                .setNegativeButton("Cancel", null)
                .show()
        }
    }

    private fun setupLayoutEditButton() {
        findViewById<Button>(R.id.btnLayoutEdit).setOnClickListener {
            isEditingLayout = !isEditingLayout
            if (!isEditingLayout) {
                clearSelection()
                Toast.makeText(this, "Layout edit mode disabled", Toast.LENGTH_SHORT).show()
            } else {
                Toast.makeText(this, "Layout edit mode enabled", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun createButton(label: String, command: String, id: String): Button {
        val button = Button(this)
        button.text = label
        button.tag = id

        val default = findViewById<Button>(R.id.btnCoreELEC)
        val buttonWidth = (216 * resources.displayMetrics.density).toInt()
        val params = if (default?.layoutParams is GridLayout.LayoutParams) {
            GridLayout.LayoutParams(default.layoutParams as GridLayout.LayoutParams)
        } else {
            GridLayout.LayoutParams().apply {
                width = buttonWidth
                setMargins(4, 4, 4, 4)
            }
        }
        button.layoutParams = params

        button.setBackgroundResource(R.drawable.button_background)
        button.setTextColor(resources.getColor(android.R.color.white))
        button.isAllCaps = true

        button.setOnClickListener {
            if (isEditingLayout) {
                selectButtonForMove(button)
            } else {
                sendCommand(command)
            }
        }

        button.setOnLongClickListener {
            if (!isEditingLayout) {
                showEditDialog(button, "cmd_$id", "label_$id", command, label, resettable = false)
            }
            true
        }

        return button
    }

    private fun selectButtonForMove(button: Button) {
        selectedButton?.background = getDrawable(R.drawable.button_background)
        selectedButton = button
        val border = GradientDrawable().apply {
            setColor(Color.TRANSPARENT)
            setStroke(5, Color.YELLOW)
            cornerRadius = 48f
        }
        button.background = border
    }

    private fun clearSelection() {
        selectedButton?.let {
            it.background = getDrawable(R.drawable.button_background)
            it.requestFocus() // Keep focus on the same button
        }
        selectedButton = null
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (isEditingLayout && event.action == KeyEvent.ACTION_DOWN) {
            if (selectedButton != null) {
                val index = customGrid.indexOfChild(selectedButton)
                val colCount = customGrid.columnCount
                val total = customGrid.childCount

                val newIndex = when (event.keyCode) {
                    KeyEvent.KEYCODE_DPAD_LEFT -> if (index % colCount > 0) index - 1 else index
                    KeyEvent.KEYCODE_DPAD_RIGHT -> if ((index + 1) % colCount != 0 && index + 1 < total) index + 1 else index
                    KeyEvent.KEYCODE_DPAD_UP -> if (index - colCount >= 0) index - colCount else index
                    KeyEvent.KEYCODE_DPAD_DOWN -> if (index + colCount < total) index + colCount else index
                    KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER -> {
                        clearSelection()
                        return true
                    }
                    else -> return super.dispatchKeyEvent(event)
                }

                if (index != newIndex) {
                    customGrid.removeViewAt(index)
                    customGrid.addView(selectedButton, newIndex)
                    selectedButton?.requestFocus() // Keep focus on moved button
                    saveButtonOrder()
                }
                return true
            } else if (event.keyCode == KeyEvent.KEYCODE_BACK) {
                isEditingLayout = false
                Toast.makeText(this, "Exited layout edit mode", Toast.LENGTH_SHORT).show()
                return true
            }
        }
        return super.dispatchKeyEvent(event)
    }

    private fun showEditDialog(
        button: Button,
        cmdKey: String,
        labelKey: String,
        defaultCmd: String,
        defaultLabel: String,
        resettable: Boolean
    ) {
        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(40, 20, 40, 10)
        }
        val cmdInput = EditText(this).apply {
            hint = "Shell command"
            setText(prefs.getString(cmdKey, defaultCmd))
        }
        val labelInput = EditText(this).apply {
            hint = "Button label"
            setText(prefs.getString(labelKey, defaultLabel))
        }
        layout.addView(labelInput)
        layout.addView(cmdInput)

        AlertDialog.Builder(this)
            .setTitle("Edit Button")
            .setView(layout)
            .setPositiveButton("Save") { _, _ ->
                val newCmd = cmdInput.text.toString()
                val newLabel = labelInput.text.toString()
                prefs.edit().putString(cmdKey, newCmd).putString(labelKey, newLabel).apply()
                button.text = newLabel
                button.setOnClickListener { sendCommand(newCmd) }
            }
            .setNegativeButton("Cancel", null)
            .setNeutralButton(if (resettable) "Reset" else "Delete") { _, _ ->
                if (resettable) {
                    prefs.edit().remove(cmdKey).remove(labelKey).apply()
                    button.text = defaultLabel
                    button.setOnClickListener { sendCommand(defaultCmd) }
                } else {
                    (button.parent as? ViewGroup)?.removeView(button)
                    prefs.edit().remove(cmdKey).remove(labelKey).apply()
                    saveButtonOrder()
                }
            }
            .show()
    }

    private fun sendCommand(command: String) {
        connection = null
        stream = null
        myAsyncTask?.cancel()
        myAsyncTask = MyAsyncTask(this)
        myAsyncTask?.execute(command)
    }

    fun adbCommander(ip: String?, command: String) {
        val socket = Socket(ip, 5555)
        val crypto = readCryptoConfig(filesDir) ?: writeNewCryptoConfig(filesDir)
        try {
            if (stream == null || connection == null) {
                connection = AdbConnection.create(socket, crypto)
                connection?.connect()
            }
            runOnUiThread {
                Toast.makeText(this, "Running: $command", Toast.LENGTH_SHORT).show()
            }
            Thread.sleep(1500)
            stream = connection?.open("shell:$command")
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    private fun readCryptoConfig(dataDir: File?): AdbCrypto? {
        val pubKey = File(dataDir, publicKeyName)
        val privKey = File(dataDir, privateKeyName)
        return if (pubKey.exists() && privKey.exists()) {
            try {
                AdbCrypto.loadAdbKeyPair(AndroidBase64(), privKey, pubKey)
            } catch (_: Exception) {
                null
            }
        } else null
    }

    private fun writeNewCryptoConfig(dataDir: File?): AdbCrypto? {
        val pubKey = File(dataDir, publicKeyName)
        val privKey = File(dataDir, privateKeyName)
        return try {
            AdbCrypto.generateAdbKeyPair(AndroidBase64()).apply {
                saveAdbKeyPair(privKey, pubKey)
            }
        } catch (_: Exception) {
            null
        }
    }

    private fun openDeveloperSettings() {
        startActivity(Intent(Settings.ACTION_SETTINGS))
    }

    private fun isUsbDebuggingEnabled(): Boolean {
        return Settings.Global.getInt(contentResolver, Settings.Global.ADB_ENABLED, 0) == 1
    }

    private fun getWifiManager(context: Context): WifiManager {
        return context.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
    }

    private fun getGateWayIp(context: Context): String? {
        val dhcpInfo = getWifiManager(context).dhcpInfo
        return if (dhcpInfo.ipAddress == 0) localIp else int2String(dhcpInfo.ipAddress)
    }

    private val localIp: String?
        get() {
            return try {
                NetworkInterface.getNetworkInterfaces().toList()
                    .flatMap { it.inetAddresses.toList() }
                    .firstOrNull { !it.isLoopbackAddress && it is Inet4Address }
                    ?.hostAddress
            } catch (_: SocketException) {
                null
            }
        }

    private fun int2String(i: Int): String {
        return if (ByteOrder.nativeOrder() == ByteOrder.LITTLE_ENDIAN)
            "${i and 255}.${i shr 8 and 255}.${i shr 16 and 255}.${i shr 24 and 255}"
        else
            "${i shr 24 and 255}.${i shr 16 and 255}.${i shr 8 and 255}.${i and 255}"
    }

    class MyAsyncTask(context: MainActivity) {
        private val activityReference = WeakReference(context)
        private var thread: Thread? = null

        fun execute(command: String) {
            thread = Thread {
                activityReference.get()?.adbCommander(
                    activityReference.get()?.tvIP?.text.toString(), command
                )
                if (Thread.interrupted()) return@Thread
            }
            thread?.start()
        }

        fun cancel() {
            thread?.interrupt()
        }
    }

    class AndroidBase64 : AdbBase64 {
        override fun encodeToString(bArr: ByteArray): String =
            Base64.encodeToString(bArr, Base64.NO_WRAP)
    }
}