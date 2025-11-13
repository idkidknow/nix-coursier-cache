{
  inputs = {
    nixpkgs.url = "github:NixOS/nixpkgs/nixos-unstable";
  };
  outputs =
    { nixpkgs, ... }:
    let
      pkgs = nixpkgs.legacyPackages.x86_64-linux;
      lib = pkgs.lib;

      artifacts-json = builtins.fromJSON (builtins.readFile ./coursier.json);
      artifacts = lib.map (
        artifact:
        let
          src = pkgs.fetchurl {
            inherit (artifact) url hash;
          };
        in
        pkgs.runCommand (lib.last (lib.splitString "/" artifact.path)) { } ''
          target=$out/share/coursier/v1/${artifact.path}
          dir="$(dirname "$target")"
          mkdir -p $dir
          cp ${src} $target
        ''
      ) artifacts-json;

      coursier-cache = pkgs.symlinkJoin {
        name = "nix-coursier-cache-coursier-cache";
        paths = artifacts;
      };
    in
    {
      packages.x86_64-linux.default = pkgs.stdenv.mkDerivation {
        pname = "nix-coursier-cache";
        version = "0-unstable-2025-11-12";
        src = ./.;

        nativeBuildInputs = [
          pkgs.which
          pkgs.llvmPackages_latest.clang
          pkgs.openjdk25_headless
          pkgs.mill
        ];

        buildPhase = ''
          runHook preBuild

          export SCALANATIVE_MODE=release-full
          export SCALANATIVE_LTO=thin
          export COURSIER_CACHE=${coursier-cache}/share/coursier/v1
          mill nativeLink

          runHook postBuild
        '';

        installPhase = ''
          runHook preInstall

          mkdir -p $out/bin
          install -Dm755 out/nativeLink.dest/out $out/bin/nix-coursier-cache

          runHook postInstall
        '';
      };
      devShells.x86_64-linux.default = pkgs.mkShell rec {
        buildInputs = with pkgs; [
          clang-tools
          llvmPackages_latest.clang
          openjdk25
          boehmgc
          libunwind
          zlib
          openssl
          mill
          coursier
          metals
        ];

        shellHook = ''
          export LD_LIBRARY_PATH="$LD_LIBRARY_PATH:${pkgs.lib.makeLibraryPath buildInputs}"
        '';
      };
    };
}
