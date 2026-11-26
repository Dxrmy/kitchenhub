package com.kyle.kitchenhub.data

import android.content.Context
import androidx.activity.ComponentActivity
import com.google.home.FactoryRegistry
import com.google.home.ForcePermissionFlow
import com.google.home.Home
import com.google.home.HomeClient
import com.google.home.HomeConfig
import com.google.home.HomeDevice
import com.google.home.PermissionsResultStatus
import com.google.home.PermissionsState
import com.google.home.matter.standard.DimmableLightDevice
import com.google.home.matter.standard.OnOff
import com.google.home.matter.standard.OnOffLightDevice
import com.google.home.matter.standard.OnOffPluginUnitDevice
import com.google.home.matter.standard.Thermostat
import com.google.home.matter.standard.ThermostatDevice
import com.google.home.matter.standard.ThermostatTrait
import kotlinx.coroutines.flow.firstOrNull

/**
 * Live Google Home APIs bridge using play-services-home:17.12.0.
 */
object GoogleHomeBridge {

    private var client: HomeClient? = null
    private var lastDevices: List<HomeDevice> = emptyList()

    private fun getOrCreateClient(context: Context): HomeClient? {
        client?.let { return it }
        return runCatching {
            val registry = FactoryRegistry(
                types = listOf(
                    OnOffLightDevice,
                    DimmableLightDevice,
                    OnOffPluginUnitDevice,
                    ThermostatDevice
                ),
                traits = listOf(OnOff, Thermostat)
            )
            Home.getClient(
                context.applicationContext,
                HomeConfig(factoryRegistry = registry)
            ).also { client = it }
        }.getOrNull()
    }

    fun registerActivity(activity: ComponentActivity) {
        runCatching {
            getOrCreateClient(activity)?.registerActivityResultCallerForPermissions(activity)
        }
    }

    suspend fun hasPermissions(context: Context): Boolean {
        val c = getOrCreateClient(context) ?: return false
        return runCatching {
            c.hasPermissions().firstOrNull() == PermissionsState.GRANTED
        }.getOrDefault(false)
    }

    suspend fun requestPermissions(context: Context): String {
        val c = getOrCreateClient(context) ?: return "Google Home SDK unavailable on this device"
        return runCatching {
            val res = c.requestPermissions(ForcePermissionFlow.FORCE_LAUNCH)
            when (res.status) {
                PermissionsResultStatus.SUCCESS -> "Google Home connected!"
                PermissionsResultStatus.CANCELLED -> "Google Home sign-in cancelled"
                else -> "Google Home auth error: ${res.status}"
            }
        }.getOrElse { "Google Home auth failed: ${it.message}" }
    }

    private suspend fun resolveOnOff(dev: HomeDevice): OnOff? {
        return when {
            dev.has(OnOffLightDevice) -> dev.type(OnOffLightDevice).firstOrNull()?.standardTraits?.onOff
            dev.has(DimmableLightDevice) -> dev.type(DimmableLightDevice).firstOrNull()?.standardTraits?.onOff
            dev.has(OnOffPluginUnitDevice) -> dev.type(OnOffPluginUnitDevice).firstOrNull()?.standardTraits?.onOff
            else -> null
        }
    }

    private suspend fun resolveThermostat(dev: HomeDevice): Thermostat? {
        return if (dev.has(ThermostatDevice)) {
            dev.type(ThermostatDevice).firstOrNull()?.standardTraits?.thermostat
        } else null
    }

    suspend fun fetchDevices(context: Context): List<SmartHomeDevice> {
        val c = getOrCreateClient(context) ?: return emptyList()
        if (!hasPermissions(context)) return emptyList()
        return runCatching {
            val devs = c.devices().list().toList()
            lastDevices = devs
            devs.mapNotNull { dev ->
                val thermTrait = resolveThermostat(dev)
                val onOffTrait = resolveOnOff(dev)
                when {
                    thermTrait != null -> {
                        val sp = (thermTrait.occupiedHeatingSetpoint ?: 2100) / 100
                        SmartHomeDevice(dev.id.id, dev.name, "climate", true, sp)
                    }
                    onOffTrait != null -> {
                        val kind = if (dev.has(OnOffLightDevice) || dev.has(DimmableLightDevice)) "light" else "switch"
                        SmartHomeDevice(dev.id.id, dev.name, kind, onOffTrait.onOff == true)
                    }
                    else -> null
                }
            }
        }.getOrDefault(emptyList())
    }

    suspend fun toggleDevice(context: Context, deviceId: String, turnOn: Boolean): Boolean {
        val dev = lastDevices.firstOrNull { it.id.id == deviceId } ?: return false
        val onOff = resolveOnOff(dev) ?: return false
        return runCatching {
            if (turnOn) onOff.on() else onOff.off()
            true
        }.getOrDefault(false)
    }

    suspend fun setThermostat(context: Context, deviceId: String, tempC: Int): Boolean {
        val dev = lastDevices.firstOrNull { it.id.id == deviceId } ?: return false
        val therm = resolveThermostat(dev) ?: return false
        return runCatching {
            val cur = (therm.occupiedHeatingSetpoint ?: 2100) / 100
            val diff = ((tempC - cur) * 10).toByte()
            therm.setpointRaiseLower(ThermostatTrait.SetpointRaiseLowerModeEnum.Heat, diff)
            true
        }.getOrDefault(false)
    }
}
