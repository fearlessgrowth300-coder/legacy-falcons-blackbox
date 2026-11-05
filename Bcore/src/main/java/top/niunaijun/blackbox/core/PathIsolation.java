package top.niunaijun.blackbox.core;

/**
 * Decides whether a path may be opened by one virtual user.
 *
 * <p>This is a string policy, not a kernel mount. Guests still share the host uid, so a raw
 * {@code syscall} that skips libc can ignore it. Callers must treat {@link Decision#denied} as
 * "do not open the caller's path".
 */
public final class PathIsolation {
    public static final class Decision {
        public final boolean applyRules;
        public final boolean denied;
        public final String path;

        private Decision(boolean applyRules, boolean denied, String path) {
            this.applyRules = applyRules;
            this.denied = denied;
            this.path = path;
        }

        public static Decision rules() {
            return new Decision(true, false, null);
        }

        public static Decision allow(String path) {
            return new Decision(false, false, path);
        }

        public static Decision deny(String path) {
            return new Decision(false, true, path);
        }
    }

    private PathIsolation() {}

    public static String normalize(String path) {
        if (path == null || path.isEmpty()) return path;
        boolean absolute = path.charAt(0) == '/';
        String[] parts = path.split("/");
        java.util.ArrayDeque<String> stack = new java.util.ArrayDeque<>();
        for (String part : parts) {
            if (part.isEmpty() || ".".equals(part)) continue;
            if ("..".equals(part)) {
                if (!stack.isEmpty()) stack.removeLast();
                continue;
            }
            stack.addLast(part);
        }
        StringBuilder out = new StringBuilder();
        if (absolute) out.append('/');
        boolean first = true;
        for (String part : stack) {
            if (!first) out.append('/');
            first = false;
            out.append(part);
        }
        if (absolute && out.length() == 0) return "/";
        return out.toString();
    }

    public static String denyPath(String virtualRoot, int userId) {
        String root = stripTrailingSlash(normalize(virtualRoot));
        if (root == null || root.isEmpty()) root = "/data/local/tmp";
        return root + "/data/user/" + userId + "/.isolation-denied";
    }

    /**
     * @param virtualRoot host {@code blackbox/} data root, or null when unknown
     * @param externalRoot host external {@code blackbox/} root, or null
     */
    public static Decision decide(String raw, String virtualRoot, String externalRoot, int userId) {
        if (raw == null || raw.isEmpty() || userId < 0) return Decision.rules();
        String path = normalize(raw);
        if (path == null) return Decision.rules();
        String root = stripTrailingSlash(normalize(virtualRoot));
        String ext = stripTrailingSlash(normalize(externalRoot));
        if (root != null && isUnder(path, root)) {
            return virtualDecision(path, root, userId);
        }
        if (ext != null && isUnder(path, ext)) {
            return externalDecision(path, ext, root, userId);
        }
        if (path.contains("/blackbox/")) {
            return blackboxMarkerDecision(path, root, userId);
        }
        return Decision.rules();
    }

    private static Decision virtualDecision(String path, String root, int userId) {
        if (path.equals(root)) return Decision.deny(denyPath(root, userId));
        String rel = path.substring(root.length() + 1);
        if (rel.equals("system") || rel.startsWith("system/")) {
            return Decision.deny(denyPath(root, userId));
        }
        if (rel.startsWith("data/user/") || rel.startsWith("data/user_de/")) {
            return userSegment(rel, userId) ? Decision.allow(path)
                    : Decision.deny(denyPath(root, userId));
        }
        if (rel.startsWith("profiles/")) {
            return profilesOwned(rel, userId) ? Decision.allow(path)
                    : Decision.deny(denyPath(root, userId));
        }
        if (rel.equals("data/app") || rel.startsWith("data/app/")
                || rel.equals("proc") || rel.startsWith("proc/")
                || rel.equals("cache") || rel.startsWith("cache/")) {
            return Decision.allow(path);
        }
        return Decision.deny(denyPath(root, userId));
    }

    private static Decision externalDecision(String path, String ext, String virtualRoot, int userId) {
        String denyRoot = virtualRoot != null ? virtualRoot : ext;
        if (path.equals(ext)) return Decision.deny(denyPath(denyRoot, userId));
        String rel = path.substring(ext.length() + 1);
        String prefix = "storage/emulated/";
        if (!rel.startsWith(prefix)) return Decision.deny(denyPath(denyRoot, userId));
        String rest = rel.substring(prefix.length());
        int slash = rest.indexOf('/');
        String id = slash < 0 ? rest : rest.substring(0, slash);
        if (id.equals(Integer.toString(userId)) && slash >= 0) return Decision.allow(path);
        if (id.equals(Integer.toString(userId)) && slash < 0) return Decision.allow(path);
        return Decision.deny(denyPath(denyRoot, userId));
    }

    private static Decision blackboxMarkerDecision(String path, String root, int userId) {
        String denyRoot = root != null ? root : "/data/local/tmp";
        int marker = path.indexOf("/blackbox/");
        if (marker < 0) return Decision.rules();
        String rel = path.substring(marker + "/blackbox/".length());
        if (rel.equals("system") || rel.startsWith("system/")) {
            return Decision.deny(denyPath(denyRoot, userId));
        }
        if (rel.startsWith("data/user/") || rel.startsWith("data/user_de/")) {
            String segment = rel.startsWith("data/user_de/") ? "data/user_de/" : "data/user/";
            String rest = rel.substring(segment.length());
            int slash = rest.indexOf('/');
            String id = slash < 0 ? rest : rest.substring(0, slash);
            if (id.equals(Integer.toString(userId))) return Decision.allow(path);
            return Decision.deny(denyPath(denyRoot, userId));
        }
        if (rel.startsWith("storage/emulated/")) {
            String rest = rel.substring("storage/emulated/".length());
            int slash = rest.indexOf('/');
            String id = slash < 0 ? rest : rest.substring(0, slash);
            if (id.equals(Integer.toString(userId))) return Decision.allow(path);
            return Decision.deny(denyPath(denyRoot, userId));
        }
        if (rel.startsWith("profiles/")) {
            return profilesOwned(rel, userId) ? Decision.allow(path)
                    : Decision.deny(denyPath(denyRoot, userId));
        }
        return Decision.rules();
    }

    private static boolean userSegment(String rel, int userId) {
        String segment = rel.startsWith("data/user_de/") ? "data/user_de/" : "data/user/";
        String rest = rel.substring(segment.length());
        int slash = rest.indexOf('/');
        String id = slash < 0 ? rest : rest.substring(0, slash);
        return id.equals(Integer.toString(userId));
    }

    private static boolean profilesOwned(String rel, int userId) {
        // profiles/cur/<id>/... or profiles/ref/<id>/...
        String[] parts = rel.split("/");
        if (parts.length < 3) return false;
        return parts[2].equals(Integer.toString(userId));
    }

    private static boolean isUnder(String path, String root) {
        if (root == null || root.isEmpty()) return false;
        return path.equals(root) || path.startsWith(root + "/");
    }

    private static String stripTrailingSlash(String path) {
        if (path == null || path.length() <= 1) return path;
        int end = path.length();
        while (end > 1 && path.charAt(end - 1) == '/') end--;
        return end == path.length() ? path : path.substring(0, end);
    }
}
