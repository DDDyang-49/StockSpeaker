package com.stockspeaker

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.media.AudioDeviceCallback
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.os.Build

enum class PrivateAudioState { NO_HEADSET, READY, SPEAKING, LATCHED_MUTE }

/** 最小私密路由门禁：耳机丢失立即锁定，重连后必须由用户显式恢复。 */
class AudioRouteGuard(
    context: Context,
    private val onRouteLost: () -> Unit,
    private val onStateChanged: (PrivateAudioState) -> Unit
) {
    private val app = context.applicationContext
    private val audio = app.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    private val lock = Any()
    private var registered = false
    private var verified: DeviceIdentity? = null

    @Volatile
    var state: PrivateAudioState = PrivateAudioState.NO_HEADSET
        private set

    private data class DeviceIdentity(val id: Int, val type: Int, val name: String, val address: String)

    fun start() {
        if (registered) return
        try {
            app.registerReceiver(noisyReceiver, IntentFilter(AudioManager.ACTION_AUDIO_BECOMING_NOISY))
            audio.registerAudioDeviceCallback(deviceCallback, null)
            registered = true
            publish(if (candidate() == null) PrivateAudioState.NO_HEADSET else PrivateAudioState.LATCHED_MUTE)
        } catch (_: Exception) {
            latch()
        }
    }

    fun stop() {
        if (!registered) return
        try { app.unregisterReceiver(noisyReceiver) } catch (_: Exception) {}
        try { audio.unregisterAudioDeviceCallback(deviceCallback) } catch (_: Exception) {}
        registered = false
        verified = null
        publish(PrivateAudioState.LATCHED_MUTE)
    }

    /** “开始盯盘”或“恢复播报”都属于用户显式确认当前耳机。 */
    fun resume(): Boolean = synchronized(lock) {
        val device = candidate()
        if (device == null) {
            verified = null
            publish(PrivateAudioState.NO_HEADSET)
            false
        } else {
            verified = identity(device)
            publish(PrivateAudioState.READY)
            true
        }
    }

    fun canSpeakNow(): Boolean = synchronized(lock) {
        if (state != PrivateAudioState.READY && state != PrivateAudioState.SPEAKING) return false
        val current = candidate()?.let(::identity) ?: run {
            latch()
            return false
        }
        val ok = current == verified
        if (!ok) latch()
        ok
    }

    fun verifiedOutput(): AudioDeviceInfo? = synchronized(lock) {
        val current = candidate() ?: return null
        current.takeIf { identity(it) == verified && canSpeakNow() }
    }

    fun markSpeaking() {
        synchronized(lock) { if (state == PrivateAudioState.READY) publish(PrivateAudioState.SPEAKING) }
    }

    fun markIdle() {
        synchronized(lock) { if (state == PrivateAudioState.SPEAKING) publish(PrivateAudioState.READY) }
    }

    private fun latch() {
        val notify = synchronized(lock) {
            verified = null
            val changed = state != PrivateAudioState.LATCHED_MUTE
            publish(PrivateAudioState.LATCHED_MUTE)
            changed
        }
        if (notify) onRouteLost()
    }

    private fun publish(next: PrivateAudioState) {
        state = next
        onStateChanged(next)
    }

    private fun candidate(): AudioDeviceInfo? = try {
        audio.getDevices(AudioManager.GET_DEVICES_OUTPUTS).firstOrNull { isPrivateType(it.type) }
    } catch (_: Exception) { null }

    private fun identity(device: AudioDeviceInfo) = DeviceIdentity(
        id = device.id,
        type = device.type,
        name = device.productName?.toString().orEmpty(),
        address = if (Build.VERSION.SDK_INT >= 28) device.address.orEmpty() else ""
    )

    private fun isPrivateType(type: Int): Boolean = when (type) {
        AudioDeviceInfo.TYPE_WIRED_HEADPHONES,
        AudioDeviceInfo.TYPE_WIRED_HEADSET,
        AudioDeviceInfo.TYPE_BLUETOOTH_A2DP,
        AudioDeviceInfo.TYPE_BLUETOOTH_SCO,
        AudioDeviceInfo.TYPE_USB_HEADSET,
        AudioDeviceInfo.TYPE_HEARING_AID -> true
        else -> Build.VERSION.SDK_INT >= 31 && type == AudioDeviceInfo.TYPE_BLE_HEADSET
    }

    private val noisyReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) = latch()
    }

    private val deviceCallback = object : AudioDeviceCallback() {
        override fun onAudioDevicesRemoved(removedDevices: Array<out AudioDeviceInfo>) {
            if (removedDevices.any { isPrivateType(it.type) }) latch()
        }

        override fun onAudioDevicesAdded(addedDevices: Array<out AudioDeviceInfo>) {
            // 重连只更新系统设备列表，绝不自动解除 LATCHED_MUTE。
            if (state == PrivateAudioState.NO_HEADSET && addedDevices.any { isPrivateType(it.type) }) {
                publish(PrivateAudioState.LATCHED_MUTE)
            }
        }
    }
}
