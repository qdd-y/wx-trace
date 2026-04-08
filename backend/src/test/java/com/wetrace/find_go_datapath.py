import os

keywords = ['DataPath', 'data_path', 'db_storage', 'WxDir', 'wxid_', 'DataDir',
            'FindWeChat', 'FindWx', 'registry', 'RegQuery', 'InstallPath', 'DataFiles']

results = []
for root, dirs, files in os.walk(r'D:\Projects\wetrace'):
    for fn in files:
        if fn.endswith('.go'):
            fp = os.path.join(root, fn)
            with open(fp, 'rb') as f:
                try:
                    text = f.read().decode('utf-8', errors='replace')
                except:
                    continue
            rel = os.path.relpath(fp, r'D:\Projects\wetrace')
            for i, line in enumerate(text.split('\n'), 1):
                stripped = line.strip()
                if any(k in stripped for k in keywords):
                    if not stripped.startswith('//') and not stripped.startswith('*'):
                        results.append(f'{rel}:{i}: {stripped}')

for r in results:
    print(r)
