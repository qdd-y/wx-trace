import sqlite3, os

base = r'D:\Projects\wetrace-java\backend\target\data\decrypted'
for fn in ['session.db', 'contact.db', 'message_0.db', 'message_1.db', 'message_2.db', 'message_3.db',
           'biz_message_0.db', 'media_0.db', 'hardlink.db']:
    fp = os.path.join(base, fn)
    if not os.path.exists(fp):
        continue
    size = os.path.getsize(fp)
    with open(fp, 'rb') as f:
        h = f.read(32).hex()

    print(f'{fn:25s} size={size:>12,d}  header={h[:32]}')

    # Check page size (bytes 16-17 big-endian for real SQLite)
    ps_bytes = bytes.fromhex(h[32:40])
    ps = int.from_bytes(ps_bytes, 'big')
    print(f'  page_size_field: {ps}')

    try:
        conn = sqlite3.connect(f'file:{fp}?mode=ro', uri=True)
        cur = conn.cursor()
        n = cur.execute('SELECT count(*) FROM sqlite_master').fetchone()[0]
        tables = cur.execute(
            'SELECT name FROM sqlite_master WHERE type=? LIMIT 5', ('table',)
        ).fetchall()
        print(f'  [OK] SQLite: {n} schemas, tables: {[t[0] for t in tables]}')
        conn.close()
    except Exception as e:
        print(f'  [FAIL] SQLite: {e}')
    print()
