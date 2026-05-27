import type { Metadata } from "next";
import { LegalTitle, H2, P, UL } from "../_prose";

export const metadata: Metadata = {
  title: "Termos de Uso — NORA",
  description: "Termos de Uso da plataforma NORA.",
};

export default function TermosPage() {
  return (
    <article>
      <LegalTitle updated="27 de maio de 2026">Termos de Uso</LegalTitle>

      <P>
        Estes Termos regem o uso da NORA, plataforma de inteligência conversacional que transforma
        transcrições de reuniões em resumos, decisões e action items. Ao criar uma conta ou usar o
        serviço, você concorda com estes Termos.
      </P>

      <H2>1. A conta</H2>
      <P>
        Você é responsável por manter a confidencialidade das suas credenciais e por toda atividade
        realizada na sua conta. Cada cadastro cria um workspace isolado (tenant). O primeiro usuário
        do workspace é seu administrador (Root).
      </P>

      <H2>2. Uso aceitável</H2>
      <UL>
        <li>Você só deve enviar transcrições que tem o direito de processar.</li>
        <li>É proibido usar a NORA para fins ilegais ou para violar direitos de terceiros.</li>
        <li>Não tente burlar o isolamento entre workspaces nem os controles de acesso (IAM).</li>
      </UL>

      <H2>3. Planos</H2>
      <P>
        A NORA oferece um plano gratuito (Core) e um plano corporativo (Enterprise) sob consulta.
        Recursos disponíveis em cada plano podem evoluir; mudanças relevantes serão comunicadas.
      </P>

      <H2>4. Conteúdo e propriedade</H2>
      <P>
        As transcrições e análises geradas pertencem ao seu workspace. A NORA não reivindica
        propriedade sobre o seu conteúdo e não o utiliza para treinar modelos de terceiros.
      </P>

      <H2>5. Privacidade e dados</H2>
      <P>
        O tratamento de dados pessoais segue a nossa{" "}
        <a href="/legal/privacidade" style={{ color: "var(--accent-ink)" }}>
          Política de Privacidade
        </a>{" "}
        e a Lei Geral de Proteção de Dados (LGPD). Dados sensíveis (PII) são redigidos pelo PII
        Shield antes de qualquer envio a provedores de IA externos.
      </P>

      <H2>6. Disponibilidade e limitação de responsabilidade</H2>
      <P>
        O serviço é fornecido &quot;como está&quot;. As análises de IA são insumo para revisão
        humana e podem conter imprecisões; não substituem julgamento profissional. Na máxima medida
        permitida em lei, a NORA não se responsabiliza por decisões tomadas exclusivamente com base
        nas saídas automáticas.
      </P>

      <H2>7. Encerramento</H2>
      <P>
        Você pode encerrar sua conta a qualquer momento. Podemos suspender contas que violem estes
        Termos. Após o encerramento, dados são tratados conforme a Política de Privacidade.
      </P>

      <H2>8. Alterações</H2>
      <P>
        Podemos atualizar estes Termos. Mudanças significativas serão comunicadas pelos canais da
        plataforma. O uso continuado após a vigência implica concordância.
      </P>

      <H2>9. Contato</H2>
      <P>Dúvidas sobre estes Termos podem ser encaminhadas ao suporte da NORA.</P>
    </article>
  );
}
