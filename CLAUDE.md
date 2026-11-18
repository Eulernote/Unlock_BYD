# Unlock BYD — regras do projeto

- O nome do app no carro é sempre **Unlock BYD** (`app_name` em `app/src/main/res/values/strings.xml`). Não alterar.
- O APK do app se chama sempre **`Unlock_BYD.apk`** (CI, `dist/`, R2 em `app/Unlock_BYD.apk` e `app_version.json`).
- Nova versão do app: aumentar `versionCode`/`versionName` em `app/build.gradle.kts`, testar em um carro, e só então copiar o APK para `dist/Unlock_BYD.apk` e subir o `versionCode` no `app_version.json` (isso dispara a auto-atualização em todos os carros).
- Assinatura: sempre com `app/keystore.jks` (mesma chave), senão os carros recusam a atualização.
- Worker (painel/API) fica em `worker/worker.js`; publicado pelo workflow `cloudflare.yml` a cada push no `main`.
- **Catálogo:** a fonte da verdade é o `catalog.json` no R2, editado pela aba **Loja** do painel (backups em `backups/` no R2). O `catalog.json` do repositório ficou congelado só para carros na v12 (que ainda leem do GitHub); não é mais enviado ao R2.
- Para enviar arquivos ao R2 pelo git: commit em `r2/<caminho>` na branch `r2-envio` (workflow `enviar-r2.yml`).
