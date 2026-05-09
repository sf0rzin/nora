"use client";

import { useRouter } from "next/navigation";
import { clearSession } from "@/lib/auth";

export default function LogoutButton() {
  const router = useRouter();
  return (
    <button
      type="button"
      onClick={() => {
        clearSession();
        router.replace("/auth/login");
        router.refresh();
      }}
      className="rounded-md border border-slate-300 bg-white px-2 py-1 text-xs text-slate-700 hover:bg-slate-100"
    >
      Sair
    </button>
  );
}
