import type { Metadata } from "next";
import { LegalTitle, H2, P, UL } from "../_prose";

export const metadata: Metadata = {
  title: "Política de Privacidade — NORA",
  description: "Como a NORA trata dados pessoais, em conformidade com a LGPD.",
};

export default function PrivacidadePage() {
  return (
    <article>
      <LegalTitle updated="27 de maio de 2026">Política de Privacidade</LegalTitle>

      <P>
        Esta Política descreve como a NORA trata dados pessoais, em conformidade com a Lei Geral de
        Proteção de Dados (LGPD, Lei nº 13.709/2018). NORA é &quot;privacy by design&quot;: minimiza
        a exposição de dados sensíveis em todas as etapas.
      </P>

      <H2>1. Dados que tratamos</H2>
      <UL>
        <li>
          <strong>Cadastro:</strong> nome, e-mail e nome do workspace.
        </li>
        <li>
          <strong>Conteúdo:</strong> transcrições enviadas e as análises geradas a partir delas.
        </li>
        <li>
          <strong>Uso:</strong> logs técnicos e de auditoria (acesso, ações de IAM).
        </li>
      </UL>

      <H2>2. Finalidades e base legal</H2>
      <P>
        Tratamos dados para prestar o serviço (execução de contrato), garantir segurança e cumprir
        obrigações legais. Não vendemos dados pessoais.
      </P>

      <H2>3. PII Shield — redação antes da IA</H2>
      <P>
        Antes de qualquer envio a um provedor de IA externo, o PII Shield detecta e substitui dados
        pessoais (e-mail, telefone, CPF, CNPJ, cartão e nomes) por marcadores. O provedor de IA não
        recebe o dado bruto e o conteúdo não é usado para treinar modelos de terceiros.
      </P>

      <H2>4. Isolamento entre clientes (multi-tenancy)</H2>
      <P>
        Cada workspace é isolado por <code>tenant_id</code> em todas as tabelas, com defesa em
        profundidade (filtro de aplicação + Row-Level Security no banco). Seus dados nunca se
        misturam aos de outro cliente.
      </P>

      <H2>5. Compartilhamento</H2>
      <P>
        Compartilhamos dados apenas com subprocessadores necessários à operação (ex.: provedor de
        LLM, infraestrutura em nuvem), sempre sob contrato e com a redação de PII descrita acima.
      </P>

      <H2>6. Retenção</H2>
      <P>
        Retemos dados enquanto a conta estiver ativa ou conforme exigido por lei. Você pode
        solicitar a exclusão a qualquer momento (ver direitos abaixo).
      </P>

      <H2>7. Seus direitos (LGPD)</H2>
      <UL>
        <li>Confirmação e acesso aos seus dados.</li>
        <li>Correção de dados incompletos ou desatualizados.</li>
        <li>Eliminação dos dados tratados com seu consentimento.</li>
        <li>Portabilidade e informação sobre compartilhamentos.</li>
      </UL>

      <H2>8. Segurança</H2>
      <P>
        Adotamos criptografia em trânsito, segredos em cofre gerenciado, tokens de acesso de curta
        duração com rotação e registro de auditoria. Nenhum sistema é 100% imune, mas tratamos
        segurança como requisito de primeira classe.
      </P>

      <H2>9. Encarregado (DPO) e contato</H2>
      <P>
        Para exercer seus direitos ou tirar dúvidas sobre privacidade, entre em contato com o
        Encarregado de Dados da NORA pelos canais de suporte da plataforma.
      </P>

      <H2>10. Alterações</H2>
      <P>
        Esta Política pode ser atualizada. Mudanças relevantes serão comunicadas pelos canais da
        plataforma.
      </P>
    </article>
  );
}
