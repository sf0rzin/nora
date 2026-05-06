import { createContext, useContext, useState, useEffect, type ReactNode } from "react";
import type { SessionUser } from "@/lib/types";
import { getCurrentUser, isAuthenticated, logout as doLogout } from "@/lib/auth";
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
    if (isAuthenticated()) {
      const stored = getCurrentUser();
      if (stored) {
        setUser(stored);
      }
    }
    setLoading(false);
  }, []);

  const handleLogin = (u: SessionUser) => {
    setUser(u);
  };

  const logout = () => {
    doLogout();
    setUser(null);
  };

  useEffect(() => {
    apiClient.on401(logout);
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
