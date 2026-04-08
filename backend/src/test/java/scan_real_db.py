import os, struct, hashlib, hmac as hmac_mod, sqlite3
from Crypto.Cipher import AES

KEY_HEX = 'e2f4b7faef364edcac37ef9e1117f1141536066e4e184dacba136b8a7d42e51f'
PAGE_SIZE = 4096; SALT_SIZE = 16; IV_SIZE = 16; HMAC_SIZE = 64
ITER_COUNT = 256000; KEY_SIZE = 32
key_bytes = bytes.fromhex(KEY_HEX)
SQLITE_HEADER = b"SQLite format 3\x00"

def derive_keys(key, salt):
    enc_key = hashlib.pbkdf2_hmac('sha512', key, salt, ITER_COUNT, KEY_SIZE)
    mac_salt = bytes(b ^ 0x3a for b in salt)
    mac_key = hashlib.pbkdf2_hmac('sha512', enc_key, mac_salt, 2, KEY_SIZE)
    return enc_key, mac_key

def test_hmac(page1, page2, data_end, p1_off, p2_off, mac_k):
    pg1 = struct.pack('<I', 1)
    pg2 = struct.pack('<I', 2)
    m1 = hmac_mod.new(mac_k, page1[p1_off:data_end] + pg1, hashlib.sha512).digest()
    m2 = hmac_mod.new(mac_k, page2[p2_off:data_end] + pg2, hashlib.sha512).digest()
    h1 = page1[data_end:data_end + HMAC_SIZE]
    h2 = page2[data_end:data_end + HMAC_SIZE]
    return m1 == h1, m2 == h2

base = r'D:\xwechat_files\wxid_bet6zjydp93o22_26bc'
print(f'Scanning: {base}\n')

for root, dirs, files in os.walk(base):
    depth = root.replace(base, '').count(os.sep)
    if depth > 3: continue
    
    # Show directory structure
    indent = '  ' * depth
    basename = os.path.basename(root)
    has_db = any(f.endswith('.db') and os.path.getsize(os.path.join(root, f)) >= PAGE_SIZE for f in files)
    has_db_storage = 'db_storage' in dirs
    
    if has_db_storage or has_db or depth <= 1:
        print(f'{indent}{basename}/ db_storage={has_db_storage}')
    
    if has_db_storage:
        ds = os.path.join(root, 'db_storage')
        db_files = [(f, os.path.join(ds, f)) for f in sorted(os.listdir(ds)) 
                    if f.endswith('.db') and os.path.getsize(os.path.join(ds, f)) >= PAGE_SIZE]
        print(f'{indent}  [{len(db_files)} encrypted .db files >= 4KB]')
        
        for fn, fp in db_files:
            size = os.path.getsize(fp)
            with open(fp, 'rb') as f:
                page1 = f.read(PAGE_SIZE)
                f.seek(PAGE_SIZE)
                page2 = f.read(PAGE_SIZE)
            
            is_sqlite = page1[:15] == b'SQLite format 3'
            salt = page1[:SALT_SIZE]
            enc_key, mac_key = derive_keys(key_bytes, salt)
            
            # Test all plausible (dataEnd, offset) combos
            best = None
            for de in range(4000, 4100, 16):
                for p1o, p2o in [(16, 0), (0, 0), (0, 16)]:
                    ok1, ok2 = test_hmac(page1, page2, de, p1o, p2o, mac_key)
                    if ok1 and ok2:
                        best = (de, p1o, p2o)
                        break
                if best: break
            
            status = f'MATCH de={best[0]} p1off={best[1]}' if best else 'no-match'
            print(f'{indent}    {fn:35s} {size:>12,d}  sqlite={is_sqlite}  {status}')
