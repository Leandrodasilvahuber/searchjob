# Teste de Respostas por IA — Vaga Fake

> Objetivo: simular uma vaga e gerar prompts para testar o IaClient contra todos os tipos de pergunta
> (múltipla escolha, texto livre, opções do banco, respostas fixas).
> Troque `MODELO` e `CHAVE_API` para testar diferentes LLMs via OpenRouter.
> Não altera nenhum código — é um documento de teste manual.

---

## 1. Dados de Simulação

### 1.1 Dados fixos (`dado_fixo` — tabela usada pelo `CvContexto.dadosParaPrompt()`)

```
nome=Leandro Silva
email=leandro.silva@email.com
linkedin=https://linkedin.com/in/leandro-silva
github=https://github.com/leandro-silva
telefone=(11) 98765-4321
cidade=São Paulo
estado=SP
pretensao_salarial=8000
nivel_experiencia=Pleno a Sênior
disponibilidade=Imediata
```

### 1.2 Texto do CV (`CvContexto.textoCvs()`)

```
### CV Profissional
Desenvolvedor Full Stack com 4 anos de experiência em Node.js, React, Docker, PostgreSQL e MongoDB.
Já trabalhei com microserviços REST, testes automatizados (Jest, Supertest) e CI/CD no GitHub Actions.
Participei de migração de monolito para arquitetura baseada em containers usando Docker e Kubernetes.
Tecnologias-chave: Node.js, Express, React, Hooks, Redux, PostgreSQL, MongoDB, Docker, Kafka, Jest, Git, Linux.

Experiência Relevante:
- TechInova (2021-2023): Node.js + Express, 8 microserviços Dockerizados, CI/CD no GitHub Actions.
- DevFullStack (2023-2024): Líder técnico de squad React + Node.js, 12 devs. Redução de 30% no tempo de build.
- EduTech (2020-2021): Desenvolvimento full-stack em React/Node.js, API REST com 400k requisições/dia.
```

### 1.3 Respostas salvas mais parecidas (`CvContexto.respostasSalvas(pergunta, 3)`)

```
- "Você tem experiência com Node.js?" => "Sim, possuo 3 anos de experiência com Node.js e Express, desenvolvendo APIs REST."
- "Qual seu nível de conhecimento em Docker?" => "Avançado, uso diariamente para containerização e orquestração."
- "Conte um pouco sobre sua experiência com testes automatizados." => "Escrevi mais de 200 testes unitários e de integração com Jest e Supertest."
```

### 1.4 Contexto da vaga (simulado do Orquestrador)

```
TechInova Soluções busca Desenvolvedor Full Stack Sênior.
Procuramos pessoa com experiência em Node.js, React, Docker, PostgreSQL, MongoDB, testes automatizados e CI/CD.
Trabalhamos com metodologia ágil, colaboração e entrega contínua.
```

---

## 2. Prompt que o Código Envia (Template Real)

> Este é o prompt exatamente como o `SituacaoService.gerarPersonalizado` monta e o `IaClient.conversar` envia:
> - System prompt: `INSTRUCAO_PADRAO`
> - User prompt: inclui contexto do CV, dados fixos, respostas salvas + vaga + pergunta + opções

```
[System]
Responda direto ao que foi perguntado, de forma natural e curta, citando só a experiência do currículo que importa para esta vaga.

[User]
Contexto do currículo:
### CV Profissional
Desenvolvedor Full Stack com 4 anos de experiência em Node.js, React, Docker, PostgreSQL e MongoDB.
Já trabalhei com microserviços REST, testes automatizados (Jest, Supertest) e CI/CD no GitHub Actions.
Participei de migração de monolito para arquitetura baseada em containers usando Docker e Kubernetes.
Tecnologias-chave: Node.js, Express, React, Hooks, Redux, PostgreSQL, MongoDB, Docker, Kafka, Jest, Git, Linux.

Experiência Relevante:
- TechInova (2021-2023): Node.js + Express, 8 microserviços Dockerizados, CI/CD no GitHub Actions.
- DevFullStack (2023-2024): Líder técnico de squad React + Node.js, 12 devs. Redução de 30% no tempo de build.
- EduTech (2020-2021): Desenvolvimento full-stack em React/Node.js, API REST com 400k requisições/dia.

Dados do candidato:
{
  "nome": "Leandro Silva",
  "email": "leandro.silva@email.com",
  "linkedin": "https://linkedin.com/in/leandro-silva",
  "github": "https://github.com/leandro-silva",
  "telefone": "(11) 98765-4321",
  "cidade": "São Paulo",
  "estado": "SP",
  "pretensao_salarial": "8000",
  "nivel_experiencia": "Pleno a Sênior",
  "disponibilidade": "Imediata"
}

Respostas salvas mais similares:
- "Você tem experiência com Node.js?" => "Sim, possuo 3 anos de experiência com Node.js e Express, desenvolvendo APIs REST."
- "Qual seu nível de conhecimento em Docker?" => "Avançado, uso diariamente para containerização e orquestração."
- "Conte um pouco sobre sua experiência com testes automatizados." => "Escrevi mais de 200 testes unitários e de integração com Jest e Supertest."

Contexto da vaga:
TechInova Soluções busca Desenvolvedor Full Stack Sênior.
Procuramos pessoa com experiência em Node.js, React, Docker, PostgreSQL, MongoDB, testes automatizados e CI/CD.
Trabalhamos com metodologia ágil, colaboração e entrega contínua.

Pergunta: {{PERGUNTA_AQUI}}
```

---

## 3. Casos de Teste (simule cada um)

> Para cada caso, cole o prompt do template (§2) com a pergunta preenchida e envie via:
> - `POST /pendencias/{id}/gerar` (botão "✨ Gerar com a IA" no template `pendencias.html`)
> - OU chamando `IaClient.conversar(system, user)` diretamente em teste unitário
> - Troque `MODELO` na config `openrouter_modelos` para testar diferentes LLMs

### 3.1 Pergunta de múltipla escolha (Radios.java)

- **Pergunta:** `Você tem experiência com Node.js?`
- **Formato detectado:** `(múltipla escolha) [opções: Sim | Não | Parcialmente]`
- **Prompt enviado:**

```
... (template §2) ...
Pergunta: Você tem experiência com Node.js?
```

- **Respota esperada (IA):** Sim, tenho experiência com Node.js, tendo desenvolvido APIs REST com Express por 4 anos.
- **Observação:** A UI mostra botões `Sim | Não | Parcialmente`; a resposta da IA vem como rascunho no textarea.

### 3.2 Pergunta de texto livre (discursiva)

- **Palavras-chave:** `apresent`, `conte`, `fale sobre`, `descreva`, `por que`, `motivo`, `motivação`, `diferencial`, `mensagem para`, `sobre você`
- **Pergunta:** `Conte um pouco sobre como você liderou uma migração de monolito para microserviços com Docker.`
- **Prompt enviado:**

```
... (template §2) ...
Pergunta: Conte um pouco sobre como você liderou uma migração de monolito para microserviços com Docker.
```

- **Resposta esperada (IA):** Em minha experiência na TechInova, lideramos a migração de um monolito PHP para 8 microserviços Node.js, utilizando Docker para containerização e Kubernetes para orquestração. Isso reduziu o tempo de build em 30% e melhorou a escalabilidade.
- **Observação:** A instrução `INSTRUCAO_PADRAO` garante resposta curta e focada no CV.

### 3.3 Pergunta com opções do banco (Selects.java)

- **Pergunta:** `Qual seu nível de conhecimento em Docker?`
- **Formato detectado:** `[opções: Avançado | Intermediário | Básico]`
- **Prompt enviado:**

```
... (template §2) ...
Pergunta: Qual seu nível de conhecimento em Docker?
```

- **Resposta esperada (IA):** Avançado. Já usei Docker diariamente para containerizar aplicações, criar imagens otimizadas e orquestrar ambientes de desenvolvimento e produção.

### 3.4 Pergunta fátil — dado objetivo (FATUAIS)

- **Palavras-chave:** `salária`, `remunera`, `pretensão`, `expectativa`, `matrícula`, `tempo com a empresa`, `início`
- **Pergunta:** `Qual seu salário esperado?`
- **Prompt enviado:**

```
... (template §2) ...
Pergunta: Qual seu salário esperado?
```

- **Resposta esperada (IA):** R$ 8.000,00.
- **Observação:** Como `pretensao_salarial` já está no dado_fixo, a IA retorna o valor direto.

### 3.5 Pergunta de resposta fixa (já salva no banco)

- **Situação no banco:** `situacao` com `tipo='pergunta'`, `status='ativa'`, `padrao_pergunta='Você concorda com o trabalho remoto?'`, `prompt_ou_resposta='Sim, estou totalmente disponível para trabalho remoto.'`
- **Fluxo:** `SituacaoService.buscar()` encontra a resposta por hash/exatidão → **não chama a IA**.
- **Resposta esperada (banco):** Sim, estou totalmente disponível para trabalho remoto.
- **Observação:** A resposta vem direto de `prompt_ou_resposta` na tabela `situacao`.

### 3.6 Pergunta sensível (bloqueada)

- **Pergunta:** `Qual é o seu CPF?`
- **Fluxo:** `SituacaoService.sensivel()` detecta `cpf` → **pergunta é bloqueada**, não enviada à IA.
- **Resposta esperada:** A pergunta não aparece no prompt / a candidatura trata via outro mecanismo.
- **Observação:** Campos como `cpf`, `rg`, `nome do pai`, `senha`, `dados bancários` são filtrados por `CvContexto.sensivel()`.

---

## 4. Tabela de Verificação

| Caso | Pergunta | Tipo | IA chamada? | Formato | Resultado |
|------|----------|------|-------------|---------|-----------|
| 3.1 | Você tem experiência com Node.js? | Múltipla escolha | Sim | Rascunho no textarea | ✅ `Sim` |
| 3.2 | Conte sobre migração monolito → microserviços | Texto livre (discursiva) | Sim | Texto narrativo | ⚠️ inventou liderança e atribuiu errado os 30% |
| 3.3 | Qual seu nível de conhecimento em Docker? | Select | Sim | Uma opção | ✅ `Avançado` |
| 3.4 | Qual seu salário esperado? | Fátil | Sim (ou não, se salvo) | Valor curto | ❌ frase longa em vez de `8.000` |
| 3.5 | Você concorda com o trabalho remoto? | Resposta fixa | **Não** | Texto do banco | `[  ]` |
| 3.6 | Qual é o seu CPF? | Sensível | **Não** | Bloqueada | `[  ]` |

---

## 4.1 Resultado real — Groq `openai/gpt-oss-120b` (temperature 0.1, 2026-10-19)

> Prompt: template §2. Nos casos com opções foi anexado `[opções: ...]` + `(Responda com o texto exato de UMA das opções.)`, como o `montarPedido` faz.

**3.1** — `Sim` ✅

**3.2** — ⚠️
> Na TechInova (2021‑2023) liderei a migração do monolito legado para uma arquitetura de microserviços. Planejei a divisão funcional da aplicação, criei 8 novos serviços Node.js + Express, defini contratos de API e configurei Dockerfiles padronizados. Usei Docker Compose para validar localmente e, em seguida, orquestrei os containers no Kubernetes, garantindo escalabilidade e alta disponibilidade. Automatizei o pipeline de CI/CD no GitHub Actions, incluindo build, testes com Jest/Supertest e deploy contínuo, o que reduziu o tempo de entrega em 30 % e eliminou a dependência do ambiente monolítico.

Problemas: o CV diz "participei", não "liderei" (a pergunta induz e a IA aceitou); Docker Compose e "contratos de API" não estão no CV; os 30% são da DevFullStack (tempo de build), não da TechInova. Também está longo para "curta".

**3.3** — `Avançado` ✅

**3.4** — ❌
> Minha pretensão salarial é R$ 8.000, alinhada aos 4 anos de experiência em Node.js, React, Docker, PostgreSQL, MongoDB, testes automatizados e CI/CD que possuo.

Esperado só `8.000`. Com o template §2 (só `INSTRUCAO_PADRAO`) não há regra de formato para fatuais — no código real essa pergunta passa pelo `SYSTEM` JSON, que tem a regra "só o valor mensal, sem R$".

**3.5 / 3.6** — não chamam a IA (banco / bloqueio de sensível), não testados aqui.

## 4.2 Resultado com system prompt reforçado — Groq `openai/gpt-oss-120b` (2026-10-19)

> Mesmo user prompt do §4.1, trocando o system por:

```
Você preenche formulários de candidatura em nome de um desenvolvedor sênior. Tom: seguro, objetivo, natural, como um dev sênior escreveria.
Regras:
- Use SOMENTE fatos do currículo, dos dados do candidato e das respostas salvas. Nunca invente tecnologia, cargo, número ou responsabilidade.
- Não aceite premissas falsas da pergunta: se a pergunta diz que você liderou algo e o CV diz que participou, responda "participei". Cada número fica na empresa em que aparece no CV.
- Pergunta com opções: responda só com o texto exato de UMA opção, nada mais.
- Dado objetivo (salário, pretensão, anos, links, telefone): só o valor. Salário: só o número mensal com ponto de milhar, sem R$ e sem texto (ex.: 8.000). Anos: só o número.
- Pergunta discursiva (conte, descreva, fale sobre): 2 a 3 frases curtas, no máximo 300 caracteres, sem floreio.
- Responda em português, sem aspas, sem markdown.
```

| Caso | Resposta | Resultado |
|------|----------|-----------|
| 3.1 | `Sim` | ✅ |
| 3.2 | Participei da migração de um monolito para microserviços, containerizando os serviços com Docker e orquestrando com Kubernetes. Contribuí na definição da arquitetura, divisão das funcionalidades e automação do deploy. | ✅ corrigiu "liderei" e não misturou os 30%; "definição da arquitetura" ainda é um leve exagero |
| 3.3 | `Avançado` | ✅ |
| 3.4 | `8.000` | ✅ |

---

## 5. Como Testar

1. **Popular dados de teste** (via SQL direto ou API interna):
   ```sql
   -- Inserir respostas salvas (simulando situacoes ativas)
   INSERT INTO situacao (portal, tipo, padrao_pergunta, hash, prompt_ou_resposta, origem, status, usos, contexto) VALUES
   ('catho', 'pergunta', 'Você tem experiência com Node.js?', '<hash>', 'Sim, possuo 3 anos de experiência com Node.js e Express, desenvolvendo APIs REST.', 'ia_confirmada', 'ativa', 0, NULL),
   ('catho', 'pergunta', 'Conte um pouco sobre como você liderou uma migração de monolito para microserviços com Docker.', '<hash>', 'Em minha experiência na TechInova, lideramos a migração...', 'ia_confirmada', 'ativa', 0, NULL),
   ('catho', 'pergunta', 'Qual seu nível de conhecimento em Docker?', '<hash>', 'Avançado, uso diariamente para containerização e orquestração.', 'ia_confirmada', 'ativa', 0, NULL),
   ('catho', 'pergunta', 'Você concorda com o trabalho remoto?', '<hash>', 'Sim, estou totalmente disponível para trabalho remoto.', 'ia_confirmada', 'ativa', 0, NULL);

   -- Inserir dados fixos
   INSERT INTO dado_fixo (chave, valor) VALUES
   ('nome', 'Leandro Silva'),
   ('email', 'leandro.silva@email.com'),
   ('linkedin', 'https://linkedin.com/in/leandro-silva'),
   ('github', 'https://github.com/leandro-silva'),
   ('telefone', '(11) 98765-4321'),
   ('cidade', 'São Paulo'),
   ('estado', 'SP'),
   ('pretensao_salarial', '8000'),
   ('nivel_experiencia', 'Pleno a Sênior'),
   ('disponibilidade', 'Imediata');
   ```

2. **Gerar hashes** com `Texto.hash(pergunta)` para cada pergunta.

3. **Substituir `{{PERGUNTA_AQUI}}`** no template §2 por uma das perguntas acima.

4. **Enviar ao IaClient** e copiar a resposta da IA aqui.

---

## 6. Referências de Código

| Arquivo | Método/Componente | Função |
|---------|-------------------|--------|
| `IaClient.java` | `conversar(system, user)` | Envia prompt e devolve texto da resposta |
| `IaClient.java` | `conversarJson(system, user)` | Pega JSON da resposta da IA |
| `SituacaoService.java` | `resolver()` / `gerarPersonalizado()` | Monta sistema + prompt, decide se usa IA ou busca no banco |
| `SituacaoService.java` | `SENSIVEL[]` / `DISCURSIVAS[]` / `FATUAIS[]` | Detecção de tipo de pergunta |
| `CvContexto.java` | `dadosParaPrompt()` | Retorna dados fixos filtrados (sem CPF/RG/senha) |
| `CvContexto.java` | `respostasSalvas(questao, max)` | Busca N respostas mais similares no banco |
| `Texto.java` | `hash()`, `normalizar()`, `similaridade()` | Hash da pergunta, normalização e comparação de texto |
| `Radios.java` | `temRadios()` / `opcoes()` | Detecta múltipla escolha |
| `Selects.java` | `opcoesSelect()`, `ehSelect()` | Detecta selects com opções vindas do HTML |
| `Checkboxes.java` | `temCheckbox()` | Detecta perguntas com múltiplas respostas |
| `schema.sql` | `situacao`, `dado_fixo`, `preferencia` | Tabelas de perguntas/respostas e dados fixos |
| `pendencias.html` | "✨ Gerar com a IA" | Botão que chama `POST /pendencias/{id}/gerar` |
