package br.com.nora.api.application.platform;

import br.com.nora.api.domain.platform.BusinessSnapshot;
import br.com.nora.api.infrastructure.platform.PlatformProperties;
import java.time.OffsetDateTime;
import org.springframework.stereotype.Service;

/**
 * Telemetria de negócio (frente (c), CORTÁVEL, ADR 0024). Agrega cross-tenant do banco primário
 * (operador-only). Desligável via {@code nora.platform.business.enabled=false}.
 */
@Service
public class BusinessTelemetryService {

    private final BusinessMetricsSource source;
    private final PlatformProperties props;

    public BusinessTelemetryService(BusinessMetricsSource source, PlatformProperties props) {
        this.source = source;
        this.props = props;
    }

    public BusinessSnapshot business(OffsetDateTime from, OffsetDateTime to) {
        if (!props.getBusiness().isEnabled()) {
            return BusinessSnapshot.disabled(from, to);
        }
        return source.fetch(from, to);
    }
}
