import os

from fastapi import APIRouter
from fastapi.responses import FileResponse

BASE_DIR = "/srv/reports"

files_router = APIRouter()


@files_router.get("/files/{name}")
def download(name: str):
    return FileResponse(os.path.join(BASE_DIR, name))


@files_router.get("/notes/{name}")
def read_note(name: str):
    with open(f"/srv/notes/{name}.txt") as f:
        return f.read()


@files_router.get("/about")
def about():
    with open("ABOUT.md") as f:
        return f.read()
