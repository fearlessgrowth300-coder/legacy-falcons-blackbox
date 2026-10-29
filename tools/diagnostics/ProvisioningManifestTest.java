import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;
import top.niunaijun.blackbox.core.ProvisioningModeChoice;

import javax.xml.parsers.DocumentBuilderFactory;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Collections;

/**
 * Confirms the Android 12+ DPC activities are exported to ManagedProvisioning
 * and that the provisioning intent uses the component-name extra.
 */
public final class ProvisioningManifestTest {
    private static final String ANDROID = "http://schemas.android.com/apk/res/android";
    private static final String BIND = "android.permission.BIND_DEVICE_ADMIN";
    private static int checks;

    private static void check(boolean ok, String label) {
        checks++;
        if (!ok) throw new AssertionError(label);
    }

    public static void main(String[] args) throws Exception {
        Path root = Path.of(args.length > 0 ? args[0] : ".");
        Path manifest = root.resolve("app/src/main/AndroidManifest.xml");
        DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
        factory.setNamespaceAware(true);
        Document document = factory.newDocumentBuilder().parse(manifest.toFile());
        Element application = (Element) document.getElementsByTagName("application").item(0);
        check(application != null, "manifest has an application");

        assertHandler(application, "android.app.action.GET_PROVISIONING_MODE",
                ".profile.GetProvisioningModeActivity");
        assertHandler(application, "android.app.action.ADMIN_POLICY_COMPLIANCE",
                ".profile.AdminPolicyComplianceActivity");
        assertAdminReceiver(application);

        String controller = Files.readString(
                root.resolve("app/src/main/java/top/niunaijun/blackboxa/profile/ManagedProfileController.kt"),
                StandardCharsets.UTF_8);
        check(controller.contains("EXTRA_PROVISIONING_DEVICE_ADMIN_COMPONENT_NAME"),
                "provisioning intent names the admin component");
        check(controller.contains("EXTRA_PROVISIONING_SKIP_ENCRYPTION"),
                "provisioning intent skips encryption");
        check(controller.contains("EXTRA_PROVISIONING_ALLOW_OFFLINE"),
                "API 33 provisioning can continue offline");
        check(!controller.contains("EXTRA_PROVISIONING_DEVICE_ADMIN_PACKAGE_NAME"),
                "deprecated package-name extra is not set");
        check(!controller.contains("EXTRA_PROVISIONING_SKIP_USER_CONSENT"),
                "skip-user-consent is not set");

        String main = Files.readString(
                root.resolve("app/src/main/java/top/niunaijun/blackboxa/view/main/MainActivity.kt"),
                StandardCharsets.UTF_8);
        check(main.contains("managedProfileProvisioning.launch(controller.provisionIntent())"),
                "profile setup is launched for a result");
        check(!main.contains("startActivity(controller.provisionIntent())"),
                "profile setup is not launched without a result");

        check(ProvisioningModeChoice.choose(null) == ProvisioningModeChoice.MANAGED_PROFILE,
                "a missing allow-list returns managed profile");
        check(ProvisioningModeChoice.choose(Collections.emptyList())
                        == ProvisioningModeChoice.MANAGED_PROFILE,
                "an empty allow-list returns managed profile");
        check(ProvisioningModeChoice.choose(Arrays.asList(2, 1))
                        == ProvisioningModeChoice.MANAGED_PROFILE,
                "managed profile is chosen when it is allowed");
        check(ProvisioningModeChoice.choose(Collections.singletonList(3))
                        == ProvisioningModeChoice.MANAGED_PROFILE_ON_PERSONAL_DEVICE,
                "a personal-device profile is accepted when it is the only profile mode");
        check(ProvisioningModeChoice.choose(Collections.singletonList(1))
                        == ProvisioningModeChoice.NONE,
                "fully managed device is refused");

        System.out.println("PASS: " + checks + " provisioning manifest checks");
    }

    private static void assertHandler(Element application, String action, String activityName) {
        Element activity = activityWithAction(application, action);
        check(activity != null, action + " has an activity");
        if (activity == null) return;
        check(activityName.equals(activity.getAttributeNS(ANDROID, "name")),
                action + " is handled by " + activityName);
        check("true".equals(activity.getAttributeNS(ANDROID, "exported")),
                action + " activity is exported");
        check(BIND.equals(activity.getAttributeNS(ANDROID, "permission")),
                action + " activity requires BIND_DEVICE_ADMIN");
        check(hasCategory(activity, "android.intent.category.DEFAULT"),
                action + " activity accepts CATEGORY_DEFAULT");
    }

    private static void assertAdminReceiver(Element application) {
        NodeList children = application.getChildNodes();
        Element receiver = null;
        for (int i = 0; i < children.getLength(); i++) {
            Node node = children.item(i);
            if (!(node instanceof Element)) continue;
            Element element = (Element) node;
            if (!"receiver".equals(element.getTagName())) continue;
            if (".profile.BlackBoxDeviceAdminReceiver".equals(element.getAttributeNS(ANDROID, "name"))) {
                receiver = element;
                break;
            }
        }
        check(receiver != null, "device admin receiver is declared");
        if (receiver == null) return;
        check("true".equals(receiver.getAttributeNS(ANDROID, "exported")),
                "device admin receiver is exported");
        check(BIND.equals(receiver.getAttributeNS(ANDROID, "permission")),
                "device admin receiver requires BIND_DEVICE_ADMIN");
        check(hasAction(receiver, "android.app.action.PROFILE_PROVISIONING_COMPLETE"),
                "device admin receiver handles provisioning completion");
        check(hasAction(receiver, "android.app.action.DEVICE_ADMIN_ENABLED"),
                "device admin receiver handles admin enabled");
    }

    private static Element activityWithAction(Element application, String action) {
        NodeList children = application.getChildNodes();
        for (int i = 0; i < children.getLength(); i++) {
            Node node = children.item(i);
            if (!(node instanceof Element)) continue;
            Element element = (Element) node;
            if (!"activity".equals(element.getTagName())) continue;
            if (hasAction(element, action)) return element;
        }
        return null;
    }

    private static boolean hasAction(Element component, String action) {
        return hasNamedChild(component, "action", action);
    }

    private static boolean hasCategory(Element component, String category) {
        return hasNamedChild(component, "category", category);
    }

    private static boolean hasNamedChild(Element component, String tag, String name) {
        NodeList nodes = component.getElementsByTagName(tag);
        for (int i = 0; i < nodes.getLength(); i++) {
            Element element = (Element) nodes.item(i);
            if (name.equals(element.getAttributeNS(ANDROID, "name"))) return true;
        }
        return false;
    }
}
