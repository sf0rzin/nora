import { createContext, useContext, useState, useEffect, type ReactNode } from "react";
import type { SessionUser } from "@/lib/types";
import { getCurrentUser, isAuthenticated, logout as doLogout } from "@/lib/auth";

interface AuthState {
  user: SessionUser | null;
  authenticated: boolean;
  loading: boolean;
  logout: () => void;
}

const AuthContext = createContext<AuthState>({
  user: null,
  authenticated: false,
  loading: true,
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

  const logout = () => {
    doLogout();
    setUser(null);
  };

  return (
    <AuthContext.Provider
      value={{
        user,
        authenticated: !!user,
        loading,
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
