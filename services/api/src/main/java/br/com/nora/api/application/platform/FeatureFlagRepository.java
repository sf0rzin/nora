package br.com.nora.api.application.platform;

import br.com.nora.api.domain.platform.FeatureFlag;
import java.util.List;
import java.util.Optional;

/** Porta de leitura de feature flags (tabela feature_flags, ADR 0024). */
public interface FeatureFlagRepository {

    List<FeatureFlag> findAll();

    Optional<FeatureFlag> findByKey(String key);
}
