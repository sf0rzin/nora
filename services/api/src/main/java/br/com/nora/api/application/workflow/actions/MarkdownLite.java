package br.com.nora.api.application.workflow.actions;

import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Conversor Markdown→HTML minimalista para os e-mails do Flows.
 *
 * <p>O resumo gerado pelo LLM vem em Markdown (negrito, listas, títulos) e cliente de e-mail não
 * renderiza Markdown — sem conversão o destinatário via asteriscos literais (bug reportado pelo PO
 * em 2026-06-12). Sem lib nova de propósito: o subconjunto coberto é o que o prompt de análise
 * produz na prática.
 *
 * <p>Suporta: títulos ({@code #}–{@code ###} → {@code <h3>/<h4>}), listas com marcador ({@code
 * -}/{@code *}/{@code •}), listas numeradas ({@code 1.}), {@code **negrito**}, {@code
 * *itálico*}/{@code _itálico_}, {@code `código`}, links {@code [texto](https://…)}, autolink de
 * URLs https soltas e parágrafos separados por linha em branco.
 *
 * <p>Segurança: TODO o texto é HTML-escapado antes das transformações — conteúdo vindo do LLM ou do
 * usuário nunca injeta markup.
 */
public final class MarkdownLite {

    private MarkdownLite() {}

    private static final Pattern BOLD = Pattern.compile("\\*\\*(.+?)\\*\\*");
    private static final Pattern ITALIC_STAR =
            Pattern.compile("(?<![*\\w])\\*([^*\\n]+?)\\*(?![*\\w])");
    private static final Pattern ITALIC_UNDER =
            Pattern.compile("(?<![_\\w])_([^_\\n]+?)_(?![_\\w])");
    private static final Pattern CODE = Pattern.compile("`([^`\\n]+?)`");
    private static final Pattern LINK =
            Pattern.compile("\\[([^\\]\\n]+)\\]\\((https://[^)\\s]+)\\)");
    private static final Pattern AUTOLINK = Pattern.compile("(?<![\"=>\\]])(https://[^\\s<()]+)");
    private static final Pattern BULLET = Pattern.compile("^\\s{0,3}[-*•]\\s+(.*)$");
    private static final Pattern NUMBERED = Pattern.compile("^\\s{0,3}\\d{1,2}[.)]\\s+(.*)$");
    private static final Pattern HEADING = Pattern.compile("^(#{1,6})\\s+(.*)$");

    private static final String P_STYLE = "margin:0 0 12px;line-height:1.6;";
    private static final String LI_STYLE = "margin:0 0 4px;line-height:1.55;";
    private static final String LIST_STYLE = "margin:0 0 12px;padding-left:22px;";
    private static final String H_STYLE = "margin:18px 0 8px;letter-spacing:-0.01em;";
    private static final String CODE_STYLE =
            "background:#f2f1ef;border-radius:4px;padding:1px 5px;font-size:90%;";
    private static final String LINK_STYLE = "color:#15171a;text-decoration:underline;";

    /** Renderiza o Markdown como blocos HTML com estilos inline (e-mail-safe). */
    public static String render(String markdown) {
        if (markdown == null || markdown.isBlank()) {
            return "";
        }
        StringBuilder out = new StringBuilder();
        List<String> lines = markdown.replace("\r\n", "\n").lines().toList();

        StringBuilder paragraph = new StringBuilder();
        String openList = null; // "ul" | "ol" | null

        for (String line : lines) {
            Matcher heading = HEADING.matcher(line);
            Matcher bullet = BULLET.matcher(line);
            Matcher numbered = NUMBERED.matcher(line);

            if (line.isBlank()) {
                flushParagraph(out, paragraph);
                openList = closeList(out, openList);
            } else if (heading.matches()) {
                flushParagraph(out, paragraph);
                openList = closeList(out, openList);
                String tag = heading.group(1).length() <= 2 ? "h3" : "h4";
                String size = tag.equals("h3") ? "font-size:16px;" : "font-size:14px;";
                out.append('<')
                        .append(tag)
                        .append(" style=\"")
                        .append(H_STYLE)
                        .append(size)
                        .append("\">")
                        .append(inline(heading.group(2)))
                        .append("</")
                        .append(tag)
                        .append('>');
            } else if (bullet.matches()) {
                flushParagraph(out, paragraph);
                openList = switchList(out, openList, "ul");
                out.append("<li style=\"")
                        .append(LI_STYLE)
                        .append("\">")
                        .append(inline(bullet.group(1)))
                        .append("</li>");
            } else if (numbered.matches()) {
                flushParagraph(out, paragraph);
                openList = switchList(out, openList, "ol");
                out.append("<li style=\"")
                        .append(LI_STYLE)
                        .append("\">")
                        .append(inline(numbered.group(1)))
                        .append("</li>");
            } else {
                openList = closeList(out, openList);
                if (paragraph.length() > 0) {
                    paragraph.append("<br>");
                }
                paragraph.append(inline(line.strip()));
            }
        }
        flushParagraph(out, paragraph);
        closeList(out, openList);
        return out.toString();
    }

    private static void flushParagraph(StringBuilder out, StringBuilder paragraph) {
        if (paragraph.length() > 0) {
            out.append("<p style=\"")
                    .append(P_STYLE)
                    .append("\">")
                    .append(paragraph)
                    .append("</p>");
            paragraph.setLength(0);
        }
    }

    private static String switchList(StringBuilder out, String open, String wanted) {
        if (wanted.equals(open)) {
            return open;
        }
        closeList(out, open);
        out.append('<').append(wanted).append(" style=\"").append(LIST_STYLE).append("\">");
        return wanted;
    }

    private static String closeList(StringBuilder out, String open) {
        if (open != null) {
            out.append("</").append(open).append('>');
        }
        return null;
    }

    /** Transformações inline sobre texto JÁ escapado: negrito, itálico, código, links. */
    static String inline(String raw) {
        String s = WorkflowActionTemplates.escape(raw);
        s = CODE.matcher(s).replaceAll("<code style=\"" + CODE_STYLE + "\">$1</code>");
        s = BOLD.matcher(s).replaceAll("<strong>$1</strong>");
        s = ITALIC_STAR.matcher(s).replaceAll("<em>$1</em>");
        s = ITALIC_UNDER.matcher(s).replaceAll("<em>$1</em>");
        s = LINK.matcher(s).replaceAll("<a href=\"$2\" style=\"" + LINK_STYLE + "\">$1</a>");
        s = AUTOLINK.matcher(s).replaceAll("<a href=\"$1\" style=\"" + LINK_STYLE + "\">$1</a>");
        return s;
    }
}
