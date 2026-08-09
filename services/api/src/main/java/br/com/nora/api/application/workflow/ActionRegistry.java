package br.com.nora.api.application.workflow;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.springframework.stereotype.Component;

/**
 * Registry of the actions available in NORA Flows. Spring injects all {@link ActionExecutor} beans;
 * each one's type becomes the key used in the definition_json and in the save validation.
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
