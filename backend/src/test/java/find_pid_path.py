import os, struct, hashlib, hmac as hmac_mod, sqlite3
from Crypto.Cipher import AES

KEY_HEX = 'e2f4b7faef364edcac37ef9e1117f1141536066e4e184dacba136b8a7d42e51f'
PAGE_SIZE = 4096; SALT_SIZE = 16; IV_SIZE = 16; HMAC_SIZE = 64
ITER_COUNT = 256000; KEY_SIZE = 32
key_bytes = bytes.fromhex(KEY_HEX)

def derive_keys(key, salt):
    enc_key = hashlib.pbkdf2_hmac('sha512', key, salt, ITER_COUNT, KEY_SIZE)
    mac_salt = bytes(b ^ 0x3a for b in salt)
    mac_key = hashlib.pbkdf2_hmac('sha512', enc_key, mac_salt, 2, KEY_SIZE)
    return enc_key, mac_key

def test_hmac(page, data_end, enc_off, mac_k, page_num):
    pg_no = struct.pack('<I', page_num)
    mac_input = page[enc_off:data_end] + pg_no
    calc = hmac_mod.new(mac_k, mac_input, hashlib.sha512).digest()
    stored = page[data_end:data_end + HMAC_SIZE]
    return calc == stored

# 找 pid=36496 的进程打开的文件 -> 找数据目录
import subprocess
result = subprocess.run(
    ['powershell', '-Command',
     'Get-Process -Id 36496 | Select-Object -ExpandProperty Path; '
     '(Get-Process -Id 36496).MainModule.FileName'],
    capture_output=True, text=True, timeout=10
)
print("WeChat process path:")
print(result.stdout)
print(result.stderr[:200] if result.stderr else '')

# 也用 handle 工具找打开的文件
result2 = subprocess.run(
    ['powershell', '-Command',
     '$p = Get-Process -Id 36496; '
     '$p.Modules | Where-Object {$_.FileName -like "*.db"} | Select-Object FileName | Head 10'],
    capture_output=True, text=True, timeout=10
)
print("Modules with .db:")
print(result2.stdout[:500])
