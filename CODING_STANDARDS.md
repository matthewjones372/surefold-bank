# Coding standards

Applied in review, to the diff. Each rule names what to flag and what the fix is.

## Mappings go through kimney where the build applies it

In a module that applies kimney's plugin (lark-bank's `protocol` today), flag a mapping between a domain type and
a DTO or wire type (`toDto`, `toWire`, `toDomain`) that calls the target's constructor field by field. The fix is
`transformInto()`, or `into<_, B>()` with `withFieldComputed`, `withFieldConst` or `withTransformer` for the fields
that differ. A comment claiming kimney cannot express the mapping is itself a finding until the override chain has
been checked.

Hand-written is right only where a field depends on context the source type does not carry. Then kimney still maps
the rest, through `withFieldConst`. A module without the plugin (bank-approvals' `protocol`) maps by hand, each field
named once each way; that is not a finding.

## A fix that moves pinned behaviour is a requirement question

When a finding's fix would change what a test asserts, name that test in the finding and report the finding
as a question for the owner, not a bug. Fixing it means changing the test that states the requirement.
