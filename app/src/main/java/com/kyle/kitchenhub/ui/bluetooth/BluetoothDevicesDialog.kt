package com.kyle.kitchenhub.ui.bluetooth

import android.annotation.SuppressLint
import android.app.Dialog
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Color
import android.os.Bundle
import android.provider.Settings
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import com.google.android.material.button.MaterialButton
import com.google.android.material.card.MaterialCardView
import com.kyle.kitchenhub.core.audio.TactileAudioEngine
import com.kyle.kitchenhub.core.ui.FontCache

/**
 * Material 3 dialog listing Paired Bluetooth devices and Available nearby devices.
 */
class BluetoothDevicesDialog(context: Context) : Dialog(context) {

    private val adapter: BluetoothAdapter? = BluetoothAdapter.getDefaultAdapter()
    private val discoveredDevices = LinkedHashMap<String, BluetoothDevice>()
    private lateinit var pairedContainer: LinearLayout
    private lateinit var availableContainer: LinearLayout
    private lateinit var tvScanStatus: TextView
    private var receiverRegistered = false

    private val receiver = object : BroadcastReceiver() {
        @SuppressLint("MissingPermission")
        override fun onReceive(ctx: Context?, intent: Intent?) {
            when (intent?.action) {
                BluetoothDevice.ACTION_FOUND -> {
                    val dev = intent.getParcelableExtra<BluetoothDevice>(BluetoothDevice.EXTRA_DEVICE) ?: return
                    val addr = dev.address ?: return
                    if (adapter?.bondedDevices?.any { it.address == addr } == true) return
                    discoveredDevices[addr] = dev
                    renderAvailableList()
                }
                BluetoothAdapter.ACTION_DISCOVERY_FINISHED -> {
                    if (::tvScanStatus.isInitialized) {
                        tvScanStatus.text = if (discoveredDevices.isEmpty()) "No new devices found" else "Scan complete"
                    }
                }
            }
        }
    }

    override fun show() = com.kyle.kitchenhub.core.ui.DialogImmersive.show(this) { super.show() }

    @SuppressLint("MissingPermission")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val d = context.resources.displayMetrics.density
        val tf = FontCache.plusJakarta(context)

        val rootCard = MaterialCardView(context).apply {
            radius = 32f * d
            cardElevation = 10f * d
            strokeWidth = 0
            setCardBackgroundColor(Color.parseColor("#1B1F2A"))
        }

        val root = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding((24 * d).toInt(), (20 * d).toInt(), (24 * d).toInt(), (20 * d).toInt())
        }

        // Header
        val header = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        header.addView(TextView(context).apply {
            text = "Bluetooth"
            typeface = tf
            paint.isFakeBoldText = true
            textSize = 21f
            setTextColor(Color.WHITE)
            layoutParams = LinearLayout.LayoutParams(0, -2, 1f)
        })
        header.addView(MaterialButton(context, null, com.google.android.material.R.attr.materialButtonOutlinedStyle).apply {
            text = "Settings"
            isAllCaps = false
            setTextColor(Color.parseColor("#A8C7FA"))
            layoutParams = LinearLayout.LayoutParams(-2, -2).apply { marginEnd = (8 * d).toInt() }
            setOnClickListener {
                TactileAudioEngine.playClick()
                runCatching {
                    context.startActivity(Intent(Settings.ACTION_BLUETOOTH_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                }
                dismiss()
            }
        })
        header.addView(MaterialButton(context).apply {
            text = "Close"
            isAllCaps = false
            setOnClickListener { TactileAudioEngine.playClick(); dismiss() }
        })
        root.addView(header)

        val scroll = ScrollView(context).apply {
            overScrollMode = View.OVER_SCROLL_NEVER
            layoutParams = LinearLayout.LayoutParams(-1, 0, 1f).apply { topMargin = (14 * d).toInt() }
        }
        val body = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }

        // Paired section
        body.addView(TextView(context).apply {
            text = "Paired Devices"
            typeface = tf
            paint.isFakeBoldText = true
            textSize = 14f
            setTextColor(Color.parseColor("#A8C7FA"))
        })
        pairedContainer = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(-1, -2).apply { topMargin = (8 * d).toInt(); bottomMargin = (16 * d).toInt() }
        }
        body.addView(pairedContainer)

        // Available section
        val availHeader = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        availHeader.addView(TextView(context).apply {
            text = "Available Devices"
            typeface = tf
            paint.isFakeBoldText = true
            textSize = 14f
            setTextColor(Color.parseColor("#A8C7FA"))
            layoutParams = LinearLayout.LayoutParams(0, -2, 1f)
        })
        tvScanStatus = TextView(context).apply {
            text = "Scanning..."
            typeface = tf
            textSize = 12f
            setTextColor(Color.parseColor("#9BA6B8"))
        }
        availHeader.addView(tvScanStatus)
        body.addView(availHeader)

        availableContainer = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(-1, -2).apply { topMargin = (8 * d).toInt() }
        }
        body.addView(availableContainer)

        scroll.addView(body)
        root.addView(scroll)
        rootCard.addView(root)
        setContentView(rootCard)

        window?.setBackgroundDrawableResource(android.R.color.transparent)
        window?.setDimAmount(0.60f)
        val w = (context.resources.displayMetrics.widthPixels * 0.65f).toInt()
        val h = (context.resources.displayMetrics.heightPixels * 0.72f).toInt()
        window?.setLayout(w, h)

        renderPairedList()
        startBluetoothScan()
    }

    @SuppressLint("MissingPermission")
    private fun renderPairedList() {
        pairedContainer.removeAllViews()
        val bt = adapter
        if (bt == null) {
            pairedContainer.addView(emptyRow("Bluetooth not available on this device"))
            return
        }
        val bonded = runCatching { bt.bondedDevices?.toList().orEmpty() }.getOrDefault(emptyList())
        if (bonded.isEmpty()) {
            pairedContainer.addView(emptyRow("No paired devices"))
            return
        }
        bonded.forEach { dev ->
            val name = runCatching { dev.name }.getOrNull()?.takeIf { it.isNotBlank() } ?: dev.address
            pairedContainer.addView(deviceRow(name, "${dev.address} • Paired") {
                Toast.makeText(context, "Opening Bluetooth settings for $name", Toast.LENGTH_SHORT).show()
                runCatching {
                    context.startActivity(Intent(Settings.ACTION_BLUETOOTH_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                }
            })
        }
    }

    @SuppressLint("MissingPermission")
    private fun renderAvailableList() {
        availableContainer.removeAllViews()
        if (discoveredDevices.isEmpty()) {
            availableContainer.addView(emptyRow("Searching for nearby Bluetooth devices..."))
            return
        }
        discoveredDevices.values.forEach { dev ->
            val name = runCatching { dev.name }.getOrNull()?.takeIf { it.isNotBlank() } ?: dev.address
            availableContainer.addView(deviceRow(name, "${dev.address} • Tap to pair") {
                runCatching { adapter?.cancelDiscovery() }
                val ok = runCatching { dev.createBond() }.getOrDefault(false)
                Toast.makeText(
                    context,
                    if (ok) "Pairing with $name..." else "Open Settings to pair $name",
                    Toast.LENGTH_SHORT
                ).show()
            })
        }
    }

    @SuppressLint("MissingPermission")
    private fun startBluetoothScan() {
        val bt = adapter
        if (bt == null || !bt.isEnabled) {
            tvScanStatus.text = if (bt == null) "Unavailable" else "Bluetooth off"
            renderAvailableList()
            return
        }
        runCatching {
            val filter = IntentFilter().apply {
                addAction(BluetoothDevice.ACTION_FOUND)
                addAction(BluetoothAdapter.ACTION_DISCOVERY_FINISHED)
            }
            context.registerReceiver(receiver, filter)
            receiverRegistered = true
            if (bt.isDiscovering) bt.cancelDiscovery()
            val started = bt.startDiscovery()
            tvScanStatus.text = if (started) "Scanning..." else "Location permission needed to scan"
        }.onFailure {
            tvScanStatus.text = "Scan unavailable"
        }
        renderAvailableList()
    }

    private fun deviceRow(title: String, subtitle: String, onClick: () -> Unit): View {
        val d = context.resources.displayMetrics.density
        val tf = FontCache.plusJakarta(context)
        val card = MaterialCardView(context).apply {
            radius = 18f * d
            cardElevation = 0f
            strokeWidth = 0
            setCardBackgroundColor(Color.parseColor("#252B3B"))
            layoutParams = LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = (8 * d).toInt() }
            setOnClickListener { TactileAudioEngine.playClick(); onClick() }
        }
        val col = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding((16 * d).toInt(), (12 * d).toInt(), (16 * d).toInt(), (12 * d).toInt())
        }
        col.addView(TextView(context).apply {
            text = title
            typeface = tf
            paint.isFakeBoldText = true
            textSize = 15f
            setTextColor(Color.WHITE)
        })
        col.addView(TextView(context).apply {
            text = subtitle
            typeface = tf
            textSize = 12f
            setTextColor(Color.parseColor("#9BA6B8"))
        })
        card.addView(col)
        return card
    }

    private fun emptyRow(msg: String): View {
        val d = context.resources.displayMetrics.density
        return TextView(context).apply {
            text = msg
            typeface = FontCache.plusJakarta(context)
            textSize = 13f
            setTextColor(Color.parseColor("#8D97A8"))
            setPadding(0, (6 * d).toInt(), 0, (6 * d).toInt())
        }
    }

    @SuppressLint("MissingPermission")
    override fun onStop() {
        runCatching { adapter?.cancelDiscovery() }
        if (receiverRegistered) {
            runCatching { context.unregisterReceiver(receiver) }
            receiverRegistered = false
        }
        super.onStop()
    }
}
