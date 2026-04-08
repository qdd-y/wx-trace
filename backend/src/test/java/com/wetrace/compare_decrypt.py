"""
Empirically determine the correct SQLCipher layout by brute-forcing all parameter combinations.
"""
import os, struct, hashlib, hmac as hmac_mod, sqlite3
from Crypto.Cipher import AES

KEY_HEX = 'e2f4b7faef364edcac37ef9e1117f1141536066e4e184dacba136b8a7d42e51f'
DB = r'D:\Wechat_temp\WeChat Files\wxid_bet6zjydp93o22\Msg\MicroMsg.db'
OUT_DIR = r'D:\Projects\wetrace-java\backend\target\data'
PAGE_SIZE = 4096; SALT_SIZE = 16; IV_SIZE = 16; HMAC_SIZE = 64
ITER_COUNT = 256000; KEY_SIZE = 32
SQLITE_HEADER = b"SQLite format 3\x00"

key_bytes = bytes.fromhex(KEY_HEX)
file_size = os.path.getsize(DB)
total_pages = (file_size + PAGE_SIZE - 1) // PAGE_SIZE

with open(DB, 'rb') as f:
    page1 = f.read(PAGE_SIZE)
    f.seek(PAGE_SIZE)
    page2 = f.read(PAGE_SIZE)

salt = page1[:SALT_SIZE]
enc_key = hashlib.pbkdf2_hmac('sha512', key_bytes, salt, ITER_COUNT, KEY_SIZE)
mac_salt = bytes(b ^ 0x3a for b in salt)
mac_key = hashlib.pbkdf2_hmac('sha512', enc_key, mac_salt, 2, KEY_SIZE)

print(f"File: {file_size:,} bytes, pages={total_pages}")
print(f"Salt: {salt.hex()}")
print(f"EncKey: {enc_key.hex()[:32]}...")
print(f"MacKey: {mac_key.hex()[:32]}...")
print()

# Also derive wrong mac_key (Java bug): HMAC key from original key (not encKey)
mac_key_wrong = hashlib.pbkdf2_hmac('sha512', key_bytes, mac_salt, 2, KEY_SIZE)

def test_hmac(page, data_end, enc_off, mac_k, page_num):
    """Returns (hmac_ok, mac_input_debug)"""
    pg_no = struct.pack('<I', page_num)
    mac_input = page[enc_off:data_end] + pg_no
    calc = hmac_mod.new(mac_k, mac_input, hashlib.sha512).digest()
    stored = page[data_end:data_end + HMAC_SIZE]
    return calc == stored, mac_input[:20].hex(), stored[:20].hex()

def try_decrypt_all(data_end, enc_off, mac_k, label):
    """Try HMAC on pages 1 and 2. Returns True if both pass."""
    ok1, _, _ = test_hmac(page1, data_end, enc_off, mac_k, 1)
    ok2, _, _ = test_hmac(page2, data_end, enc_off, mac_k, 2)
    print(f"  {label}: p1={ok1} p2={ok2}")
    return ok1 and ok2

print("=== Brute-force all (data_end, enc_off, mac_key) combos ===")
print()

found = []
for data_end in range(4000, 4100, 16):  # Try multiples of 16 around expected values
    for enc_off in [0, 16]:  # Salt offset
        for mk_name, mk in [('correct', mac_key), ('wrong', mac_key_wrong)]:
            try:
                ok1, mi1, sm1 = test_hmac(page1, data_end, enc_off, mk, 1)
                ok2, mi2, sm2 = test_hmac(page2, data_end, enc_off, mk, 2)
                if ok1 and ok2:
                    label = f"data_end={data_end} enc_off={enc_off} mk={mk_name}"
                    print(f"MATCH: {label}")
                    print(f"  Page1 mac_input[{len(page1[enc_off:data_end])}]: {mi1}  stored: {sm1}")
                    print(f"  Page2 mac_input[{len(page2[enc_off:data_end])}]: {mi2}  stored: {sm2}")
                    found.append((data_end, enc_off, mk, label))
            except:
                pass

print()
if not found:
    print("No exact HMAC match found!")
    print("Trying with salt included in HMAC input...")
    # Maybe HMAC includes the salt at start
    for data_end in [4016, 4032, 4048]:
        for enc_off in [16]:
            for mk_name, mk in [('correct', mac_key), ('wrong', mac_key_wrong)]:
                try:
                    # For page 1: HMAC includes salt (bytes 0-15) before page data
                    pg_no = struct.pack('<I', 1)
                    mac_input = page1[:data_end] + pg_no  # salt + page_data
                    calc = hmac_mod.new(mk, mac_input, hashlib.sha512).digest()
                    stored = page1[data_end:data_end + HMAC_SIZE]
                    ok1 = calc == stored

                    pg_no2 = struct.pack('<I', 2)
                    mac_input2 = page2[:data_end] + pg_no2
                    calc2 = hmac_mod.new(mk, mac_input2, hashlib.sha512).digest()
                    stored2 = page2[data_end:data_end + HMAC_SIZE]
                    ok2 = calc2 == stored2

                    if ok1 and ok2:
                        print(f"MATCH (salt+data): data_end={data_end} enc_off={enc_off} mk={mk_name}")
                        found.append((data_end, enc_off, mk, f"data_end={data_end} salt_in_mac mk={mk_name}"))
                except:
                    pass

print()
print(f"=== Found {len(found)} matching configs ===")
for d_end, e_off, mk, lbl in found:
    print(f"  {lbl}")

# Now try actual decryption with matching configs
print()
print("=== Trying full decryption with matching configs ===")
for d_end, e_off, mk, lbl in found:
    print(f"\nTrying: {lbl}")
    try:
        # Decrypt page 1
        iv = page1[PAGE_SIZE - IV_SIZE:PAGE_SIZE]
        enc_data = page1[e_off:d_end]
        cipher = AES.new(enc_key, AES.MODE_CBC, iv)
        dec = cipher.decrypt(enc_data)

        # Try: result = dec as-is
        result = dec

        # Check for SQLite header
        has_sqlite = result[:16] == SQLITE_HEADER
        has_sqlite_15 = result[:15] == b'SQLite format 3'

        # Also try: prepend salt
        result_salt = page1[:SALT_SIZE] + dec  # salt + dec = 16 + N

        print(f"  Encrypted bytes: page[{e_off}:{d_end}] = {d_end-e_off}")
        print(f"  Decrypted ({len(dec)} bytes)[:32]: {dec[:32].hex()}")
        print(f"  dec[:16] == SQLITE_HEADER: {has_sqlite}")
        print(f"  dec[:15] == b'SQLite format 3': {has_sqlite_15}")
        print(f"  result_salt[:16]: {result_salt[:16].hex()}")
        print(f"  result_salt[:16] == SQLITE_HEADER: {result_salt[:16] == SQLITE_HEADER}")

        # Try writing test DB
        test_path = os.path.join(OUT_DIR, f'test_{d_end}_{e_off}.db')
        with open(test_path, 'wb') as f:
            f.write(SQLITE_HEADER)
            f.write(result)

        # Try full decrypt
        with open(DB, 'rb') as src:
            src.seek(PAGE_SIZE)
            with open(test_path, 'ab') as out:
                for i in range(2, min(total_pages + 1, 100)):  # first 99 pages
                    buf = src.read(PAGE_SIZE)
                    if not buf: break
                    if all(b == 0 for b in buf):
                        out.write(buf)
                        continue
                    pg_no = struct.pack('<I', i)
                    mac_input = buf[e_off:d_end] + pg_no
                    calc_mac = hmac_mod.new(mk, mac_input, hashlib.sha512).digest()
                    if calc_mac != buf[d_end:d_end + HMAC_SIZE]:
                        # Try wrong offset
                        alt_ok = False
                        for alt_e_off in [0, 16]:
                            if alt_e_off == e_off: continue
                            try:
                                mac_input2 = buf[alt_e_off:d_end] + pg_no
                                calc2 = hmac_mod.new(mk, mac_input2, hashlib.sha512).digest()
                                if calc2 == buf[d_end:d_end + HMAC_SIZE]:
                                    alt_ok = True
                                    e_off = alt_e_off
                                    break
                            except: pass
                        if not alt_ok:
                            out.write(buf)
                            continue

                    iv2 = buf[PAGE_SIZE - IV_SIZE:PAGE_SIZE]
                    enc2 = buf[e_off:d_end]
                    c2 = AES.new(enc_key, AES.MODE_CBC, iv2)
                    dec2 = c2.decrypt(enc2)
                    if i == 1:
                        out.write(dec2)  # page 1 was already written
                    else:
                        out.write(dec2)

        # Test SQLite
        try:
            conn = sqlite3.connect(f'file:{test_path}?mode=ro', uri=True)
            cur = conn.cursor()
            tables = cur.execute('SELECT name FROM sqlite_master WHERE type=? LIMIT 5', ('table',)).fetchall()
            print(f"  SQLite [OK]: {[t[0] for t in tables]}")
            for t in tables[:3]:
                n = cur.execute(f'SELECT count(*) FROM [{t[0]}]').fetchone()[0]
                print(f"    [{t[0]}] {n:,} rows")
            conn.close()
            print(f"  *** SUCCESS: {lbl} ***")
        except Exception as e:
            print(f"  SQLite [FAIL]: {str(e)[:80]}")
    except Exception as e:
        print(f"  Error: {e}")
