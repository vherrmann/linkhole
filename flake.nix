{
  description = "the-next-thing: self-hosted link queue (Go server, Emacs client, Android app)";

  inputs = {
    flake-utils.url = "github:numtide/flake-utils";
    nixpkgs.url = "github:nixos/nixpkgs/nixpkgs-unstable";
  };

  outputs =
    {
      self,
      nixpkgs,
      flake-utils,
    }:
    let
      version = "0.1.0";
      # Components land incrementally (server -> Nix -> Emacs -> Android);
      # outputs that need a missing directory are simply left out until then.
      has = path: builtins.pathExists path;
    in
    flake-utils.lib.eachDefaultSystem (
      system:
      let
        pkgs = import nixpkgs {
          inherit system;
          config = {
            android_sdk.accept_license = true;
            allowUnfree = true;
          };
        };
        lib = pkgs.lib;

        androidComposition = pkgs.androidenv.composeAndroidPackages {
          buildToolsVersions = [ "35.0.0" ];
          platformVersions = [ "35" ];
          abiVersions = [
            "arm64-v8a"
            "x86_64"
          ];
          includeNDK = false;
          includeEmulator = false;
          includeSystemImages = false;
        };
        androidSdk = androidComposition.androidsdk;
        androidHome = "${androidSdk}/libexec/android-sdk";

        server = pkgs.buildGoModule {
          pname = "the-next-thing";
          inherit version;
          src = ./server;
          # Replace with the hash reported by the first failing build.
          vendorHash = lib.fakeHash;
          subPackages = [ "cmd/the-next-thing" ];
          env.CGO_ENABLED = "0"; # modernc.org/sqlite is pure Go
          meta.mainProgram = "the-next-thing";
        };

        emacs-package = pkgs.emacsPackages.trivialBuild {
          pname = "the-next-thing";
          inherit version;
          src = ./emacs;
        };
      in
      {
        packages =
          lib.optionalAttrs (has ./server) {
            inherit server;
            default = server;
          }
          // lib.optionalAttrs (has ./emacs) { inherit emacs-package; };

        checks = lib.optionalAttrs (has ./server && has ./nix/module.nix && pkgs.stdenv.isLinux) {
          vm = pkgs.testers.runNixOSTest {
            name = "the-next-thing";
            nodes.machine =
              { pkgs, ... }:
              {
                imports = [ self.nixosModules.default ];
                environment.etc."the-next-thing-token".text = "test-token";
                services.the-next-thing = {
                  enable = true;
                  package = self.packages.${system}.server;
                  tokenFile = "/etc/the-next-thing-token";
                };
                environment.systemPackages = [ pkgs.curl ];
              };
            testScript = ''
              machine.wait_for_unit("the-next-thing.service")
              machine.wait_for_open_port(8787)
              machine.succeed("curl -sf http://127.0.0.1:8787/healthz")
              machine.fail("curl -sf http://127.0.0.1:8787/api/items")
              auth = "-H 'Authorization: Bearer test-token'"
              machine.succeed(
                  f"curl -sf {auth} -d '{{\"url\":\"https://example.com/a\"}}' http://127.0.0.1:8787/api/items"
              )
              out = machine.succeed(f"curl -sf {auth} http://127.0.0.1:8787/api/items/latest")
              assert "https://example.com/a" in out, out
            '';
          };
        };

        devShells.default = pkgs.mkShell {
          ANDROID_HOME = androidHome;
          ANDROID_SDK_ROOT = androidHome;
          # Use the Nix-provided aapt2 instead of the one Gradle downloads.
          GRADLE_OPTS = "-Dorg.gradle.project.android.aapt2FromMavenOverride=${androidHome}/build-tools/35.0.0/aapt2";
          JAVA_HOME = "${pkgs.jdk17}";
          packages = [
            # server
            pkgs.go
            pkgs.gopls
            pkgs.sqlite
            pkgs.curl
            pkgs.jq
            # emacs
            pkgs.emacs-nox
            # android
            androidSdk
            pkgs.jdk17
            pkgs.gradle
          ];
        };
      }
    )
    // nixpkgs.lib.optionalAttrs (has ./nix/module.nix) {
      nixosModules.default = import ./nix/module.nix { inherit self; };
    };
}
