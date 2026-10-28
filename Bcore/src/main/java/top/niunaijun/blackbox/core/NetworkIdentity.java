package top.niunaijun.blackbox.core;

/**
 * Stable per-profile network surface. These values replace the phone's local address.
 * They are not a second uplink.
 */
public final class NetworkIdentity {
    private NetworkIdentity() {}

    public static byte[] localIpv4(int userId, String seed) {
        int hash = mix(seed, userId);
        int b = 64 + Math.floorMod(userId, 64);
        int c = 20 + Math.floorMod(hash, 200);
        int d = 2 + Math.floorMod(hash / 17, 200);
        return new byte[]{10, (byte) b, (byte) c, (byte) d};
    }

    public static String dotted(byte[] ip) {
        if (ip == null || ip.length != 4) return "10.64.20.2";
        return (ip[0] & 0xff) + "." + (ip[1] & 0xff) + "." + (ip[2] & 0xff) + "." + (ip[3] & 0xff);
    }

    public static String dotted(int userId, String seed) {
        return dotted(localIpv4(userId, seed));
    }

    public static String ssid(String seed) {
        return "CloneNet-" + hex(seed, 4);
    }

    /** Locally administered, unicast MAC. */
    public static String bssid(String seed) {
        int hash = mix(seed, 0xB551D);
        int[] octets = new int[6];
        octets[0] = ((hash >>> 8) & 0xFC) | 0x02;
        for (int i = 1; i < 6; i++) {
            hash = mix(Integer.toHexString(hash), i);
            octets[i] = Math.floorMod(hash, 256);
        }
        StringBuilder out = new StringBuilder();
        for (int i = 0; i < octets.length; i++) {
            if (i > 0) out.append(':');
            out.append(String.format("%02x", octets[i]));
        }
        return out.toString();
    }

    /** A few seconds to two minutes. Intervals stay correct; absolute uptime does not match the phone. */
    public static long uptimeOffsetMs(String seed) {
        int hash = mix(seed, 0x0A17);
        return 1000L + Math.floorMod(hash, 119000);
    }

    public static String procNet(String name, String dottedIp, String bssid) {
        String ip = dottedIp == null ? "10.64.20.2" : dottedIp;
        String ap = bssid == null ? "02:00:00:00:00:01" : bssid;
        if ("arp".equals(name)) {
            return "IP address       HW type     Flags       HW address            Mask     Device\n";
        }
        if ("route".equals(name)) {
            return "Iface Destination Gateway Flags RefCnt Use Metric Mask MTU Window IRTT\n"
                    + "wlan0 00000000 00000000 0003 0 0 0 00000000 0 0 0\n";
        }
        if ("dev".equals(name)) {
            return "Inter-|   Receive                                                |  Transmit\n"
                    + " face |bytes    packets errs drop fifo frame compressed multicast|bytes    packets errs drop fifo colls carrier compressed\n"
                    + " wlan0: 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0\n";
        }
        if ("wireless".equals(name)) {
            return "Inter-| sta        |   Quality        |   Discarded packets               | Missed | WE\n"
                    + " wlan0: " + ap + " 0 0 0 0 0 0 0 0 0\n";
        }
        if ("tcp".equals(name) || "udp".equals(name) || "tcp6".equals(name) || "udp6".equals(name)) {
            return "  sl  local_address rem_address   st tx_queue rx_queue tr tm->when retrnsmt   uid  timeout inode\n";
        }
        if ("if_inet6".equals(name) || "fib_trie".equals(name)) {
            return "# profile " + ip + "\n" + ip + "\n";
        }
        return ip + "\n";
    }

    private static int mix(String seed, int salt) {
        int hash = 0x811c9dc5 ^ salt;
        String value = seed == null ? "" : seed;
        for (int i = 0; i < value.length(); i++) {
            hash ^= value.charAt(i);
            hash *= 0x01000193;
        }
        return hash;
    }

    private static String hex(String seed, int bytes) {
        int hash = mix(seed, 0x551D);
        StringBuilder out = new StringBuilder();
        for (int i = 0; i < bytes; i++) {
            hash = mix(Integer.toHexString(hash), i + 1);
            out.append(String.format("%02x", Math.floorMod(hash, 256)));
        }
        return out.toString();
    }
}
