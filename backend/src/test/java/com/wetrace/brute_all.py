import os, struct, hashlib, hmac as hmac_mod, sqlite3
from Crypto.Cipher import AES

KEY_HEX = 'e2f4b7faef364edcac37ef9e1117f1141536066e4e184dacba136b8a7d42e51f'
SRC_BASE = r'D:\Wechat_temp\WeChat Files'
OUT_DIR = r'D:\Projects\wetrace-java\backend\target\data'

PAGE_SIZE = 4096; SALT_SIZE = 16; IV_SIZE = 16; HMAC_SIZE = 64
ITER_COUNT = 256000; KEY_SIZE = 32; RESERVE_SIZE = 80
DATA_END = PAGE_SIZE - RESERVE_SIZE  # 4016
SQLITE_HEADER = b"SQLite format 3\x00"

def derive_keys(key_bytes, salt):
    enc_key = hashlib.pbkdf2_hmac('sha512', key_bytes, salt, ITER_COUNT, KEY_SIZE)
    mac_salt = bytes(b ^ 0x3a for b in salt)
    mac_key = hashlib.pbkdf2_hmac('sha512', enc_key, mac_salt, 2, KEY_SIZE)
    return enc_key, mac_key

def decrypt_page(page, enc_key, mac_key, page_num):
    offset = SALT_SIZE if page_num == 1 else 0
    pg_no = struct.pack('<I', page_num)
    mac = hmac_mod.new(mac_key, page[offset:DATA_END] + pg_no, hashlib.sha512).digest()
    if mac != page[DATA_END:DATA_END + HMAC_SIZE]:
        raise Exception(f"HMAC mismatch page {page_num}")
    iv = page[PAGE_SIZE - IV_SIZE:PAGE_SIZE]
    cipher = AES.new(enc_key, AES.MODE_CBC, iv)
    decrypted = cipher.decrypt(page[offset:DATA_END])
    return decrypted + page[DATA_END + HMAC_SIZE:]

def decrypt_db(src_path, out_path, key_bytes):
    size = os.path.getsize(src_path)
    total_pages = (size + PAGE_SIZE - 1) // PAGE_SIZE
    with open(src_path, 'rb') as f:
        page1 = f.read(PAGE_SIZE)
    salt = page1[:SALT_SIZE]
    enc_key, mac_key = derive_keys(key_bytes, salt)

    with open(out_path, 'wb') as out:
        out.write(SQLITE_HEADER)
        dec_p1 = decrypt_page(page1, enc_key, mac_key, 1)
        out.write(dec_p1)
        with open(src_path, 'rb') as f:
            f.seek(PAGE_SIZE)
            for i in range(2, total_pages + 1):
                buf = f.read(PAGE_SIZE)
                if not buf: break
                if all(b == 0 for b in buf):
                    out.write(buf)
                    continue
                try:
                    dec_p = decrypt_page(buf, enc_key, mac_key, i)
                    out.write(dec_p)
                except:
                    out.write(buf)

def test_sqlite(path):
    try:
        conn = sqlite3.connect(f'file:{path}?mode=ro', uri=True)
        cur = conn.cursor()
        tables = cur.execute('SELECT name FROM sqlite_master WHERE type=? LIMIT 3', ('table',)).fetchall()
        for t in tables:
            n = cur.execute(f'SELECT count(*) FROM [{t[0]}]').fetchone()[0]
            print(f"      [{t[0]}] {n:,} rows")
        conn.close()
        return True
    except:
        return False

key_bytes = bytes.fromhex(KEY_HEX)
print(f"Scanning ALL .db files under {SRC_BASE}\n")

for wxid in sorted(os.listdir(SRC_BASE)):
    if not wxid.startswith('wxid_'): continue
    wxid_base = os.path.join(SRC_BASE, wxid)
    print(f"=== {wxid} ===")
    for root, dirs, files in os.walk(wxid_base):
        for fn in sorted(files):
            if not fn.endswith('.db'): continue
            fp = os.path.join(root, fn)
            size = os.path.getsize(fp)
            if size < 50000: continue  # skip tiny files
            with open(fp, 'rb') as f:
                page1 = f.read(PAGE_SIZE)
            if page1[:15] == b'SQLite format 3':
                print(f"  {os.path.relpath(fp, SRC_BASE)}: already plaintext SQLite")
                continue
            salt = page1[:SALT_SIZE]
            enc_key, mac_key = derive_keys(key_bytes, salt)
            pg_no = struct.pack('<I', 1)
            mac = hmac_mod.new(mac_key, page1[SALT_SIZE:DATA_END] + pg_no, hashlib.sha512).digest()
            ok = mac == page1[DATA_END:DATA_END + HMAC_SIZE]
            print(f"  {os.path.relpath(fp, SRC_BASE):45s} ({size:>12,d}) salt={salt.hex()} {'*** MATCH ***' if ok else ''}")
            if ok:
                out_path = os.path.join(OUT_DIR, f'test_{wxid}_{fn}')
                print(f"    -> Decrypting to {out_path}...")
                decrypt_db(fp, out_path, key_bytes)
                if test_sqlite(out_path):
                    print(f"    -> SUCCESS! SQLite readable!")
                print()
