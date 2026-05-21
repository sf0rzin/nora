package br.com.nora.api.api.dto.meeting;

import java.util.List;

/**
 * Customer Confidence persistido (ADR 0015), retornado no detalhe da reuniao. Presente apenas para
 * reunioes externas (conversa com cliente/lead); null para reunioes internas.
 *
 * <p>{@code trend} e o valor autoritativo calculado server-side (null na primeira reuniao da
 * conta). {@code accountName} e o nome da conta resolvida via get-or-create.
 */
public record CustomerConfidenceResponse(
        int score,
        String band,
        String trend,
        String accountName,
        String rationale,
        List<BuyingSignalResponse> buyingSignals,
        List<ObjectionResponse> objections) {

    /** Sinal de compra detectado (ADR 0015). */
    public record BuyingSignalResponse(String type, String quote, Double weight) {}

    /** Objecao levantada pelo cliente (ADR 0015). */
    public record ObjectionResponse(
            String type, String quote, String severity, String competitor) {}
}
