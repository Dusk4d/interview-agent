from __future__ import annotations

import uvicorn

from .api import create_app
from .config import Settings

settings = Settings.load()
app = create_app(settings)


def run() -> None:
    uvicorn.run("interview_agent.main:app", host=settings.host, port=settings.port, reload=False)


if __name__ == "__main__":
    run()
