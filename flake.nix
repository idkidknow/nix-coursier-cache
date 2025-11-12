{
  inputs = {
    nixpkgs.url = "github:NixOS/nixpkgs/nixos-unstable";
  };
  outputs =
    { nixpkgs, ... }:
    let
      pkgs = import nixpkgs {
        system = "x86_64-linux";
      };
    in
    {
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
        ];

        shellHook = ''
          export LD_LIBRARY_PATH="$LD_LIBRARY_PATH:${pkgs.lib.makeLibraryPath buildInputs}"
        '';
      };
    };
}
