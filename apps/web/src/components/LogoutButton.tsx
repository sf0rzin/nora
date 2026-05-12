'use client';

import { useRouter } from 'next/navigation';
import { useState } from 'react';
import { clearSession } from '@/lib/auth';

export default function LogoutButton() {
  const router = useRouter();
  const [loading, setLoading] = useState(false);
  return (
    <button
      type="button"
      disabled={loading}
      onClick={async () => {
        if (loading) return;
        setLoading(true);
        await clearSession();
        router.replace('/auth/login');
        router.refresh();
      }}
      className="rounded-md border border-slate-300 bg-white px-2 py-1 text-xs text-slate-700 hover:bg-slate-100 disabled:opacity-50"
    >
      {loading ? 'Saindo…' : 'Sair'}
    </button>
  );
}
