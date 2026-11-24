def find_user(conn, name):
    conn.execute("SELECT 1")
    query = "SELECT * FROM users WHERE name = '%s'" % name
    cursor = conn.cursor()
    cursor.execute(query)
    return cursor.fetchall()


def find_by_email(conn, email):
    return conn.execute("SELECT * FROM users WHERE email = ?", (email,)).fetchone()


class UserRepo:
    def __init__(self, conn):
        self.conn = conn

    def delete(self, user_id):
        self.conn.execute(f"DELETE FROM users WHERE id = {user_id}")
