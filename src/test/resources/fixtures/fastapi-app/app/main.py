from fastapi import FastAPI

from app.routers import scan, admin
from app.routers.files import files_router

app = FastAPI(title="netinv")

app.include_router(scan.router, prefix="/api")
app.include_router(admin.router)
app.include_router(files_router)


@app.get("/health")
def health():
    return {"status": "ok"}
