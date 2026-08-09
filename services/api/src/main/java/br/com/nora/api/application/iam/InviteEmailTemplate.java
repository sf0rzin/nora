package br.com.nora.api.application.iam;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

/**
 * Renders the invite HTML template (US06). Loads {@code email-templates/invite.html} from the
 * classpath and replaces {@code {{...}}} placeholders.
 *
 * <p>We keep manual rendering (no Thymeleaf) so as not to add a dependency: the template is static
 * and simple. The values are HTML-escaped to avoid injection in fields controlled by the tenant
 * ({@code tenantName}, {@code invitedByName}).
 */
public final class InviteEmailTemplate {

    private static final String TEMPLATE_PATH = "email-templates/invite.html";
    private static final String CACHED_TEMPLATE = loadTemplate();

    private InviteEmailTemplate() {}

    public static String render(
            String tenantName,
            String invitedByName,
            String email,
            String acceptUrl,
            int expiresInDays) {
        return CACHED_TEMPLATE
                .replace("{{tenantName}}", escape(tenantName))
                .replace("{{invitedByName}}", escape(invitedByName))
                .replace("{{email}}", escape(email))
                .replace("{{acceptUrl}}", escape(acceptUrl))
                .replace("{{expiresInDays}}", String.valueOf(expiresInDays));
    }

    private static String loadTemplate() {
        ClassLoader cl = InviteEmailTemplate.class.getClassLoader();
        try (InputStream is = cl.getResourceAsStream(TEMPLATE_PATH)) {
            if (is == null) {
                throw new IllegalStateException(
                        "Invite email template not found on classpath: " + TEMPLATE_PATH);
            }
            return new String(is.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException ex) {
            throw new IllegalStateException("Could not load invite template", ex);
        }
    }

    private static String escape(String s) {
        if (s == null) {
            return "";
        }
        return s.replace("&", "&amp;")
                .replace("<", "&lt;")
                .replace(">", "&gt;")
                .replace("\"", "&quot;")
                .replace("'", "&#39;");
    }
}
