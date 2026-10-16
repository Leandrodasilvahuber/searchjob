# SearchJob

Automação de candidaturas (substitui o loop do LLM da skill `busca-emprego`) com tela web.
O código faz o trabalho repetitivo; a z.ai (GLM) só entra em situação nova ou quando o modo "z.ai avalia" está ligado.

## Rodar

```bash
./run.sh            # http://localhost:8787
mvn test            # 11 testes (a busca do Gupy usa a rede)
```

Na primeira subida, importa de `searchjob.pasta-origem` (application.yml): o `respostas_candidaturas.md`
(dados fixos, perguntas, histórico) e os PDFs dos CVs. Depois disso o banco `data/searchjob.db` (SQLite) é a fonte da verdade.

## Dois modos (Painel)

- **Código**: filtros (recência, estágio, inglês oral, stack), escolha de CV por palavras-chave e respostas vêm de regras e do banco.
- **z.ai avalia**: a z.ai decide se a vaga serve e qual CV usar.

Em ambos, uma pergunta de formulário sem regra segue este fluxo:

1. Acha situação ativa (hash exato ou ≥ 80% de similaridade) → aplica sem LLM.
2. Senão, pergunta à z.ai. Se ela resolve → **pendência de confirmação** (bip + tela); ao confirmar vira regra (`zai_confirmada`).
3. Se ela não resolve → **pendência de ajuda**; o que você responder vira regra (`usuario`).

RG, nome do pai, senha e dados bancários nunca vão para a z.ai: sempre chamam você.

## Configuração

Tela **Configuração**: `zai_chave` (obrigatória para usar a z.ai), `zai_url`, `zai_modelo`, `dry_run`, `portais`, `max_vagas`.
**Dry-run vem ligado**: preenche o formulário até o último passo mas não envia. Desligue no Painel quando validar.
O Firefox usa o perfil `data/firefox-profile`: faça login no Gupy uma vez nessa janela e a sessão fica salva.

## Estado

- Gupy: busca pela API pública (testada); candidatura via Selenium escrita seguindo o fluxo da skill, **ainda não validada ponta a ponta** — validar com dry-run.
- Perguntas de múltipla escolha (radio/select) ainda não são preenchidas; só campos de texto.
- LinkedIn, Sólides, Indeed, Catho, ProgramaThor: pendentes.
