# Protocol Specification

Transport: a single persistent WebSocket connection. One side (the "client") connects to the
other (the "server"). Once the connection is authenticated, messages flow in both directions
and both sides can send queries.

All messages are JSON text frames.

## Packet types

| Direction        | Name  | `t` (type) |
|-------------------|-------|------------|
| Server-bound (sent by whoever is asking) | `Auth`  | `100` |
| Client-bound (sent by whoever is answering) | `Auth`  | `200` |
| Server-bound | `Query` | `300` |
| Client-bound | `Query` | `301` |

"Server-bound" and "client-bound" describe the roles in a single exchange, not who opened the
connection. Either peer can send a `300` and either peer can answer with a `301`.

## Message shapes

### `In`: a request (Auth or Query)

```json
{
  "t": 300,
  "id": "b3e1f2c0-...",
  "token": "shared-secret",       // present only for Auth (t=100)
  "uuids": ["<uuid>", "..."],     // present only for Query (t=300)
  "maxAge": 7200000               // optional, only for Query (t=300)
}
```

| Field | Type | Notes |
|---|---|---|
| `t` | int | Packet type, see table above |
| `id` | string | Correlation ID, unique per outstanding request. Echoed back in the response. |
| `token` | string? | Shared secret, only sent with `Auth` |
| `uuids` | string[]? | UUIDs being requested, only sent with `Query`. Can be dashed or undashed, so normalize on receipt. |
| `maxAge` | long? | Max age in milliseconds the requester accepts, only sent with `Query`. The responder must omit entries with `now - ts > maxAge`. If absent, the responder applies no limit beyond its own cache policy. |

### `Out`: a response (Auth/Query result)

```json
{
  "t": 301,
  "id": "b3e1f2c0-...",
  "success": true,
  "error": null,
  "uuids": ["<uuid>", "..."],
  "data": {
    "<uuid>": { "d": "<json string>", "ts": 1756900000000 }
  }
}
```

| Field | Type | Notes |
|---|---|---|
| `t` | int | `200` for Auth ack, `301` for Query result |
| `id` | string | Must match the `id` of the request being answered |
| `success` | bool? | Used for Auth (`true`/`false`) |
| `error` | string? | Human-readable error |
| `uuids` | string[]? | Echo of the requested UUIDs (Query only) |
| `data` | map<string, Entry>? | UUIDs that were found in cache. A UUID missing from the map is a cache miss. |

### `Entry`

```json
{ "d": "<string>", "ts": 1756900000000 }
```

| Field | Type | Notes |
|---|---|---|
| `d` | string | The cached payload |
| `ts` | long | UTC time the data was originally fetched |

## Auth flow

1. Client opens the WebSocket connection.
2. Client immediately sends `In { t: 100, id, token }`.
3. Server checks `token` against its configured shared secret.
   - If it matches, the server adds the session to its authenticated peer set and replies
     `Out { t: 200, id, success: true }`.
   - If it doesn't, the server replies `Out { t: 200, id, success: false, error: "Unauthorized" }`
     and closes the connection (`VIOLATED_POLICY`).
4. Client waits (with a timeout) for the `200` response with the matching `id` before treating
   the connection as usable.

## Query flow

1. Requester sends `In { t: 300, id, uuids: [...], maxAge? }`.
2. Responder looks up each UUID in its own cache.
   - Only UUIDs that are actually cached go into the response `data` map.
   - If `maxAge` is set, entries fetched more than `maxAge` ms ago are left out, so stale
     profiles aren't sent only to be discarded by the requester.
   - The requester treats a UUID missing from `data` as a cache miss.
3. Responder replies `Out { t: 301, id, uuids, data }`, echoing the same `id`.
4. Requester resolves the pending request keyed by `id` and applies its own staleness check to
   each returned `Entry` before trusting it, since a peer may not support `maxAge` yet.

Either peer can be the requester. The same handler code processes an incoming `300` no matter
who opened the connection.

## Connection lifecycle

- The client dials out and reconnects. The server only accepts.
- On disconnect (auth failure, network error, server restart, etc.), the client retries with
  exponential backoff and jitter, capped at a maximum delay (e.g. 30s), so it doesn't hammer
  a peer that's down or misbehaving.
- A query that doesn't get a response within a timeout is treated as a miss.