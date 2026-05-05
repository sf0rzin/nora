package br.com.nora.api.domain.tenant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

class TenantSlugTest {

    @Test
    void slugifiesAccentsAndSpaces() {
        assertThat(Tenant.slugify("Lucas Silva")).isEqualTo("lucas-silva");
        assertThat(Tenant.slugify("CRM&Sales 2026")).isEqualTo("crm-sales-2026");
    }

    @Test
    void rejectsEmpty() {
        assertThatThrownBy(() -> Tenant.slugify("@@@"))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
