package com.nerfherder.walkthor.controls

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothClass
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.os.Build
import kotlinx.coroutines.flow.MutableStateFlow

/**
 * Pairing controllers from WalkThor (no trip to Android's Bluetooth settings): looks for
 * controllers in pairing mode (Joy-Cons, Pro Controllers and other gamepads) and pairs the one
 * you pick. A paired Joy-Con connects again by itself when you press one of its buttons.
 */
@SuppressLint("MissingPermission")
object PadBluetooth {
    data class Found(val address: String, val name: String, val bonded: Boolean)

    val searching = MutableStateFlow(false)
    val found = MutableStateFlow<List<Found>>(emptyList())
    /** What's happening ("Pairing Joy-Con (L)…", "Joy-Con (L) paired"), or null. */
    val status = MutableStateFlow<String?>(null)

    /** The permissions Android asks for before WalkThor may look for and pair Bluetooth devices. */
    val permissions: Array<String> = if (Build.VERSION.SDK_INT >= 31)
        arrayOf(android.Manifest.permission.BLUETOOTH_SCAN, android.Manifest.permission.BLUETOOTH_CONNECT)
    else arrayOf(android.Manifest.permission.ACCESS_FINE_LOCATION)

    fun allowed(c: Context) = permissions.all { c.checkSelfPermission(it) == PackageManager.PERMISSION_GRANTED }

    private fun adapter(c: Context): BluetoothAdapter? = c.getSystemService(BluetoothManager::class.java)?.adapter

    fun bluetoothOn(c: Context) = runCatching { adapter(c)?.isEnabled == true }.getOrDefault(false)

    /** A controller, going by its name or the kind of device it says it is. */
    private fun isController(d: BluetoothDevice): Boolean {
        val name = runCatching { d.name }.getOrNull().orEmpty()
        if (name.contains("Joy-Con", true) || name.contains("Pro Controller", true) || name.contains("controller", true) || name.contains("gamepad", true)) return true
        val cls = runCatching { d.bluetoothClass }.getOrNull() ?: return false
        return cls.majorDeviceClass == BluetoothClass.Device.Major.PERIPHERAL && (cls.deviceClass and 0x0C) == 0x08
    }

    private var receiver: BroadcastReceiver? = null

    /** Starts looking (about 12 seconds). Hold a Joy-Con's sync button until its lights run first. */
    fun search(c: Context) {
        val app = c.applicationContext
        val a = adapter(app) ?: run { status.value = "This device has no Bluetooth"; return }
        if (!a.isEnabled) { status.value = "Bluetooth is off: switch it on in the quick settings"; return }
        stop(app)
        found.value = runCatching {
            a.bondedDevices.filter(::isController).map { Found(it.address, it.name ?: it.address, true) }
        }.getOrDefault(emptyList())
        val r = object : BroadcastReceiver() {
            override fun onReceive(ctx: Context, i: Intent) {
                when (i.action) {
                    BluetoothDevice.ACTION_FOUND -> {
                        val d = device(i) ?: return
                        if (!isController(d)) return
                        val f = Found(d.address, d.name ?: d.address, d.bondState == BluetoothDevice.BOND_BONDED)
                        found.value = found.value.filter { it.address != f.address } + f
                    }
                    BluetoothAdapter.ACTION_DISCOVERY_FINISHED -> searching.value = false
                    BluetoothDevice.ACTION_BOND_STATE_CHANGED -> {
                        val d = device(i) ?: return
                        val name = d.name ?: d.address
                        when (i.getIntExtra(BluetoothDevice.EXTRA_BOND_STATE, -1)) {
                            BluetoothDevice.BOND_BONDING -> status.value = "Pairing $name…"
                            BluetoothDevice.BOND_BONDED -> {
                                status.value = "$name paired"
                                found.value = found.value.map { if (it.address == d.address) it.copy(bonded = true) else it }
                            }
                            BluetoothDevice.BOND_NONE -> if (status.value?.startsWith("Pairing") == true) status.value = "$name didn’t pair. Hold its sync button and try again"
                        }
                    }
                }
            }
        }
        receiver = r
        val filter = IntentFilter().apply {
            addAction(BluetoothDevice.ACTION_FOUND)
            addAction(BluetoothAdapter.ACTION_DISCOVERY_FINISHED)
            addAction(BluetoothDevice.ACTION_BOND_STATE_CHANGED)
        }
        androidx.core.content.ContextCompat.registerReceiver(app, r, filter, androidx.core.content.ContextCompat.RECEIVER_EXPORTED)
        searching.value = runCatching { a.startDiscovery() }.getOrDefault(false)
        status.value = if (searching.value) null else "Couldn’t start looking"
    }

    fun stop(c: Context) {
        runCatching { adapter(c)?.cancelDiscovery() }
        searching.value = false
    }

    /** Stops listening (leaving the Controllers tab). */
    fun close(c: Context) {
        stop(c)
        receiver?.let { r -> runCatching { c.applicationContext.unregisterReceiver(r) } }
        receiver = null
    }

    /** Pairs [f]; it connects once paired. */
    fun pair(c: Context, f: Found) {
        val a = adapter(c) ?: return
        runCatching { a.cancelDiscovery() }
        val d = runCatching { a.getRemoteDevice(f.address) }.getOrNull() ?: return
        status.value = "Pairing ${f.name}…"
        if (runCatching { d.createBond() }.getOrDefault(false) != true) status.value = "Couldn’t pair ${f.name}"
    }

    private fun device(i: Intent): BluetoothDevice? =
        if (Build.VERSION.SDK_INT >= 33) i.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE, BluetoothDevice::class.java)
        else @Suppress("DEPRECATION") i.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE)
}
