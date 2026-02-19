/*
 * Copyright 2026 Rundeck, Inc. (http://rundeck.com)
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.rundeck.plugin.llm;

import com.dtolabs.rundeck.core.dispatcher.ContextView;
import com.dtolabs.rundeck.core.execution.workflow.steps.FailureReason;
import com.dtolabs.rundeck.core.execution.workflow.steps.StepException;
import com.dtolabs.rundeck.core.plugins.Plugin;
import com.dtolabs.rundeck.core.plugins.configuration.StringRenderingConstants;
import com.dtolabs.rundeck.plugins.ExecutionEnvironmentConstants;
import com.dtolabs.rundeck.plugins.ServiceNameConstants;
import com.dtolabs.rundeck.plugins.descriptions.PluginDescription;
import com.dtolabs.rundeck.plugins.descriptions.PluginMetadata;
import com.dtolabs.rundeck.plugins.descriptions.PluginProperty;
import com.dtolabs.rundeck.plugins.descriptions.RenderingOption;
import com.dtolabs.rundeck.plugins.descriptions.RenderingOptions;
import com.dtolabs.rundeck.plugins.step.PluginStepContext;
import com.dtolabs.rundeck.plugins.step.StepPlugin;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.Map;

import static com.dtolabs.rundeck.core.plugins.configuration.StringRenderingConstants.CODE_SYNTAX_MODE;
import static com.dtolabs.rundeck.core.plugins.configuration.StringRenderingConstants.DISPLAY_TYPE_KEY;

@Plugin(name = LlmPromptWorkflowStep.PROVIDER_NAME, service = ServiceNameConstants.WorkflowStep)
@PluginDescription(
        title = "LLM Prompt",
        description = "Send prompt data to an OpenAI-compatible LLM endpoint and export response data."
)
@PluginMetadata(key = "faicon", value = "robot")
@PluginMetadata(
        key = ExecutionEnvironmentConstants.ENVIRONMENT_TYPE_KEY,
        value = ExecutionEnvironmentConstants.LOCAL_RUNNER
)
public class LlmPromptWorkflowStep implements StepPlugin {
    public static final String PROVIDER_NAME = "llm-prompt-step";
    private static final ObjectMapper MAPPER = new ObjectMapper();

    @PluginProperty(
            title = "Endpoint URL",
            description = "POST endpoint for chat completions style requests.",
            required = true,
            defaultValue = "https://api.openai.com/v1/chat/completions"
    )
    String endpoint;

    @PluginProperty(
            title = "API Token",
            description = "Optional bearer token for the Authorization header."
    )
    @RenderingOption(key = StringRenderingConstants.DISPLAY_TYPE_KEY, value = "PASSWORD")
    String apiToken;

    @PluginProperty(title = "Model", required = true, defaultValue = "gpt-4o-mini")
    String model;

    @PluginProperty(
            title = "System Prompt",
            description = "Optional system instruction sent to the model."
    )
    @RenderingOptions(
            {
                    @RenderingOption(key = DISPLAY_TYPE_KEY, value = "CODE"),
                    @RenderingOption(key = CODE_SYNTAX_MODE, value = "markdown")
            }
    )
    String systemPrompt;

    @PluginProperty(
            title = "User Prompt",
            description = "Prompt text (supports Rundeck variable expansion).",
            required = true
    )
    @RenderingOptions(
            {
                    @RenderingOption(key = DISPLAY_TYPE_KEY, value = "CODE"),
                    @RenderingOption(key = CODE_SYNTAX_MODE, value = "markdown")
            }
    )
    String userPrompt;

    @PluginProperty(
            title = "Temperature",
            description = "Sampling temperature.",
            defaultValue = "0.2"
    )
    Double temperature;

    @PluginProperty(
            title = "Timeout (seconds)",
            description = "Connection and read timeout in seconds.",
            defaultValue = "60"
    )
    Integer timeoutSeconds;

    @PluginProperty(
            title = "Output Group",
            description = "Data context group to store output values.",
            defaultValue = "export",
            required = true
    )
    String outputGroup;

    @PluginProperty(
            title = "Output Variable Name",
            description = "Variable name for extracted text response.",
            defaultValue = "llm_response",
            required = true
    )
    String outputName;

    @PluginProperty(
            title = "Raw Response Variable Name",
            description = "Optional variable name for the raw JSON response body."
    )
    String rawOutputName;

    @PluginProperty(
            title = "Fail on HTTP Error",
            description = "Fail this step if endpoint returns non-2xx status.",
            defaultValue = "true"
    )
    boolean failOnHttpError = true;

    enum LlmStepFailureReason implements FailureReason {
        InvalidConfiguration,
        RequestFailed,
        InvalidResponse
    }

    @Override
    public void executeStep(final PluginStepContext context, final Map<String, Object> configuration)
            throws StepException
    {
        validateConfiguration();

        final String requestBody = createRequestBody();
        final HttpResponse httpResponse = invokeEndpoint(requestBody);
        final String responseText = extractContent(httpResponse.body);

        if (!hasText(responseText)) {
            throw new StepException(
                    "LLM response did not contain text content",
                    LlmStepFailureReason.InvalidResponse
            );
        }

        context.getOutputContext().addOutput(ContextView.global(), outputGroup, outputName, responseText);
        context.getOutputContext().addOutput(
                ContextView.global(),
                outputGroup,
                outputName + "_status_code",
                String.valueOf(httpResponse.statusCode)
        );
        if (hasText(rawOutputName)) {
            context.getOutputContext().addOutput(ContextView.global(), outputGroup, rawOutputName, httpResponse.body);
        }

        context.getLogger().log(2, "LLM response exported as ${" + outputGroup + "." + outputName + "*}");
    }

    private void validateConfiguration() throws StepException {
        if (!hasText(endpoint) || !hasText(model) || !hasText(userPrompt) || !hasText(outputGroup) || !hasText(outputName)) {
            throw new StepException(
                    "Missing required configuration for endpoint/model/prompt/output settings",
                    LlmStepFailureReason.InvalidConfiguration
            );
        }
        if (timeoutSeconds == null || timeoutSeconds <= 0) {
            throw new StepException(
                    "Timeout (seconds) must be greater than zero",
                    LlmStepFailureReason.InvalidConfiguration
            );
        }
    }

    private String createRequestBody() throws StepException {
        try {
            final ObjectNode payload = MAPPER.createObjectNode();
            payload.put("model", model);
            if (temperature != null) {
                payload.put("temperature", temperature);
            }
            final ArrayNode messages = payload.putArray("messages");
            if (hasText(systemPrompt)) {
                final ObjectNode system = messages.addObject();
                system.put("role", "system");
                system.put("content", systemPrompt);
            }
            final ObjectNode user = messages.addObject();
            user.put("role", "user");
            user.put("content", userPrompt);
            return MAPPER.writeValueAsString(payload);
        } catch (IOException e) {
            throw new StepException("Could not build request payload", e, LlmStepFailureReason.InvalidConfiguration);
        }
    }

    private HttpResponse invokeEndpoint(final String requestBody) throws StepException {
        HttpURLConnection connection = null;
        try {
            final URL url = new URL(endpoint);
            connection = (HttpURLConnection) url.openConnection();
            connection.setRequestMethod("POST");
            connection.setConnectTimeout(timeoutSeconds * 1000);
            connection.setReadTimeout(timeoutSeconds * 1000);
            connection.setDoOutput(true);
            connection.setRequestProperty("Content-Type", "application/json");
            connection.setRequestProperty("Accept", "application/json");
            if (hasText(apiToken)) {
                connection.setRequestProperty("Authorization", "Bearer " + apiToken);
            }

            try (OutputStream outputStream = connection.getOutputStream()) {
                outputStream.write(requestBody.getBytes(StandardCharsets.UTF_8));
            }

            final int statusCode = connection.getResponseCode();
            final String responseBody = readResponseBody(connection, statusCode);

            if (statusCode >= 400 && failOnHttpError) {
                throw new StepException(
                        "LLM endpoint returned HTTP " + statusCode,
                        LlmStepFailureReason.RequestFailed
                );
            }
            return new HttpResponse(statusCode, responseBody);
        } catch (IOException e) {
            throw new StepException("LLM request failed: " + e.getMessage(), e, LlmStepFailureReason.RequestFailed);
        } finally {
            if (connection != null) {
                connection.disconnect();
            }
        }
    }

    private String readResponseBody(final HttpURLConnection connection, final int statusCode) throws IOException {
        final InputStream stream = statusCode >= 400 ? connection.getErrorStream() : connection.getInputStream();
        if (stream == null) {
            return "";
        }
        try (InputStream inputStream = stream; ByteArrayOutputStream outputStream = new ByteArrayOutputStream()) {
            final byte[] buffer = new byte[2048];
            int read;
            while ((read = inputStream.read(buffer)) != -1) {
                outputStream.write(buffer, 0, read);
            }
            return outputStream.toString(StandardCharsets.UTF_8.name());
        }
    }

    private String extractContent(final String responseBody) {
        if (!hasText(responseBody)) {
            return responseBody;
        }
        try {
            final JsonNode root = MAPPER.readTree(responseBody);

            final JsonNode outputText = root.path("output_text");
            if (outputText.isTextual()) {
                return outputText.asText();
            }

            final JsonNode content = root.path("choices").path(0).path("message").path("content");
            if (content.isTextual()) {
                return content.asText();
            }
            if (content.isArray()) {
                final StringBuilder sb = new StringBuilder();
                for (JsonNode node : content) {
                    final JsonNode text = node.path("text");
                    if (text.isTextual()) {
                        if (sb.length() > 0) {
                            sb.append("\n");
                        }
                        sb.append(text.asText());
                    }
                }
                if (sb.length() > 0) {
                    return sb.toString();
                }
            }

            return responseBody;
        } catch (IOException e) {
            return responseBody;
        }
    }

    private boolean hasText(final String value) {
        return value != null && !value.trim().isEmpty();
    }

    private static class HttpResponse {
        private final int statusCode;
        private final String body;

        private HttpResponse(final int statusCode, final String body) {
            this.statusCode = statusCode;
            this.body = body;
        }
    }
}
