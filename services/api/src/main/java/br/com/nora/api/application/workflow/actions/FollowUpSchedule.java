package br.com.nora.api.application.workflow.actions;

import br.com.nora.api.application.workflow.WorkflowEventContext;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Comparator;
import java.util.Map;
import java.util.Optional;

/**
 * Agenda do follow-up das ações de calendário (Google e Outlook) — compartilhada para as duas
 * baterem no mesmo comportamento.
 *
 * <p>Regra (pedido do PO, 2026-06-12): o evento deve nascer dos DADOS DA REUNIÃO quando possível.
 * Sem {@code startInDays} explícito no nó, usamos o prazo mais próximo entre os action items
 * extraídos pela análise (estritamente depois de hoje — prazo de hoje às 10h pode já ter passado);
 * sem prazo utilizável, cai no padrão "amanhã". {@code startInDays} preenchido pelo usuário SEMPRE
 * vence — configuração explícita não é adivinhada.
 */
public final class FollowUpSchedule {

    private static final DateTimeFormatter DATA_BR = DateTimeFormatter.ofPattern("dd/MM");

    private FollowUpSchedule() {}

    /**
     * Janela resolvida do evento. {@code origem} explica de onde a data veio quando não foi o
     * default (vai pro log da execução e pro retorno da ação) — null no caminho padrão/manual.
     */
    public record Resolved(OffsetDateTime start, OffsetDateTime end, String origem) {}

    /** {@code now} deve vir no fuso do calendário (America/Sao_Paulo nas ações atuais). */
    public static Resolved resolve(
            WorkflowEventContext ctx, Map<String, Object> params, OffsetDateTime now) {
        int hour = clamp(intParam(params, "hour", 10), 0, 23);
        int durationMinutes = Math.max(intParam(params, "durationMinutes", 30), 5);

        Integer explicitStart = intParamOrNull(params, "startInDays");
        OffsetDateTime start;
        String origem = null;
        if (explicitStart != null) {
            start = atHour(now.plusDays(explicitStart), hour);
        } else {
            Optional<WorkflowEventContext.ActionItemView> comPrazo =
                    earliestFutureDueDate(ctx, now.toLocalDate());
            if (comPrazo.isPresent()) {
                WorkflowEventContext.ActionItemView item = comPrazo.get();
                start = OffsetDateTime.of(item.dueDate(), LocalTime.of(hour, 0), now.getOffset());
                origem =
                        "prazo de \""
                                + item.title()
                                + "\" ("
                                + DATA_BR.format(item.dueDate())
                                + ")";
            } else {
                start = atHour(now.plusDays(1), hour);
            }
        }
        return new Resolved(start, start.plusMinutes(durationMinutes), origem);
    }

    private static Optional<WorkflowEventContext.ActionItemView> earliestFutureDueDate(
            WorkflowEventContext ctx, LocalDate today) {
        return ctx.actionItems().stream()
                .filter(i -> i.dueDate() != null && i.dueDate().isAfter(today))
                .min(Comparator.comparing(WorkflowEventContext.ActionItemView::dueDate));
    }

    private static OffsetDateTime atHour(OffsetDateTime base, int hour) {
        return base.withHour(hour).withMinute(0).withSecond(0).withNano(0);
    }

    private static int clamp(int v, int min, int max) {
        return Math.min(Math.max(v, min), max);
    }

    private static int intParam(Map<String, Object> params, String key, int defaultValue) {
        Integer v = intParamOrNull(params, key);
        return v == null ? defaultValue : v;
    }

    private static Integer intParamOrNull(Map<String, Object> params, String key) {
        Object raw = params.get(key);
        if (raw instanceof Number n) {
            return n.intValue();
        }
        if (raw instanceof String s && !s.isBlank()) {
            try {
                return Integer.parseInt(s.trim());
            } catch (NumberFormatException ignored) {
                // sem valor utilizável
            }
        }
        return null;
    }
}
