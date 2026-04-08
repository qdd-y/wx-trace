import os

# Go wetrace 的配置文件可能在项目目录或工作目录
for pattern in [r'D:\Projects\wetrace\*.yml', r'D:\Projects\wetrace\*.yaml', r'D:\Projects\wetrace\*.json', 
                r'D:\Projects\wetrace\*.toml', r'D:\Projects\wetrace\*.env',
                r'D:\Projects\wetrace\config\*.*', r'D:\Projects\wetrace\configs\*.*']:
    import glob
    for fp in glob.glob(pattern):
        print(f'\n=== {fp} ===')
        try:
            with open(fp, 'rb') as f: text = f.read().decode('utf-8', errors='replace')
            # Show lines with path/key/data/db
            for i, line in enumerate(text.split('\n'), 1):
                low = line.lower()
                if any(k in low for k in ['datapath', 'data_path', 'db_storage', 'wechat', 'srcpath', 'src_path', 'dir:', 'path:']):
                    print(f'  {i}: {line.rstrip()[:120]}')
        except: pass

# 也看 Go main.go 的 viper 配置 key
for fp in [r'D:\Projects\wetrace\main.go']:
    if os.path.isfile(fp):
        with open(fp, 'rb') as f: text = f.read().decode('utf-8', errors='replace')
        for i, line in enumerate(text.split('\n'), 1):
            if 'viper' in line.lower() or 'WXKEY' in line or 'DataPath' in line or 'WechatDataPath' in line:
                print(f'\nmain.go:{i}: {line.rstrip()[:120]}')
