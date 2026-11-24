import pickle

import yaml
from fastapi import APIRouter, Depends, Request
from sqlalchemy import text

from app import repo
from app.deps import get_db, require_admin

router = APIRouter(prefix="/admin", dependencies=[Depends(require_admin)])


@router.post("/import")
async def import_backup(request: Request):
    body = await request.body()
    return pickle.loads(body)


@router.post("/config")
async def load_config(request: Request):
    body = await request.body()
    unsafe = yaml.load(body)
    safe = yaml.load(body, Loader=yaml.SafeLoader)
    return {"unsafe": unsafe, "safe": safe}


@router.get("/users")
def users(name: str, db=Depends(get_db)):
    return repo.find_user(db, name)


@router.get("/report")
def report(order: str, db=Depends(get_db)):
    return db.execute(text(f"SELECT * FROM reports ORDER BY {order}")).fetchall()
