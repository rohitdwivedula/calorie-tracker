# Project collaboration rules

- Ask the user before publishing any GitHub release. Preparing and validating local APKs is permitted.
- Never commit signing keys, local configuration, databases, personal backups, exports, or raw user lookup responses.
- Never upload personal backups, databases, eating-history reports, or user lookup responses to GitHub releases. Deliver personal files through the private LAN download service documented in `docs/private-downloads.md`; its access URL and host configuration must stay out of Git. Public releases may contain APKs and synthetic/nonpersonal review material only. Keep host-only download server code, tests and service configuration outside this repository.
- Every future AI-assisted commit must include a `Co-authored-by` trailer naming the contributing model/agent. Use the model identity actually available in the session; do not invent a more specific model version.
- For the current Codex agent (future sessions should use their own known identity), use: `Co-authored-by: OpenAI Codex (GPT-6) <noreply@openai.com>`.
- Preserve recorded facts separately from estimates, keep missing nutrition distinct from zero, and preserve backward-compatible database/backup handling.
