package com.goplan.app.server

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class NetworkUtilsTest {

    @Test
    fun getLocalIpv4Address_neverReturnsLoopback() {
        val ip = NetworkUtils.getLocalIpv4Address()
        if (ip != null) {
            assertFalse("IP should not be loopback", ip == "127.0.0.1")
            assertFalse("IP should not start with 127.", ip.startsWith("127."))
            assertTrue("IP should be a valid IPv4 string format", ip.matches(Regex("\\d+\\.\\d+\\.\\d+\\.\\d+")))
        }
    }

    @Test
    fun selectBestIpv4Address_filtersOutLoopbackAndVirtualAndDownInterfaces() {
        val interfaces = listOf(
            NetworkInterfaceInfo(
                name = "lo",
                isUp = true,
                isLoopback = true,
                isVirtual = false,
                addresses = listOf("127.0.0.1")
            ),
            NetworkInterfaceInfo(
                name = "veth0",
                isUp = true,
                isLoopback = false,
                isVirtual = true,
                addresses = listOf("192.168.100.1")
            ),
            NetworkInterfaceInfo(
                name = "eth0",
                isUp = false,
                isLoopback = false,
                isVirtual = false,
                addresses = listOf("192.168.1.50")
            )
        )

        val selected = NetworkUtils.selectBestIpv4Address(interfaces)
        assertNull(selected)
    }

    @Test
    fun selectBestIpv4Address_prioritizesWifiInterface() {
        val interfaces = listOf(
            NetworkInterfaceInfo(
                name = "eth0",
                isUp = true,
                isLoopback = false,
                isVirtual = false,
                addresses = listOf("192.168.1.50")
            ),
            NetworkInterfaceInfo(
                name = "wlan0",
                isUp = true,
                isLoopback = false,
                isVirtual = false,
                addresses = listOf("192.168.1.100")
            )
        )

        val selected = NetworkUtils.selectBestIpv4Address(interfaces)
        assertEquals("192.168.1.100", selected)
    }

    @Test
    fun selectBestIpv4Address_prioritizesInterfaceWithWifiInName() {
        val interfaces = listOf(
            NetworkInterfaceInfo(
                name = "en0",
                isUp = true,
                isLoopback = false,
                isVirtual = false,
                addresses = listOf("10.0.0.2")
            ),
            NetworkInterfaceInfo(
                name = "wi-fi_0",
                isUp = true,
                isLoopback = false,
                isVirtual = false,
                addresses = listOf("192.168.0.20")
            )
        )

        val selected = NetworkUtils.selectBestIpv4Address(interfaces)
        assertEquals("192.168.0.20", selected)
    }

    @Test
    fun selectBestIpv4Address_returnsFirstValidIpv4WhenNoWifi() {
        val interfaces = listOf(
            NetworkInterfaceInfo(
                name = "eth0",
                isUp = true,
                isLoopback = false,
                isVirtual = false,
                addresses = listOf("192.168.1.50")
            ),
            NetworkInterfaceInfo(
                name = "eth1",
                isUp = true,
                isLoopback = false,
                isVirtual = false,
                addresses = listOf("192.168.2.60")
            )
        )

        val selected = NetworkUtils.selectBestIpv4Address(interfaces)
        assertEquals("192.168.1.50", selected)
    }

    @Test
    fun selectBestIpv4Address_ignores127LoopbackAddressesWithinValidInterface() {
        val interfaces = listOf(
            NetworkInterfaceInfo(
                name = "eth0",
                isUp = true,
                isLoopback = false,
                isVirtual = false,
                addresses = listOf("127.0.0.1", "10.0.0.5")
            )
        )

        val selected = NetworkUtils.selectBestIpv4Address(interfaces)
        assertEquals("10.0.0.5", selected)
    }

    @Test
    fun selectBestIpv4Address_returnsNullWhenNoValidAddresses() {
        val interfaces = listOf(
            NetworkInterfaceInfo(
                name = "eth0",
                isUp = true,
                isLoopback = false,
                isVirtual = false,
                addresses = listOf("127.0.0.1")
            )
        )

        val selected = NetworkUtils.selectBestIpv4Address(interfaces)
        assertNull(selected)
    }
}
