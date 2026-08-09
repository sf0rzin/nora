package br.com.nora.api.application.platform;

import br.com.nora.api.domain.platform.FeatureFlag;
import java.util.List;
import java.util.Optional;

/** Read port for feature flags (feature_flags table, ADR 0024). */
public interface FeatureFlagRepository {

    List<FeatureFlag> findAll();

    Optional<FeatureFlag> findByKey(String key);
}
