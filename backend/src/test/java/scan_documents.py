import os

# WeChat 4.x 用 FileSavePath -> xwechat_files
# 检查 Documents 和 FileSavePath 注册表指向的位置
search = [
    os.path.expandvars(r'%USERPROFILE%\Documents'),
    os.path.expandvars(r'%USERPROFILE%\Documents\xwechat_files'),
    r'D:\Wechat_temp\WeChat Files',
]

for root in search:
    if not os.path.isdir(root): 
        print(f'NOT: {root}')
        continue
    print(f'Scanning: {root}')
    for dirpath, dirs, files in os.walk(root):
        depth = dirpath.replace(root, '').count(os.sep)
        if depth > 3: continue
        has_db = any(f.endswith('.db') for f in files)
        has_db_storage = 'db_storage' in dirs
        indent = '  ' * depth
        basename = os.path.basename(dirpath)
        if has_db_storage or has_db or depth <= 1:
            db_count = len([f for f in files if f.endswith('.db')])
            print(f'{indent}{basename}/ db_storage={has_db_storage} dbs={db_count}')
            if has_db_storage:
                ds = os.path.join(dirpath, 'db_storage')
                for fn in sorted(os.listdir(ds))[:8]:
                    fp = os.path.join(ds, fn)
                    if os.path.isfile(fp):
                        sz = os.path.getsize(fp)
                        print(f'{indent}  {fn}: {sz:,}')
