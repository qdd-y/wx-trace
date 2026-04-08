import os, struct, hashlib, hmac as hmac_mod

KEY_HEX = 'e2f4b7faef364edcac37ef9e1117f1141536066e4e184dacba136b8a7d42e51f'
PAGE_SIZE = 4096; SALT_SIZE = 16; IV_SIZE = 16; HMAC_SIZE = 64
ITER_COUNT = 256000; KEY_SIZE = 32
key_bytes = bytes.fromhex(KEY_HEX)

def derive_keys(key, salt):
    enc_key = hashlib.pbkdf2_hmac('sha512', key, salt, ITER_COUNT, KEY_SIZE)
    mac_salt = bytes(b ^ 0x3a for b in salt)
    mac_key = hashlib.pbkdf2_hmac('sha512', enc_key, mac_salt, 2, KEY_SIZE)
    return enc_key, mac_key

def test_file(fp, verbose=False):
    try:
        size = os.path.getsize(fp)
        if size < PAGE_SIZE: return None
        with open(fp, 'rb') as f:
            page1 = f.read(PAGE_SIZE)
            f.seek(PAGE_SIZE)
            page2 = f.read(PAGE_SIZE)
        hdr = page1[:15].hex()
        is_sqlite = hdr[:30] == '53514c69746520666f726d6174'
        if is_sqlite: return None

        salt = page1[:SALT_SIZE]
        enc_key, mac_key = derive_keys(key_bytes, salt)

        # Try Go's dataEnd=4032 (offset=16 for page1, 0 for page2)
        for data_end, p1_off, p2_off in [(4032, 16, 0), (4016, 16, 0), (4096, 16, 0)]:
            pg1 = struct.pack('<I', 1)
            pg2 = struct.pack('<I', 2)
            m1 = hmac_mod.new(mac_key, page1[p1_off:data_end] + pg1, hashlib.sha512).digest()
            m2 = hmac_mod.new(mac_key, page2[p2_off:data_end] + pg2, hashlib.sha512).digest()
            h1 = page1[data_end:data_end+HMAC_SIZE]
            h2 = page2[data_end:data_end+HMAC_SIZE]
            if m1 == h1 and m2 == h2:
                return (data_end, p1_off, p2_off, 'both')
            elif m1 == h1:
                return (data_end, p1_off, p2_off, 'p1_only')
            elif m2 == h2:
                return (data_end, p1_off, p2_off, 'p2_only')
        return None
    except:
        return None

# Scan all .db files in known locations
search = [
    r'D:\Wechat_temp',
]

print(f'Key: {KEY_HEX[:32]}...')
print()

for root in search:
    if not os.path.isdir(root): continue
    print(f'Scanning: {root}')
    found_any = False
    for dirpath, dirs, files in os.walk(root):
        for fn in files:
            if not fn.endswith('.db'): continue
            fp = os.path.join(dirpath, fn)
            size = os.path.getsize(fp)
            if size < PAGE_SIZE: continue
            result = test_file(fp)
            if result:
                found_any = True
                data_end, p1_off, p2_off, match = result
                print(f'  MATCH: {os.path.relpath(fp, root)}')
                print(f'    size={size:,}  dataEnd={data_end}  p1_off={p1_off}  p2_off={p2_off}  match={match}')
    if not found_any:
        print(f'  No matches in {root}')
