# Health Assistant app documentation

This directory is the app's manual, split by audience. It is the canonical,
public documentation surface; everything here ships with the repository.

| Audience | Start here | What it covers |
|---|---|---|
| **Users** | [user/README.md](user/README.md) | Getting started, the full feature catalog, offline behavior & data, troubleshooting |
| **Developers** | [dev/README.md](dev/README.md) | Architecture, development workflow, UI patterns, dev troubleshooting |
| **Everyone** | [STATUS.md](STATUS.md) | What exists, current phase, known limitations |

The [root README](../../README.md) is the short overview — connect model,
architecture summary, build commands. This directory is the manual.

## The docs tree

`docs-tree.json` in this directory is the **machine-readable navigation
tree** — the repo-level source of truth for how these pages are grouped and
ordered, with categories, icons, and one-line descriptions per page, so the
website's docs section (or any renderer) can build navigation without
guessing from filenames.

Implementation plans, audits, and other internal working notes live in the
**gitignored repo-root `dev/` directory** — they are deliberately not part
of this published surface.
