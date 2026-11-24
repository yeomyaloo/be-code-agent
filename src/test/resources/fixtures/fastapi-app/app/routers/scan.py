import subprocess
from typing import Annotated

import httpx
from fastapi import APIRouter, Depends
from pydantic import BaseModel

from app.deps import get_current_user
from ..services import scanner

router = APIRouter(prefix="/scan", tags=["scan"])


class ScanRequest(BaseModel):
    target: str


@router.post("/nmap")
async def run_nmap(req: ScanRequest, user=Depends(get_current_user)):
    return await scanner.run_nmap(req.target)


@router.post("/deep")
def deep_scan(hosts: str, user: Annotated[dict, Depends(get_current_user)]):
    return scanner.scan_with_lib(hosts)


@router.get("/fetch")
def fetch(url: str):
    with httpx.Client(timeout=5) as client:
        return client.get(url).text


@router.post("/ping")
def ping(host: str):
    uptime = subprocess.run(["uptime"], capture_output=True)
    result = subprocess.run(f"ping -c 1 {host}", shell=True, capture_output=True)
    return {"out": result.stdout, "uptime": uptime.stdout}
