package br.com.nora.api.application.workflow;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.springframework.stereotype.Component;

/**
 * Registry das ações disponíveis no NORA Flows. Spring injeta todos os beans {@link
 * ActionExecutor}; o tipo de cada um vira a chave usada no definition_json e na validação do save.
 */
@Component
public class ActionRegistry {

    private final Map<String, ActionExecutor> byType;

    public ActionRegistry(List<ActionExecutor> executors) {
        Map<String, ActionExecutor> map = new LinkedHashMap<>();
        for (ActionExecutor executor : executors) {
            ActionExecutor previous = map.put(executor.type(), executor);
            if (previous != null) {
                throw new IllegalStateException(
                        "duas ações registradas com o mesmo type: " + executor.type());
            }
        }
        this.byType = Map.copyOf(map);
    }

    public Optional<ActionExecutor> byType(String type) {
        return Optional.ofNullable(byType.get(type));
    }

    public Set<String> types() {
        return byType.keySet();
    }
}
