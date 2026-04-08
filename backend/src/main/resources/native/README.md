# Native DLL Files

This directory should contain the following DLL files:

## wx_key.dll

Required for extracting WeChat database key through DLL injection.

### How to obtain:

1. **From original WeTrace project**:
   - Build the original Go project, the DLL will be embedded in the binary
   - Or check if you have a pre-built `wx_key.dll` from releases

2. **Alternative sources**:
   - Contact the original project maintainer
   - Build from source if available

### Notes:

- The DLL must be compiled for Windows x64
- It implements the following exported functions:
  - `InitializeHook(targetPid: int) : bool`
  - `PollKeyData(buffer: byte[], len: int) : bool`
  - `GetStatusMessage(buffer: byte[], len: int, level: int*) : bool`
  - `CleanupHook() : bool`
  - `GetLastErrorMsg() : ptr`

If you don't have this DLL, the key extraction feature will not work,
but other features (database decryption, message viewing, export) will still function
if you provide the key manually.
