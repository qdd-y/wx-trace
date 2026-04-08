import os, struct, hashlib, hmac as hmac_mod

KEY_HEX = 'e2f4b7faef364edcac37ef9e1117f1141536066e4e184dacba136b8a7d42e51f'
PAGE_SIZE = 4096; SALT_SIZE = 16; HMAC_SIZE = 64
ITER_COUNT = 256000; KEY_SIZE = 32
key_bytes = bytes.fromhex(KEY_HEX)

def derive_keys(key, salt):
    enc_key = hashlib.pbkdf2_hmac('sha512', key, salt, ITER_COUNT, KEY_SIZE)
    mac_salt = bytes(b ^ 0x3a for b in salt)
    mac_key = hashlib.pbkdf2_hmac('sha512', enc_key, mac_salt, 2, KEY_SIZE)
    return enc_key, mac_key

def test_file(fp):
    size = os.path.getsize(fp)
    if size < PAGE_SIZE: return None
    with open(fp, 'rb') as f:
        page1 = f.read(PAGE_SIZE)
        f.seek(PAGE_SIZE)
        page2 = f.read(PAGE_SIZE)
    if page1[:15] == b'SQLite format 3': return 'plaintext'
    
    salt = page1[:SALT_SIZE]
    _, mac_key = derive_keys(key_bytes, salt)
    
    for de in range(4000, 4100, 16):
        for p1o, p2o in [(16, 0), (0, 0)]:
            pg1 = struct.pack('<I', 1)
            pg2 = struct.pack('<I', 2)
            m1 = hmac_mod.new(mac_key, page1[p1o:de] + pg1, hashlib.sha512).digest()
            m2 = hmac_mod.new(mac_key, page2[p2o:de] + pg2, hashlib.sha512).digest()
            h1 = page1[de:de+HMAC_SIZE]
            h2 = page2[de:de+HMAC_SIZE]
            if m1 == h1 and m2 == h2:
                return f'MATCH de={de} p1off={p1o} p2off={p2o}'
    return None

base = r'D:\xwechat_files\wxid_bet6zjydp93o22_26bc\db_storage'
checked = 0
matched = 0

for dirpath, dirs, files in os.walk(base):
    for fn in files:
        if not fn.endswith('.db'): continue
        fp = os.path.join(dirpath, fn)
        checked += 1
        size = os.path.getsize(fp)
        rel = os.path.relpath(fp, base)
        result = test_file(fp)
        if result and result != 'plaintext':
            matched += 1
            print(f'[MATCH] {rel:40s} {size:>12,d}  {result}')
        elif result == 'plaintext':
            print(f'[PLAIN] {rel:40s} {size:>12,d}')

print(f'\nTotal: {checked} files, {matched} HMAC matches')
