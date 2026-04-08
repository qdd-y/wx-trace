"""
Brute-force search: find which database file the key works for.
Scan ALL .db files in ALL locations, test HMAC on first 2 pages.
"""
import os, struct, hashlib, hmac as hmac_mod, sqlite3
from Crypto.Cipher import AES

KEY_HEX = 'e2f4b7faef364edcac37ef9e1117f1141536066e4e184dacba136b8a7d42e51f'
OUT_DIR = r'D:\Projects\wetrace-java\backend\target\data'

PAGE_SIZE = 4096; SALT_SIZE = 16; IV_SIZE = 16; HMAC_SIZE = 64
ITER_COUNT = 256000; KEY_SIZE = 32
SQLITE_HEADER = b"SQLite format 3\x00"

key_bytes = bytes.fromhex(KEY_HEX)

def derive_keys(key, salt):
    enc_key = hashlib.pbkdf2_hmac('sha512', key, salt, ITER_COUNT, KEY_SIZE)
    mac_salt = bytes(b ^ 0x3a for b in salt)
    mac_key = hashlib.pbkdf2_hmac('sha512', enc_key, mac_salt, 2, KEY_SIZE)
    return enc_key, mac_key

def is_sqlite_plaintext(path):
    """Fast check: does the file start with SQLite header?"""
    try:
        with open(path, 'rb') as f:
            hdr = f.read(16)
        return hdr[:15] == b'SQLite format 3'
    except:
        return False

def test_file(path):
    """Test if this file's HMAC matches the key. Try ALL plausible (dataEnd, enc_off) combos."""
    try:
        size = os.path.getsize(path)
        if size < PAGE_SIZE: return None

        with open(path, 'rb') as f:
            page1 = f.read(PAGE_SIZE)
            f.seek(PAGE_SIZE)
            page2_raw = f.read(PAGE_SIZE)

        if is_sqlite_plaintext(path): return None

        salt = page1[:SALT_SIZE]
        enc_key, mac_key = derive_keys(key_bytes, salt)

        found_combos = []

        # Try all plausible (dataEnd, enc_off) combos
        # We know: enc_off is 0 or 16
        # We know: dataEnd is around 4000-4096
        # HMAC = HMAC(key, page[enc_off:dataEnd] + pageNo_LE)
        # Try: dataEnd from 4000 to 4096 in steps of 16
        for data_end in range(4000, 4100, 16):
            for enc_off in [0, 16]:
                try:
                    enc_range1 = page1[enc_off:data_end]
                    enc_range2 = page2_raw[enc_off:data_end]
                    if len(enc_range1) < 16 or len(enc_range2) < 16: continue

                    pg1 = struct.pack('<I', 1)
                    pg2 = struct.pack('<I', 2)
                    mac1 = hmac_mod.new(mac_key, enc_range1 + pg1, hashlib.sha512).digest()
                    mac2 = hmac_mod.new(mac_key, enc_range2 + pg2, hashlib.sha512).digest()

                    hm1 = page1[data_end:data_end + HMAC_SIZE]
                    hm2 = page2_raw[data_end:data_end + HMAC_SIZE]

                    if mac1 == hm1 and mac2 == hm2:
                        found_combos.append((data_end, enc_off, 'both'))
                    elif mac1 == hm1:
                        found_combos.append((data_end, enc_off, 'p1_only'))
                    elif mac2 == hm2:
                        found_combos.append((data_end, enc_off, 'p2_only'))
                except:
                    pass

        return found_combos
    except:
        return None

# Scan locations
search_roots = [
    r'D:\Wechat_temp',
    os.path.expandvars(r'%APPDATA%\Tencent\WeChat'),
    os.path.expandvars(r'%LOCALAPPDATA%\Tencent\WeChat'),
    r'D:\',
]

print(f"Key: {KEY_HEX[:32]}...")
print(f"Scanning all .db files for matching HMAC...\n")

checked = 0
for root in search_roots:
    if not os.path.isdir(root): continue
    print(f"Scanning: {root}")
    for dirpath, dirs, files in os.walk(root):
        for fn in files:
            if not fn.endswith('.db'): continue
            fp = os.path.join(dirpath, fn)
            checked += 1
            if checked % 500 == 0:
                print(f"  checked {checked}...")

            try:
                size = os.path.getsize(fp)
                if size < PAGE_SIZE: continue
            except:
                continue

            combos = test_file(fp)
            if combos:
                print(f"\n*** MATCH FOUND: {fp}")
                print(f"    Size: {size:,}")
                for c in combos:
                    print(f"    Combo: dataEnd={c[0]}, enc_off={c[1]}, match={c[2]}")

                # Try full decryption with best combo
                data_end, enc_off, match_type = combos[0]
                with open(fp, 'rb') as f:
                    page1 = f.read(PAGE_SIZE)
                salt = page1[:SALT_SIZE]
                enc_key, mac_key = derive_keys(key_bytes, salt)
                iv = page1[PAGE_SIZE - IV_SIZE:PAGE_SIZE]
                enc_data = page1[enc_off:data_end]

                cipher = AES.new(enc_key, AES.MODE_CBC, iv)
                dec = cipher.decrypt(enc_data)
                print(f"    Encrypted range: page[{enc_off}:{data_end}] = {data_end - enc_off}")
                print(f"    Decrypted[:32]: {dec[:32].hex()}")
                print(f"    SQLite header: {dec[:16] == SQLITE_HEADER}")
                print(f"    Salt in header: {dec[:16].hex()}")

                # Write test
                out = os.path.join(OUT_DIR, f'found_match_{os.path.basename(fp)}')
                with open(out, 'wb') as f:
                    f.write(SQLITE_HEADER)
                    f.write(dec)
                    f.seek(PAGE_SIZE)
                    total = (size + PAGE_SIZE - 1) // PAGE_SIZE
                    for i in range(2, min(total + 1, 200)):
                        buf = f.raw.read(PAGE_SIZE)
                        if not buf: break
                        if all(b == 0 for b in buf):
                            f.raw.write(buf)
                            continue
                        pg_no = struct.pack('<I', i)
                        mac_in = buf[enc_off:data_end] + pg_no
                        calc = hmac_mod.new(mac_key, mac_in, hashlib.sha512).digest()
                        if calc != buf[data_end:data_end + HMAC_SIZE]:
                            break
                        iv2 = buf[PAGE_SIZE - IV_SIZE:PAGE_SIZE]
                        enc2 = buf[enc_off:data_end]
                        c2 = AES.new(enc_key, AES.MODE_CBC, iv2)
                        dec2 = c2.decrypt(enc2)
                        f.raw.write(dec2)

                try:
                    conn = sqlite3.connect(f'file:{out}?mode=ro', uri=True)
                    cur = conn.cursor()
                    tables = cur.execute('SELECT name FROM type=? LIMIT 5', ('table',)).fetchall()
                    print(f"    SQLite OK: {[t[0] for t in tables]}")
                    conn.close()
                except:
                    pass
                print()

    print(f"  Done scanning {root}: checked {checked} total\n")

print(f"Total checked: {checked}")
