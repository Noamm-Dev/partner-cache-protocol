# Protocol Specification

Transport: a single persistent WebSocket connection. One side (the "client") connects to the
other (the "server").
once connected and authenticated, messages flow in both directions and
both side can query.

All messages are JSON text frames.

## Packet types

| Direction        | Name  | `t` (type) |
|-------------------|-------|------------|
| Server-bound (sent by whoever is asking) | `Auth`  | `100` |
| Client-bound (sent by whoever is answering) | `Auth`  | `200` |
| Server-bound | `Query` | `300` |
| Client-bound | `Query` | `301` |

"Server-bound" / "client-bound" describe the *roles in a given exchange*, not fixed connection
roles since either peer can initiate a query, either peer can send a `300` and either peer can
answer with a `301`.

## Message shapes

### `In` a request (Auth or Query)

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

### `Out` a response (Auth/Query result)

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
| `data` | map<string, Entry>? | UUIDs that were found in cache. **Absence of a UUID means a cache miss** |

### `Entry`

```json
{ "d": "<string>", "ts": 1756900000000 }
```

| Field | Type | Notes                                                                                                                        |
|---|---|------------------------------------------------------------------------------------------------------------------------------|
| `d` | string | The cached payload.                                                           |
| `ts` | long | **UTC time of when the data was originally fetched** |

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

## Query flow

1. Requester sends `In { t: 300, id, uuids: [...] }`.
2. Responder looks up each UUID in its own cache.
   - Only UUIDs that are actually cached are included in the response `data` map.
   - A UUID that's missing from `data` is treated as a cache miss by the requester.
3. Responder replies `Out { t: 301, id, uuids, data }`, echoing the same `id`.
4. Requester resolves the pending request keyed by `id` and applies its own staleness check (see
   below) to each returned `Entry` before trusting it.

Either peer can be the requester. the same handler code processes an incoming `300` regardless
of who established the connection.

## Connection lifecycle

- The client is responsible for dialing out and reconnecting. the server only accepts.
- On disconnect (auth failure, network error, server restart, etc.), the client retries with
  **exponential backoff + jitter**, capped at a maximum delay (e.g. 30s), to avoid hammering a
  down or misbehaving peer.
 - A query that doesn't get a response within a timeout is treated as a miss.