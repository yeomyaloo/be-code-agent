import sqlite3

from fastapi import Depends, HTTPException
from fastapi.security import OAuth2PasswordBearer

oauth2_scheme = OAuth2PasswordBearer(tokenUrl="token")


def get_db():
    conn = sqlite3.connect("app.db")
    try:
        yield conn
    finally:
        conn.close()


def get_current_user(token: str = Depends(oauth2_scheme)):
    if not token:
        raise HTTPException(status_code=401)
    return {"name": token}


def require_admin(user=Depends(get_current_user)):
    if user["name"] != "admin":
        raise HTTPException(status_code=403)
    return user
