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
    /** Numéro du répondeur : réglé par le parent, sinon celui de la carte SIM. */
    @android.annotation.SuppressLint("MissingPermission")
    fun voicemail(c: Context): String? {
        if (Store.voicemailDisabled(c)) return null
        return voicemailNumber(c)
    }

    /** Numéro du répondeur, même s'il est désactivé (pour le réactiver). */
    @android.annotation.SuppressLint("MissingPermission")
    fun voicemailNumber(c: Context): String? {
        Store.voicemailOverride(c).takeIf { it.isNotBlank() }?.let { return it }
        return try {
            c.getSystemService(android.telephony.TelephonyManager::class.java)?.voiceMailNumber?.takeIf { it.isNotBlank() }
        } catch (_: Exception) { null }
    }

    fun isVoicemail(c: Context, number: String?): Boolean {
        val vm = voicemailNumber(c) ?: return false
        val a = (number ?: "").filter { it.isDigit() }
        return a.isNotEmpty() && a == vm.filter { it.isDigit() }
    }

    /** Code opérateur (renvois d'appel), réservé au menu parent. */
    fun sendOperatorCode(c: Context, code: String) {
        val uri = Uri.fromParts("tel", code, null)
        if (c.checkSelfPermission(Manifest.permission.CALL_PHONE) == PackageManager.PERMISSION_GRANTED) {
            c.getSystemService(TelecomManager::class.java).placeCall(uri, Bundle())
        } else {
            c.startActivity(Intent(Intent.ACTION_CALL, uri).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }
    }

    fun dial(c: Context, number: String) {
        val emergency = Store.isEmergencyNumber(number)
        if (!emergency && !isVoicemail(c, number) && Store.isAllowed(c, number) && !Rules.numberAllowedNow(c, number)) {
            Toast.makeText(c, "Mode école : seuls tes parents sont joignables", Toast.LENGTH_SHORT).show()
            return
        }
        if (!emergency && !Store.isAllowed(c, number) && !isVoicemail(c, number)) {
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
        override fun onStateChanged(call: Call, state: Int) {
            updateRinging()
            CallManager.notifyChanged()
        }
    }

    /** L'appli joue elle-même la sonnerie (choisie par l'enfant ou envoyée par les parents). */
    private fun updateRinging() {
        val ringing = CallManager.calls.any { stateOf(it) == Call.STATE_RINGING }
        val busy = CallManager.calls.any { stateOf(it) == Call.STATE_ACTIVE || stateOf(it) == Call.STATE_HOLDING }
        if (ringing && !busy) CallRinger.start(this) else CallRinger.stop(this)
    }

    override fun onCreate() {
        super.onCreate()
        CallManager.service = this
    }

    override fun onDestroy() {
        CallRinger.stop(this)
        if (CallManager.service === this) CallManager.service = null
        super.onDestroy()
    }

    override fun onCallAdded(call: Call) {
        super.onCallAdded(call)
        val number = call.details.handle?.schemeSpecificPart
        val incoming = call.details.callDirection == Call.Details.DIRECTION_INCOMING
        if (incoming && !Store.incomingAllowed(this, number)) {
            Store.logBlocked(this, "appel", number, reason = Store.blockReason(this, number))
            call.reject(false, null)
            return
        }
        call.registerCallback(cb)
        CallManager.calls += call
        updateRinging()
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
        updateRinging()
        CallManager.notifyChanged()
    }

    /** Touche volume pendant la sonnerie : couper le son. */
    override fun onSilenceRinger() {
        CallRinger.stop(this)
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
            val n = details.handle?.schemeSpecificPart
            Store.logBlocked(this, "appel", n, reason = Store.blockReason(this, n))
            CallResponse.Builder()
                .setDisallowCall(true)
                .setRejectCall(true)
                .setSkipNotification(true)
                .build()
        }
        respondToCall(details, response)
    }
}
