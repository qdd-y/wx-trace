import os, struct, hashlib, hmac as hmac_mod
from Crypto.Cipher import AES

KEY_HEX = 'e2f4b7faef364edcac37ef9e1117f1141536066e4e184dacba136b8a7d42e51f'
SRC = r'D:\Wechat_temp\WeChat Files\wxid_bet6zjydp93o22\Msg\MicroMsg.db'

PAGE_SIZE = 4096
SALT_SIZE = 16
IV_SIZE = 16
HMAC_SIZE = 64
ITER_COUNT = 256000
KEY_SIZE = 32
RESERVE_SIZE = IV_SIZE + HMAC_SIZE  # 80
data_end = PAGE_SIZE - RESERVE_SIZE  # 4016

key_bytes = bytes.fromhex(KEY_HEX)
salt = open(SRC, 'rb').read(SALT_SIZE)
stored_mac = open(SRC, 'rb').read(PAGE_SIZE)[data_end:data_end + HMAC_SIZE]

print(f'Key: {KEY_HEX}')
print(f'Salt: {salt.hex()}')
print(f'Stored MAC: {stored_mac[:8].hex()}...')
print()

# enc_key derivation
enc_key = hashlib.pbkdf2_hmac('sha512', key_bytes, salt, ITER_COUNT, KEY_SIZE)

# Try different mac key derivations
def try_mac(name, ms_func, data_range, pg_num):
    mk = ms_func(enc_key)
    pg_no = struct.pack('<I', pg_num)
    mac_input = data_range() + pg_no
    mac = hmac_mod.new(mk, mac_input, hashlib.sha512).digest()
    match = mac == stored_mac
    sym = '[OK]' if match else '[NO]'
    print(f'{sym} {name}')
    if match:
        print(f'    MAC: {mac[:8].hex()}...')
        print(f'  FOUND!')
    return match

# Different macSalt derivations
def mac_salt_std(): return bytes(b ^ 0x3a for b in salt)
def mac_salt_raw(): return salt
def mac_salt_zero(): return bytes(16)

# Different data ranges
def range_with_salt(): return salt
def range_no_salt(): return salt[SALT_SIZE:]
def range_offset0(): return salt  # same as with_salt for page 1

# Try combos
results = []
results.append(try_mac(
    'Go-style: macSalt=salt^0x3a, data=salt+pg#',
    lambda ek: hashlib.pbkdf2_hmac('sha512', ek, mac_salt_std(), 2, KEY_SIZE),
    range_with_salt, 1
))
results.append(try_mac(
    'Var2: macSalt=salt, data=salt+pg#',
    lambda ek: hashlib.pbkdf2_hmac('sha512', ek, mac_salt_raw(), 2, KEY_SIZE),
    range_with_salt, 1
))
results.append(try_mac(
    'Var3: macSalt=zero, data=salt+pg#',
    lambda ek: hashlib.pbkdf2_hmac('sha512', ek, mac_salt_zero(), 2, KEY_SIZE),
    range_with_salt, 1
))
results.append(try_mac(
    'Var4: macSalt=salt^0x3a, data=no-salt+pg#',
    lambda ek: hashlib.pbkdf2_hmac('sha512', ek, mac_salt_std(), 2, KEY_SIZE),
    range_no_salt, 1
))

# Also try without page number
def try_no_pg(name, ms_func, data_range):
    mk = ms_func(enc_key)
    mac_input = data_range()
    mac = hmac_mod.new(mk, mac_input, hashlib.sha512).digest()
    match = mac == stored_mac
    sym = '[OK]' if match else '[NO]'
    print(f'{sym} {name} (no page#)')
    if match:
        print(f'    MAC: {mac[:8].hex()}...')
        print(f'  FOUND!')
    return match

print()
try_no_pg('No-pg# std', lambda ek: hashlib.pbkdf2_hmac('sha512', ek, mac_salt_std(), 2, KEY_SIZE), range_with_salt)
try_no_pg('No-pg# raw', lambda ek: hashlib.pbkdf2_hmac('sha512', ek, mac_salt_raw(), 2, KEY_SIZE), range_with_salt)
try_no_pg('No-pg# no-salt', lambda ek: hashlib.pbkdf2_hmac('sha512', ek, mac_salt_std(), 2, KEY_SIZE), range_no_salt)

if not any(results):
    print()
    print('All failed. Trying to brute-force:')
    # Maybe key is wrong. Try different iterations
    for iters in [64000, 128000, 4000, 40000]:
        ek = hashlib.pbkdf2_hmac('sha512', key_bytes, salt, iters, KEY_SIZE)
        mk = hashlib.pbkdf2_hmac('sha512', ek, bytes(b ^ 0x3a for b in salt), 2, KEY_SIZE)
        mac = hmac_mod.new(mk, salt + struct.pack('<I', 1), hashlib.sha512).digest()
        match = mac == stored_mac
        print(f'  iter={iters}: {"MATCH!" if match else "no"}')
        if match:
            print(f'  FOUND! iterations={iters}')
            break
