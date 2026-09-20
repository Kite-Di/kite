# Security policy

## Supported versions

Kite is at 0.x. Only the latest release receives fixes.

## Reporting a vulnerability

Report privately through GitHub's
[security advisories](https://github.com/Kite-Di/kite/security/advisories/new)
rather than a public issue. Expect an acknowledgement within a week.

Please include what you found, how to reproduce it, and which version you were on.

## What is in scope

Kite's central guarantee is that the dependency graph and its tooling never reach
a shipped app. Anything that breaks it is a security issue, not just a bug:

- graph data, board assets, the inspector, Ktor, or the `INTERNET` permission
  appearing in a release APK
- the decision annotations (`@Root`/`@Bind`/`@Scoped`) surviving compilation
- the on-device inspector binding to anything other than `127.0.0.1`, or starting
  in a non-debuggable build

`scripts/check-apk-safety.sh` asserts the first of these; a case it misses is
worth reporting too.

## What is out of scope

The board server and the on-device inspector are development tools and trust the
machine they run on. Reports amounting to "a local process can reach the board on
localhost" will be closed.
