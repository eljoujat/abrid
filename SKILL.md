# Abrid — MCP Mobility Skill

## Description

**Abrid** ("path" in Amazigh) is a multimodal mobility assistant for Morocco.
It answers questions like *"how do I get from A to B?"* using ONCF trains, buses and grand taxis.
The assistant speaks darija when the user does.

**Data honesty guarantee**: Abrid never invents trips, schedules or fares.
If data is missing, it says so explicitly.

---

## MCP Server

| Property | Value |
|----------|-------|
| Protocol | MCP over HTTP (SSE) |
| SSE stream | `GET  http://localhost:8080/sse` |
| Tool calls | `POST http://localhost:8080/mcp/message` |
| Server type | SYNC (Spring MVC) |

### Connecting Claude Desktop

Add to `~/.config/claude/claude_desktop_config.json`:

```json
{
  "mcpServers": {
    "abrid": {
      "url": "http://localhost:8080/sse"
    }
  }
}
```

---

## Available Tools

### `plan_trip`

Plan a trip between two stations.

| Parameter | Type | Description |
|-----------|------|-------------|
| `from` | string | Departure station — French, darija or partial (e.g. `"tanger"`, `"dar bidaa"`) |
| `to` | string | Arrival station — same format |
| `date` | string | Travel date `YYYY-MM-DD` — use today if not specified |

**Status values in response**:

| Status | Meaning | LLM action |
|--------|---------|-----------|
| `found` | Journeys available | Narrate departure/arrival/duration |
| `not_found` | No route for this O/D | Say honestly there is no connection |
| `ambiguous` | Station name matches several stops | Ask user which one (list in `candidates`) |
| `station_unknown` | Station not in database | Ask user to rephrase |
| `error` | Unexpected error | Apologise |

**Example darija prompt → tool call**:

> *"kifach nemchi men Fès l Marrakech ghedda f sbah?"*
>
> → LLM calls `plan_trip(from="Fès", to="Marrakech", date="<tomorrow>")`

---

### `get_schedule`

Get the departure board for a station on a given date.

| Parameter | Type | Description |
|-----------|------|-------------|
| `station` | string | Station name |
| `date` | string | Date `YYYY-MM-DD` |

---

### `get_station_info`

Look up a station and return its details (ID, coordinates, mode).

| Parameter | Type | Description |
|-----------|------|-------------|
| `query` | string | Partial name, darija transcription or exact French name |

Useful to confirm which station the user means before planning a trip.

---

### `get_disruptions`

Get active real-time disruptions on the ONCF network.

| Parameter | Type | Description |
|-----------|------|-------------|
| `routeId` | string | Optional route ID filter (e.g. `AL_BORAQ_TNG_CASA`). Pass empty for all. |

> **Note**: the ONCF TRAFIC real-time source is not yet connected (Lot 1-bis pending legal validation). The disruption list may be empty.

---

### `submit_correction`

Report a data error or improvement for review by the data team.

| Parameter | Type | Description |
|-----------|------|-------------|
| `type` | string | `FARE` \| `ROUTE` \| `STATION` \| `SCHEDULE` \| `OTHER` |
| `description` | string | Detailed description in any language |
| `dataSource` | string | Optional — affected entity (station ID, route ID, etc.) |

---

## System prompt for the LLM

When using Abrid tools, instruct the LLM with:

```
You are Abrid, a mobility assistant for Morocco.
Use the available tools to answer questions about train and bus schedules.
Respond in the same language as the user — darija, French or Arabic.
NEVER invent or guess schedule information.
If a tool returns status='not_found', tell the user honestly that no trip was found.
If a tool returns status='ambiguous', list the candidate stations and ask which one.
```

---

## Darija example exchanges

**Direct trip**
> User: *"kifach nemchi men Rabat l Casa ghedda?"*
> Tool: `plan_trip(from="Rabat-Ville", to="Casa-Voyageurs", date="2025-08-31")`
> Response: *"kayna qitar men Rbat l Casa f 06:00, yewsel 07:10, mudda saa w 'ashra d'qiqa"*

**Disambiguation**
> User: *"bghit nmchi l casa"*
> Tool: `plan_trip(from="...", to="casa", ...)` → `status=ambiguous, candidates=["Casa-Voyageurs", "Casa-Port", "Casa-Oasis"]`
> Response: *"fin bghit f Casa? Casa-Voyageurs, Casa-Port, wla Casa-Oasis?"*

**No data**
> User: *"wach kayn qitar men Nador l Marrakech?"*
> Tool: `plan_trip(...)` → `status=not_found`
> Response: *"ma kaynach qitar direct men Nador l Marrakech f had l-waqt, had l-khat ma 'andoush horaire f qaa' dyalona"*

**Correction**
> User: *"le prix Tanger-Casa c'est 95 MAD pas 110"*
> Tool: `submit_correction(type="FARE", description="Tanger-Casa fare: 95 MAD not 110 MAD", dataSource="AL_BORAQ_TNG_CASA")`
> Response: *"choukran, correction msejalat, ghadi yt-revisa men l-ferik"*
