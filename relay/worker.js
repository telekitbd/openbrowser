// Cloudflare Worker relay for FreeBrowser.
// Deploy: Cloudflare dashboard -> Workers & Pages -> Create -> Hello World -> Edit code -> paste this -> Deploy.
// The app connects with  wss://<your-worker>.workers.dev/t?h=<host>&p=<port>&k=<KEY>
// and every byte of the website connection travels through this tunnel.
import { connect } from "cloudflare:sockets";

const KEY = "23228a446f85b394e78f2ed9f8bf0864"; // must match the key in the app (already filled in the APK build)

export default {
  async fetch(req) {
    if (req.headers.get("Upgrade") !== "websocket") {
      return new Response("ok", { status: 200 });
    }
    const url = new URL(req.url);
    if (url.searchParams.get("k") !== KEY) {
      return new Response("forbidden", { status: 403 });
    }
    const host = url.searchParams.get("h");
    const port = parseInt(url.searchParams.get("p"), 10);
    if (!host || !port) {
      return new Response("bad request", { status: 400 });
    }

    const pair = new WebSocketPair();
    const [client, server] = Object.values(pair);
    server.accept();

    let sock;
    try {
      sock = connect({ hostname: host, port });
    } catch (e) {
      server.close(1011, "connect failed");
      return new Response(null, { status: 101, webSocket: client });
    }

    const writer = sock.writable.getWriter();
    const closeAll = () => {
      try { sock.close(); } catch (_) {}
      try { server.close(); } catch (_) {}
    };

    // app -> website
    server.addEventListener("message", async (ev) => {
      try {
        const d = typeof ev.data === "string" ? new TextEncoder().encode(ev.data) : new Uint8Array(ev.data);
        await writer.write(d);
      } catch (_) {
        closeAll();
      }
    });
    server.addEventListener("close", closeAll);
    server.addEventListener("error", closeAll);

    // website -> app
    sock.readable
      .pipeTo(
        new WritableStream({
          write(chunk) { server.send(chunk); },
          close() { closeAll(); },
          abort() { closeAll(); },
        })
      )
      .catch(closeAll);

    return new Response(null, { status: 101, webSocket: client });
  },
};
