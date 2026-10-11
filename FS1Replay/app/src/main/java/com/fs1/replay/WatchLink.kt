package com.fs1.replay

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothProfile
import android.bluetooth.BluetoothStatusCodes
import android.content.Context
import android.os.Build
import java.util.TimeZone
import java.util.UUID
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException

/**
 * BLE side of the FS1 uploader. Protocol logic (AT sequence, write types, retry/ack rules,
 * timeouts) is ported 1:1 from the original fs1replay MainActivity that is confirmed on hardware.
 */
@SuppressLint("MissingPermission")
class WatchLink(private val ctx: Context, private val ui: Listener) {

    interface Listener {
        fun log(msg: String, cls: String)
        fun status(state: String, text: String)   // idle|connecting|ready|sending|done|error
        fun progress(done: Int, total: Int)
        fun ready()
    }

    companion object {
        private fun u(n: String) = UUID.fromString("c2e6ff$n-e966-1000-8000-bef9c223df6a")
        val SERVICE = u("b0"); val CHAR_WRITE = u("b1"); val CHAR_NOTIFY = u("b2")
        val CHAR_AT_WRITE = u("b3"); val CHAR_AT_NOTIFY = u("b4"); val CHAR_STATUS_NOTIFY = u("b6")
        val CCCD: UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")

        const val WRITE_RESPONSE_TIMEOUT_MS = 5000L
        const val QUEUE_BUSY_RETRIES = 50
        const val BLOCK_ACK_TIMEOUT_MS = 3000L
        const val BLOCK_MAX_RETRIES = 5

        // Captured verbatim from a real transfer; identical in every capture. STZ/SUT/SUM are patched at send time.
        val PRE_AT: List<String> = listOf(
            "AT+SPS:AND", "AT+GST?", "AT+BDQ:5711,6593", "AT+MTU?", "AT+MTU:244",
            "AT+GBS?", "AT+GBS?",
            "AT+STZ:330", "AT+SUT:1790430072", "AT+STZ:330", "AT+SUT:1790430072",
            "AT+SHM:12", "AT+SHM:12",
            "AT+CCS:1",
            "AT+CFD:M,1000", "AT+CFD:M,1000", "AT+CFD:M,1000",
            "AT+CCS:0",
            "AT+CFD:M,1000",
            "AT+CCS:0",
            "AT+CFD:M,1000",
            "AT+CCS:1",
            "AT+CFD:M,1000", "AT+CFD:M,1000",
            "AT+CCS:0", "AT+CCS:1", "AT+CCS:0",
            "AT+SUM:1",
            "AT+CCS:1"
        )
        val POST_AT: List<String> = listOf("AT+CCS:0")

        // 0xF4 -> 1, 0xF3 -> 20 (observed); anything else unconfirmed, falls back to 1.
        fun sumValueForType(typ: Int) = when (typ) { 0xF4 -> 1; 0xF3 -> 20; else -> 1 }
    }

    private var gatt: BluetoothGatt? = null
    private var writeChar: BluetoothGattCharacteristic? = null
    private var atChar: BluetoothGattCharacteristic? = null
    @Volatile private var pendingWrite: CompletableFuture<Boolean>? = null
    @Volatile private var pendingBlockAck: CompletableFuture<Int>? = null
    @Volatile private var connected = false
    @Volatile private var sending = false

    val isReady get() = connected && !sending
    val isConnected get() = connected

    fun connect(dev: BluetoothDevice) {
        close()
        ui.status("connecting", "Connecting to ${dev.address}…")
        ui.log("Connecting to ${dev.address} ...", "")
        gatt = dev.connectGatt(ctx, false, callback, BluetoothDevice.TRANSPORT_LE)
    }

    fun close() {
        connected = false; sending = false
        try { gatt?.disconnect(); gatt?.close() } catch (_: Exception) {}
        gatt = null
    }

    private val callback = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(g: BluetoothGatt, status: Int, newState: Int) {
            if (newState == BluetoothProfile.STATE_CONNECTED) {
                ui.log("Connected. Discovering services...", "")
                g.requestConnectionPriority(BluetoothGatt.CONNECTION_PRIORITY_HIGH)
                g.discoverServices()
            } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                val was = sending
                connected = false; sending = false
                ui.log("Disconnected (status=$status).", if (was) "err" else "")
                try { g.close() } catch (_: Exception) {}
                if (gatt === g) gatt = null
                ui.status(if (was) "error" else "idle", if (was) "Disconnected during send." else "Not connected.")
            }
        }

        override fun onServicesDiscovered(g: BluetoothGatt, status: Int) {
            val service = g.getService(SERVICE)
            if (service == null) { ui.log("Service $SERVICE not found — wrong device?", "err"); ui.status("error", "Not an FS1."); return }
            writeChar = service.getCharacteristic(CHAR_WRITE)
            if (writeChar == null) { ui.log("Write characteristic not found.", "err"); ui.status("error", "Unexpected GATT layout."); return }
            atChar = service.getCharacteristic(CHAR_AT_WRITE)
            if (atChar == null) ui.log("WARNING: AT characteristic not found — transfer will fail.", "warn")

            ui.log("Enabling notifications...", "")
            Thread {
                for (uuid in listOf(CHAR_NOTIFY, CHAR_AT_NOTIFY, CHAR_STATUS_NOTIFY)) {
                    var found: BluetoothGattCharacteristic? = null
                    for (s in g.services) { val c = s.getCharacteristic(uuid); if (c != null) { found = c; break } }
                    if (found == null) { ui.log("  notify char $uuid not present, skipping", ""); continue }
                    g.setCharacteristicNotification(found, true)
                    val cccd = found.getDescriptor(CCCD)
                    if (cccd != null) {
                        if (Build.VERSION.SDK_INT >= 33) g.writeDescriptor(cccd, BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE)
                        else writeDescriptorLegacy(g, cccd)
                        ui.log("  subscribed to $uuid", "")
                    }
                    Thread.sleep(200)
                }
                connected = true
                ui.status("ready", "Connected.")
                ui.ready()
            }.start()
        }

        @Suppress("DEPRECATION")
        private fun writeDescriptorLegacy(g: BluetoothGatt, d: BluetoothGattDescriptor) {
            d.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE; g.writeDescriptor(d)
        }

        @Suppress("DEPRECATION", "OVERRIDE_DEPRECATION")
        override fun onCharacteristicChanged(g: BluetoothGatt, c: BluetoothGattCharacteristic) {
            if (Build.VERSION.SDK_INT < 33) c.value?.let { onNotify(c.uuid, it) }
        }

        override fun onCharacteristicChanged(g: BluetoothGatt, c: BluetoothGattCharacteristic, value: ByteArray) {
            onNotify(c.uuid, value)
        }

        override fun onCharacteristicWrite(g: BluetoothGatt, c: BluetoothGattCharacteristic, status: Int) {
            pendingWrite?.complete(status == BluetoothGatt.GATT_SUCCESS)
        }
    }

    private fun onNotify(uuid: UUID, value: ByteArray) {
        val tag = when (uuid) { CHAR_NOTIFY -> "data"; CHAR_AT_NOTIFY -> "AT-reply"; CHAR_STATUS_NOTIFY -> "status"; else -> uuid.toString() }
        val asText = String(value, Charsets.US_ASCII).filter { it.code in 32..126 }
        // Per-block "data" acks are noisy (one per block); only log the other channels.
        if (uuid != CHAR_NOTIFY) ui.log("<- [$tag] " + (if (asText.isNotEmpty()) asText else value.hex()), "")

        if (uuid == CHAR_NOTIFY && value.size >= 18 && isHeaderWrite(value)) {
            pendingBlockAck?.complete(((value[16].toInt() and 0xFF) shl 8) or (value[17].toInt() and 0xFF))
        }
    }

    // ---------------------------------------------------------------- write primitives (ported)
    private enum class Outcome { SUCCESS, FAILED, TIMEOUT }

    private fun isHeaderWrite(d: ByteArray) =
        d.size >= 4 && d[0] == 0xFF.toByte() && d[1] == 0xFF.toByte() && d[2] == 'B'.code.toByte() && d[3] == 'G'.code.toByte()

    @Suppress("DEPRECATION")
    private fun tryQueue(g: BluetoothGatt, ch: BluetoothGattCharacteristic, data: ByteArray, type: Int): Boolean =
        if (Build.VERSION.SDK_INT >= 33) g.writeCharacteristic(ch, data, type) == BluetoothStatusCodes.SUCCESS
        else { ch.writeType = type; ch.value = data; g.writeCharacteristic(ch) }

    private fun writeOne(g: BluetoothGatt, ch: BluetoothGattCharacteristic, data: ByteArray, noResponse: Boolean): Outcome {
        if (noResponse) {
            repeat(QUEUE_BUSY_RETRIES) {
                if (tryQueue(g, ch, data, BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE)) return Outcome.SUCCESS
                Thread.sleep(3)
            }
            return Outcome.FAILED
        }
        repeat(QUEUE_BUSY_RETRIES) {
            val f = CompletableFuture<Boolean>()
            pendingWrite = f
            if (!tryQueue(g, ch, data, BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT)) { pendingWrite = null; Thread.sleep(15); return@repeat }
            return try { if (f.get(WRITE_RESPONSE_TIMEOUT_MS, TimeUnit.MILLISECONDS)) Outcome.SUCCESS else Outcome.FAILED }
            catch (_: TimeoutException) { Outcome.TIMEOUT }
        }
        return Outcome.FAILED
    }

    private fun buildAt(template: String, sum: Int): ByteArray {
        val now = System.currentTimeMillis()
        val text = when {
            template.startsWith("AT+SUT:") -> "AT+SUT:${now / 1000}"
            template.startsWith("AT+STZ:") -> "AT+STZ:${TimeZone.getDefault().getOffset(now) / 60000}"
            template.startsWith("AT+SUM:") -> "AT+SUM:$sum"
            else -> template
        }
        return text.toByteArray(Charsets.US_ASCII) + byteArrayOf(0)   // exactly one trailing NUL
    }

    // ---------------------------------------------------------------- upload
    /** blocks = list of messages; each = [20-byte header, frame, frame, ...]. */
    fun send(blocks: List<List<ByteArray>>) {
        if (!isReady) { ui.status("error", "Not connected."); return }
        sending = true
        Thread {
            try { upload(blocks) }
            catch (e: Exception) { fail("Upload crashed: ${e.message}") }
            finally { sending = false }
        }.start()
    }

    private fun fail(msg: String) { ui.log(msg, "err"); ui.status("error", msg) }

    private fun upload(blocks: List<List<ByteArray>>) {
        val g = gatt ?: return fail("Not connected.")
        val ch = writeChar ?: return fail("No write characteristic.")
        val at = atChar ?: return fail("No AT characteristic.")
        val typ = blocks.first()[0][7].toInt() and 0xFF
        val sum = sumValueForType(typ)
        ui.log("Detected upload type 0x%02X -> AT+SUM:%d".format(typ, sum) +
                if (typ != 0xF3 && typ != 0xF4) " (unrecognised type — verify on hardware)" else "", "")
        ui.status("sending", "Preparing watch…")
        ui.progress(0, blocks.size)

        fun sendAt(t: String): Boolean {
            val data = buildAt(t, sum)
            val o = writeOne(g, at, data, noResponse = false)
            if (o == Outcome.FAILED) { fail("AT write FAILED: $t"); return false }
            if (o == Outcome.TIMEOUT) ui.log("$t: no GATT ack within ${WRITE_RESPONSE_TIMEOUT_MS}ms — continuing.", "warn")
            Thread.sleep(10)
            return true
        }

        for (t in PRE_AT) if (!sendAt(t)) return
        ui.log("Sent ${PRE_AT.size} setup AT commands.", "")

        for ((bi, block) in blocks.withIndex()) {
            val header = block[0]
            val index = ((header[16].toInt() and 0xFF) shl 8) or (header[17].toInt() and 0xFF)
            var attempt = 0; var acked = false
            while (attempt < BLOCK_MAX_RETRIES && !acked) {
                attempt++
                val f = CompletableFuture<Int>(); pendingBlockAck = f
                if (writeOne(g, ch, header, noResponse = false) == Outcome.FAILED) {
                    ui.log("Block $index header FAILED (attempt $attempt).", "warn"); continue
                }
                for (i in 1 until block.size) {
                    if (writeOne(g, ch, block[i], noResponse = true) == Outcome.FAILED)
                        ui.log("Block $index frame FAILED (attempt $attempt).", "warn")
                }
                val got = try { f.get(BLOCK_ACK_TIMEOUT_MS, TimeUnit.MILLISECONDS) } catch (_: TimeoutException) { null }
                pendingBlockAck = null
                if (gatt == null) return fail("Disconnected at block $index.")
                when {
                    got == index -> acked = true
                    got != null -> ui.log("Block $index: got ack for block $got instead, retrying (attempt $attempt).", "warn")
                    else -> ui.log("Block $index: no ack within ${BLOCK_ACK_TIMEOUT_MS}ms, retrying (attempt $attempt).", "warn")
                }
            }
            if (!acked) return fail("Block $index FAILED after $BLOCK_MAX_RETRIES attempts. Stopping.")
            ui.progress(bi + 1, blocks.size)
            ui.status("sending", "Sending… block ${bi + 1}/${blocks.size}")
        }

        for (t in POST_AT) if (!sendAt(t)) return
        ui.log("Done. ${blocks.size} blocks confirmed by device ack.", "ok")
        ui.status("done", "Sent ✓ — check the watch.")
    }
}

fun ByteArray.hex(): String = joinToString("") { "%02x".format(it) }
