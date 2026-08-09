package br.com.nora.api.application.platform;

import br.com.nora.api.domain.platform.BusinessSnapshot;
import br.com.nora.api.infrastructure.platform.PlatformProperties;
import java.time.OffsetDateTime;
import org.springframework.stereotype.Service;

/**
 * Business telemetry (track (c), CUTTABLE, ADR 0024). Aggregates cross-tenant from the primary
 * database (operator-only). Can be switched off via {@code nora.platform.business.enabled=false}.
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
