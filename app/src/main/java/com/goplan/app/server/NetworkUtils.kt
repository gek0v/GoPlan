package com.goplan.app.server

import java.net.Inet4Address
import java.net.NetworkInterface

data class NetworkInterfaceInfo(
    val name: String,
    val isUp: Boolean,
    val isLoopback: Boolean,
    val isVirtual: Boolean,
    val addresses: List<String>
)

object NetworkUtils {

    fun getLocalIpv4Address(): String? {
        val interfaces = try {
            NetworkInterface.getNetworkInterfaces()?.toList() ?: return null
        } catch (_: Exception) {
            return null
        }

        val interfaceInfos = interfaces.mapNotNull { iface ->
            try {
                val isUp = iface.isUp
                val isLoopback = iface.isLoopback
                val isVirtual = iface.isVirtual
                val inetAddresses = iface.inetAddresses?.toList() ?: emptyList()
                val ipv4List = inetAddresses.filterIsInstance<Inet4Address>()
                    .filter { !it.isLoopbackAddress }
                    .mapNotNull { it.hostAddress }
                    .filter { !it.startsWith("127.") }

                NetworkInterfaceInfo(
                    name = iface.name,
                    isUp = isUp,
                    isLoopback = isLoopback,
                    isVirtual = isVirtual,
                    addresses = ipv4List
                )
            } catch (_: Exception) {
                null
            }
        }

        return selectBestIpv4Address(interfaceInfos)
    }

    internal fun selectBestIpv4Address(interfaces: List<NetworkInterfaceInfo>): String? {
        val candidates = mutableListOf<Pair<String, String>>()

        for (iface in interfaces) {
            if (!iface.isUp || iface.isLoopback || iface.isVirtual) {
                continue
            }
            for (addr in iface.addresses) {
                if (addr != "127.0.0.1" && !addr.startsWith("127.")) {
                    candidates.add(iface.name.lowercase() to addr)
                }
            }
        }

        val wifiCandidate = candidates.firstOrNull { (name, _) ->
            name.contains("wlan") || name.contains("wi-fi") || name.contains("wifi")
        }

        return wifiCandidate?.second ?: candidates.firstOrNull()?.second
    }
}
