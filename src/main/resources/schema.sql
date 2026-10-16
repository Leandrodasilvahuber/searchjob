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
  data TEXT NOT NULL DEFAULT (date('now'))
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
