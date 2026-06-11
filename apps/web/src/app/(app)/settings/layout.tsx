/**
 * NORA Core — hub de Configurações.
 *
 * Layout fino: estabelece o container `.page` e o cabeçalho do hub. A
 * navegação interna por seções (Conta · Segurança · Workspace · Contexto da
 * empresa) e os respectivos painéis vivem na página cliente (context/page.tsx),
 * onde o estado da seção ativa pode dirigir a troca de painel.
 *
 * Sem IAM aqui: o Core é individual; times e permissões são do Enterprise.
 */
export default function SettingsLayout({ children }: { children: React.ReactNode }) {
  return (
    <div className="page" style={{ maxWidth: 880 }}>
      <header style={{ marginBottom: 32 }}>
        <h1 className="h1">Configurações</h1>
        <p className="lede" style={{ marginTop: 8 }}>
          Sua conta, segurança e workspace — tudo num lugar só.
        </p>
      </header>
      {children}
    </div>
  );
}
