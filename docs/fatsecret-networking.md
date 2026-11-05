# FatSecret outbound networking

FatSecret access now works from this workspace after the account's IP restrictions
were adjusted. Host credentials remain in the ignored `.fatsecret.env`. The Android
app accepts the user's own credentials in Settings and stores them encrypted in its
no-backup directory; no host credentials are built into the APK. Secrets and tokens
must never appear in Git, release notes, portable backups or download-service files.

In the dashboard, navigate to **Generate / View API Keys → IP Restrictions**.
FatSecret staff document `0.0.0.0/0` as an allowed range covering all IPv4 sources;
this supports a rotating VPN address without renting another server. Authentication
is still required, but this removes the additional IP restriction. A narrow public-IP
allowlist remains an option when a stable outbound address is available. A private
LAN/tunnel address is not the public address FatSecret observes.

Optional fixed-IP approaches, if a narrower allowlist is desired:

A tunnel does not itself create a stable public address. Its far end must have one:

- Reuse a server/VPS with a fixed public IP and run a small, authenticated food-lookup
  proxy there. It keeps the FatSecret secret on that server and sends API requests
  from the allowlisted address. The Android app can later use that proxy.
- For workspace-only auditing, an SSH SOCKS tunnel to the same fixed-IP server can
  route only the explicitly configured FatSecret HTTP client. Leave other host
  traffic on its existing VPN. Configure DNS resolution through the SOCKS tunnel.
- A VPN provider's dedicated/static exit IP is another option if already available.

No global VPN/routes were changed and no paid service was provisioned. Setting up
an actual tunnel requires an existing fixed-IP endpoint and authorized access to it.
A rotating exit node, a dynamic-DNS name or a public inbound HTTP tunnel does not
supply a fixed outbound public IP by itself. Avoid routing the whole host through
a different exit node just to perform food lookups.

Official references:

- [FatSecret staff on allowing all IPv4 sources](https://groups.google.com/g/fatsecret-platform-api/c/L0VjvpjqkoQ)
- [FatSecret account/IP setup](https://github.com/fatsecret-group/postman-fatsecret-apis/blob/main/readme.md)
- [FatSecret OAuth2 proxy/IP restrictions](https://platform.fatsecret.com/docs/guides/authentication/oauth2)
- [Tailscale exit-node behavior](https://tailscale.com/docs/features/exit-nodes)
