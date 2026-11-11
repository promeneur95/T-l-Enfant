package fr.promeneur.telenfant

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.telecom.Call
import android.telecom.CallAudioState
import android.telecom.CallScreeningService
import android.telecom.CallScreeningService.CallResponse
import android.telecom.InCallService
import android.telecom.TelecomManager
import android.widget.Toast

/** État partagé des appels en cours. */
object CallManager {
    var service: InCallService? = null
    val calls = mutableListOf<Call>()
    private val listeners = mutableListOf<() -> Unit>()

    val current: Call? get() = calls.lastOrNull()

    fun addListener(l: () -> Unit) { listeners += l }
    fun removeListener(l: () -> Unit) { listeners -= l }
    fun notifyChanged() { listeners.toList().forEach { it() } }

    fun setSpeaker(on: Boolean) {
        @Suppress("DEPRECATION")
        service?.setAudioRoute(if (on) CallAudioState.ROUTE_SPEAKER else CallAudioState.ROUTE_EARPIECE)
    }

    fun setMuted(on: Boolean) { service?.setMuted(on) }

    @Suppress("DEPRECATION")
    fun isSpeaker() = service?.callAudioState?.route == CallAudioState.ROUTE_SPEAKER

    @Suppress("DEPRECATION")
    fun isMuted() = service?.callAudioState?.isMuted == true

    /** Lance un appel sortant (numéros autorisés ou urgences uniquement). */
    fun dial(c: Context, number: String) {
        val emergency = Store.isEmergencyNumber(number)
        if (!emergency && !Store.isAllowed(c, number)) {
            Toast.makeText(c, "Numéro non autorisé", Toast.LENGTH_SHORT).show()
            return
        }
        if (emergency) Store.markEmergencyCall(c)
        val uri = Uri.fromParts("tel", number, null)
        if (c.checkSelfPermission(Manifest.permission.CALL_PHONE) == PackageManager.PERMISSION_GRANTED) {
            c.getSystemService(TelecomManager::class.java).placeCall(uri, Bundle())
        } else {
            c.startActivity(Intent(Intent.ACTION_CALL, uri).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }
    }
}

/** Interface d'appel système : reçoit tous les appels quand l'appli est le téléphone par défaut. */
class CallService : InCallService() {

    private val cb = object : Call.Callback() {
        override fun onStateChanged(call: Call, state: Int) = CallManager.notifyChanged()
    }

    override fun onCreate() {
        super.onCreate()
        CallManager.service = this
    }

    override fun onDestroy() {
        if (CallManager.service === this) CallManager.service = null
        super.onDestroy()
    }

    override fun onCallAdded(call: Call) {
        super.onCallAdded(call)
        val number = call.details.handle?.schemeSpecificPart
        val incoming = call.details.callDirection == Call.Details.DIRECTION_INCOMING
        if (incoming && !Store.incomingAllowed(this, number)) {
            Store.incBlocked(this)
            call.reject(false, null)
            return
        }
        call.registerCallback(cb)
        CallManager.calls += call
        CallManager.notifyChanged()
        startActivity(
            Intent(this, CallActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        )
    }

    override fun onCallRemoved(call: Call) {
        super.onCallRemoved(call)
        call.unregisterCallback(cb)
        CallManager.calls -= call
        CallManager.notifyChanged()
    }

    override fun onCallAudioStateChanged(audioState: CallAudioState?) {
        CallManager.notifyChanged()
    }
}

/** Filtre les appels entrants avant même la sonnerie. */
class ScreeningService : CallScreeningService() {
    override fun onScreenCall(details: Call.Details) {
        val incoming = details.callDirection == Call.Details.DIRECTION_INCOMING
        val allowed = !incoming || Store.incomingAllowed(this, details.handle?.schemeSpecificPart)
        val response = if (allowed) {
            CallResponse.Builder().build()
        } else {
            Store.incBlocked(this)
            CallResponse.Builder()
                .setDisallowCall(true)
                .setRejectCall(true)
                .setSkipNotification(true)
                .build()
        }
        respondToCall(details, response)
    }
}
