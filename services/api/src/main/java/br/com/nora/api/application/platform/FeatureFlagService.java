package br.com.nora.api.application.platform;

import br.com.nora.api.domain.platform.FeatureFlag;
import br.com.nora.api.infrastructure.platform.PlatformAvailability;
import java.util.List;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.dao.DataAccessException;
import org.springframework.stereotype.Service;

/**
 * Leitura de feature flags pro console do operador (GET /admin/platform/flags, ADR 0024). A tabela
 * feature_flags já existe e alimenta o resolver; este service expõe a superfície de leitura que o
 * nora-admin consome. Exige o banco de plataforma usável (503 quando degradado/off; traduz queda de
 * runtime per-call).
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
