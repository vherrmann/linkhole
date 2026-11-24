{ self }:
{ config, lib, pkgs, ... }:
let
  cfg = config.services.linkhole;
in
{
  options.services.linkhole = {
    enable = lib.mkEnableOption "linkhole link queue server";

    package = lib.mkOption {
      type = lib.types.package;
      default = self.packages.${pkgs.stdenv.hostPlatform.system}.server;
      defaultText = lib.literalExpression "linkhole flake's packages.server";
      description = "The server package to run.";
    };

    listenAddress = lib.mkOption {
      type = lib.types.str;
      default = "127.0.0.1";
      description = "Address to listen on. Put a TLS reverse proxy in front for remote access.";
    };

    port = lib.mkOption {
      type = lib.types.port;
      default = 8787;
      description = "TCP port to listen on.";
    };

    tokenFile = lib.mkOption {
      type = lib.types.path;
      example = "/run/agenix/linkhole-token";
      description = ''
        File containing the bearer token. Loaded via systemd LoadCredential,
        so it only needs to be readable by root.
      '';
    };

    openFirewall = lib.mkOption {
      type = lib.types.bool;
      default = false;
      description = "Open the listen port in the firewall.";
    };
  };

  config = lib.mkIf cfg.enable {
    systemd.services.linkhole = {
      description = "linkhole link queue";
      wantedBy = [ "multi-user.target" ];
      after = [ "network-online.target" ];
      wants = [ "network-online.target" ];
      serviceConfig = {
        ExecStart = lib.escapeShellArgs [
          (lib.getExe cfg.package)
          "-listen"
          "${cfg.listenAddress}:${toString cfg.port}"
          "-token-file"
          "%d/token"
        ];
        LoadCredential = "token:${cfg.tokenFile}";
        DynamicUser = true;
        StateDirectory = "linkhole";
        Restart = "on-failure";

        NoNewPrivileges = true;
        ProtectSystem = "strict";
        ProtectHome = true;
        PrivateTmp = true;
        PrivateDevices = true;
        ProtectKernelTunables = true;
        ProtectKernelModules = true;
        ProtectKernelLogs = true;
        ProtectControlGroups = true;
        ProtectClock = true;
        ProtectHostname = true;
        RestrictNamespaces = true;
        RestrictRealtime = true;
        RestrictSUIDSGID = true;
        LockPersonality = true;
        MemoryDenyWriteExecute = true;
        RestrictAddressFamilies = [ "AF_INET" "AF_INET6" "AF_UNIX" ];
        SystemCallArchitectures = "native";
        CapabilityBoundingSet = "";
        UMask = "0077";
      };
    };

    networking.firewall.allowedTCPPorts = lib.mkIf cfg.openFirewall [ cfg.port ];
  };
}
