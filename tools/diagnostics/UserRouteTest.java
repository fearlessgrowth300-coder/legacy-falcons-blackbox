import java.util.Arrays;
import java.util.Collections;
import top.niunaijun.blackbox.core.UserRoute;

public final class UserRouteTest {
    private static int checks;

    private static void check(boolean ok, String label) {
        checks++;
        if (!ok) throw new AssertionError(label);
    }

    private static UserRoute.Assignment listener(String pkg, int port) {
        return new UserRoute.Assignment(pkg, true, "route-" + port, "127.0.0.1", port, "u", "p");
    }

    public static void main(String[] args) {
        UserRoute.Assignment instagram = listener("com.instagram.android", 21001);
        UserRoute.Decision play = UserRoute.resolve("com.android.vending",
                Collections.singletonList(instagram), true);
        check(play.kind == UserRoute.Kind.USE, "Play Store uses the user's listener");
        check("com.instagram.android".equals(play.sourcePkg), "the listener is the assigned app");
        check(play.localPort == 21001, "Play Store dials the same port");
        check(play.routeId.equals(instagram.routeId), "Play Store keeps the same route id");

        UserRoute.Decision gms = UserRoute.resolve("com.google.android.gms",
                Collections.singletonList(instagram), true);
        check(gms.kind == UserRoute.Kind.USE && gms.localPort == 21001,
                "Google services use the same user route");

        UserRoute.Decision own = UserRoute.resolve("com.instagram.android",
                Collections.singletonList(instagram), true);
        check(own.kind == UserRoute.Kind.USE && own.localPort == 21001,
                "the assigned app still uses its own listener");

        UserRoute.Decision missing = UserRoute.resolve("com.android.vending",
                Collections.emptyList(), true);
        check(missing.kind == UserRoute.Kind.NO_ROUTE, "no route fails closed");
        check(UserRoute.NO_ROUTE_MESSAGE.equals(missing.message),
                "the missing-route message names ShieldProxy");

        UserRoute.Decision optional = UserRoute.resolve("com.android.vending",
                Collections.emptyList(), false);
        check(optional.kind == UserRoute.Kind.DIRECT, "an unrequired empty user can stay direct");

        UserRoute.Assignment broken = new UserRoute.Assignment(
                "com.instagram.android", true, "route", "1.2.3.4", 443, "", "");
        UserRoute.Decision brokenOnly = UserRoute.resolve("com.android.vending",
                Collections.singletonList(broken), false);
        check(brokenOnly.kind == UserRoute.Kind.NO_ROUTE,
                "a saved route without a loopback listener does not go direct");

        UserRoute.Decision conflict = UserRoute.resolve("com.android.vending",
                Arrays.asList(instagram, listener("com.fiverr.fiverr", 21002)), true);
        check(conflict.kind == UserRoute.Kind.CONFLICT, "two listeners do not pick one for Play");
        UserRoute.Decision instagramStill = UserRoute.resolve("com.instagram.android",
                Arrays.asList(instagram, listener("com.fiverr.fiverr", 21002)), true);
        check(instagramStill.kind == UserRoute.Kind.USE && instagramStill.localPort == 21001,
                "an app with its own listener is not blocked by another app");

        check(!UserRoute.validListener("8.8.8.8", 443), "an upstream address is not a clone listener");
        check(UserRoute.validListener("127.0.0.1", 20000), "the low loopback port is accepted");
        check(!UserRoute.validListener("127.0.0.1", 19999), "ports below the listener range are refused");

        System.out.println("PASS: " + checks + " user route checks");
    }
}
