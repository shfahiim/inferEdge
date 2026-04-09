const http = require("node:http");
const fs = require("node:fs");
const path = require("node:path");
const { Readable } = require("node:stream");
const { pipeline } = require("node:stream/promises");

const port = process.env.PORT || 3000;
const root = __dirname;

const upstreamV1 = (process.env.INFEREDGE_UPSTREAM_V1 ||
  process.env.UPSTREAM_BASE_URL ||
  "https://poco.fahiim.me/v1"
).replace(/\/+$/, "");

const upstreamHealthBase = upstreamV1.replace(/\/+$/, "").replace(/\/v1$/, "");

const serverApiKey =
  process.env.INFEREDGE_API_KEY || process.env.UPSTREAM_API_KEY || "";

const types = {
  ".html": "text/html; charset=utf-8",
  ".css": "text/css; charset=utf-8",
  ".js": "text/javascript; charset=utf-8",
  ".json": "application/json; charset=utf-8",
  ".svg": "image/svg+xml",
};

function normalizeBearer(value) {
  const trimmed = String(value || "").trim();
  if (!trimmed) return "";
  return trimmed.toLowerCase().startsWith("bearer ") ? trimmed : `Bearer ${trimmed}`;
}

async function readRequestBody(req) {
  const chunks = [];
  let total = 0;
  for await (const chunk of req) {
    total += chunk.length;
    if (total > 10 * 1024 * 1024) {
      throw new Error("Request body too large.");
    }
    chunks.push(chunk);
  }
  return Buffer.concat(chunks);
}

function writeJson(res, status, body) {
  res.writeHead(status, { "Content-Type": "application/json; charset=utf-8" });
  res.end(JSON.stringify(body));
}

async function proxyToUpstream(req, res, upstreamUrl) {
  const method = req.method || "GET";
  const body = method === "GET" || method === "HEAD" ? null : await readRequestBody(req);

  const headers = {
    "content-type": req.headers["content-type"] || "application/json",
    accept: req.headers.accept || "*/*",
  };

  const incomingAuth = req.headers.authorization;
  if (incomingAuth) {
    headers.authorization = incomingAuth;
  } else if (serverApiKey) {
    headers.authorization = normalizeBearer(serverApiKey);
  }

  const upstreamResponse = await fetch(upstreamUrl, {
    method,
    headers,
    body: body ? body : undefined,
  });

  const contentType = upstreamResponse.headers.get("content-type") || "application/octet-stream";
  const cacheControl = upstreamResponse.headers.get("cache-control");

  const responseHeaders = {
    "Content-Type": contentType,
  };

  if (cacheControl) {
    responseHeaders["Cache-Control"] = cacheControl;
  }

  res.writeHead(upstreamResponse.status, responseHeaders);

  if (!upstreamResponse.body) {
    res.end();
    return;
  }

  await pipeline(Readable.fromWeb(upstreamResponse.body), res);
}

const server = http.createServer(async (req, res) => {
  const url = new URL(req.url || "/", `http://${req.headers.host || "localhost"}`);

  try {
    if (url.pathname === "/api/config") {
      writeJson(res, 200, {
        proxyBaseUrl: `${url.origin}/api/v1`,
        upstreamV1,
        hasServerApiKey: !!serverApiKey,
      });
      return;
    }

    if (url.pathname === "/api/health") {
      await proxyToUpstream(req, res, `${upstreamHealthBase}/health${url.search}`);
      return;
    }

    if (url.pathname.startsWith("/api/v1/")) {
      const upstreamPath = url.pathname.slice("/api".length);
      await proxyToUpstream(req, res, `${upstreamV1}${upstreamPath}${url.search}`);
      return;
    }
  } catch (error) {
    writeJson(res, 502, {
      message: error instanceof Error ? error.message : "Upstream request failed.",
    });
    return;
  }

  const requestPath = url.pathname === "/" ? "/index.html" : url.pathname;
  const filePath = path.join(root, path.normalize(requestPath));

  if (!filePath.startsWith(root)) {
    res.writeHead(403);
    res.end("Forbidden");
    return;
  }

  fs.readFile(filePath, (error, content) => {
    if (error) {
      res.writeHead(error.code === "ENOENT" ? 404 : 500);
      res.end(error.code === "ENOENT" ? "Not found" : "Server error");
      return;
    }

    const ext = path.extname(filePath);
    res.writeHead(200, {
      "Content-Type": types[ext] || "application/octet-stream",
    });
    res.end(content);
  });
});

server.listen(port, () => {
  console.log(`Server running at http://localhost:${port}`);
  console.log(`Proxying /api/v1/* -> ${upstreamV1}`);
});
