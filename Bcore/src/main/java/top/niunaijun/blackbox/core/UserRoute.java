package top.niunaijun.blackbox.core;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * One ShieldProxy route belongs to a BlackBox user. An app with its own loopback
 * listener uses that listener. Play Store, Google services, and every other clone
 * without a listener use the user's single listener. Nothing falls through to the
 * phone network when a route is required or a saved route cannot be dialed.
 */
public final class UserRoute {
    public static final String NO_ROUTE_MESSAGE =
            "No secure route assigned to this user, open ShieldProxy";
    public static final String CONFLICT_MESSAGE =
            "This user has more than one route, open ShieldProxy";

    public enum Kind { USE, NO_ROUTE, CONFLICT, DIRECT }

    public static final class Assignment {
        public final String pkg;
        public final boolean enabled;
        public final String routeId;
        public final String localHost;
        public final int localPort;
        public final String socksUser;
        public final String socksPassword;

        public Assignment(String pkg, boolean enabled, String routeId, String localHost,
                          int localPort, String socksUser, String socksPassword) {
            this.pkg = pkg;
            this.enabled = enabled;
            this.routeId = routeId;
            this.localHost = localHost == null ? "" : localHost;
            this.localPort = localPort;
            this.socksUser = socksUser == null ? "" : socksUser;
            this.socksPassword = socksPassword == null ? "" : socksPassword;
        }

        public boolean hasListener() {
            return enabled && routeId != null && validListener(localHost, localPort);
        }
    }

    public static final class Decision {
        public final Kind kind;
        public final String sourcePkg;
        public final String routeId;
        public final String localHost;
        public final int localPort;
        public final String socksUser;
        public final String socksPassword;
        public final String message;

        private Decision(Kind kind, Assignment source, String message) {
            this.kind = kind;
            this.sourcePkg = source == null ? null : source.pkg;
            this.routeId = source == null ? null : source.routeId;
            this.localHost = source == null ? "" : source.localHost;
            this.localPort = source == null ? 0 : source.localPort;
            this.socksUser = source == null ? "" : source.socksUser;
            this.socksPassword = source == null ? "" : source.socksPassword;
            this.message = message;
        }

        public static Decision use(Assignment source) {
            return new Decision(Kind.USE, source, null);
        }

        public static Decision noRoute() {
            return new Decision(Kind.NO_ROUTE, null, NO_ROUTE_MESSAGE);
        }

        public static Decision conflict() {
            return new Decision(Kind.CONFLICT, null, CONFLICT_MESSAGE);
        }

        public static Decision direct() {
            return new Decision(Kind.DIRECT, null, null);
        }
    }

    private UserRoute() {}

    /** ShieldProxy's per-clone SOCKS listener. Ports outside this range are not dialed. */
    public static boolean validListener(String host, int port) {
        if (port < 20000 || port > 27999) return false;
        if (host == null) return false;
        String value = host.trim().toLowerCase(Locale.ROOT);
        return value.isEmpty() || value.equals("127.0.0.1") || value.equals("localhost")
                || value.equals("::1");
    }

    public static Decision resolve(String pkg, List<Assignment> assignments, boolean requireProxy) {
        List<Assignment> rows = assignments == null ? new ArrayList<Assignment>() : assignments;
        Assignment own = find(rows, pkg);
        if (own != null && own.hasListener()) return Decision.use(own);

        Map<Integer, Assignment> listeners = new LinkedHashMap<>();
        boolean anyEnabled = false;
        for (Assignment row : rows) {
            if (row == null || !row.enabled) continue;
            anyEnabled = true;
            if (!row.hasListener()) continue;
            if (!listeners.containsKey(row.localPort)) listeners.put(row.localPort, row);
        }
        if (listeners.size() == 1) return Decision.use(listeners.values().iterator().next());
        if (listeners.size() > 1) return Decision.conflict();
        if (!requireProxy && !anyEnabled) return Decision.direct();
        return Decision.noRoute();
    }

    private static Assignment find(List<Assignment> rows, String pkg) {
        if (pkg == null || pkg.isEmpty()) return null;
        for (Assignment row : rows) {
            if (row != null && pkg.equals(row.pkg)) return row;
        }
        return null;
    }
}
