package br.com.nora.api.domain.workflow;

/** State of a workflow execution. RUNNING is transient; SUCCESS/FAILED are terminal. */
public enum WorkflowExecutionStatus {
    RUNNING,
    SUCCESS,
    FAILED
}
