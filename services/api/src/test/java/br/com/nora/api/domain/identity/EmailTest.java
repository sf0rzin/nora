package br.com.nora.api.domain.identity;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

class EmailTest {

    @Test
    void normalizesAndAcceptsValid() {
        Email e = Email.of("  Foo.Bar+x@Example.COM ");
        assertThat(e.value()).isEqualTo("foo.bar+x@example.com");
    }

    @Test
    void rejectsInvalid() {
        assertThatThrownBy(() -> Email.of("not-an-email"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> Email.of(null)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> Email.of("a@b")).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void equalsByValue() {
        assertThat(Email.of("a@b.com")).isEqualTo(Email.of("A@B.COM"));
    }
}
