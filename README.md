# SearchJob

Automação de candidaturas a vagas com tela web local. Substitui o loop de LLM da skill `busca-emprego`:
o **código** faz o trabalho repetitivo (buscar, filtrar, escolher o CV, preencher, enviar, registrar) e a **IA (OpenRouter, modelos free)**
só entra quando precisa de julgamento: pergunta nova, texto livre, CV indefinido ou, se você ligar, avaliar a vaga.

- Spring Boot 3.3 · Java 17 · SQLite · Thymeleaf · Selenium (Firefox)
- Roda só na sua máquina (`127.0.0.1:8787`), sem login próprio
- Estado: veja [Estado e limitações](#estado-e-limitações)

## Sumário

1. [Como rodar](#como-rodar)
2. [Como funciona](#como-funciona)
3. [A tela](#a-tela)
4. [Configuração](#configuração)
5. [Portais](#portais)
6. [Sons](#sons)
7. [Segurança](#segurança)
8. [Dados e banco](#dados-e-banco)
9. [Testes e qualidade](#testes-e-qualidade)
10. [Estrutura do código](#estrutura-do-código)
11. [Estado e limitações](#estado-e-limitações)

## Como rodar

Pré-requisitos: Java 17, Maven, Firefox e `geckodriver` no PATH, `pdftotext` (poppler-utils) e `paplay` (para os sons).

```bash
./run.sh                 # sobe em http://localhost:8787
```

Para parar, use o botão **⏻ Encerrar serviço** no menu da tela. Ele para a sessão, fecha o Firefox do robô e derruba a
JVM, liberando a memória. Para subir de novo, rode `./run.sh` outra vez.

Na **primeira subida** o sistema importa de `searchjob.pasta-origem` (`application.yml`):

- `respostas_candidaturas.md` → dados fixos, situações (perguntas já respondidas) e histórico de candidaturas;
- os PDFs dos CVs → texto extraído com `pdftotext` e palavras-chave de stack por CV.

Depois disso a importação **não roda mais** (flag `md_importado` em Configuração) e o SQLite passa a ser a fonte da
verdade: o que você apagar na tela não volta. Para reimportar, apague a chave `md_importado`.

### Primeiro uso, passo a passo

1. Abra **Configuração** e preencha `openrouter_chave` (confira `openrouter_url` e `openrouter_modelos`). Sem a chave, o sistema funciona, mas
   toda pergunta sem regra espera por você e não há textos sob medida.
2. No **Painel**, deixe o **dry-run ligado** (é o padrão) e `max_vagas` pequeno (3 a 5).
3. Clique em **▶ Iniciar busca**. O sistema abre o Firefox do robô (perfil próprio em `data/firefox-profile`). Faça login
   nos portais nessa janela; a sessão fica salva.
4. Acompanhe o log do Painel e a aba **Pendências**. Confira o que ele preencheu e, em **Textos da IA**, o que foi escrito.
5. Quando estiver satisfeito, desmarque o dry-run e rode de novo.

## Como funciona

Uma sessão percorre **portal → termo de busca → vaga**, uma vaga por vez:

```
buscar vagas (API/HTML/Firefox)
  └─ para cada vaga:
       já enviada/pulada (ou ensaiada, em dry-run)?  → ignora (compara pelo ID da vaga)
       filtros por código (recência, estágio, inglês oral, stack, fora do Brasil) → registra "pulada"
       escolha do CV
       [modo IA] a IA decide se vale candidatar e qual CV
       candidatar (Firefox): preenche campos → avança → envia → confere o texto de sucesso
       registra no histórico, toca o som
```

### Dois modos de avaliação (botão no Painel)

| Modo | Quem decide se a vaga serve e qual CV |
|---|---|
| **Por código** (padrão) | Regras e banco. A IA só é chamada para pergunta nova, texto livre ou CV indefinido. |
| **IA avalia** | A IA lê a vaga, compara com o CV e as preferências e decide candidatar/pular e o CV. |

Se `modo = ia` mas não há chave, ele avisa no log e volta ao modo por código.

### Escolha do CV

1. Título com **arquiteto, líder, tech lead, staff, principal ou coordenador** → CV *Sistemas Distribuídos*.
2. Senão, pontua as palavras-chave de cada CV (aba **CVs**) no título e na descrição. A comparação é por **palavra
   inteira** (`java` não casa com `javascript`; sufixos `js`/`boot` são aceitos; termos com 9+ letras casam por radical).
3. Vitória clara → esse CV. **Sem palavra-chave ou empate → escolha indefinida: pergunta à IA** qual CV mandar.
4. Se a IA não está configurada ou falha, usa o CV *NodeJS* (padrão em empate, como na skill).

Os seus CVs são por stack e não por nível: pleno ou sênior sozinho não troca de CV; a stack decide.

### Perguntas de formulário: as "situações"

Cada pergunta de formulário passa por `SituacaoService`:

1. **Dado sensível** (RG, nome do pai, senha, dados bancários) → nunca vai para a IA; o sistema chama você.
2. **Texto livre** (qualquer `textarea` ou pergunta aberta: *apresente-se, conte sobre, por que, motivação, carta…*)
   → a **IA escreve sob medida para cada vaga**, usando a descrição da vaga e o texto do CV escolhido, sem pedir
   confirmação (para manter o fluxo automático). Cada texto fica registrado em **Textos da IA**. A instrução usada
   (tipo `personalizada`) é editável em **Situações**. Se já existia uma resposta aprovada parecida, ela entra no prompt
   como base de fatos e de tom.
3. **Pergunta curta** (pretensão, CNH, anos de experiência…):
   - achou situação ativa equivalente → aplica **sem LLM**;
   - não achou → pergunta à **IA**. Se ela resolve, vira **pendência de confirmação** (som + tela); ao você confirmar
     ou corrigir, vira regra (`origem = ia_confirmada`);
   - se a IA não resolve (ou não há chave), vira **pendência de ajuda**; o que você responder vira regra
     (`origem = usuario`).
4. Pendências abertas da mesma pergunta são reaproveitadas, não duplicadas.

**Equivalência de perguntas:** duas perguntas só são "a mesma" se forem muito parecidas (≥ 80%) **e** as palavras que
diferem forem só conectivos. Assim *experiência com Go* nunca reaproveita a resposta de *experiência com R*, nem *CNH A*
a de *CNH B*.

**Privacidade no prompt:** CPF, RG, filiação, nascimento e dados bancários são removidos dos dados e das respostas
enviados à IA. O CPF continua disponível para preencher formulários que o peçam.

### Dry-run

Ligado por padrão. O robô preenche tudo e **para antes do botão de envio**, marcando a vaga como `dry_run`. Vagas
ensaiadas não são refeitas em novos ensaios (não repete as chamadas à IA); no envio real elas são tentadas de novo.
No Gupy, mesmo em dry-run, "Salvar e continuar" grava as respostas no seu perfil da Gupy; só o envio final é evitado.

### Quando ele chama você

Login, CAPTCHA, dado sensível, pergunta sem resposta, confirmação de resposta da IA. Se um portal pedir login ou
CAPTCHA, **só aquele portal** fica de fora da rodada; os demais seguem. Faça o login na janela do Firefox e inicie de novo.

### Deduplicação

A chave da vaga é o ID extraído do link (token base64 do Gupy, `/jobs/<id>`), então o mesmo job em formatos de URL
diferentes não é reenviado. Vagas com `erro`, `pendente`, `dry_run` ou `aguardando_usuario` reutilizam a mesma linha do
histórico em vez de criar outra. Há **uma tentativa por vaga por rodada**: não há retry automático depois de um clique
em "enviar", para nunca candidatar duas vezes.

## A tela

| Aba | O que faz |
|---|---|
| **Painel** | Iniciar/Parar, modo (código ou IA), dry-run, contadores, log ao vivo e alerta de pendências. |
| **Pendências** | Respostas da IA para confirmar/corrigir e perguntas que ela não resolveu. |
| **Histórico** | Todas as vagas com status (`enviada`, `pulada`, `erro`, `pendente`, `dry_run`, `aguardando_usuario`), filtro por portal/status, link e exclusão. |
| **Situações** | Regras aprendidas: listar, editar, ativar/desativar, excluir e criar. |
| **Textos da IA** | Cada texto livre escrito por vaga, com vaga, CV e data. |
| **CVs** | Palavras-chave de stack e texto extraído de cada CV; editar e excluir. |
| **Dados fixos** | Nome, e-mail, telefone etc. (chave/valor, editável). |
| **Preferências** | Pretensão, recência, termos, filtros (chave/valor, editável). |
| **Configuração** | Modo, portais, dry-run, `max_vagas`, dados da IA. |
| **⏻ Encerrar serviço** | Para a sessão, fecha o Firefox e derruba o serviço. |

## Configuração

**Preferências** (aba Preferências; vêm com estes padrões):

| Chave | Padrão | Uso |
|---|---|---|
| `termos` | full stack, Vue.js, Laravel, PHP, React, Node.js, backend | Termos de busca, em ordem |
| `recencia_dias` | 14 | Descarta vagas mais antigas |
| `pretensao_pj` / `pretensao_clt` | 10000 / 7000 | Usadas pela IA nas respostas |
| `excluir_titulos` | estágio, estagiário, jovem aprendiz, banco de talentos | Pula pelo título |
| `exige_ingles_oral` | calls/reuniões/entrevista em inglês, fluência oral… | Pula pelo texto da vaga |
| `stack_proibida` | python, .net, golang, kotlin, angular… | Pula pelo **título** |

Os filtros comparam **palavra inteira** (`.net` não pega "Kubernetes"; "inglês avançado" sozinho não descarta mais a vaga).

**Configuração** (aba Configuração):

| Chave | Padrão | Uso |
|---|---|---|
| `modo` | codigo | `codigo` ou `ia` |
| `portais` | gupy,programathor,catho,indeed | Portais ativos; nome desconhecido é ignorado com aviso |
| `dry_run` | true | Preenche sem enviar. **Lido no início da sessão.** |
| `max_vagas` | 30 | Vagas listadas **por portal e por termo** (não é limite de candidaturas) |
| `openrouter_chave` | vazio | Chave do OpenRouter. Nunca é exibida; salvar em branco mantém a atual |
| `openrouter_url` | https://openrouter.ai/api/v1/chat/completions | Endpoint compatível com OpenAI |
| `openrouter_modelos` | nemotron-3-super-120b, laguna-xs-2.1, gemma-4-31b-it (todos `:free`) | Lista separada por vírgula; tenta em ordem (fallback) |
| `md_importado` | — | Marca que a importação inicial já ocorreu |

Valores numéricos inválidos caem no padrão, sem derrubar a sessão.

## Portais

| Portal | Busca | Candidatura | Observações |
|---|---|---|---|
| **Gupy** | API pública (`portal.gupy.io/api/job-search/jobs`), sem navegador | Firefox: Continuar → Dados adicionais → perguntas → Apresente-se → Finalizar | Fluxo da skill; **ainda não validado numa vaga real** |
| **ProgramaThor** | HTML de `/jobs-<termo>` | Botão "Quero me candidatar" | Listagem sem data: não filtra por recência |
| **Catho** | HTML de `/vagas/<termo>/` | "Quero me candidatar" / "Enviar currículo" | Data só com dia/mês (ano inferido) |
| **Indeed** | Via Firefox (HTTP direto leva 403) | Só "Candidatura simplificada" | Vaga que vai ao site da empresa é pulada; CAPTCHA chama você |

Sólides e LinkedIn: **ainda não implementados** (o nome na lista de portais é ignorado com aviso). Os drivers de
ProgramaThor, Catho e Indeed usam um laço genérico (`PortalWebDriver`): detecta sucesso/login/CAPTCHA/"já candidatou",
preenche campos de texto e clica no próximo botão. Só campos de texto são preenchidos; **radio/select ainda não**.

## Sons

Tocam em fila, um por vez, com o volume do stream em 300% (o do sistema é o que você configurou).

| Evento | Som |
|---|---|
| Preciso de você (pendência) | `complete` |
| Candidatura enviada | `bell` |
| Vaga pulada | `message-new-instant` (no máximo um a cada 3 s) |
| Login ou CAPTCHA | `phone-incoming-call` |
| Fim da sessão (não toca se você parou) | `alarm-clock-elapsed` |

## Login automático

Quando o portal pede login durante a busca, o robô entra sozinho com a conta **ativa** do portal (tela *Credenciais*) e
segue até o fim. Sem login salvo, ele chama você, **captura o que você digita** no formulário do Firefox do robô e salva
no cofre; nas próximas vezes entra sozinho. Se a senha salva falhar (2 tentativas por vaga), chama você de novo e salva o
login novo. CAPTCHA e verificação em duas etapas sempre chamam você. Funciona para formulário com usuário e senha na mesma
página ou em duas etapas (`LoginAutomatico`).

## Segurança

O app guarda CPF, nome da mãe, CVs e a chave da IA, e pode enviar candidaturas. Por isso:

- Escuta só em `127.0.0.1`.
- Um filtro (`OrigemLocalFilter`) aceita apenas `Host` de loopback (barra DNS rebinding) e exige `Origin`/`Referer` de
  loopback em todo POST (barra CSRF vindo de outros sites abertos no navegador).
- A chave da IA nunca é renderizada na página.
- Não é autenticação: **qualquer processo na sua máquina** ainda acessa o app.
- Dados sensíveis (RG, pai, banco) nunca vão à IA e sempre chamam você.
- **Credenciais dos portais** (tela *Credenciais*): senha cifrada com AES-256-GCM no SQLite; a chave fica em
  `data/cofre.key` (permissão 600), separada do banco. A senha nunca aparece na tela nem vai à IA. Quem já tem acesso
  à sua conta de usuário na máquina consegue ler a chave: não é proteção contra isso.
- `data/` (banco, perfil do Firefox, logs) está no `.gitignore`.

## Dados e banco

SQLite em `data/searchjob.db` (criado e migrado por `schema.sql` na subida).

| Tabela | Conteúdo |
|---|---|
| `dado_fixo`, `preferencia`, `config` | Chave/valor editáveis na tela |
| `cv` | Nome, PDF, texto extraído, palavras-chave |
| `situacao` | Regras: pergunta, resposta/instrução, `tipo` (`pergunta`/`personalizada`), `origem` (`md`/`usuario`/`ia`/`ia_confirmada`), `status` (`ativa`/`inativa`/`pendente_confirmacao`/`aguardando_usuario`), nº de usos |
| `candidatura` | Portal, empresa, cargo, URL, CV usado, status, motivo, data |
| `texto_gerado` | Textos livres escritos pela IA, por vaga |

## Testes e qualidade

```bash
mvn test      # unitários + integração (43 testes)
mvn verify    # tudo acima + análise estática + e2e (58 testes no total)
```

| Camada | O que cobre | Onde |
|---|---|---|
| **Unitários** (sem Spring/banco) | Normalização e equivalência de textos, chave de vaga, escolha de CV (`casa`/`pontuar`), `FiltroVaga` com `KvRepo` simulado, parsers de ProgramaThor/Catho/Gupy com HTML fixo, parsing do `.md`, filtro de origem | `TextoTest`, `ChaveVagaTest`, `EscolhaCvUnitTest`, `FiltroVagaUnitTest`, `ParsersPortaisTest`, `MdParsingTest`, `OrigemLoopbackTest` |
| **Integração** (Spring + SQLite temporário) | Fluxo de situações (aplica, IA resolve→confirma, IA não resolve→usuário, sem duplicar pendência, texto sob medida), importação do `.md` real, filtros e escolha de CV, filtro de segurança, busca real no Gupy e nas listagens (abortam sem rede) | `SituacaoServiceTest`, `MdImporterTest`, `FiltroEscolhaTest`, `OrigemLocalFilterTest`, `GupyDriverTest`, `PortaisParseTest` |
| **Estática** | `javac -Xlint:all -Werror`, **Checkstyle** (`checkstyle.xml`) e **SpotBugs** (`spotbugs-exclude.xml`); falham o build | `mvn verify` |
| **E2E — sessão** | Iniciar → busca → filtros → CV → candidatura → histórico, pela interface HTTP com um portal simulado: portal deslogado é pulado, retomada sem duplicar, dry-run, dedupe entre formatos de link | `e2e/SessaoE2ETest` |
| **E2E — interface** | Todas as telas e formulários (CRUD de situações e CVs, pendências, modo, dry-run, chave secreta, filtros, botão encerrar) | `e2e/InterfaceE2ETest` |
| **E2E — navegador** | **Firefox headless real** contra um formulário local: preenche campo curto pela resposta salva e textarea pela IA, para antes do envio no dry-run, envia de verdade, distingue campo de senha visível/oculto e reconhece "já se candidatou". É ignorado se o Firefox/geckodriver não subirem | `e2e/FormularioFirefoxE2ETest` |

Os e2e rodam no `verify` (failsafe), separados do `test` (surefire). Rodar um só: `mvn verify -Dit.test=SessaoE2ETest`.

O e2e de navegador usa o Firefox do snap pelo executável real (`/snap/firefox/current/usr/lib/firefox/firefox`),
detectado automaticamente, porque o atalho `/snap/bin/firefox` não é aceito pelo geckodriver; dá para forçar outro com
`-Dsearchjob.firefox-binary=...`. Outras propriedades: `-Dsearchjob.headless=true`, `-Dsearchjob.firefox-profile=<pasta>`.

## Estrutura do código

```
src/main/java/com/huber/searchjob/
  SearchJobApplication.java
  db/        KvRepo, CvRepo, SituacaoRepo, CandidaturaRepo, TextoGeradoRepo (JdbcClient)
  importer/  MdImporter (+ ImportRunner): .md e PDFs → banco, uma vez
  engine/    Orquestrador      sessão, thread, contadores, log, dedupe
             FabricaDrivers    cria o driver de cada portal (substituível nos testes)
             PortalDriver      contrato: buscar + candidatar
             GupyDriver, PortalWebDriver (base genérica), ProgramaThorDriver, CathoDriver, IndeedDriver
             NavegadorFirefox  Selenium, perfil persistente, headless opcional
             FiltroVaga, EscolhaCv, ChaveVaga, Texto, Html
             SituacaoService   aprendizado de situações e textos sob medida
             CvContexto        monta o contexto (sem dados sensíveis) para a IA
             IaClient         HTTP chat/completions
             Aviso             sons em fila
             Encerramento      derruba o serviço
  web/       PainelController, CadastrosController, OrigemLocalFilter
src/main/resources/  schema.sql, application.yml, templates/ (Thymeleaf)
```

## Estado e limitações

Funciona e está testado (unitário, integração, e2e e estática): importação, telas, aprendizado de situações, textos sob
medida (com a IA simulada), escolha de CV, filtros, dedupe, dry-run, sons, botão de encerrar, segurança local e o laço
de candidatura num Firefox real contra um formulário local.

**Ainda não validado / limitado:**

- A **IA nunca foi chamada de verdade** (URL e modelo são valores iniciais a confirmar; nos testes ela é simulada).
- O **fluxo real de cada portal** (Gupy, ProgramaThor, Catho, Indeed) nunca rodou ponta a ponta, pois depende do seu
  login. Os seletores de botão são o ponto mais provável de ajuste. Comece sempre com dry-run e `max_vagas` pequeno.
- **Radio/select/checkbox** (ex.: perguntas Sim/Não) não são preenchidos; a vaga cai em erro ou em "preciso de você".
- **Sólides e LinkedIn** não existem ainda. Não há tela para **subir um CV novo** (os CVs vêm da pasta de origem).
- Não há teto de candidaturas por rodada (`max_vagas` limita só a listagem).
- Vagas com `erro` são tentadas de novo a cada rodada.
- Não há login no app; a proteção é só contra acesso externo e CSRF.
