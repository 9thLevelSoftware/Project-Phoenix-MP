# Wiki

This is the Almanac wiki for this repository. It captures the knowledge
the code itself can't say — decisions, flows, invariants, gotchas, incidents.

The primary reader is an AI coding agent. The secondary reader is a human
skimming to understand the shape of the codebase. Write accordingly: dense,
factual, linked.

## Notability bar

Write a page when there is **non-obvious knowledge that will help a future
agent**. Specifically:

- A decision that took discussion, research, or trial-and-error
- A gotcha discovered through failure
- A cross-cutting flow that spans multiple files and isn't obvious from any
  one of them
- A constraint or invariant not visible from the code
- An entity (technology, service, system) referenced by multiple pages

Do not write pages that restate what the code does. Do not write pages of
inference — only of observation. Silence is an acceptable outcome.

## Topic taxonomy

Topics form a DAG; pages can belong to multiple topics. Start with these and
grow as the wiki does:

- `stack` — external runtimes, SDKs, and services the app depends on
- `systems` — core Phoenix subsystems built in shared code and platform adapters
- `flows` — end-to-end behaviors that cross managers, repositories, platform code, or external services
- `concepts` — project-specific vocabulary and durable domain models

Domain topics live alongside these. This wiki currently uses `product`,
`frontend`, `android`, `ios`, `ble`, `workouts`, `data`, `sync`, `auth`, and
`integrations`. A page about portal login persistence belongs to both `sync`
and `auth`; a page about backup repair belongs to both `data` and `flows`.

## Page shapes

Four shapes cover most of what gets written. They are suggestions, not a
schema — a page that fits none of them is fine.

- **Entity** — a stable named thing (Supabase, Stripe, the search service)
- **Decision** — why we chose X over Y
- **Flow** — how a multi-file process works end-to-end
- **Gotcha** — a specific surprise, failure, or constraint

## Writing conventions

- Every sentence contains a specific fact. If it doesn't, cut it.
- Neutral tone. "is", not "serves as". No "plays a pivotal role", no
  interpretive "-ing" clauses, no vague attribution ("experts argue").
- No hedging or knowledge-gap disclaimers. If you don't know, don't write
  the sentence.
- Prose first. Bullets for genuine lists. Tables only for structured
  comparison.
- No formulaic conclusions. End with the last substantive fact.

## Linking

One `[[...]]` syntax for everything, disambiguated by content:

- `[[checkout-flow]]` — page slug
- `[[src/checkout/handler.ts]]` — file reference
- `[[src/checkout/]]` — folder reference (trailing slash)
- `[[other-wiki:slug]]` — cross-wiki reference

Every page should link to at least one entity when possible. A page with no
entity link is suspect.

## Pages live in `.almanac/pages/`

One markdown file per page, kebab-case slug. Frontmatter carries `topics:` and
`sources:`. Use `type: file` sources for repo grounding and cite non-obvious
claims with `[@source-id]`. The rest is prose.
