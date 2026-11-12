package com.workbuddy.localmodel.util

import com.workbuddy.localmodel.llm.ModelInfo
import java.net.Inet4Address
import java.net.NetworkInterface
import java.util.Locale

object NetworkUtils {

    /** 手机在局域网中的 IPv4 地址（微信/PC 与手机需在同一网络） */
    fun localIpv4Addresses(): List<String> {
        val result = mutableListOf<String>()
        try {
            val interfaces = NetworkInterface.getNetworkInterfaces() ?: return result
            for (nic in interfaces) {
                if (!nic.isUp || nic.isLoopback) continue
                for (address in nic.inetAddresses) {
                    if (address is Inet4Address && !address.isLoopbackAddress && !address.isLinkLocalAddress) {
                        result += address.hostAddress ?: continue
                    }
                }
            }
        } catch (_: Throwable) {
            // 忽略读取网络接口失败
        }
        return result.distinct()
    }

    fun formatBytes(bytes: Long): String {
        if (bytes <= 0) return "0 B"
        val units = arrayOf("B", "KB", "MB", "GB", "TB")
        var value = bytes.toDouble()
        var index = 0
        while (value >= 1024 && index < units.lastIndex) {
            value /= 1024
            index++
        }
        return String.format(Locale.US, "%.2f %s", value, units[index])
    }

    fun formatParams(params: Long): String = when {
        params >= 1_000_000_000L -> String.format(Locale.US, "%.2fB", params / 1_000_000_000.0)
        params >= 1_000_000L -> String.format(Locale.US, "%.1fM", params / 1_000_000.0)
        else -> params.toString()
    }

    fun modelTitle(info: ModelInfo): String = info.desc.ifBlank { info.name }
}
