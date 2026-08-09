package br.com.nora.api.application.workflow;

import java.util.Map;

/**
 * Port for NORA Flows' MCP-style actions. Each adapter executes ONE action type (e.g. {@code
 * send_email} via Resend; in the future {@code gmail_send}, {@code calendar_create_event}, {@code
 * slack_post} via the user's OAuth).
 *
 * <p>Contract: success returns a short PT-BR message for the execution log ("E-mail enviado para
 * x@y.z"). Failure MUST propagate an exception — the {@link WorkflowEngine} catches it, records it
 * in the log and marks the execution as FAILED. Never fake success.
 */
public interface ActionExecutor {

    /** Identifier of the action type in the definition_json (e.g. {@code send_email}). */
    String type();

    String execute(WorkflowEventContext context, Map<String, Object> params);
}
