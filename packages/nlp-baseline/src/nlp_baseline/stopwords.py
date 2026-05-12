"""Stopwords PT-BR hardcoded (sem dep de runtime do NLTK).

Lista base inspirada no corpus padrao do NLTK PT-BR e em listas
comuns para portugues brasileiro. Cobre artigos, preposicoes, conjuncoes,
pronomes, verbos auxiliares e advs frequentes.

A funcao ``get_ptbr_stopwords()`` retorna uma copia *imutavel logicamente*
(via novo ``list``) para permitir consumo seguro sem mutacao acidental do
modulo global. Tambem ha uma variante ``get_ptbr_stopwords_normalized()``
que retorna a mesma lista com acentos removidos --- util quando o pipeline
normaliza o texto (lowercase + strip accents) antes da tokenizacao.
"""

from __future__ import annotations

import unicodedata

# Lista base de stopwords PT-BR. Mantemos com acentos para legibilidade --- o
# pipeline normaliza acentos e a versao normalizada eh exposta via
# ``get_ptbr_stopwords_normalized()``.
_PTBR_STOPWORDS: tuple[str, ...] = (
    # Artigos
    "a", "o", "as", "os", "um", "uma", "uns", "umas",
    # Preposicoes (e contracoes comuns)
    "de", "do", "da", "dos", "das",
    "em", "no", "na", "nos", "nas",
    "por", "pelo", "pela", "pelos", "pelas",
    "para", "pra", "pras", "pros",
    "com", "sem", "sob", "sobre", "ate", "ate", "entre", "contra",
    "ante", "apos", "perante", "trás", "tras",
    "a", "as",
    # Conjuncoes
    "e", "ou", "mas", "porem", "porém", "todavia", "contudo",
    "entretanto", "entanto", "nem",
    "que", "porque", "porquê", "porquê", "pois", "logo", "portanto",
    "assim", "quando", "enquanto", "antes", "depois", "ainda", "embora",
    "caso", "como", "conforme", "consoante", "se", "se",
    # Pronomes pessoais e tratamento
    "eu", "tu", "ele", "ela", "nos", "nós", "vos", "vós", "eles", "elas",
    "me", "te", "se", "lhe", "lhes", "mim", "ti", "si", "comigo", "contigo",
    "consigo", "conosco", "convosco", "voce", "voces", "vc", "vcs",
    # Pronomes possessivos
    "meu", "minha", "meus", "minhas",
    "teu", "tua", "teus", "tuas",
    "seu", "sua", "seus", "suas",
    "nosso", "nossa", "nossos", "nossas",
    "vosso", "vossa", "vossos", "vossas",
    "dele", "dela", "deles", "delas",
    # Pronomes demonstrativos
    "este", "esta", "estes", "estas", "isto",
    "esse", "essa", "esses", "essas", "isso",
    "aquele", "aquela", "aqueles", "aquelas", "aquilo",
    "neste", "nesta", "nesse", "nessa", "naquele", "naquela",
    "deste", "desta", "desse", "dessa", "daquele", "daquela",
    # Pronomes indefinidos / interrogativos
    "algum", "alguma", "alguns", "algumas",
    "nenhum", "nenhuma", "nenhuns", "nenhumas",
    "todo", "toda", "todos", "todas",
    "outro", "outra", "outros", "outras",
    "muito", "muita", "muitos", "muitas",
    "pouco", "pouca", "poucos", "poucas",
    "tanto", "tanta", "tantos", "tantas",
    "qualquer", "quaisquer",
    "alguem", "ninguem", "tudo", "nada", "algo",
    "quem", "qual", "quais", "quanto", "quanta", "quantos", "quantas",
    "onde", "aonde", "donde", "cujo", "cuja", "cujos", "cujas",
    # Verbo SER
    "sou", "es", "é", "e", "somos", "sois", "são", "sao",
    "era", "eras", "eramos", "éramos", "ereis", "eram",
    "fui", "foste", "foi", "fomos", "fostes", "foram",
    "serei", "seras", "será", "seremos", "sereis", "serão", "serao",
    "seria", "serias", "seriamos", "seríamos", "serieis", "seriam",
    "seja", "sejas", "sejamos", "sejais", "sejam",
    "fosse", "fosses", "fossemos", "fôssemos", "fosseis", "fossem",
    "for", "fores", "formos", "fordes", "forem",
    "ser", "sendo", "sido",
    # Verbo ESTAR
    "estou", "estas", "está", "esta", "estamos", "estais", "estão", "estao",
    "estava", "estavas", "estavamos", "estávamos", "estaveis", "estavam",
    "estive", "estiveste", "esteve", "estivemos", "estivestes", "estiveram",
    "estarei", "estaras", "estará", "estaremos", "estareis", "estarão", "estarao",
    "estaria", "estarias", "estariamos", "estaríamos", "estarieis", "estariam",
    "esteja", "estejas", "estejamos", "estejais", "estejam",
    "estivesse", "estivesses", "estivessemos", "estivéssemos",
    "estiver", "estiveres", "estivermos", "estiverdes", "estiverem",
    "estar", "estando", "estado",
    # Verbo TER
    "tenho", "tens", "tem", "têm", "temos", "tendes",
    "tinha", "tinhas", "tinhamos", "tínhamos", "tinheis", "tinham",
    "tive", "tiveste", "teve", "tivemos", "tivestes", "tiveram",
    "terei", "teras", "terá", "teremos", "tereis", "terão", "terao",
    "teria", "terias", "teriamos", "teríamos", "terieis", "teriam",
    "tenha", "tenhas", "tenhamos", "tenhais", "tenham",
    "tivesse", "tivesses", "tivessemos", "tivéssemos",
    "tiver", "tiveres", "tivermos", "tiverdes", "tiverem",
    "ter", "tendo", "tido",
    # Verbo HAVER
    "hei", "has", "ha", "há", "havemos", "haveis", "hao", "hão",
    "havia", "havias", "haviamos", "havíamos", "havieis", "haviam",
    "houve", "houveste", "houvemos", "houvestes", "houveram",
    "haver", "havendo", "havido",
    # Verbos auxiliares / muito frequentes
    "vai", "vou", "vamos", "vão", "vao", "ia", "iamos", "íamos", "iam",
    "foi", "fora", "será", "serao", "são", "sao",
    "fazer", "faz", "faço", "fizemos", "feito",
    # Adverbios frequentes
    "nao", "não", "sim", "ja", "já", "ainda", "agora", "antes",
    "depois", "amanhã", "amanha", "hoje", "ontem", "sempre",
    "nunca", "jamais", "talvez", "tambem", "também", "so", "só", "somente",
    "apenas", "muito", "pouco", "bem", "mal", "mais", "menos",
    "aqui", "ali", "ai", "aí", "la", "lá", "acola", "acolá",
    "perto", "longe", "dentro", "fora", "atras", "atrás", "diante",
    "talvez", "certamente", "provavelmente",
    # Outros frequentes
    "etc", "etc.",
    "vai", "vem", "ir", "vim", "vir",
    "ah", "oh", "uh", "ei", "uai",
)


def _strip_accents(text: str) -> str:
    """Remove acentos via decomposicao NFD + filtro de combining marks."""
    nfd = unicodedata.normalize("NFD", text)
    return "".join(ch for ch in nfd if not unicodedata.combining(ch))


def get_ptbr_stopwords() -> list[str]:
    """Retorna lista de stopwords PT-BR (com acentos preservados).

    A funcao devolve uma nova ``list`` a cada chamada --- mutar o resultado
    nao afeta o modulo. A lista esta deduplicada (lower + dedup).
    """
    seen: set[str] = set()
    out: list[str] = []
    for word in _PTBR_STOPWORDS:
        w = word.lower()
        if w and w not in seen:
            seen.add(w)
            out.append(w)
    return out


def get_ptbr_stopwords_normalized() -> list[str]:
    """Retorna stopwords PT-BR com acentos removidos (NFD + filter).

    Util quando o pipeline ja normaliza os tokens antes da remocao --- evita
    falso-negativo (ex.: ``é`` no texto normalizado seria ``e``, mas a lista
    sem normalizar contem somente ``é``).
    """
    seen: set[str] = set()
    out: list[str] = []
    for word in _PTBR_STOPWORDS:
        w = _strip_accents(word.lower())
        if w and w not in seen:
            seen.add(w)
            out.append(w)
    return out
