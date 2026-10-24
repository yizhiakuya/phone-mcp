"""Entry point: `python -m phone_mcp` or the `phone-mcp` console script."""

from __future__ import annotations

import sys


def main() -> None:
    from .server import server

    transport = "stdio"
    if "--http" in sys.argv:
        transport = "streamable-http"
    server.run(transport)


if __name__ == "__main__":
    main()
