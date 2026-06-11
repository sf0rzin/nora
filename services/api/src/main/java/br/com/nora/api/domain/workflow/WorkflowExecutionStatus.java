package br.com.nora.api.domain.workflow;

/** Estado de uma execução de workflow. RUNNING é transitório; SUCCESS/FAILED são terminais. */
public enum WorkflowExecutionStatus {
    RUNNING,
    SUCCESS,
    FAILED
}
