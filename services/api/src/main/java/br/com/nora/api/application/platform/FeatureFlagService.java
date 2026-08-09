package br.com.nora.api.application.platform;

import br.com.nora.api.domain.platform.FeatureFlag;
import br.com.nora.api.infrastructure.platform.PlatformAvailability;
import java.util.List;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.dao.DataAccessException;
import org.springframework.stereotype.Service;

/**
 * Feature flag reads for the operator console (GET /admin/platform/flags, ADR 0024). The
 * feature_flags table already exists and feeds the resolver; this service exposes the read surface
 * that nora-admin consumes. Requires the platform database to be usable (503 when degraded/off;
 * translates a runtime outage per-call).
 */
@Service
public class FeatureFlagService {

    private final ObjectProvider<FeatureFlagRepository> flagProvider;
    private final PlatformAvailability availability;

    public FeatureFlagService(
            ObjectProvider<FeatureFlagRepository> flagProvider, PlatformAvailability availability) {
        this.flagProvider = flagProvider;
        this.availability = availability;
    }

    public List<FeatureFlag> listFlags() {
        if (!availability.isUsable()) {
            throw new PlatformUnavailableException(
                    "control plane indisponível (banco de plataforma)");
        }
        try {
            return flagProvider.getObject().findAll();
        } catch (DataAccessException ex) {
            throw new PlatformUnavailableException("banco de plataforma indisponível em runtime");
        }
    }
}
