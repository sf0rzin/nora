package br.com.nora.api.application.ports;

import br.com.nora.api.domain.identity.Email;
import br.com.nora.api.domain.identity.User;
import java.util.Optional;
import java.util.UUID;

/** Porta de persistencia para o agregado User. */
public interface UserRepository {

    Optional<User> findById(UUID id);

    Optional<User> findByEmail(Email email);

    User save(User user);
}
