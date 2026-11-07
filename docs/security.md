# Aegis server security

Everything here is server side and applies only to real network connections, never to singleplayer's in-memory
connection. Settings are in `config/aegis.properties` under `security.*`; a restart applies them.

## Connection throttle (on by default)

Vanilla 1.20.1 does not limit connections that have not logged in yet. One address can open sockets as fast as it
likes (server-list pings, half-finished logins) and keep all of them busy. Aegis limits each address:

| Setting | Default | What it does |
|---|---|---|
| `security.connectionsPerWindow` / `security.throttleWindowSeconds` | 10 per 3 s | New connections per address: a burst of 10, then one more every 0.3 s. |
| `security.maxPendingPerAddress` | 8 | Connections per address that have not reached the game yet (pings, logins in progress). |
| `security.offenceBanSeconds` | 60 | An address that sends something no real client sends is refused for this long. 0 = never ban. |
| `security.trustedAddresses` | empty | Addresses or CIDR blocks that are never limited, e.g. `203.0.113.7, 10.0.0.0/8, 2001:db8::/32`. |

- Over a limit, the connection is closed as soon as it opens. Nothing of it is decoded.
- Loopback (`127.0.0.1`, `::1`) is never limited, so a proxy or a status bot on the same machine is never affected.
- IPv6 addresses count per /64, the block one host or household usually gets.
- Offences that ban: a malformed frame length, a frame above the limit (before login: vanilla's 2 MiB), a compressed
  packet with an impossible header or a size it cannot have (decompression bomb), and a zlib stream that asks for a
  preset dictionary.
- Each refusal is logged once a minute per address and reason, with a count. A flood cannot fill the console.

### Proxies, load balancers and NAT

If all players reach the server from one address (a BungeeCord or Velocity proxy on another machine, a load balancer,
a NAT in front of the server), they share that address's limits. Add the address to `security.trustedAddresses`, or set
`security.connectionThrottle=false`, and protect the proxy itself. When a proxy-forwarding mod is installed
(Proxy-Compatible-Forge, BungeeForge, FabricProxy-Lite through Connector), Aegis turns the throttle and the block log off
by itself. `/aegis` shows whether it did.

## Block log for fail2ban / CrowdSec (off by default)

With `security.blockLog=true`, Aegis also writes refusals and offences to `logs/aegis-security.log`. It writes at most one
line per address and reason every 10 seconds, from a background thread. The format is fixed:

```
2026-11-05T14:01:00-0500 reason=OVERSIZED_FRAME ip=203.0.113.7 count=1 detail="3145728 bytes, limit 2097151"
```

The reasons are `THROTTLE_RATE`, `THROTTLE_PENDING`, `BANNED`, `BAD_FRAME`, `OVERSIZED_FRAME`, `BAD_COMPRESSION`,
`COMPRESSION_BOMB` and `PRESET_DICTIONARY`. `count` is how many events the line stands for.

Example fail2ban setup: [`fail2ban/filter.d/aegis.conf`](fail2ban/filter.d/aegis.conf) and
[`fail2ban/jail.d/aegis.conf`](fail2ban/jail.d/aegis.conf). The filter matches protocol offences and `BANNED`. It leaves out
the throttle reasons, because many players behind one NAT can hit them now and then. With it, an address that keeps
attacking after Aegis's own 60 s ban is blocked in the firewall, before it reaches Java. Run `fail2ban-regex` with your
log and the filter before enabling the jail.

Keep the block log off behind a proxy: every line would name the proxy.

## Linux host tuning (outside Aegis; not measured with it)

These are host settings, not part of the mod. Test them on your own server.

- **TCP BBR congestion control** can help players on long, lossy links (other continents), where the default congestion
  control slows down on single lost packets:
  `sysctl -w net.core.default_qdisc=fq` and `sysctl -w net.ipv4.tcp_congestion_control=bbr` (make them permanent in
  `/etc/sysctl.d/`). Needs kernel 4.9 or newer.
- **Kernel-level packet filtering** (XDP/eBPF filters for Minecraft, such as Minecraft-XDP-eBPF) drops floods before
  the kernel's TCP stack. It runs as its own root service next to the server; Aegis does not talk to it. The block log
  plus fail2ban is the simpler way to get Aegis's verdicts into the firewall.
