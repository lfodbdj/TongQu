#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
Tiny LAN file server with GET + PUT support, for the 童趣 quiz-bank / user-backup workflow.

Usage:
    python server_put_server.py <port> <serve-directory>

Examples:
    python server_put_server.py 8080 ./bank
    python server_put_server.py 8080 "F:/手机题库"

- GET  /questions.json   -> serves files from the directory (like http.server)
- PUT  /<name>.json      -> stores the uploaded body as a file (手机端"备份到题库服务器")

Security notes:
- Intended for a TRUSTED home LAN only. It has no authentication: anyone on the
  network can read AND overwrite files in the directory.
- Runs with `SimpleHTTPRequestHandler` semantics; directory traversal is handled
  by translate_path like the stdlib server.
"""
import os
import sys
from http.server import SimpleHTTPRequestHandler, ThreadingHTTPServer


class PutHandler(SimpleHTTPRequestHandler):
    directory = "."

    def translate_path(self, path):
        # serve from the configured directory instead of cwd
        path = path.split("?", 1)[0].split("#", 1)[0]
        return os.path.join(self.directory, path.lstrip("/").replace("\\", "/"))

    def do_PUT(self):
        try:
            length = int(self.headers.get("Content-Length", 0))
            body = self.rfile.read(length)
            target = self.translate_path(self.path)
            os.makedirs(os.path.dirname(target) or ".", exist_ok=True)
            with open(target, "wb") as f:
                f.write(body)
            self.send_response(201)
            self.send_header("Content-Length", "0")
            self.end_headers()
            print(f"[PUT] saved {target} ({length} bytes)")
        except Exception as e:  # noqa: BLE001
            self.send_response(500)
            self.send_header("Content-Length", "0")
            self.end_headers()
            print(f"[PUT] failed: {e}")

    def log_message(self, fmt, *args):  # quieter logs
        sys.stderr.write("[http] " + (fmt % args) + "\n")


def main():
    port = int(sys.argv[1]) if len(sys.argv) > 1 else 8080
    directory = sys.argv[2] if len(sys.argv) > 2 else "."
    PutHandler.directory = directory
    server = ThreadingHTTPServer(("0.0.0.0", port), PutHandler)
    print(f"Serving {directory} (GET+PUT) on 0.0.0.0:{port} — Ctrl+C to stop")
    server.serve_forever()


if __name__ == "__main__":
    main()
