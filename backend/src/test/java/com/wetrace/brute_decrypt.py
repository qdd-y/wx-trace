import os, struct, hashlib, hmac as hmac_mod
from Crypto.Cipher import AES

KEY_HEX = 'e2f4b7faef364edcac37ef9e1117f1141536066e4e184dacba136b8a7d42e51f'
SRC = r'D:\Wechat_temp\WeChat Files\wxid_bet6zjydp93o22\Msg\MicroMsg.db'
OUT = r'D:\Projects\wetrace-java\backend\target\data\test_no_hmac.db'

PAGE_SIZE, SALT_SIZE, IV_SIZE, HMAC_SIZE = 4096, 16, 16, 64
ITER_COUNT, KEY_SIZE = 256000, 32
RESERVE_SIZE = IV_SIZE + HMAC_SIZE

key_bytes = bytes.fromhex(KEY_HEX)

with open(SRC, 'rb') as f:
    page1 = f.read(PAGE_SIZE)
    file_size = os.path.getsize(SRC)

salt = page1[:SALT_SIZE]
enc_key = hashlib.pbkdf2_hmac('sha512', key_bytes, salt, ITER_COUNT, KEY_SIZE)
mac_salt = bytes(b ^ 0x3a for b in salt)
mac_key = hashlib.pbkdf2_hmac('sha512', enc_key, mac_salt, 2, KEY_SIZE)

print(f'Salt: {salt.hex()}')
print(f'File: {file_size:,} bytes, pages={(file_size+PAGE_SIZE-1)//PAGE_SIZE}')

total_pages = (file_size + PAGE_SIZE - 1) // PAGE_SIZE

def decrypt_page_raw(page, enc_key, page_num, verify_hmac=True):
    offset = SALT_SIZE if page_num == 1 else 0
    data_end = PAGE_SIZE - RESERVE_SIZE

    if verify_hmac:
        page_no = struct.pack('<I', page_num)
        mac = hmac_mod.new(mac_key, page[offset:data_end] + page_no, hashlib.sha512).digest()
        stored_mac = page[data_end:data_end + HMAC_SIZE]
        if mac != stored_mac:
            return None

    iv = page[PAGE_SIZE - IV_SIZE:PAGE_SIZE]
    cipher = AES.new(enc_key, AES.MODE_CBC, iv)
    decrypted = cipher.decrypt(page[offset:data_end])
    return decrypted + page[data_end + HMAC_SIZE:]

# Try decrypting first page (with HMAC check)
dec_p1 = decrypt_page_raw(page1, enc_key, 1, verify_hmac=False)  # skip HMAC
if dec_p1:
    print(f'\nDecrypted page 1 ({len(dec_p1)} bytes):')
    print(f'  First 64 bytes hex: {dec_p1[:64].hex()}')
    text_start = dec_p1[16:100]
    try:
        print(f'  Text (bytes 16-100): {text_start!r}')
    except:
        pass

    # Check for SQLite header at offset 100
    print(f'  Bytes 100-116: {dec_p1[100:116].hex()} = {dec_p1[100:116]}')

    # Check page size in SQLite header (bytes 16-17, big-endian)
    ps = struct.unpack('>H', dec_p1[16:18])[0]
    print(f'  Page size (bytes 16-17): {ps}')

    # Check for readable strings
    printable = sum(1 for b in dec_p1 if 32 <= b < 127)
    print(f'  Printable ASCII: {printable}/{len(dec_p1)} ({100*printable/len(dec_p1):.1f}%)')

    # Write full decrypted file
    with open(OUT, 'wb') as f:
        f.write(b'SQLite format 3\x00')
        f.write(dec_p1)

        with open(SRC, 'rb') as src:
            src.seek(PAGE_SIZE)
            for i in range(2, total_pages + 1):
                buf = src.read(PAGE_SIZE)
                if not buf:
                    break
                if all(b == 0 for b in buf):
                    f.write(buf)
                    continue
                dec_p = decrypt_page_raw(buf, enc_key, i, verify_hmac=False)
                if dec_p:
                    f.write(dec_p)
                else:
                    print(f'  Page {i}: decrypt failed, write raw')
                    f.write(buf)

    print(f'\nWrote to: {OUT}')

    # Try to open with sqlite3
    try:
        import sqlite3
        conn = sqlite3.connect(f'file:{OUT}?mode=ro', uri=True)
        cur = conn.cursor()
        cur.execute('SELECT name FROM sqlite_master WHERE type="table"')
        tables = [r[0] for r in cur.fetchall()]
        print(f'SQLite tables: {tables[:5]}')
        for t in tables[:3]:
            try:
                n = cur.execute(f'SELECT count(*) FROM [{t}]').fetchone()[0]
                print(f'  [{t}]: {n:,} rows')
            except Exception as e:
                print(f'  [{t}]: error - {e}')
        conn.close()
    except Exception as e:
        print(f'SQLite open failed: {e}')
else:
    print('Failed to decrypt page 1')
