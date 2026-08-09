"""Hardcoded PT-BR stopwords (no NLTK runtime dep).

Base list inspired by the standard NLTK PT-BR corpus and by common
lists for Brazilian Portuguese. Covers articles, prepositions, conjunctions,
pronouns, auxiliary verbs and frequent adverbs.

The ``get_ptbr_stopwords()`` function returns a *logically immutable* copy
(via a new ``list``) to allow safe consumption without accidental mutation of
the global module. There is also a ``get_ptbr_stopwords_normalized()`` variant
that returns the same list with accents removed --- useful when the pipeline
normalizes the text (lowercase + strip accents) before tokenization.
"""

from __future__ import annotations

import unicodedata

# Base list of PT-BR stopwords. We keep the accents for readability --- the
# pipeline normalizes accents and the normalized version is exposed via
# ``get_ptbr_stopwords_normalized()``.
_PTBR_STOPWORDS: tuple[str, ...] = (
    # Articles
    "a", "o", "as", "os", "um", "uma", "uns", "umas",
    # Prepositions (and common contractions)
    "de", "do", "da", "dos", "das",
    "em", "no", "na", "nos", "nas",
    "por", "pelo", "pela", "pelos", "pelas",
    "para", "pra", "pras", "pros",
    "com", "sem", "sob", "sobre", "ate", "ate", "entre", "contra",
    "ante", "apos", "perante", "trás", "tras",
    "a", "as",
    # Conjunctions
    "e", "ou", "mas", "porem", "porém", "todavia", "contudo",
    "entretanto", "entanto", "nem",
    "que", "porque", "porquê", "porquê", "pois", "logo", "portanto",
    "assim", "quando", "enquanto", "antes", "depois", "ainda", "embora",
    "caso", "como", "conforme", "consoante", "se", "se",
    # Personal and address pronouns
    "eu", "tu", "ele", "ela", "nos", "nós", "vos", "vós", "eles", "elas",
    "me", "te", "se", "lhe", "lhes", "mim", "ti", "si", "comigo", "contigo",
    "consigo", "conosco", "convosco", "voce", "voces", "vc", "vcs",
    # Possessive pronouns
    "meu", "minha", "meus", "minhas",
    "teu", "tua", "teus", "tuas",
    "seu", "sua", "seus", "suas",
    "nosso", "nossa", "nossos", "nossas",
    "vosso", "vossa", "vossos", "vossas",
    "dele", "dela", "deles", "delas",
    # Demonstrative pronouns
    "este", "esta", "estes", "estas", "isto",
    "esse", "essa", "esses", "essas", "isso",
    "aquele", "aquela", "aqueles", "aquelas", "aquilo",
    "neste", "nesta", "nesse", "nessa", "naquele", "naquela",
    "deste", "desta", "desse", "dessa", "daquele", "daquela",
    # Indefinite / interrogative pronouns
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
    # Verb SER
    "sou", "es", "é", "e", "somos", "sois", "são", "sao",
    "era", "eras", "eramos", "éramos", "ereis", "eram",
    "fui", "foste", "foi", "fomos", "fostes", "foram",
    "serei", "seras", "será", "seremos", "sereis", "serão", "serao",
    "seria", "serias", "seriamos", "seríamos", "serieis", "seriam",
    "seja", "sejas", "sejamos", "sejais", "sejam",
    "fosse", "fosses", "fossemos", "fôssemos", "fosseis", "fossem",
    "for", "fores", "formos", "fordes", "forem",
    "ser", "sendo", "sido",
    # Verb ESTAR
    "estou", "estas", "está", "esta", "estamos", "estais", "estão", "estao",
    "estava", "estavas", "estavamos", "estávamos", "estaveis", "estavam",
    "estive", "estiveste", "esteve", "estivemos", "estivestes", "estiveram",
    "estarei", "estaras", "estará", "estaremos", "estareis", "estarão", "estarao",
    "estaria", "estarias", "estariamos", "estaríamos", "estarieis", "estariam",
    "esteja", "estejas", "estejamos", "estejais", "estejam",
    "estivesse", "estivesses", "estivessemos", "estivéssemos",
    "estiver", "estiveres", "estivermos", "estiverdes", "estiverem",
    "estar", "estando", "estado",
    # Verb TER
    "tenho", "tens", "tem", "têm", "temos", "tendes",
    "tinha", "tinhas", "tinhamos", "tínhamos", "tinheis", "tinham",
    "tive", "tiveste", "teve", "tivemos", "tivestes", "tiveram",
    "terei", "teras", "terá", "teremos", "tereis", "terão", "terao",
    "teria", "terias", "teriamos", "teríamos", "terieis", "teriam",
    "tenha", "tenhas", "tenhamos", "tenhais", "tenham",
    "tivesse", "tivesses", "tivessemos", "tivéssemos",
    "tiver", "tiveres", "tivermos", "tiverdes", "tiverem",
    "ter", "tendo", "tido",
    # Verb HAVER
    "hei", "has", "ha", "há", "havemos", "haveis", "hao", "hão",
    "havia", "havias", "haviamos", "havíamos", "havieis", "haviam",
    "houve", "houveste", "houvemos", "houvestes", "houveram",
    "haver", "havendo", "havido",
    # Auxiliary / very frequent verbs
    "vai", "vou", "vamos", "vão", "vao", "ia", "iamos", "íamos", "iam",
    "foi", "fora", "será", "serao", "são", "sao",
    "fazer", "faz", "faço", "fizemos", "feito",
    # Frequent adverbs
    "nao", "não", "sim", "ja", "já", "ainda", "agora", "antes",
    "depois", "amanhã", "amanha", "hoje", "ontem", "sempre",
    "nunca", "jamais", "talvez", "tambem", "também", "so", "só", "somente",
    "apenas", "muito", "pouco", "bem", "mal", "mais", "menos",
    "aqui", "ali", "ai", "aí", "la", "lá", "acola", "acolá",
    "perto", "longe", "dentro", "fora", "atras", "atrás", "diante",
    "talvez", "certamente", "provavelmente",
    # Other frequent ones
    "etc", "etc.",
    "vai", "vem", "ir", "vim", "vir",
    "ah", "oh", "uh", "ei", "uai",
)


def _strip_accents(text: str) -> str:
    """Removes accents via NFD decomposition + combining-mark filter."""
    nfd = unicodedata.normalize("NFD", text)
    return "".join(ch for ch in nfd if not unicodedata.combining(ch))


def get_ptbr_stopwords() -> list[str]:
    """Returns the PT-BR stopword list (with accents preserved).

    The function returns a new ``list`` on every call --- mutating the result
    does not affect the module. The list is deduplicated (lower + dedup).
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
    """Returns PT-BR stopwords with accents removed (NFD + filter).

    Useful when the pipeline already normalizes the tokens before removal --- avoids
    a false negative (e.g.: ``é`` in the normalized text would be ``e``, but the
    unnormalized list only contains ``é``).
    """
    seen: set[str] = set()
    out: list[str] = []
    for word in _PTBR_STOPWORDS:
        w = _strip_accents(word.lower())
        if w and w not in seen:
            seen.add(w)
            out.append(w)
    return out
