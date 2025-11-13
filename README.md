# nix-coursier-cache

For Nix, generate lockfiles of scala projects that use [Coursier](https://get-coursier.io/) to fetch artifacts (scala-cli/mill/sbt).

## Usage

- Pick an empty directory, e.g. `CACHE=$PWD/cache/`
- `export COURSIER_CACHE=$CACHE`
- Fetch artifacts (`cs fetch`, `mill` or `scala-cli`)
- `nix run github:idkidknow/nix-coursier-cache -- -c $CACHE -o coursier.json`.
This generates `coursier.json` which is an array of
`{url: string, path: string, hash: string}`.
`url` and `hash` can be used in `pkgs.fetchurl`.
`path` is a relative path to `$CACHE`.
