"""
Python test: decrypt MicroMsg.db using the 32-byte key.
Matches Go SqlCipherDecryptor exactly.
"""
import os, struct, hashlib, hmac as hmac_mod
from Crypto.Cipher import AES

KEY_HEX = "e2f4b7faef364edcac37ef9e1117f1141536066e4e184dacba136b8a7d42e51f"
SRC = r"D:\Wechat_temp\WeChat Files\wxid_bet6zjydp93o22\Msg\MicroMsg.db"
OUT = r"D:\Projects\wetrace-java\backend\target\data\test_decrypted.db"

PAGE_SIZE = 4096
SALT_SIZE = 16
IV_SIZE = 16
HMAC_SIZE = 64
ITER_COUNT = 256000
KEY_SIZE = 32
RESERVE_SIZE = IV_SIZE + HMAC_SIZE  # 80
SQLITE_HEADER = b"SQLite format 3\x00"

def pbkdf2_sha512(password, salt, iterations, keylen):
    return hashlib.pbkdf2_hmac('sha512', password, salt, iterations, dklen=keylen)

def derive_keys(key_bytes, salt):
    enc_key = pbkdf2_sha512(key_bytes, salt, ITER_COUNT, KEY_SIZE)
    mac_salt = bytes(b ^ 0x3a for b in salt)
    mac_key = pbkdf2_sha512(enc_key, mac_salt, 2, KEY_SIZE)
    return enc_key, mac_key

def decrypt_page(page, enc_key, mac_key, page_num):
    offset = SALT_SIZE if page_num == 1 else 0
    data_end = PAGE_SIZE - RESERVE_SIZE  # 4016

    # Verify HMAC
    page_no = struct.pack('<I', page_num)
    mac = hmac_mod.new(mac_key, page[offset:data_end] + page_no, hashlib.sha512).digest()
    stored_mac = page[data_end:data_end + HMAC_SIZE]
    if mac != stored_mac:
        raise Exception(f"HMAC mismatch on page {page_num}")

    # Decrypt
    iv = page[PAGE_SIZE - IV_SIZE:PAGE_SIZE]
    cipher = AES.new(enc_key, AES.MODE_CBC, iv)
    decrypted = cipher.decrypt(page[offset:data_end])

    # Append reserve area (unmodified bytes after HMAC)
    result = decrypted + page[data_end + HMAC_SIZE:]
    return result

def decrypt_db(src_path, out_path, key_hex):
    key_bytes = bytes.fromhex(key_hex)
    assert len(key_bytes) == 32, f"Key must be 32 bytes, got {len(key_bytes)}"

    with open(src_path, 'rb') as f:
        first_page = f.read(PAGE_SIZE)

    # Check if already decrypted
    if first_page[:15] == b"SQLite format 3":
        print("Already decrypted, copying...")
        with open(out_path, 'wb') as f:
            f.write(first_page)
            while True:
                chunk = f.read(65536)
                if not chunk: break
        return

    salt = first_page[:SALT_SIZE]
    enc_key, mac_key = derive_keys(key_bytes, salt)

    file_size = os.path.getsize(src_path)
    total_pages = (file_size + PAGE_SIZE - 1) // PAGE_SIZE

    with open(out_path, 'wb') as f:
        # Write SQLite header
        f.write(SQLITE_HEADER)

        # Decrypt first page
        dec_p1 = decrypt_page(first_page, enc_key, mac_key, 1)
        f.write(dec_p1)
        print(f"  page 1: HMAC OK, decrypted {len(dec_p1)} bytes")

        # Decrypt remaining pages
        with open(src_path, 'rb') as src:
            src.seek(PAGE_SIZE)
            for i in range(2, total_pages + 1):
                buf = src.read(PAGE_SIZE)
                if not buf:
                    break
                if all(b == 0 for b in buf):
                    f.write(buf)
                    continue
                dec_p = decrypt_page(buf, enc_key, mac_key, i)
                f.write(dec_p)
                print(f"  page {i}: HMAC OK, decrypted {len(dec_p)} bytes")

    print(f"Decrypted: {out_path}")

def test_db(path):
    """Try to open as SQLite and count rows."""
    try:
        import sqlite3
        conn = sqlite3.connect(f"file:{path}?mode=ro", uri=True)
        cur = conn.cursor()

        # Get table names
        cur.execute("SELECT name FROM sqlite_master WHERE type='table'")
        tables = [r[0] for r in cur.fetchall()]
        print(f"  Tables: {tables[:5]}...")

        # Count rows in first big table
        for t in tables:
            try:
                n = cur.execute(f"SELECT count(*) FROM [{t}]").fetchone()[0]
                print(f"  [{t}]: {n:,} rows")
            except: pass

        conn.close()
        return True
    except Exception as e:
        print(f"  SQLite error: {e}")
        return False

if __name__ == "__main__":
    print(f"Source: {SRC}")
    print(f"Key: {KEY_HEX[:16]}...")
    print(f"Output: {OUT}")
    print()

    decrypt_db(SRC, OUT, KEY_HEX)
    print()

    print("Testing decrypted database:")
    test_db(OUT)
