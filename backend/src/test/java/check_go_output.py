import os, sqlite3

db_path = r'C:\Users\钱广\Documents\chatlog\wxid_bet6zjydp93o22_26bc\db_storage'
if os.path.isdir(db_path):
    files = sorted(os.listdir(db_path))
    print(f'db_storage: {len(files)} files')
    for fn in files:
        fp = os.path.join(db_path, fn)
        size = os.path.getsize(fp)
        try:
            conn = sqlite3.connect(f'file:{fp}?mode=ro', uri=True)
            cur = conn.cursor()
            tables = [r[0] for r in cur.execute('SELECT name FROM sqlite_master WHERE type=?', ('table',)).fetchall()]
            rc = []
            for t in tables[:3]:
                try:
                    n = cur.execute(f'SELECT count(*) FROM [{t}]').fetchone()[0]
                    rc.append(f'{t}({n:,})')
                except: pass
            conn.close()
            print(f'[OK] {fn:40s} {size:>12,d}  {rc}')
        except Exception as e:
            print(f'[FAIL] {fn:40s} {size:>12,d}  {str(e)[:60]}')
else:
    print(f'NOT: {db_path}')
