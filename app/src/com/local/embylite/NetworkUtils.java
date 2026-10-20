package com.local.embylite;

import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.util.Enumeration;
import java.util.Locale;

/** 网络地址工具：提供当前设备可供局域网客户端访问的 IPv4 地址。 */
public final class NetworkUtils {

    private NetworkUtils() {
    }

    public static String getLanIp() {
        try {
            Enumeration<NetworkInterface> ifaces = NetworkInterface.getNetworkInterfaces();
            String ethernetIp = null;
            while (ifaces != null && ifaces.hasMoreElements()) {
                NetworkInterface ni = ifaces.nextElement();
                if (!ni.isUp() || ni.isLoopback() || ni.isVirtual() || ni.isPointToPoint()) continue;
                int priority = getInterfacePriority(ni.getName());
                if (priority < 0) continue;
                Enumeration<InetAddress> addrs = ni.getInetAddresses();
                while (addrs.hasMoreElements()) {
                    InetAddress address = addrs.nextElement();
                    if (!(address instanceof Inet4Address) || !isPrivateIpv4(address)) continue;
                    String host = address.getHostAddress();
                    if (host == null) continue;
                    if (priority == 0) return host;
                    if (ethernetIp == null) ethernetIp = host;
                }
            }
            return ethernetIp;
        } catch (Throwable ignored) {
            return null;
        }
    }

    private static int getInterfacePriority(String name) {
        if (name == null) return -1;
        String lowerName = name.toLowerCase(Locale.ROOT);
        if (lowerName.startsWith("rmnet") || lowerName.startsWith("ccmni")
                || lowerName.startsWith("pdp") || lowerName.startsWith("tun")
                || lowerName.startsWith("tap") || lowerName.startsWith("ppp")
                || lowerName.startsWith("vpn") || lowerName.indexOf(':') >= 0) return -1;
        // 仅接受已知 LAN 接口名，避免未被 isVirtual 标记的蜂窝或虚拟接口混入。
        if (lowerName.startsWith("wlan") || lowerName.startsWith("ap")
                || lowerName.startsWith("softap") || lowerName.startsWith("swlan")
                || lowerName.startsWith("wifi")) return 0;
        if (lowerName.startsWith("eth") || (lowerName.startsWith("en")
                && lowerName.length() > 2 && lowerName.charAt(2) >= '0'
                && lowerName.charAt(2) <= '9')) return 1;
        return -1;
    }

    private static boolean isPrivateIpv4(InetAddress address) {
        if (!(address instanceof Inet4Address)) return false;
        byte[] bytes = address.getAddress();
        int first = bytes[0] & 0xff;
        int second = bytes[1] & 0xff;
        return first == 10 || (first == 172 && second >= 16 && second <= 31)
                || (first == 192 && second == 168);
    }
}
