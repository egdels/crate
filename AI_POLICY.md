# AI-Assisted Development

This document exists for transparency: it explains how AI tooling is used
to build Crate, so that reviewers and contributors do not have to piece it
together from commit trailers.

## Summary

Crate is developed primarily with [Claude Code](https://claude.com/claude-code),
an AI coding agent by Anthropic, directed and reviewed throughout by a
single human developer. This is substantive involvement, not
autocomplete-level assistance: the agent designs and writes most of the
implementation, tests, build tooling and documentation (including this
file), from requirements and direction the developer provides.

Every commit the agent contributes to carries a `Co-Authored-By: Claude ...`
trailer, so the exact scope is always checkable in the history:

```sh
git log --oneline | wc -l                                   # total commits
git log --format="%b" | grep -c "Co-Authored-By: Claude"    # AI-assisted commits
```

The work before 0.1.0 was squashed into one commit when the repository
went public, so the history starts there; the per-commit trailer rule
holds from that point on.

## What the AI does

- Implements features, refactors and bug fixes the developer specifies
- Writes and maintains the unit tests
- Writes build and CI configuration and the documentation

## What the developer does

- Decides what the app is and is not, and what gets built in which order
- Reviews every change before it is committed
- Runs the app and tests on real hardware with a real music collection,
  which the agent cannot do
- Owns the licensing and release decisions

## What this means for you

All code is GPL-3.0-or-later regardless of who or what wrote it. Bug
reports and contributions are handled by the developer; the agent does not
act on the repository on its own.
