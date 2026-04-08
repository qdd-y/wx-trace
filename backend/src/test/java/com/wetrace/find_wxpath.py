import os

keywords = ['wechatDbPath', 'wechatDbSrc', 'wxid_', 'findWeChatPath', 'getDataPath',
            'DataFiles', 'db_storage', 'RegQuery', 'DataPath']

for root, dirs, files in os.walk(r'D:\Projects\wetrace-java\backend\src'):
    for fn in files:
        if fn.endswith('.java'):
            fp = os.path.join(root, fn)
            with open(fp, 'rb') as f:
                try:
                    text = f.read().decode('utf-8')
                except:
                    continue
            rel = os.path.relpath(fp, r'D:\Projects\wetrace-java')
            for i, line in enumerate(text.split('\n'), 1):
                stripped = line.strip()
                if any(k in stripped for k in keywords):
                    if not stripped.startswith('//') and not stripped.startswith('*'):
                        print(f'{rel}:{i}: {stripped}')
