package com.plugin.approvaljobstep;

import com.dtolabs.rundeck.core.execution.ExecutionListener;
import com.dtolabs.rundeck.core.storage.ResourceMeta;
import com.dtolabs.rundeck.core.execution.workflow.steps.StepException;
import com.dtolabs.rundeck.core.execution.workflow.steps.StepFailureReason;
import com.dtolabs.rundeck.core.plugins.Plugin;
import com.dtolabs.rundeck.core.plugins.configuration.Description;
import com.dtolabs.rundeck.core.plugins.configuration.Describable;
import com.dtolabs.rundeck.core.plugins.configuration.DynamicProperties;
import com.dtolabs.rundeck.core.plugins.configuration.Property;
import com.dtolabs.rundeck.core.plugins.configuration.PropertyScope;
import com.dtolabs.rundeck.core.plugins.configuration.StringRenderingConstants;
import com.dtolabs.rundeck.plugins.descriptions.PluginDescription;
import com.dtolabs.rundeck.plugins.descriptions.PluginProperty;
import com.dtolabs.rundeck.plugins.descriptions.RenderingOption;
import com.dtolabs.rundeck.plugins.descriptions.RenderingOptions;
import com.dtolabs.rundeck.plugins.step.PluginStepContext;
import com.dtolabs.rundeck.plugins.step.StepPlugin;
import com.dtolabs.rundeck.plugins.util.DescriptionBuilder;
import com.dtolabs.rundeck.plugins.util.PropertyBuilder;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.rundeck.storage.api.PathUtil;
import org.rundeck.storage.api.Resource;
import org.rundeck.app.spi.Services;

import javax.mail.Message;
import javax.mail.PasswordAuthentication;
import javax.mail.Session;
import javax.mail.Transport;
import javax.mail.internet.MimeBodyPart;
import javax.mail.internet.InternetAddress;
import javax.mail.internet.MimeMessage;
import javax.mail.internet.MimeMultipart;
import java.io.ByteArrayOutputStream;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Date;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Properties;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;

@Plugin(service = "WorkflowStep", name = "approval-job-step")
@PluginDescription(
    title = "Approval Job Step",
    description = "Inserts a user approval step into a job workflow with sequential email approvals and callback links."
)
public class ApprovalJobStep implements StepPlugin, Describable, DynamicProperties {
    private static final String PROP_APPROVAL_MESSAGE = "approvalMessage";
    private static final String PROP_APPROVAL_TIMEOUT_MINUTES = "approvalTimeoutMinutes";
    private static final String PROP_AUTO_APPROVE_ON_TIMEOUT = "autoApproveOnTimeout";
    private static final String PROP_PRIMARY_APPROVER_EMAIL = "primaryApproverEmail";
    private static final String PROP_SECONDARY_APPROVER_EMAIL = "secondaryApproverEmail";
    private static final String PROP_ESCALATION_TIME_MINUTES = "escalationTimeMinutes";
    private static final String PROP_SMTP_SERVER = "smtpServer";
    private static final String PROP_SMTP_PORT = "smtpPort";
    private static final String PROP_SMTP_USERNAME = "smtpUsername";
    private static final String PROP_SMTP_PASSWORD_PATH = "smtpPasswordPath";
    private static final String PROP_FROM_EMAIL_ADDRESS = "fromEmailAddress";
    private static final String PROP_USE_TLS = "useTls";
    private static final String PROP_APPROVAL_URL_BASE = "approvalUrlBase";
    private static final String PROP_CHECK_INTERVAL_SECONDS = "checkIntervalSeconds";

    private static final int CALLBACK_PORT = 5555;
    private static final Logger LOG = LoggerFactory.getLogger(ApprovalJobStep.class);
    private static final AtomicBoolean CALLBACK_SERVER_STARTED = new AtomicBoolean(false);
    private static final Map<String, String> APPROVAL_RESULTS = new ConcurrentHashMap<>();
    private static final Map<String, String> APPROVAL_TOKENS = new ConcurrentHashMap<>();
    private static final Map<String, String> APPROVAL_APPROVER = new ConcurrentHashMap<>();
    private static final Object USER_CACHE_LOCK = new Object();
    private static final long USER_CACHE_TTL_MS = 60_000L;
    private static volatile UserSelectData USER_SELECT_CACHE;
    private static volatile long USER_SELECT_CACHE_TS;

    @PluginProperty(title = "Approval Message", description = "Message sent to approvers", required = true)
    @RenderingOptions({@RenderingOption(key = "displayType", value = "MULTI_LINE"), @RenderingOption(key = "groupName", value = "Approval Configuration")})
    private String approvalMessage;

    @PluginProperty(title = "Approval Timeout (minutes)", description = "Maximum wait time", defaultValue = "60", required = false)
    @RenderingOption(key = "groupName", value = "Approval Configuration")
    private Integer approvalTimeoutMinutes;

    @PluginProperty(title = "Auto-approve on Timeout", description = "Auto approve when timeout reached", defaultValue = "false", required = false)
    @RenderingOption(key = "groupName", value = "Approval Configuration")
    private Boolean autoApproveOnTimeout;

    @PluginProperty(title = "Primary Approver Email", description = "Primary approver", required = true)
    @RenderingOption(key = "groupName", value = "Sequential Approvers")
    private String primaryApproverEmail;

    @PluginProperty(title = "Secondary Approver Email", description = "Escalation approver", required = false)
    @RenderingOption(key = "groupName", value = "Sequential Approvers")
    private String secondaryApproverEmail;

    @PluginProperty(title = "Escalation Time (minutes)", description = "Escalation delay", defaultValue = "30", required = false)
    @RenderingOption(key = "groupName", value = "Sequential Approvers")
    private Integer escalationTimeMinutes;

    @PluginProperty(title = "SMTP Server", description = "SMTP host", required = true)
    @RenderingOption(key = "groupName", value = "Email Configuration")
    private String smtpServer;

    @PluginProperty(title = "SMTP Port", description = "SMTP port", defaultValue = "587", required = false)
    @RenderingOption(key = "groupName", value = "Email Configuration")
    private Integer smtpPort;

    @PluginProperty(title = "SMTP Username", description = "SMTP username", required = true)
    @RenderingOption(key = "groupName", value = "Email Configuration")
    private String smtpUsername;

    @PluginProperty(title = "SMTP Password Path", description = "Key Storage path for SMTP password", required = true)
    @RenderingOptions({
        @RenderingOption(key = "selectionAccessor", value = "STORAGE_PATH"),
        @RenderingOption(key = "storagePathRoot", value = "keys"),
        @RenderingOption(key = "storageFileMetaFilter", value = "Rundeck-data-type=password"),
        @RenderingOption(key = "groupName", value = "Email Configuration")
    })
    private String smtpPasswordPath;

    @PluginProperty(title = "From Email Address", description = "Sender address", required = true)
    @RenderingOption(key = "groupName", value = "Email Configuration")
    private String fromEmailAddress;

    @PluginProperty(title = "Use TLS", description = "Enable SMTP STARTTLS", defaultValue = "true", required = false)
    @RenderingOption(key = "groupName", value = "Email Configuration")
    private Boolean useTls;

    @PluginProperty(title = "Approval URL Base", description = "Base URL for approve/deny links", required = false)
    @RenderingOptions({@RenderingOption(key = "groupName", value = "Advanced Options"), @RenderingOption(key = "grouping", value = "secondary")})
    private String approvalUrlBase;

    @PluginProperty(title = "Check Interval (seconds)", description = "Polling interval", defaultValue = "30", required = false)
    @RenderingOptions({@RenderingOption(key = "groupName", value = "Advanced Options"), @RenderingOption(key = "grouping", value = "secondary")})
    private Integer checkIntervalSeconds;

    @Override
    public Description getDescription() {
        UserSelectData selectData = loadUserSelectData();
        DescriptionBuilder builder = DescriptionBuilder.builder();
        builder.name("approval-job-step")
            .title("Approval Job Step")
            .description("Inserts a user approval step into a job workflow with sequential email approvals and callback links.");

        builder.property(PropertyBuilder.builder()
            .string(PROP_APPROVAL_MESSAGE)
            .title("Approval Message")
            .description("Message sent to approvers")
            .required(true)
            .renderingOption(StringRenderingConstants.DISPLAY_TYPE_KEY, StringRenderingConstants.DisplayType.MULTI_LINE)
            .renderingOption(StringRenderingConstants.GROUP_NAME, "Approval Configuration"));

        builder.property(PropertyBuilder.builder()
            .integer(PROP_APPROVAL_TIMEOUT_MINUTES)
            .title("Approval Timeout (minutes)")
            .description("Maximum wait time")
            .defaultValue("60")
            .required(false)
            .renderingOption(StringRenderingConstants.GROUP_NAME, "Approval Configuration"));

        builder.property(PropertyBuilder.builder()
            .booleanType(PROP_AUTO_APPROVE_ON_TIMEOUT)
            .title("Auto-approve on Timeout")
            .description("Auto approve when timeout reached")
            .defaultValue("false")
            .required(false)
            .renderingOption(StringRenderingConstants.GROUP_NAME, "Approval Configuration"));

        builder.property(PropertyBuilder.builder()
            .type(Property.Type.FreeSelect)
            .name(PROP_PRIMARY_APPROVER_EMAIL)
            .title("Primary Approver")
            .description("Select a user email or enter a custom email address")
            .required(true)
            .values(selectData.values)
            .labels(selectData.labels)
            .renderingOption(StringRenderingConstants.GROUP_NAME, "Sequential Approvers"));

        builder.property(PropertyBuilder.builder()
            .type(Property.Type.FreeSelect)
            .name(PROP_SECONDARY_APPROVER_EMAIL)
            .title("Secondary Approver (Escalation)")
            .description("Optional escalation approver")
            .required(false)
            .values(selectData.values)
            .labels(selectData.labels)
            .renderingOption(StringRenderingConstants.GROUP_NAME, "Sequential Approvers"));

        builder.property(PropertyBuilder.builder()
            .integer(PROP_ESCALATION_TIME_MINUTES)
            .title("Escalation Time (minutes)")
            .description("Escalation delay")
            .defaultValue("30")
            .required(false)
            .renderingOption(StringRenderingConstants.GROUP_NAME, "Sequential Approvers"));

        builder.property(PropertyBuilder.builder()
            .string(PROP_SMTP_SERVER)
            .title("SMTP Server")
            .description("SMTP host")
            .required(true)
            .renderingOption(StringRenderingConstants.GROUP_NAME, "Email Configuration"));

        builder.property(PropertyBuilder.builder()
            .integer(PROP_SMTP_PORT)
            .title("SMTP Port")
            .description("SMTP port")
            .defaultValue("587")
            .required(false)
            .renderingOption(StringRenderingConstants.GROUP_NAME, "Email Configuration"));

        builder.property(PropertyBuilder.builder()
            .string(PROP_SMTP_USERNAME)
            .title("SMTP Username")
            .description("SMTP username")
            .required(true)
            .renderingOption(StringRenderingConstants.GROUP_NAME, "Email Configuration"));

        builder.property(PropertyBuilder.builder()
            .string(PROP_SMTP_PASSWORD_PATH)
            .title("SMTP Password Path")
            .description("Key Storage path for SMTP password")
            .required(true)
            .renderingOption(StringRenderingConstants.SELECTION_ACCESSOR_KEY, StringRenderingConstants.SelectionAccessor.STORAGE_PATH)
            .renderingOption(StringRenderingConstants.STORAGE_PATH_ROOT_KEY, "keys")
            .renderingOption(StringRenderingConstants.STORAGE_FILE_META_FILTER_KEY, "Rundeck-data-type=password")
            .renderingOption(StringRenderingConstants.GROUP_NAME, "Email Configuration"));

        builder.property(PropertyBuilder.builder()
            .string(PROP_FROM_EMAIL_ADDRESS)
            .title("From Email Address")
            .description("Sender address")
            .required(true)
            .renderingOption(StringRenderingConstants.GROUP_NAME, "Email Configuration"));

        builder.property(PropertyBuilder.builder()
            .booleanType(PROP_USE_TLS)
            .title("Use TLS")
            .description("Enable SMTP STARTTLS")
            .defaultValue("true")
            .required(false)
            .renderingOption(StringRenderingConstants.GROUP_NAME, "Email Configuration"));

        builder.property(PropertyBuilder.builder()
            .string(PROP_APPROVAL_URL_BASE)
            .title("Approval URL Base")
            .description("Base URL for approve/deny links")
            .required(false)
            .renderingOption(StringRenderingConstants.GROUP_NAME, "Advanced Options")
            .renderingOption(StringRenderingConstants.GROUPING, "secondary"));

        builder.property(PropertyBuilder.builder()
            .integer(PROP_CHECK_INTERVAL_SECONDS)
            .title("Check Interval (seconds)")
            .description("Polling interval")
            .defaultValue("30")
            .required(false)
            .renderingOption(StringRenderingConstants.GROUP_NAME, "Advanced Options")
            .renderingOption(StringRenderingConstants.GROUPING, "secondary"));

        return builder.build();
    }

    @Override
    public Map<String, Object> dynamicProperties(Map<String, Object> projectAndFrameworkValues, Services services) {
        UserSelectData selectData = loadUserSelectData();
        if (selectData.values.isEmpty()) {
            System.err.println("ApprovalJobStep dynamicProperties: no users loaded");
            LOG.warn("ApprovalJobStep dynamicProperties: no users loaded");
            return null;
        }
        System.err.println("ApprovalJobStep dynamicProperties: loaded " + selectData.values.size() + " users");
        LOG.info("ApprovalJobStep dynamicProperties: loaded {} users", selectData.values.size());
        Map<String, Object> out = new LinkedHashMap<>();
        out.put(PROP_PRIMARY_APPROVER_EMAIL, selectData.labels);
        out.put(PROP_SECONDARY_APPROVER_EMAIL, selectData.labels);
        return out;
    }

    @Override
    public void executeStep(PluginStepContext context, Map<String, Object> configuration) throws StepException {
        final ExecutionListener logger = context.getExecutionContext().getExecutionListener();

        normalizeConfig(configuration);
        validateConfiguration();
        startCallbackServerIfNeeded(logger);

        final String smtpPassword;
        try {
            smtpPassword = getPasswordFromKeyStorage(context, this.smtpPasswordPath);
        } catch (Exception e) {
            throw new StepException("Error accessing SMTP password from key storage: " + e.getMessage(), e, StepFailureReason.ConfigurationFailure);
        }

        logger.log(2, "Starting approval workflow step");

        final String approvalId = UUID.randomUUID().toString();
        final String token = UUID.randomUUID().toString();
        APPROVAL_TOKENS.put(approvalId, token);

        if (isBlank(this.approvalUrlBase)) {
            this.approvalUrlBase = "http://localhost:" + CALLBACK_PORT;
        }

        final Map<String, Object> approvalData = new ConcurrentHashMap<>();
        approvalData.put("id", approvalId);
        approvalData.put("token", token);
        approvalData.put("message", this.approvalMessage);
        approvalData.put("jobName", context.getDataContext() != null && context.getDataContext().get("job") != null ? String.valueOf(context.getDataContext().get("job").get("name")) : "Unknown Job");
        approvalData.put("projectName", context.getFrameworkProject());
        approvalData.put("executionId", context.getDataContext() != null && context.getDataContext().get("job") != null ? String.valueOf(context.getDataContext().get("job").get("execid")) : "unknown");
        approvalData.put("requestTime", new Date().toString());
        approvalData.put("status", "pending");
        approvalData.put("currentApprover", this.primaryApproverEmail);

        try {
            sendApprovalEmail(smtpPassword, this.primaryApproverEmail, approvalData, false, logger);
            logger.log(2, "Sent approval request to primary approver");
        } catch (Exception e) {
            throw new StepException("Failed to send approval email: " + e.getMessage(), e, StepFailureReason.IOFailure);
        }

        final String result = waitForApproval(smtpPassword, approvalData, logger);

        context.getExecutionContext().getOutputContext().addOutput("approval", "id", approvalId);
        context.getExecutionContext().getOutputContext().addOutput("approval", "result", result);
        context.getExecutionContext().getOutputContext().addOutput("approval", "approver", APPROVAL_APPROVER.getOrDefault(approvalId, "system"));

        APPROVAL_RESULTS.remove(approvalId);
        APPROVAL_TOKENS.remove(approvalId);
        APPROVAL_APPROVER.remove(approvalId);

        if ("denied".equalsIgnoreCase(result)) {
            throw new StepException("Job execution denied by approver", StepFailureReason.PluginFailed);
        }
        if ("timeout".equalsIgnoreCase(result)) {
            throw new StepException("Approval request timed out", StepFailureReason.PluginFailed);
        }
    }

    private void normalizeConfig(Map<String, Object> configuration) {
        this.approvalTimeoutMinutes = toInt(configuration.get("approvalTimeoutMinutes"), valueOrDefault(this.approvalTimeoutMinutes, 60));
        this.escalationTimeMinutes = toInt(configuration.get("escalationTimeMinutes"), valueOrDefault(this.escalationTimeMinutes, 30));
        this.smtpPort = toInt(configuration.get("smtpPort"), valueOrDefault(this.smtpPort, 587));
        this.checkIntervalSeconds = toInt(configuration.get("checkIntervalSeconds"), valueOrDefault(this.checkIntervalSeconds, 30));
        this.useTls = toBool(configuration.get("useTls"), this.useTls == null ? true : this.useTls);
        this.autoApproveOnTimeout = toBool(configuration.get("autoApproveOnTimeout"), this.autoApproveOnTimeout == null ? false : this.autoApproveOnTimeout);
    }

    private void validateConfiguration() throws StepException {
        if (isBlank(approvalMessage)) throw new StepException("Approval message is required", StepFailureReason.ConfigurationFailure);
        if (isBlank(primaryApproverEmail)) throw new StepException("Primary approver email is required", StepFailureReason.ConfigurationFailure);
        if (isBlank(smtpServer)) throw new StepException("SMTP server is required", StepFailureReason.ConfigurationFailure);
        if (isBlank(smtpUsername)) throw new StepException("SMTP username is required", StepFailureReason.ConfigurationFailure);
        if (isBlank(fromEmailAddress)) throw new StepException("From email address is required", StepFailureReason.ConfigurationFailure);
        if (!isValidEmail(primaryApproverEmail)) throw new StepException("Primary approver email format is invalid", StepFailureReason.ConfigurationFailure);
        if (!isBlank(secondaryApproverEmail) && !isValidEmail(secondaryApproverEmail)) throw new StepException("Secondary approver email format is invalid", StepFailureReason.ConfigurationFailure);
    }

    private String waitForApproval(String smtpPassword, Map<String, Object> approvalData, ExecutionListener logger) throws StepException {
        final String id = String.valueOf(approvalData.get("id"));
        final long start = System.currentTimeMillis();
        final long timeoutMs = (long) valueOrDefault(this.approvalTimeoutMinutes, 60) * 60_000L;
        final long escalationMs = (long) valueOrDefault(this.escalationTimeMinutes, 30) * 60_000L;
        final long checkMs = (long) valueOrDefault(this.checkIntervalSeconds, 30) * 1000L;
        boolean escalated = false;

        while (true) {
            final String response = APPROVAL_RESULTS.get(id);
            if (response != null) {
                logger.log(2, "Received approval response: " + response);
                return response;
            }

            final long elapsed = System.currentTimeMillis() - start;

            if (!escalated && !isBlank(secondaryApproverEmail) && elapsed >= escalationMs) {
                try {
                    sendApprovalEmail(smtpPassword, secondaryApproverEmail, approvalData, true, logger);
                    approvalData.put("currentApprover", secondaryApproverEmail);
                    escalated = true;
                    logger.log(2, "Escalated approval request to secondary approver");
                } catch (Exception e) {
                    logger.log(1, "Failed to send escalation email: " + e.getMessage());
                }
            }

            if (timeoutMs > 0 && elapsed >= timeoutMs) {
                if (Boolean.TRUE.equals(autoApproveOnTimeout)) {
                    APPROVAL_APPROVER.put(id, "system-timeout");
                    return "approved";
                }
                APPROVAL_APPROVER.put(id, "system-timeout");
                return "timeout";
            }

            try {
                Thread.sleep(checkMs);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new StepException("Approval wait interrupted", e, StepFailureReason.Interrupted);
            }
        }
    }

    private void sendApprovalEmail(String smtpPassword, String toEmail, Map<String, Object> approvalData, boolean escalation, ExecutionListener logger) throws Exception {
        Properties props = new Properties();
        props.put("mail.smtp.host", smtpServer);
        props.put("mail.smtp.port", String.valueOf(valueOrDefault(smtpPort, 587)));
        props.put("mail.smtp.auth", "true");
        if (Boolean.TRUE.equals(useTls)) {
            props.put("mail.smtp.starttls.enable", "true");
        }

        Session session = Session.getInstance(props, new javax.mail.Authenticator() {
            @Override
            protected PasswordAuthentication getPasswordAuthentication() {
                return new PasswordAuthentication(smtpUsername, smtpPassword);
            }
        });

        Message message = new MimeMessage(session);
        message.setFrom(new InternetAddress(fromEmailAddress));
        message.setRecipients(Message.RecipientType.TO, InternetAddress.parse(toEmail));
        String subjectPrefix = escalation ? "[ESCALATED] " : "";
        message.setSubject(subjectPrefix + "Approval Required: " + approvalData.get("jobName"));

        String htmlBody = buildEmailBody(approvalData, escalation);
        String textBody = buildPlainTextBody(approvalData, escalation);
        MimeBodyPart textPart = new MimeBodyPart();
        textPart.setText(textBody, StandardCharsets.UTF_8.name());
        MimeBodyPart htmlPart = new MimeBodyPart();
        htmlPart.setContent(htmlBody, "text/html; charset=UTF-8");
        MimeMultipart mp = new MimeMultipart("alternative");
        mp.addBodyPart(textPart);
        mp.addBodyPart(htmlPart);
        message.setContent(mp);

        Transport.send(message);
        logger.log(3, "Email sent successfully");
    }

    private String buildEmailBody(Map<String, Object> approvalData, boolean escalation) {
        String id = String.valueOf(approvalData.get("id"));
        String token = String.valueOf(approvalData.get("token"));
        String approveUrl = approvalUrlBase + "/approve?id=" + id + "&token=" + token;
        String denyUrl = approvalUrlBase + "/deny?id=" + id + "&token=" + token;
        String title = escalation ? "Escalated Approval Required" : "Approval Required";
        String subtitle = escalation
            ? "No response yet. This request has been escalated."
            : "A workflow is waiting for your approval.";
        String timeout = String.valueOf(valueOrDefault(this.approvalTimeoutMinutes, 60));

        return "<!doctype html><html><head><meta charset=\"UTF-8\"/>"
            + "<meta name=\"viewport\" content=\"width=device-width, initial-scale=1\"/>"
            + "</head><body style=\"margin:0;background:#f5f7fa;font-family:-apple-system,BlinkMacSystemFont,'Segoe UI',Roboto,Arial,sans-serif;color:#1f2937;\">"
            + "<table role=\"presentation\" width=\"100%\" cellspacing=\"0\" cellpadding=\"0\" style=\"background:#f5f7fa;padding:24px 12px;\">"
            + "<tr><td align=\"center\">"
            + "<table role=\"presentation\" width=\"640\" cellspacing=\"0\" cellpadding=\"0\" style=\"max-width:640px;background:#ffffff;border:1px solid #e5e7eb;border-radius:12px;overflow:hidden;\">"
            + "<tr><td style=\"background:#06ac38;padding:18px 24px;color:#ffffff;\">"
            + "<div style=\"font-size:22px;font-weight:700;letter-spacing:.2px;\">Rundeck Approval</div>"
            + "<div style=\"opacity:.95;font-size:14px;margin-top:4px;\">" + escapeHtml(title) + "</div>"
            + "</td></tr>"
            + "<tr><td style=\"padding:24px;\">"
            + "<div style=\"font-size:18px;font-weight:700;color:#111827;margin-bottom:8px;\">" + escapeHtml(subtitle) + "</div>"
            + "<div style=\"font-size:14px;color:#4b5563;margin-bottom:20px;\">This request expires in <b>" + escapeHtml(timeout) + " minutes</b>.</div>"
            + "<table role=\"presentation\" width=\"100%\" cellspacing=\"0\" cellpadding=\"0\" style=\"border:1px solid #e5e7eb;border-radius:10px;background:#ffffff;\">"
            + "<tr><td style=\"padding:16px 18px;font-size:13px;color:#6b7280;text-transform:uppercase;letter-spacing:.08em;border-bottom:1px solid #eef2f7;\">Request Details</td></tr>"
            + "<tr><td style=\"padding:16px 18px;\">"
            + "<table role=\"presentation\" width=\"100%\" cellspacing=\"0\" cellpadding=\"0\" style=\"font-size:15px;line-height:1.6;\">"
            + row("Job", String.valueOf(approvalData.get("jobName")))
            + row("Project", String.valueOf(approvalData.get("projectName")))
            + row("Execution ID", String.valueOf(approvalData.get("executionId")))
            + row("Requested", String.valueOf(approvalData.get("requestTime")))
            + row("Approval ID", id)
            + row("Message", String.valueOf(approvalData.get("message")))
            + "</table></td></tr></table>"
            + "<table role=\"presentation\" width=\"100%\" cellspacing=\"0\" cellpadding=\"0\" style=\"margin-top:22px;\"><tr>"
            + "<td style=\"padding-right:8px;\" width=\"50%\">"
            + "<a href=\"" + escapeHtml(approveUrl) + "\" style=\"display:block;text-align:center;background:#06ac38;color:#ffffff;text-decoration:none;font-weight:700;padding:12px 14px;border-radius:8px;\">Approve</a>"
            + "</td>"
            + "<td style=\"padding-left:8px;\" width=\"50%\">"
            + "<a href=\"" + escapeHtml(denyUrl) + "\" style=\"display:block;text-align:center;background:#ffffff;color:#111827;text-decoration:none;font-weight:700;padding:11px 14px;border-radius:8px;border:1px solid #d1d5db;\">Deny</a>"
            + "</td>"
            + "</tr></table>"
            + "<div style=\"font-size:12px;color:#6b7280;margin-top:16px;\">If the buttons do not work, copy and paste the links from the plain-text part of this email.</div>"
            + "</td></tr></table></td></tr></table></body></html>";
    }

    private String buildPlainTextBody(Map<String, Object> approvalData, boolean escalation) {
        StringBuilder body = new StringBuilder();
        if (escalation) {
            body.append("*** ESCALATED APPROVAL REQUEST ***\n\n");
        }
        String id = String.valueOf(approvalData.get("id"));
        String token = String.valueOf(approvalData.get("token"));
        body.append("Job Approval Required\n");
        body.append("====================\n\n");
        body.append("Job Name: ").append(approvalData.get("jobName")).append("\n");
        body.append("Project: ").append(approvalData.get("projectName")).append("\n");
        body.append("Execution ID: ").append(approvalData.get("executionId")).append("\n");
        body.append("Request Time: ").append(approvalData.get("requestTime")).append("\n\n");
        body.append("Approval Message:\n").append(approvalData.get("message")).append("\n\n");
        body.append("Approve: ").append(approvalUrlBase).append("/approve?id=").append(id).append("&token=").append(token).append("\n");
        body.append("Deny: ").append(approvalUrlBase).append("/deny?id=").append(id).append("&token=").append(token).append("\n\n");
        body.append("Approval ID: ").append(id).append("\n");
        return body.toString();
    }

    private static String row(String label, String value) {
        return "<tr>"
            + "<td style=\"width:170px;color:#6b7280;vertical-align:top;padding:3px 0;\">" + escapeHtml(label) + "</td>"
            + "<td style=\"color:#111827;padding:3px 0;\">" + escapeHtml(value) + "</td>"
            + "</tr>";
    }

    private static String escapeHtml(String input) {
        if (input == null) return "";
        return input
            .replace("&", "&amp;")
            .replace("<", "&lt;")
            .replace(">", "&gt;")
            .replace("\"", "&quot;")
            .replace("'", "&#39;");
    }

    private static void startCallbackServerIfNeeded(ExecutionListener logger) throws StepException {
        if (!CALLBACK_SERVER_STARTED.compareAndSet(false, true)) {
            return;
        }
        try {
            HttpServer server = HttpServer.create(new InetSocketAddress("0.0.0.0", CALLBACK_PORT), 0);

            server.createContext("/approve", ex -> handleCallback(ex, "approved"));
            server.createContext("/deny", ex -> handleCallback(ex, "denied"));
            server.setExecutor(null);
            server.start();
            logger.log(2, "Approval callback server started on port " + CALLBACK_PORT);
        } catch (IOException e) {
            CALLBACK_SERVER_STARTED.set(false);
            throw new StepException("Failed to start callback server on port " + CALLBACK_PORT, e, StepFailureReason.IOFailure);
        }
    }

    private static void handleCallback(HttpExchange ex, String result) throws IOException {
        Map<String, String> q = parseQuery(ex.getRequestURI().getRawQuery());
        String id = q.get("id");
        String token = q.get("token");
        String approver = q.getOrDefault("approver", "link-user");

        int code;
        String body;
        if (isBlank(id) || isBlank(token) || !token.equals(APPROVAL_TOKENS.get(id))) {
            code = 403;
            body = "Invalid approval token";
        } else {
            APPROVAL_RESULTS.put(id, result);
            APPROVAL_APPROVER.put(id, approver);
            code = 200;
            body = "Approval recorded: " + result;
        }

        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        ex.getResponseHeaders().set("Content-Type", "text/plain; charset=utf-8");
        ex.sendResponseHeaders(code, bytes.length);
        ex.getResponseBody().write(bytes);
        ex.close();
    }

    private static Map<String, String> parseQuery(String query) {
        Map<String, String> out = new ConcurrentHashMap<>();
        if (query == null || query.isEmpty()) return out;
        String[] pairs = query.split("&");
        for (String p : pairs) {
            String[] kv = p.split("=", 2);
            String k = URLDecoder.decode(kv[0], StandardCharsets.UTF_8);
            String v = kv.length > 1 ? URLDecoder.decode(kv[1], StandardCharsets.UTF_8) : "";
            out.put(k, v);
        }
        return out;
    }

    private static String getPasswordFromKeyStorage(PluginStepContext context, String path) throws Exception {
        Resource<ResourceMeta> resource = context.getExecutionContext().getStorageTree().getResource(path);
        if (resource == null || resource.getContents() == null) {
            throw new IOException("Key not found: " + path);
        }
        try (ByteArrayOutputStream baos = new ByteArrayOutputStream()) {
            resource.getContents().writeContent(baos);
            return new String(baos.toByteArray(), StandardCharsets.UTF_8);
        }
    }

    private static boolean isValidEmail(String email) {
        return email != null && email.contains("@") && email.contains(".");
    }

    private static boolean isBlank(String s) {
        return s == null || s.trim().isEmpty();
    }

    private static int valueOrDefault(Integer val, int dflt) {
        return val == null ? dflt : val;
    }

    private static Integer toInt(Object val, int dflt) {
        if (val == null) return dflt;
        if (val instanceof Number) return ((Number) val).intValue();
        return Integer.parseInt(String.valueOf(val).trim());
    }

    private static Boolean toBool(Object val, boolean dflt) {
        if (val == null) return dflt;
        if (val instanceof Boolean) return (Boolean) val;
        return "true".equalsIgnoreCase(String.valueOf(val).trim());
    }

    private static UserSelectData loadUserSelectData() {
        long now = System.currentTimeMillis();
        UserSelectData cached = USER_SELECT_CACHE;
        if (cached != null && (now - USER_SELECT_CACHE_TS) < USER_CACHE_TTL_MS) {
            return cached;
        }
        synchronized (USER_CACHE_LOCK) {
            cached = USER_SELECT_CACHE;
            if (cached != null && (now - USER_SELECT_CACHE_TS) < USER_CACHE_TTL_MS) {
                return cached;
            }
            UserSelectData loaded = fetchUserSelectData();
            USER_SELECT_CACHE = loaded;
            USER_SELECT_CACHE_TS = now;
            return loaded;
        }
    }

    private static UserSelectData fetchUserSelectData() {
        String dbUrl = System.getenv("RUNDECK_DATABASE_URL");
        String dbUser = System.getenv("RUNDECK_DATABASE_USERNAME");
        String dbPass = System.getenv("RUNDECK_DATABASE_PASSWORD");
        String dbDriver = System.getenv("RUNDECK_DATABASE_DRIVER");

        if (isBlank(dbUrl) || isBlank(dbUser) || dbPass == null) {
            System.err.println("ApprovalJobStep: DB env not set for user dropdowns");
            LOG.warn("ApprovalJobStep: DB env not set for user dropdowns (url/user/pass missing)");
            DbConfig fallback = readDbConfigFromFile();
            if (fallback == null) {
                return UserSelectData.empty();
            }
            dbUrl = fallback.url;
            dbUser = fallback.user;
            dbPass = fallback.pass;
            dbDriver = fallback.driver;
        }

        if (!isBlank(dbDriver)) {
            try {
                Class.forName(dbDriver);
            } catch (ClassNotFoundException ignored) {
                System.err.println("ApprovalJobStep: DB driver not found: " + dbDriver);
                LOG.warn("ApprovalJobStep: DB driver not found: {}", dbDriver);
            }
        }

        LinkedHashMap<String, String> labels = new LinkedHashMap<>();
        List<String> values = new ArrayList<>();

        String sql = "select login, email, first_name, last_name from rduser where email is not null and email <> '' order by last_name, first_name, login";
        try (Connection conn = DriverManager.getConnection(dbUrl, dbUser, dbPass);
             PreparedStatement ps = conn.prepareStatement(sql);
             ResultSet rs = ps.executeQuery()) {
            while (rs.next()) {
                String email = trimOrNull(rs.getString("email"));
                if (isBlank(email)) continue;
                String login = trimOrNull(rs.getString("login"));
                String first = trimOrNull(rs.getString("first_name"));
                String last = trimOrNull(rs.getString("last_name"));
                String displayName = buildDisplayName(login, first, last, email);
                if (!labels.containsKey(email)) {
                    labels.put(email, displayName);
                    values.add(email);
                }
            }
        } catch (SQLException e) {
            System.err.println("ApprovalJobStep: failed to load users: " + e.getMessage());
            LOG.warn("ApprovalJobStep: failed to load users for dropdowns: {}", e.getMessage());
            return UserSelectData.empty();
        }

        System.err.println("ApprovalJobStep: loaded " + values.size() + " users for dropdowns");
        LOG.debug("ApprovalJobStep: loaded {} user emails for dropdowns", values.size());
        return new UserSelectData(values, labels);
    }

    private static DbConfig readDbConfigFromFile() {
        String base = System.getProperty("rdeck.base");
        if (isBlank(base)) {
            base = "/home/rundeck";
        }
        String path = base + "/server/config/rundeck-config.properties";
        Properties props = new Properties();
        try (InputStream in = new FileInputStream(path)) {
            props.load(in);
        } catch (IOException e) {
            System.err.println("ApprovalJobStep: failed to read " + path + ": " + e.getMessage());
            LOG.warn("ApprovalJobStep: failed to read {}: {}", path, e.getMessage());
            return null;
        }

        String url = trimOrNull(props.getProperty("dataSource.url"));
        String user = trimOrNull(props.getProperty("dataSource.username"));
        String pass = props.getProperty("dataSource.password");
        String driver = trimOrNull(props.getProperty("dataSource.driverClassName"));
        if (isBlank(url) || isBlank(user) || pass == null) {
            System.err.println("ApprovalJobStep: dataSource.* missing in " + path);
            LOG.warn("ApprovalJobStep: dataSource.* missing in {}", path);
            return null;
        }
        System.err.println("ApprovalJobStep: using DB config from " + path);
        LOG.info("ApprovalJobStep: using DB config from {}", path);
        return new DbConfig(url, user, pass, driver);
    }

    private static String trimOrNull(String value) {
        if (value == null) return null;
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    private static String buildDisplayName(String login, String first, String last, String email) {
        String name;
        if (!isBlank(first) || !isBlank(last)) {
            name = String.format("%s %s", Objects.toString(first, ""), Objects.toString(last, "")).trim();
        } else if (!isBlank(login)) {
            name = login;
        } else {
            name = email;
        }
        return name + " <" + email + ">";
    }

    private static final class UserSelectData {
        private final List<String> values;
        private final Map<String, String> labels;

        private UserSelectData(List<String> values, Map<String, String> labels) {
            this.values = values;
            this.labels = labels;
        }

        private static UserSelectData empty() {
            return new UserSelectData(List.of(), Map.of());
        }
    }

    private static final class DbConfig {
        private final String url;
        private final String user;
        private final String pass;
        private final String driver;

        private DbConfig(String url, String user, String pass, String driver) {
            this.url = url;
            this.user = user;
            this.pass = pass;
            this.driver = driver;
        }
    }
}
