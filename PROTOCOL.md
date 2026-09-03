# Protocol Specification

Transport: a single persistent WebSocket connection. One side (the "client") dials out to the
other (the "server"); once connected and authenticated, messages flow in both directions and
either side may initiate a query.

All messages are JSON text frames.

## Packet types

| Direction        | Name  | `t` (type) |
|-------------------|-------|------------|
| Server-bound (sent by whoever is asking) | `Auth`  | `100` |
| Client-bound (sent by whoever is answering) | `Auth`  | `200` |
| Server-bound | `Query` | `300` |
| Client-bound | `Query` | `301` |

"Server-bound" / "client-bound" describe the *roles in a given exchange*, not fixed connection
roles — since either peer can initiate a query, either peer can send a `300` and either peer can
answer with a `301`.

## Message shapes

### `In` — a request (Auth or Query)

```json
{
  "t": 300,
  "id": "b3e1f2c0-...",
  "token": "shared-secret",       // present only for Auth (t=100)
  "uuids": ["<uuid>", "..."]      // present only for Query (t=300)
}
```

| Field | Type | Notes |
|---|---|---|
| `t` | int | Packet type, see table above |
| `id` | string | Correlation ID, unique per outstanding request. Echoed back in the response. |
| `token` | string? | Shared secret, only sent with `Auth` |
| `uuids` | string[]? | UUIDs being requested, only sent with `Query`. Dashed or undashed — normalize on receipt. |

### `Out` — a response (Auth ack or Query result)

```json
{
  "t": 301,
  "id": "b3e1f2c0-...",
  "success": true,
  "error": null,
  "uuids": ["<uuid>", "..."],
  "data": {
    "<uuid>": { "d": "<encoded payload>", "ts": 1756900000000 }
  }
}
```

| Field | Type | Notes |
|---|---|---|
| `t` | int | `200` for Auth ack, `301` for Query result |
| `id` | string | Must match the `id` of the request being answered |
| `success` | bool? | Used for Auth (`true`/`false`); may also be set on Query responses |
| `error` | string? | Human-readable error, e.g. `"Unauthorized"` |
| `uuids` | string[]? | Echo of the requested UUIDs (Query only) |
| `data` | map<string, Entry>? | UUIDs that were found in cache. **Absence of a UUID means a cache miss** — there is no explicit "not found" entry per UUID. |

### `Entry`

```json
{ "d": "<string>", "ts": 1756900000000 }
```

| Field | Type | Notes |
|---|---|---|
| `d` | string | The cached payload — see [Payload encoding](#payload-encoding) below |
| `ts` | long | **UTC epoch milliseconds of when the underlying Hypixel data was originally fetched** — not a TTL, not "time cached," not "seconds remaining." |

## Auth flow

1. Client opens the WebSocket connection.
2. Client immediately sends `In { t: 100, id, token }`.
3. Server checks `token` against its configured shared secret.
   - Match → server adds the session to its authenticated peer set, replies
     `Out { t: 200, id, success: true }`.
   - No match → server replies `Out { t: 200, id, success: false, error: "Unauthorized" }` and
     closes the connection (`VIOLATED_POLICY`).
4. Client waits (with a timeout) for the `200` response correlated by `id` before treating the
   connection as usable.

Auth happens once per connection, immediately after connect — there's no re-auth mid-session.

## Query flow

1. Requester sends `In { t: 300, id, uuids: [...] }`.
2. Responder looks up each UUID in its own cache.
   - Only UUIDs that are actually cached are included in the response `data` map.
   - A UUID that's missing from `data` is treated as a cache miss by the requester.
3. Responder replies `Out { t: 301, id, uuids, data }`, echoing the same `id`.
4. Requester resolves the pending request keyed by `id` and applies its own staleness check (see
   below) to each returned `Entry` before trusting it.

Either peer can be the requester — the same handler code processes an incoming `300` regardless
of who established the connection.

## Payload encoding

`Entry.d` carries the underlying Hypixel API response, compressed. In this implementation:

- Payloads are compressed with **zstd**.
- The compressed bytes may be sent **base64-encoded**, or in some paths as a raw string,
  depending on which side produced them — decoders should be tolerant of both:
  1. If the string starts with `{` (i.e. looks like plain JSON already), treat it as
     uncompressed JSON directly.
  2. Otherwise, base64-decode, then zstd-decompress, to recover the original JSON string.
- Zstd frames produced with the standard encoder embed the original content size in the frame
  header, so decoders can read it back (e.g. `Zstd.getFrameContentSize(...)`) rather than
  needing to know the size out-of-band.

## Staleness / TTL semantics

`Entry.timestamp` is **fetch time**, not remaining TTL. This is intentional: the two sides don't
necessarily use the same cache duration, so trusting "the sender thinks this is still fresh"
would let one side's TTL policy silently leak into the other's. Instead, each side computes:

```
age = now_utc_ms - entry.timestamp
```

and compares `age` against its **own** configured cache TTL before deciding whether to actually
use the peer's data or treat it as a miss and fall through to a direct Hypixel fetch.

To compute a `timestamp` from a Redis key's remaining TTL (rather than storing fetch time
separately):

```
fetched_at = now_utc_ms - ((total_ttl_seconds - redis.ttl(key)) * 1000)
```

## Connection lifecycle

- The client is responsible for dialing out and reconnecting; the server only accepts.
- On disconnect (auth failure, network error, server restart, etc.), the client retries with
  **exponential backoff + jitter**, capped at a maximum delay (e.g. 30s), to avoid hammering a
  down or misbehaving peer.
- A query that doesn't get a correlated response within a timeout (e.g. 1–6s depending on side)
  is treated as a miss — the requester falls through to its normal (non-partner) cache-miss path.
