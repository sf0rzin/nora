package br.com.nora.api.domain.identity;

import static org.assertj.core.api.Assertions.assertThatNoException;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

class PasswordPolicyTest {

    @Test
    void acceptsCompliantPassword() {
        assertThatNoException().isThrownBy(() -> PasswordPolicy.validate("SenhaForte123"));
    }

    @Test
    void rejectsTooShort() {
        assertThatThrownBy(() -> PasswordPolicy.validate("a1bc"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void rejectsNoDigit() {
        assertThatThrownBy(() -> PasswordPolicy.validate("apenasletrasaqui"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void rejectsNoLetter() {
        assertThatThrownBy(() -> PasswordPolicy.validate("1234567890"))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
