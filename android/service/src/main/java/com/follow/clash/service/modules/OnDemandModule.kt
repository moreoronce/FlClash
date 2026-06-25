package com.follow.clash.service.modules

import android.Manifest
import android.app.Service
import android.content.Context
import android.content.pm.PackageManager
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.wifi.WifiInfo
import android.net.wifi.WifiManager
import android.os.Build
import androidx.core.content.ContextCompat
import androidx.core.content.getSystemService
import com.follow.clash.common.GlobalState
import com.follow.clash.core.Core
import com.follow.clash.service.OnDemandDiagnostics
import com.follow.clash.service.State
import com.follow.clash.service.VpnService
import com.google.gson.Gson
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import java.util.UUID

private data class WifiSnapshot(
    val ssid: String?,
    val validated: Boolean,
)

class OnDemandModule(private val service: Service) : Module() {
    private val scope = CoroutineScope(Dispatchers.Default)
    private val connectivity by lazy {
        service.getSystemService<ConnectivityManager>()
    }
    private val wifiManager by lazy {
        service.applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager
    }
    private val gson = Gson()
    private var updateJob: Job? = null
    private var currentWifiSnapshot: WifiSnapshot? = null
    private var suspended = false
    private var isCallbackRegistered = false
    private var locationPermissionWarningLogged = false
    private var backgroundPermissionWarningLogged = false

    private val request = NetworkRequest.Builder()
        .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
        .addCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN)
        .build()

    private val callback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) {
            OnDemandDiagnostics.recordThrottled(
                "wifi-onAvailable",
                "wifi callback onAvailable network=$network"
            )
            scheduleUpdate(reason = "onAvailable")
        }

        override fun onCapabilitiesChanged(
            network: Network,
            networkCapabilities: NetworkCapabilities
        ) {
            OnDemandDiagnostics.recordThrottled(
                "wifi-onCapabilitiesChanged",
                "wifi callback onCapabilitiesChanged network=$network"
            )
            scheduleUpdate(networkCapabilities, reason = "onCapabilitiesChanged")
        }

        override fun onLinkPropertiesChanged(
            network: Network,
            linkProperties: android.net.LinkProperties
        ) {
            OnDemandDiagnostics.recordThrottled(
                "wifi-onLinkPropertiesChanged",
                "wifi callback onLinkPropertiesChanged network=$network"
            )
            scheduleUpdate(reason = "onLinkPropertiesChanged")
        }

        override fun onLost(network: Network) {
            OnDemandDiagnostics.recordThrottled(
                "wifi-onLost",
                "wifi callback onLost network=$network"
            )
            scheduleUpdate(reason = "onLost")
        }
    }

    override fun onInstall() {
        OnDemandDiagnostics.init(service.applicationContext)
        OnDemandDiagnostics.record("on-demand module installed")
        scope.launch {
            State.onDemandExcludeSSIDsFlow.collectLatest {
                updateRules(it.toSet())
            }
        }
    }

    override fun onUninstall() {
        OnDemandDiagnostics.record("on-demand module uninstalled")
        unregisterCallback()
        updateJob?.cancel()
        scope.cancel()
    }

    private fun updateRules(excludeSSIDs: Set<String>) {
        OnDemandDiagnostics.record("on-demand rules updated count=${excludeSSIDs.size}")
        if (excludeSSIDs.isEmpty()) {
            unregisterCallback()
            updateJob?.cancel()
            currentWifiSnapshot = null
            if (suspended) {
                setCoreSuspended(false)
            }
            return
        }
        registerCallback()
        scheduleUpdate(reason = "rulesUpdated")
    }

    private fun registerCallback() {
        if (isCallbackRegistered) {
            return
        }
        val manager = connectivity ?: return
        runCatching {
            manager.registerNetworkCallback(request, callback)
            isCallbackRegistered = true
            OnDemandDiagnostics.record("wifi callback registered")
        }.onFailure {
            GlobalState.log("On-demand network callback register failed: ${it.message}")
            OnDemandDiagnostics.record("wifi callback register failed: ${it.message}")
        }
    }

    private fun unregisterCallback() {
        if (!isCallbackRegistered) {
            return
        }
        runCatching {
            connectivity?.unregisterNetworkCallback(callback)
        }
        isCallbackRegistered = false
        OnDemandDiagnostics.record("wifi callback unregistered")
    }

    private fun scheduleUpdate(capabilities: NetworkCapabilities? = null, reason: String) {
        updateJob?.cancel()
        updateJob = scope.launch {
            delay(1500)
            update(capabilities, reason)
        }
    }

    private fun update(capabilities: NetworkCapabilities?, reason: String) {
        val excludeSSIDs = State.onDemandExcludeSSIDsFlow.value.toSet()
        if (excludeSSIDs.isEmpty()) {
            currentWifiSnapshot = null
            if (suspended) {
                setCoreSuspended(false)
            }
            return
        }
        val wifiSnapshot = getWifiSnapshot(capabilities)
        val shouldSuspend = wifiSnapshot.validated && excludeSSIDs.contains(wifiSnapshot.ssid)
        if (wifiSnapshot == currentWifiSnapshot && suspended == shouldSuspend) {
            return
        }
        currentWifiSnapshot = wifiSnapshot
        GlobalState.log(
            "On-demand SSID: ${wifiSnapshot.ssid ?: "unknown"}, " +
                    "validated: ${wifiSnapshot.validated}, suspended: $shouldSuspend"
        )
        OnDemandDiagnostics.record(
            "ssid update reason=$reason ssid=${wifiSnapshot.ssid ?: "unknown"} " +
                    "validated=${wifiSnapshot.validated} shouldSuspend=$shouldSuspend"
        )
        setCoreSuspended(shouldSuspend)
    }

    private fun getWifiSnapshot(capabilities: NetworkCapabilities?): WifiSnapshot {
        if (!hasLocationPermission()) {
            if (!locationPermissionWarningLogged) {
                GlobalState.log("On-demand SSID unavailable: location permission missing")
                OnDemandDiagnostics.record("ssid unavailable: location permission missing")
                locationPermissionWarningLogged = true
            }
            return WifiSnapshot(null, validated = false)
        }
        val directSnapshot = capabilities?.wifiSnapshot()
        if (directSnapshot?.ssid != null) {
            return directSnapshot
        }
        val networkSnapshot = connectivity?.allNetworks
            ?.asSequence()
            ?.mapNotNull { connectivity?.getNetworkCapabilities(it) }
            ?.firstOrNull {
                it.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) &&
                        !it.hasTransport(NetworkCapabilities.TRANSPORT_VPN)
            }
            ?.wifiSnapshot()
        if (networkSnapshot?.ssid != null) {
            return networkSnapshot
        }
        @Suppress("DEPRECATION")
        return WifiSnapshot(
            wifiManager?.connectionInfo?.ssid?.normalizeSsid(),
            validated = hasValidatedWifiNetwork()
        )
    }

    private fun NetworkCapabilities.wifiSnapshot(): WifiSnapshot {
        val validated = hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) {
            return WifiSnapshot(null, validated)
        }
        return WifiSnapshot((transportInfo as? WifiInfo)?.ssid?.normalizeSsid(), validated)
    }

    private fun hasValidatedWifiNetwork(): Boolean {
        return connectivity?.allNetworks
            ?.asSequence()
            ?.mapNotNull { connectivity?.getNetworkCapabilities(it) }
            ?.any {
                it.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) &&
                        !it.hasTransport(NetworkCapabilities.TRANSPORT_VPN) &&
                        it.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
            } == true
    }

    private fun String?.normalizeSsid(): String? {
        return when {
            this == null -> null
            this == WifiManager.UNKNOWN_SSID -> null
            this == "0x" -> null
            isBlank() -> null
            else -> removeSurrounding("\"")
        }
    }

    private fun hasLocationPermission(): Boolean {
        val fineGranted = ContextCompat.checkSelfPermission(
            service,
            Manifest.permission.ACCESS_FINE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED
        if (!fineGranted) {
            return false
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val backgroundGranted = ContextCompat.checkSelfPermission(
                service,
                Manifest.permission.ACCESS_BACKGROUND_LOCATION
            ) == PackageManager.PERMISSION_GRANTED
            if (!backgroundGranted && !backgroundPermissionWarningLogged) {
                GlobalState.log("On-demand SSID may be unavailable in background: background location permission missing")
                OnDemandDiagnostics.record("ssid may be unavailable: background location missing")
                backgroundPermissionWarningLogged = true
            }
        }
        return true
    }

    private fun setCoreSuspended(next: Boolean) {
        if (suspended == next) {
            return
        }
        OnDemandDiagnostics.record("set core suspended=$next previous=$suspended")
        suspended = next
        if (service is VpnService) {
            service.setOnDemandSuspended(next)
            return
        }
        invokeCore(if (next) "stopListener" else "startListener")
    }

    private fun invokeCore(method: String) {
        val data = gson.toJson(
            mapOf(
                "id" to "onDemand#${UUID.randomUUID()}",
                "method" to method,
            )
        )
        Core.invokeAction(data) {
            GlobalState.log("On-demand $method result: $it")
            OnDemandDiagnostics.record("core action $method result=$it")
        }
    }
}
