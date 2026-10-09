## What & why

<!-- One or two sentences: what changes, and why. Link the issue ("Closes #N") when one exists. -->

## How to test

<!-- How a reviewer verifies this. For UI changes: which screen, what to look for. For behavior: the observable difference. -->

## Checklist

- [ ] `./gradlew build` green **and** `./gradlew :shared:test :kotlin-sdk:test` green (composite suites are not part of root `build`)
- [ ] Tests ship in the same commit as the behavior
- [ ] New user-facing strings added to **both** `values/strings.xml` and `values-el/strings.xml`
- [ ] Docs updated in the same commit (`docs/user/` for user-visible changes + `CHANGELOG.md` `[Unreleased]`; `docs/dev/` for internals)
- [ ] No secrets, no local paths, no instance URLs in the diff
