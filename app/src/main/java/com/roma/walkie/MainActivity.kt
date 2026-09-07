package com.roma.walkie

import android.Manifest
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.content.*
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.View
import android.widget.*
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat

class MainActivity : AppCompatActivity(), BluetoothChatManager.Callbacks {

    private lateinit var manager: BluetoothChatManager
    private lateinit var usernameInput: EditText
    private lateinit var messageInput: EditText
    private lateinit var statusText: TextView
    private lateinit var deviceList: ListView
    private lateinit var messagesContainer: LinearLayout
    private lateinit var messageScroll: ScrollView
    private lateinit var chatTitle: TextView

    private val devices = mutableListOf<BluetoothDevice>()
    private lateinit var deviceAdapter: ArrayAdapter<String>

    private var selectedDevice: BluetoothDevice? = null
    private var username = "ROMA_User"

    private val permissionLauncher =
        registerForActivityResult(
            ActivityResultContracts.RequestMultiplePermissions()
        ) {
            if (hasBluetoothPermissions()) {
                setupBluetooth()
            } else {
                statusText.text = "يجب السماح بصلاحيات Bluetooth لـ ROMA"
            }
        }

    private val discoveryReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            when (intent.action) {
                BluetoothDevice.ACTION_FOUND -> {
                    val device =
                        if (Build.VERSION.SDK_INT >= 33) {
                            intent.getParcelableExtra(
                                BluetoothDevice.EXTRA_DEVICE,
                                BluetoothDevice::class.java
                            )
                        } else {
                            @Suppress("DEPRECATION")
                            intent.getParcelableExtra(
                                BluetoothDevice.EXTRA_DEVICE
                            )
                        }

                    if (device != null && devices.none { it.address == device.address }) {
                        devices.add(device)
                        runOnUiThread {
                            deviceAdapter.add(
                                "${device.name ?: "جهاز Bluetooth"}\n${device.address}"
                            )
                            deviceAdapter.notifyDataSetChanged()
                        }
                    }
                }
                BluetoothAdapter.ACTION_DISCOVERY_FINISHED -> {
                    statusText.text = "انتهى البحث. اختر جهازًا للاتصال."
                }
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        usernameInput = findViewById(R.id.usernameInput)
        messageInput = findViewById(R.id.messageInput)
        statusText = findViewById(R.id.statusText)
        deviceList = findViewById(R.id.deviceList)
        messagesContainer = findViewById(R.id.messagesContainer)
        messageScroll = findViewById(R.id.messageScroll)
        chatTitle = findViewById(R.id.chatTitle)

        val prefs = getSharedPreferences("roma", MODE_PRIVATE)
        username = prefs.getString("username", "ROMA_User") ?: "ROMA_User"
        usernameInput.setText(username)

        manager = BluetoothChatManager(this, this)

        deviceAdapter = ArrayAdapter(
            this,
            android.R.layout.simple_list_item_1,
            mutableListOf()
        )
        deviceList.adapter = deviceAdapter

        registerReceiver(
            discoveryReceiver,
            IntentFilter(BluetoothDevice.ACTION_FOUND)
        )
        registerReceiver(
            discoveryReceiver,
            IntentFilter(BluetoothAdapter.ACTION_DISCOVERY_FINISHED)
        )

        findViewById<Button>(R.id.saveUsernameButton).setOnClickListener {
            val value = usernameInput.text.toString().trim()
            if (value.length < 3) {
                usernameInput.error = "اكتب 3 أحرف على الأقل"
                return@setOnClickListener
            }
            username = value
            prefs.edit().putString("username", username).apply()
            Toast.makeText(this, "تم حفظ اسم المستخدم", Toast.LENGTH_SHORT).show()
            manager.sendHello(username)
        }

        findViewById<Button>(R.id.discoverButton).setOnClickListener {
            discover()
        }

        findViewById<Button>(R.id.visibleButton).setOnClickListener {
            requestDiscoverable()
        }

        findViewById<Button>(R.id.sendButton).setOnClickListener {
            sendMessage()
        }

        findViewById<Button>(R.id.settingsButton).setOnClickListener {
            showSettings()
        }

        deviceList.setOnItemClickListener { _, _, position, _ ->
            selectedDevice = devices.getOrNull(position)
            selectedDevice?.let {
                manager.connect(it)
            }
        }

        if (!hasBluetoothPermissions()) {
            requestBluetoothPermissions()
        } else {
            setupBluetooth()
        }
    }

    private fun setupBluetooth() {
        if (!manager.isSupported()) {
            statusText.text = "هذا الهاتف لا يدعم Bluetooth"
            return
        }

        if (!manager.isEnabled()) {
            val intent = Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE)
            startActivity(intent)
        }

        manager.startServer()
    }

    private fun discover() {
        if (!hasBluetoothPermissions()) {
            requestBluetoothPermissions()
            return
        }

        val adapter = BluetoothAdapter.getDefaultAdapter()
        if (adapter == null) {
            statusText.text = "Bluetooth غير مدعوم"
            return
        }

        if (!adapter.isEnabled) {
            startActivity(Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE))
            return
        }

        devices.clear()
        deviceAdapter.clear()
        deviceAdapter.notifyDataSetChanged()

        adapter.cancelDiscovery()
        val started = adapter.startDiscovery()
        statusText.text =
            if (started) "جاري البحث عن أجهزة ROMA/Bluetooth..."
            else "تعذر بدء البحث"
    }

    private fun requestDiscoverable() {
        if (!hasBluetoothPermissions()) {
            requestBluetoothPermissions()
            return
        }

        val intent = Intent(BluetoothAdapter.ACTION_REQUEST_DISCOVERABLE)
        intent.putExtra(BluetoothAdapter.EXTRA_DISCOVERABLE_DURATION, 300)
        startActivity(intent)
    }

    private fun sendMessage() {
        val text = messageInput.text.toString().trim()
        if (text.isEmpty()) return

        if (selectedDevice == null) {
            Toast.makeText(
                this,
                "اختر جهازًا واتصل به أولًا",
                Toast.LENGTH_SHORT
            ).show()
            return
        }

        manager.sendMessage(username, text)
        addMessage("أنت", text)
        messageInput.text.clear()
    }

    private fun addMessage(sender: String, text: String) {
        val tv = TextView(this)
        tv.text = "$sender\n$text"
        tv.setTextColor(getColor(R.color.roma_text))
        tv.textSize = 16f
        tv.setPadding(18, 12, 18, 12)
        tv.setBackgroundColor(getColor(R.color.roma_panel))

        val lp = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        )
        lp.setMargins(0, 6, 0, 6)
        messagesContainer.addView(tv, lp)

        messageScroll.post {
            messageScroll.fullScroll(View.FOCUS_DOWN)
        }
    }

    private fun showSettings() {
        val options = arrayOf(
            "الحساب",
            "الخصوصية",
            "المحادثات",
            "الإشعارات",
            "التخزين والبيانات",
            "المكالمات",
            "المظهر",
            "اللغة",
            "الأمان",
            "حول ROMA"
        )

        AlertDialog.Builder(this)
            .setTitle("إعدادات ROMA")
            .setItems(options) { _, which ->
                when (which) {
                    0 -> showAccountSettings()
                    1 -> showSimple("الخصوصية", "إعدادات الظهور والحظر وقراءة الرسائل.")
                    2 -> showSimple("المحادثات", "الخلفية، النسخ الاحتياطي، والرسائل المؤقتة.")
                    3 -> showSimple("الإشعارات", "صوت الرسائل والتنبيهات.")
                    4 -> showSimple("التخزين والبيانات", "الملفات والوسائط واستخدام البيانات.")
                    5 -> showSimple("المكالمات", "الصوت والفيديو والميكروفون.")
                    6 -> showSimple("المظهر", "الثيم وحجم الخط.")
                    7 -> showSimple("اللغة", "لغة واجهة ROMA.")
                    8 -> showSimple("الأمان", "تشفير واتصال ROMA.")
                    9 -> showSimple("حول ROMA", "ROMA 1.0 — Offline Bluetooth Messenger")
                }
            }
            .show()
    }

    private fun showAccountSettings() {
        AlertDialog.Builder(this)
            .setTitle("الحساب")
            .setMessage("اسم المستخدم الحالي:\n@$username")
            .setPositiveButton("موافق", null)
            .show()
    }

    private fun showSimple(title: String, message: String) {
        AlertDialog.Builder(this)
            .setTitle(title)
            .setMessage(message)
            .setPositiveButton("موافق", null)
            .show()
    }

    private fun hasBluetoothPermissions(): Boolean {
        return if (Build.VERSION.SDK_INT >= 31) {
            ContextCompat.checkSelfPermission(
                this, Manifest.permission.BLUETOOTH_SCAN
            ) == android.content.pm.PackageManager.PERMISSION_GRANTED &&
            ContextCompat.checkSelfPermission(
                this, Manifest.permission.BLUETOOTH_CONNECT
            ) == android.content.pm.PackageManager.PERMISSION_GRANTED &&
            ContextCompat.checkSelfPermission(
                this, Manifest.permission.BLUETOOTH_ADVERTISE
            ) == android.content.pm.PackageManager.PERMISSION_GRANTED
        } else {
            true
        }
    }

    private fun requestBluetoothPermissions() {
        if (Build.VERSION.SDK_INT >= 31) {
            permissionLauncher.launch(
                arrayOf(
                    Manifest.permission.BLUETOOTH_SCAN,
                    Manifest.permission.BLUETOOTH_CONNECT,
                    Manifest.permission.BLUETOOTH_ADVERTISE
                )
            )
        }
    }

    override fun onStatus(text: String) {
        runOnUiThread { statusText.text = text }
    }

    override fun onDevice(device: BluetoothDevice) {
        runOnUiThread {
            if (devices.none { it.address == device.address }) {
                devices.add(device)
                deviceAdapter.add(
                    "${device.name ?: "ROMA"}\n${device.address}"
                )
                deviceAdapter.notifyDataSetChanged()
            }
        }
    }

    override fun onConnected(device: BluetoothDevice) {
        runOnUiThread {
            selectedDevice = device
            chatTitle.text = "المحادثة — ${device.name ?: "ROMA"}"
            manager.sendHello(username)
            Toast.makeText(
                this,
                "تم الاتصال بنجاح",
                Toast.LENGTH_SHORT
            ).show()
        }
    }

    override fun onDisconnected() {
        runOnUiThread {
            statusText.text = "تم قطع اتصال Bluetooth"
        }
    }

    override fun onMessage(sender: String, text: String) {
        runOnUiThread {
            addMessage(sender, text)
        }
    }

    override fun onDestroy() {
        try {
            unregisterReceiver(discoveryReceiver)
        } catch (_: Exception) {}
        manager.release()
        super.onDestroy()
    }
}
