/**
 * Política de senha do front — espelha o backend (SignupRequest @Size(min=10,max=128) e
 * a PasswordPolicy do serviço de identidade). Centralizado para não divergir entre as
 * telas (signup, reset de senha, aceite de convite) e o servidor.
 */
export const PASSWORD_MIN = 10;
export const PASSWORD_MAX = 128;
