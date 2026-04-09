# Simple Chat App

Minimal chat UI for an OpenAI-compatible API.

## Run

```bash
npm start
```

Then open `http://localhost:3000`.

## API settings

- By default, the UI uses the built-in proxy at `http://localhost:3000/api/v1`.
- Endpoint used: `POST /chat/completions`
- API key is optional in the UI. If left blank, the server can inject a key from its config.

### Server-side API key (recommended)

Set `INFEREDGE_API_KEY` (or `UPSTREAM_API_KEY`) before running `npm start`.

### Upstream base URL

Set `INFEREDGE_UPSTREAM_V1` (or `UPSTREAM_BASE_URL`) to point at an OpenAI-compatible `/v1` endpoint.
Default: `https://poco.fahiim.me/v1`
