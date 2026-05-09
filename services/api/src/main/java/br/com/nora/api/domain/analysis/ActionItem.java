package br.com.nora.api.domain.analysis;

import java.time.LocalDate;

/** Tarefa derivada da reuniao. Status inicial e sempre OPEN. */
public record ActionItem(
        String title,
        String assignee,
        LocalDate dueDate,
        Priority priority,
        String sourceQuote,
        ActionItemStatus status) {

    public ActionItem {
        if (title == null || title.isBlank()) {
            throw new IllegalArgumentException("action item title is required");
        }
        if (priority == null) {
            throw new IllegalArgumentException("priority is required");
        }
        if (sourceQuote == null || sourceQuote.isBlank()) {
            throw new IllegalArgumentException("sourceQuote is required");
        }
        if (status == null) {
            throw new IllegalArgumentException("status is required");
        }
    }

    /** Construtor de conveniencia para itens recem-extraidos pela analise (status OPEN). */
    public static ActionItem fresh(
            String title,
            String assignee,
            LocalDate dueDate,
            Priority priority,
            String sourceQuote) {
        return new ActionItem(
                title, assignee, dueDate, priority, sourceQuote, ActionItemStatus.OPEN);
    }
}
