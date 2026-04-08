import os
found = []
for root, dirs, files in os.walk('D:\\'):
    for d in dirs:
        if 'db_storage' in d.lower():
            path = os.path.join(root, d)
            try:
                dbs = [fn for fn in os.listdir(path) if fn.endswith('.db')]
                if dbs:
                    size = sum(os.path.getsize(os.path.join(path, f)) for f in dbs)
                    found.append((path, dbs, size))
                    print(f'DB_STORAGE: {path}')
                    print(f'  DBs: {sorted(dbs)[:10]} total={len(dbs)} size={size:,}')
                    for db in sorted(dbs)[:3]:
                        fp = os.path.join(path, db)
                        with open(fp, 'rb') as f:
                            h = f.read(16).hex()
                        print(f'    {db}: {os.path.getsize(fp):>12,d}  {h}')
            except:
                pass

if not found:
    print('No db_storage found in D:\\')
