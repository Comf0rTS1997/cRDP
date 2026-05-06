#!/usr/bin/env python3
"""Minimal mock session broker + WebSocket relay for testing GatewayConnectionProfile.

Requires: pip install aiohttp

Run: python mock_gateway.py
Then create a Gateway profile with gateway base URL http://<LAN-IP>:8080 (emulator: http://10.0.2.2:8080).

This does not speak real RDP; it only validates REST + binary WebSocket framing.
"""

from aiohttp import web, WSMsgType
async def create_session(request: web.Request) -> web.Response:
    body = await request.json()
    target_host = body.get("targetHost", "")
    target_port = body.get("targetPort", 3389)
    session_id = "mock-session"
    host_header = request.headers.get("Host", "127.0.0.1:8080")
    relay_url = f"ws://{host_header}/relay"
    return web.json_response(
        {
            "sessionId": session_id,
            "relayUrl": relay_url,
            "expiresAt": "2099-01-01T00:00:00Z",
            "debug": {"target": f"{target_host}:{target_port}"},
        }
    )


async def relay_handler(request: web.Request) -> web.WebSocketResponse:
    ws = web.WebSocketResponse()
    await ws.prepare(request)
    async for msg in ws:
        if msg.type == WSMsgType.BINARY:
            await ws.send_bytes(msg.data)
        elif msg.type == WSMsgType.TEXT:
            await ws.send_str(msg.data)
    return ws


def main() -> None:
    app = web.Application()
    app.router.add_post("/v1/sessions", create_session)
    app.router.add_get("/relay", relay_handler)
    web.run_app(app, host="0.0.0.0", port=8080)


if __name__ == "__main__":
    main()
