import { Suspense } from "react";

import { AuthScreen } from "@/components/auth/auth-screen";

export default function LoginPage() {
  return (
    <Suspense fallback={null}>
      <AuthScreen initialMode="login" />
    </Suspense>
  );
}
