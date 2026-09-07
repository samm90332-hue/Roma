package com.roma.walkie

import android.Manifest
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothServerSocket
import android.bluetooth.BluetoothSocket
import android.content.Context
import android.content.pm.PackageManager
import androidx.core.content.ContextCompat
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.PrintWriter
import java.util.UUID
import java.util.concurrent.Executors

class BluetoothChatManager(
    private val context: Context,
    private val callbacks: Callbacks
) {
    interface Callbacks {
        fun onStatus(text: String)
        fun onDevice(device: BluetoothDevice)
        fun onConnected(device: BluetoothDevice)
        fun onDisconnected()
        fun onMessage(sender: String, text: String)
    }

    companion object {
        val UUID_ROMA: UUID =
            UUID.fromString("9e7b2a10-2f6b-4a4c-9e2e-7d6a2b5a0101")
        private const val SERVICE_NAME = "ROMA"
    }

    private val adapter: BluetoothAdapter? =
        BluetoothAdapter.getDefaultAdapter()

    private val executor = Executors.newCachedThreadPool()
    private var serverSocket: BluetoothServerSocket? = null
    private var socket: BluetoothSocket? = null
    private var writer: PrintWriter? = null

    fun isSupported(): Boolean = adapter != null

    fun isEnabled(): Boolean = adapter?.isEnabled == true

    fun startServer() {
        if (!hasConnectPermission()) return
        executor.execute {
            try {
                serverSocket?.close()
                serverSocket = adapter?.listenUsingRfcommWithServiceRecord(
                    SERVICE_NAME, UUID_ROMA
                )
                callbacks.onStatus("ROMA ينتظر اتصال Bluetooth...")
                val accepted = serverSocket?.accept()
                if (accepted != null) {
                    attachSocket(accepted)
                }
            } catch (e: Exception) {
                callbacks.onStatus("خطأ Bluetooth: ${e.message}")
            }
        }
    }

    fun connect(device: BluetoothDevice) {
        if (!hasConnectPermission()) return
        executor.execute {
            try {
                adapter?.cancelDiscovery()
                socket?.close()
                callbacks.onStatus("جاري الاتصال بـ ${device.name ?: "الجهاز"}...")
                val s = device.createRfcommSocketToServiceRecord(UUID_ROMA)
                s.connect()
                attachSocket(s)
            } catch (e: Exception) {
                callbacks.onStatus("فشل الاتصال: ${e.message}")
                closeConnection()
            }
        }
    }

    private fun attachSocket(s: BluetoothSocket) {
        socket = s
        writer = PrintWriter(s.outputStream, true)
        val device = s.remoteDevice
        callbacks.onConnected(device)
        callbacks.onStatus("متصل بـ ${device.name ?: "ROMA"}")

        executor.execute {
            try {
                val reader = BufferedReader(InputStreamReader(s.inputStream))
                while (true) {
                    val line = reader.readLine() ?: break
                    if (line.startsWith("HELLO|")) {
                        continue
                    }
                    if (line.startsWith("MSG|")) {
                        val payload = line.removePrefix("MSG|")
                        val split = payload.split("|", limit = 2)
                        val sender = split.firstOrNull() ?: "ROMA"
                        val text = split.getOrNull(1) ?: ""
                        callbacks.onMessage(sender, text)
                    }
                }
            } catch (_: Exception) {
            } finally {
                closeConnection()
            }
        }
    }

    fun sendHello(username: String) {
        writer?.println("HELLO|$username")
    }

    fun sendMessage(username: String, text: String) {
        writer?.println("MSG|${safe(username)}|${safe(text)}")
    }

    private fun safe(value: String): String =
        value.replace("\n", " ").replace("\r", " ").replace("|", "/")

    fun closeConnection() {
        try { writer?.close() } catch (_: Exception) {}
        try { socket?.close() } catch (_: Exception) {}
        writer = null
        socket = null
        callbacks.onDisconnected()
    }

    fun release() {
        try { serverSocket?.close() } catch (_: Exception) {}
        closeConnection()
        executor.shutdownNow()
    }

    private fun hasConnectPermission(): Boolean {
        return android.os.Build.VERSION.SDK_INT < 31 ||
            ContextCompat.checkSelfPermission(
                context, Manifest.permission.BLUETOOTH_CONNECT
            ) == PackageManager.PERMISSION_GRANTED
    }
}
