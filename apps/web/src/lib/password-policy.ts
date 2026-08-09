/**
 * Front-end password policy — mirrors the backend (SignupRequest @Size(min=10,max=128) and
 * the identity service's PasswordPolicy). Centralized so it does not diverge between the
 * screens (signup, password reset, invite acceptance) and the server.
 */
export const PASSWORD_MIN = 10;
export const PASSWORD_MAX = 128;
