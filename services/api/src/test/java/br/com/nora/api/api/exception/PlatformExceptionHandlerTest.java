package br.com.nora.api.api.exception;

import static org.assertj.core.api.Assertions.assertThat;

import br.com.nora.api.api.dto.ErrorResponse;
import br.com.nora.api.application.platform.PlatformConflictException;
import br.com.nora.api.application.platform.PlatformNotFoundException;
import br.com.nora.api.application.platform.PlatformUnavailableException;
import br.com.nora.api.application.platform.PlatformValidationException;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

/** Maps control plane exceptions to the correct statuses (503/404/409/422/400). */
class PlatformExceptionHandlerTest {

    private final PlatformExceptionHandler handler = new PlatformExceptionHandler();

    @Test
    void unavailable_503() {
        ResponseEntity<ErrorResponse> r =
                handler.handleUnavailable(new PlatformUnavailableException("x"));
        assertThat(r.getStatusCode()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
        assertThat(r.getBody().code()).isEqualTo("PLATFORM_UNAVAILABLE");
    }

    @Test
    void notFound_404() {
        assertThat(handler.handleNotFound(new PlatformNotFoundException("x")).getStatusCode())
                .isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    void conflict_409() {
        assertThat(handler.handleConflict(new PlatformConflictException("x")).getStatusCode())
                .isEqualTo(HttpStatus.CONFLICT);
    }

    @Test
    void validationUnprocessable_422() {
        assertThat(
                        handler.handleValidation(new PlatformValidationException("x", true))
                                .getStatusCode())
                .isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
    }

    @Test
    void validationBadRequest_400() {
        assertThat(
                        handler.handleValidation(new PlatformValidationException("x", false))
                                .getStatusCode())
                .isEqualTo(HttpStatus.BAD_REQUEST);
    }
}
