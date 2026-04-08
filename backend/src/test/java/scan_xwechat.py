import os

base = r'C:\Users\钱广\AppData\Roaming\Tencent\xwechat'
for root, dirs, files in os.walk(base):
    depth = root.replace(base, '').count(os.sep)
    if depth > 4: continue
    indent = ' ' * 2 * depth
    print(f'{indent}{os.path.basename(root)}/')
    if 'db_storage' in dirs:
        db_path = os.path.join(root, 'db_storage')
        db_files = [f for f in os.listdir(db_path) if f.endswith('.db')]
        print(f'{indent}  [db_storage] {len(db_files)} files')
        for fn in sorted(db_files)[:10]:
            fp = os.path.join(db_path, fn)
            size = os.path.getsize(fp)
            print(f'{indent}    {fn}: {size:,}')
    # Also show .db files directly
    for fn in files:
        if fn.endswith('.db'):
            fp = os.path.join(root, fn)
            size = os.path.getsize(fp)
            print(f'{indent}  {fn}: {size:,}')
