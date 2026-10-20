CREATE TABLE IF NOT EXISTS dado_fixo (chave TEXT PRIMARY KEY, valor TEXT NOT NULL);
CREATE TABLE IF NOT EXISTS preferencia (chave TEXT PRIMARY KEY, valor TEXT NOT NULL);
CREATE TABLE IF NOT EXISTS config (chave TEXT PRIMARY KEY, valor TEXT NOT NULL);
CREATE TABLE IF NOT EXISTS cv (
  id INTEGER PRIMARY KEY AUTOINCREMENT,
  nome TEXT NOT NULL UNIQUE,
  arquivo_pdf TEXT,
  texto_extraido TEXT,
  regras_stack TEXT
);
CREATE TABLE IF NOT EXISTS situacao (
  id INTEGER PRIMARY KEY AUTOINCREMENT,
  portal TEXT,
  tipo TEXT NOT NULL DEFAULT 'pergunta',
  padrao_pergunta TEXT NOT NULL,
  hash TEXT NOT NULL,
  prompt_ou_resposta TEXT,
  origem TEXT NOT NULL,
  status TEXT NOT NULL,
  usos INTEGER NOT NULL DEFAULT 0,
  contexto TEXT,
  criado_em TEXT NOT NULL DEFAULT (datetime('now'))
);
CREATE INDEX IF NOT EXISTS idx_situacao_hash ON situacao(hash);
CREATE TABLE IF NOT EXISTS candidatura (
  id INTEGER PRIMARY KEY AUTOINCREMENT,
  portal TEXT,
  empresa TEXT,
  cargo TEXT,
  url TEXT,
  cv_usado TEXT,
  status TEXT NOT NULL,
  motivo TEXT,
  data TEXT NOT NULL DEFAULT (date('now')),
  descricao TEXT
);
CREATE INDEX IF NOT EXISTS idx_cand_url ON candidatura(url);
CREATE TABLE IF NOT EXISTS texto_gerado (
  id INTEGER PRIMARY KEY AUTOINCREMENT,
  portal TEXT,
  pergunta TEXT,
  vaga TEXT,
  cv TEXT,
  texto TEXT,
  criado_em TEXT NOT NULL DEFAULT (datetime('now'))
);
CREATE TABLE IF NOT EXISTS credencial (
  id INTEGER PRIMARY KEY AUTOINCREMENT,
  portal TEXT NOT NULL,
  usuario TEXT NOT NULL,
  senha_cifrada TEXT NOT NULL,
  ativa INTEGER NOT NULL DEFAULT 0,
  atualizado_em TEXT NOT NULL DEFAULT (datetime('now')),
  UNIQUE (portal, usuario)
);
-- portais e como o robô entra em cada um: google | linkedin | portal (e-mail e senha do próprio portal)
CREATE TABLE IF NOT EXISTS portal (
  chave TEXT PRIMARY KEY,
  nome TEXT NOT NULL,
  modo_login TEXT NOT NULL DEFAULT 'google',
  ordem INTEGER NOT NULL DEFAULT 0
);
-- provedores de IA (API compatível com OpenAI), tentados por ordem; sem chave, inativo ou sem modelos fica fora do fallback
CREATE TABLE IF NOT EXISTS ia_provedor (
  id INTEGER PRIMARY KEY AUTOINCREMENT,
  nome TEXT NOT NULL UNIQUE,
  url TEXT NOT NULL,
  chave_cifrada TEXT NOT NULL DEFAULT '',
  modelos TEXT NOT NULL DEFAULT '',
  ordem INTEGER NOT NULL DEFAULT 0,
  ativo INTEGER NOT NULL DEFAULT 1,
  intervalo_ms INTEGER NOT NULL DEFAULT 3000,
  timeout_s INTEGER NOT NULL DEFAULT 120,
  tentativas INTEGER NOT NULL DEFAULT 1
);
-- instrução extra de cada modelo de IA, enviada junto com todo pedido a ele (corrige manias do modelo)
CREATE TABLE IF NOT EXISTS ia_modelo_prompt (
  modelo TEXT PRIMARY KEY,
  prompt TEXT NOT NULL DEFAULT ''
);
