// VPS relay for FreeBrowser (same protocol as worker.js).
// Run on any foreign VPS:   npm i ws && KEY=23228a446f85b394e78f2ed9f8bf0864 PORT=8080 node vps-server.js
// In the app: Menu -> রিলে সেটিংস -> http://<VPS-IP>:8080  + the same key.
const { WebSocketServer } = require("ws");
const net = require("net");

const KEY = process.env.KEY || "23228a446f85b394e78f2ed9f8bf0864";
const PORT = parseInt(process.env.PORT || "8080", 10);

const wss = new WebSocketServer({ port: PORT, perMessageDeflate: false });

wss.on("connection", (ws, req) => {
  const q = new URL(req.url, "http://x").searchParams;
  if (q.get("k") !== KEY) return ws.close(1008);
  const host = q.get("h");
  const port = parseInt(q.get("p"), 10);
  if (!host || !port) return ws.close(1008);

  const s = net.connect(port, host);
  s.setNoDelay(true);
  s.on("data", (d) => { if (ws.readyState === 1) ws.send(d); });
  s.on("close", () => ws.close());
  s.on("error", () => ws.close());
  ws.on("message", (d) => s.write(d));
  ws.on("close", () => s.destroy());
  ws.on("error", () => s.destroy());
});

console.log("relay listening on :" + PORT);
