package top.niunaijun.blackbox.core;

/** Profile-stable Build strings that do not change SDK, ABI, or attestation. */
public final class BuildConsistency {
    private BuildConsistency() {}

    public static String host(String seed) {
        return "host" + token(seed, 0x1057, 6);
    }

    public static String user(String seed) {
        return "build" + token(seed, 0x05E4, 4);
    }

    /** A date inside 2022-2024, stable for the seed. Not the phone's build time. */
    public static long timeMillis(String seed) {
        int hash = mix(seed, 0x71BE);
        long day = Math.floorMod(hash, 800);
        return 1640995200000L + day * 86400000L;
    }

    private static String token(String seed, int salt, int chars) {
        int hash = mix(seed, salt);
        String alphabet = "0123456789abcdef";
        StringBuilder out = new StringBuilder(chars);
        for (int i = 0; i < chars; i++) {
            hash = mix(Integer.toHexString(hash), salt + i);
            out.append(alphabet.charAt(Math.floorMod(hash, alphabet.length())));
        }
        return out.toString();
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
}
