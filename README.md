# the-next-thing

Self-hosted link queue. Push links from Emacs, the Android share sheet or curl; the Android
app shows the newest unread link or a random one, and you mark each *read* or *uninteresting*.

## Server

```
cd server && go test ./...
echo secret > token.txt
go run ./cmd/the-next-thing -token-file token.txt -db dev.db
curl -H 'Authorization: Bearer secret' -d '{"url":"https://example.com"}' localhost:8787/api/items
```

Flags: `-listen` (default `127.0.0.1:8787`), `-db`, `-token-file`, `-insecure-no-auth`.
API: `POST/GET /api/items`, `GET /api/items/latest|random`, `PATCH|DELETE /api/items/{id}`,
unauthenticated `GET /healthz`. Timestamps are unix milliseconds.

## NixOS

```nix
{
  inputs.the-next-thing.url = "github:you/the-next-thing";
  # ...
  imports = [ inputs.the-next-thing.nixosModules.default ];
  services.the-next-thing = {
    enable = true;
    tokenFile = config.age.secrets.the-next-thing.path;
  };
  services.nginx.virtualHosts."things.example.org" = {
    enableACME = true;
    forceSSL = true;
    locations."/".proxyPass = "http://127.0.0.1:8787";
  };
}
```

## Emacs

```elisp
(use-package the-next-thing
  :load-path "path/to/emacs"   ; or packages.emacs-package from the flake
  :custom (the-next-thing-server-url "https://things.example.org"))
;; token: ~/.authinfo  ->  machine things.example.org password <token>
```

Commands: `the-next-thing-add`, `-add-at-point`, `-add-region`, `-list`.
In the list: `RET` open, `r` read, `x` uninteresting, `u` unread, `f` filter, `g` refresh, `d` delete.

## Android

`cd android && nix develop -c ./gradlew assembleDebug testDebugUnitTest`; the APK is in
`app/build/outputs/apk/debug/`. Set the server URL and token in Settings.
