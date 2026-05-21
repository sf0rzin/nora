import { createContext, useCallback, useContext, useState, useEffect, type ReactNode } from "react";
import type { SessionUser } from "@/lib/types";
import { bootstrapSession, logout as doLogout, stopTokenRefreshLoop } from "@/lib/auth";
import { apiClient } from "@/lib/api-client";

interface AuthState {
  user: SessionUser | null;
  authenticated: boolean;
  loading: boolean;
  login: (user: SessionUser) => void;
  logout: () => void;
}

const AuthContext = createContext<AuthState>({
  user: null,
  authenticated: false,
  loading: true,
  login: () => {},
  logout: () => {},
});

export function AuthProvider({ children }: { children: ReactNode }) {
  const [user, setUser] = useState<SessionUser | null>(null);
  const [loading, setLoading] = useState(true);

  useEffect(() => {
    let mounted = true;
    bootstrapSession().then((stored) => {
      if (!mounted) return;
      if (stored) {
        apiClient.setCachedUser(stored);
        setUser(stored);
      }
      setLoading(false);
    });

    return () => {
      mounted = false;
      stopTokenRefreshLoop();
    };
  }, []);

  const handleLogin = useCallback((u: SessionUser) => {
    apiClient.setCachedUser(u);
    setUser(u);
    setLoading(false);
  }, []);

  const logout = useCallback(() => {
    void doLogout();
    setUser(null);
  }, []);

  // Antes `apiClient.on401(logout)` rodava sem cleanup e a referência ficava
  // stale após HMR/remount do Provider (StrictMode roda effects 2x).
  useEffect(() => {
    apiClient.on401(logout);
    // O ApiClient não tem off401; registrar `() => {}` no unmount evita que o
    // callback antigo dispare em uma instância morta do Provider.
    return () => {
      apiClient.on401(() => {});
    };
  }, [logout]);

  // Ouve evento auth-expired vindo do loop de refresh
  useEffect(() => {
    const handler = () => {
      void doLogout();
      setUser(null);
      window.location.hash = "#/login";
    };
    window.addEventListener("auth-expired", handler);
    return () => window.removeEventListener("auth-expired", handler);
  }, []);

  return (
    <AuthContext.Provider
      value={{
        user,
        authenticated: !!user,
        loading,
        login: handleLogin,
        logout,
      }}
    >
      {children}
    </AuthContext.Provider>
  );
}

export function useAuth() {
  return useContext(AuthContext);
}
