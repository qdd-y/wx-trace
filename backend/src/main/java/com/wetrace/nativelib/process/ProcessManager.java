package com.wetrace.nativelib.process;

import com.sun.jna.*;
import com.sun.jna.platform.win32.*;
import com.sun.jna.platform.win32.WinDef.*;
import com.sun.jna.platform.win32.WinNT.HANDLE;
import com.sun.jna.ptr.IntByReference;
import com.sun.jna.ptr.PointerByReference;
import lombok.extern.slf4j.Slf4j;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.Locale;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Windows Process Manager (Pure JNA Implementation)
 */
@Slf4j
public class ProcessManager {

    // ==================== JNA Interfaces ====================

    public interface Kernel32 extends Library {
        Kernel32 INSTANCE = Native.load("kernel32", Kernel32.class);

        HANDLE CreateToolhelp32Snapshot(int dwFlags, int th32ProcessID);
        boolean Process32First(HANDLE hSnapshot, PROCESSENTRY32 lppe);
        boolean Process32Next(HANDLE hSnapshot, PROCESSENTRY32 lppe);
        boolean CloseHandle(HANDLE hObject);
        HANDLE OpenProcess(int dwDesiredAccess, boolean bInheritHandle, int dwProcessId);
        boolean TerminateProcess(HANDLE hProcess, int uExitCode);
        boolean ReadProcessMemory(HANDLE hProcess, Pointer lpBaseAddress, byte[] lpBuffer, int nSize, IntByReference lpNumberOfBytesRead);
        boolean WriteProcessMemory(HANDLE hProcess, Pointer lpBaseAddress, byte[] lpBuffer, int nSize, IntByReference lpNumberOfBytesWritten);
        Pointer VirtualAllocEx(HANDLE hProcess, Pointer lpAddress, int dwSize, int flAllocationType, int flProtect);
        boolean VirtualFreeEx(HANDLE hProcess, Pointer lpAddress, int dwSize, int dwFreeType);
        HANDLE CreateRemoteThread(HANDLE hProcess, Pointer lpThreadAttributes, int dwStackSize,
                                  Pointer lpStartAddress, Pointer lpParameter, int dwCreationFlags, IntByReference lpThreadId);
        Pointer GetProcAddress(HANDLE hModule, String lpProcName);
        HANDLE GetModuleHandleA(String lpModuleName);
        int WaitForSingleObject(HANDLE hHandle, int dwMilliseconds);
        int GetLastError();
    }

    public interface User32 extends Library {
        User32 INSTANCE = Native.load("user32", User32.class);
        boolean EnumWindows(Callback lpEnumFunc, Pointer lParam);
        int GetWindowThreadProcessId(HWND hWnd, IntByReference lpdwProcessId);
        boolean IsWindowVisible(HWND hWnd);
        int GetWindowTextA(HWND hWnd, byte[] lpString, int nMaxCount);
        boolean ShowWindow(HWND hWnd, int nCmdShow);
        boolean SetForegroundWindow(HWND hWnd);
    }

    public interface Advapi32 extends Library {
        Advapi32 INSTANCE = Native.load("advapi32", Advapi32.class);
        int RegOpenKeyExA(int hKey, String lpSubKey, int ulOptions, int samDesired, IntByReference phkResult);
        int RegQueryValueExA(int hKey, String lpValueName, int lpReserved, IntByReference lpType, byte[] lpData, IntByReference lpcbData);
        int RegCloseKey(int hKey);
    }

    public interface Shell32 extends Library {
        Shell32 INSTANCE = Native.load("shell32", Shell32.class);
        boolean ShellExecuteExA(SHELLEXECUTEINFO lpExecInfo);
    }

    // ==================== Constants ====================

    private static final int TH32CS_SNAPPROCESS = 0x00000002;
    private static final int PROCESS_TERMINATE = 0x0001;
    private static final int PROCESS_QUERY_INFORMATION = 0x0400;
    private static final int PROCESS_VM_READ = 0x0010;
    private static final int PROCESS_VM_WRITE = 0x0020;
    private static final int PROCESS_VM_OPERATION = 0x0008;
    private static final int PROCESS_CREATE_THREAD = 0x0002;
    private static final int MEM_COMMIT = 0x1000;
    private static final int MEM_RESERVE = 0x2000;
    private static final int MEM_RELEASE = 0x8000;
    private static final int PAGE_READWRITE = 0x04;
    private static final int PAGE_EXECUTE_READWRITE = 0x40;
    private static final int KEY_READ = 0x20019;
    private static final int SW_SHOW = 5;
    private static final int SW_MINIMIZE = 6;
    private static final int INFINITE = -1;
    private static final int WAIT_OBJECT_0 = 0;
    private static final int WAIT_TIMEOUT = 0x00000102;
    public static final int HKEY_LOCAL_MACHINE = 0x80000002;
    public static final int HKEY_CURRENT_USER = 0x80000001;

    // ==================== Structures ====================

    public static class PROCESSENTRY32 extends Structure {
        public int dwSize;
        public int cntUsage;
        public int th32ProcessID;
        public int th32DefaultHeapID;
        public int th32ModuleID;
        public int cntThreads;
        public int th32ParentProcessID;
        public int pcPriClassBase;
        public int dwFlags;
        public byte[] szExeFile = new byte[260];

        public PROCESSENTRY32() { dwSize = size(); }
        @Override protected List<String> getFieldOrder() {
            return Arrays.asList("dwSize", "cntUsage", "th32ProcessID", "th32DefaultHeapID",
                "th32ModuleID", "cntThreads", "th32ParentProcessID", "pcPriClassBase", "dwFlags", "szExeFile");
        }
        public String getExeFile() { return Native.toString(szExeFile); }
    }

    public static class MEMORY_BASIC_INFORMATION64 extends Structure {
        public long BaseAddress;
        public long AllocationBase;
        public int AllocationProtect;
        public int __alignment1;
        public long RegionSize;
        public int State;
        public int Protect;
        public int Type;
        public int __alignment2;
        @Override protected List<String> getFieldOrder() {
            return Arrays.asList("BaseAddress", "AllocationBase", "AllocationProtect", "__alignment1",
                "RegionSize", "State", "Protect", "Type", "__alignment2");
        }
    }

    public static class SHELLEXECUTEINFO extends Structure {
        public int cbSize;
        public int fMask;
        public HWND hwnd;
        public String lpVerb;
        public String lpFile;
        public String lpParameters;
        public String lpDirectory;
        public int nShow;
        public HANDLE hInstApp;
        public Pointer lpIDList;
        public String lpClass;
        public HANDLE hkeyClass;
        public int dwHotKey;
        public Pointer hIcon;
        public HANDLE hProcess;
        public SHELLEXECUTEINFO() { cbSize = size(); }
        @Override protected List<String> getFieldOrder() {
            return Arrays.asList("cbSize", "fMask", "hwnd", "lpVerb", "lpFile", "lpParameters",
                "lpDirectory", "nShow", "hInstApp", "lpIDList", "lpClass", "hkeyClass", "dwHotKey", "hIcon", "hProcess");
        }
    }

    public interface EnumWindowsProc extends Callback {
        boolean callback(HWND hWnd, Pointer lParam);
    }

    // ==================== Process Management ====================

    public int getProcessId(String processName) {
        // Try PowerShell first (most reliable on Windows without elevation)
        List<Integer> pids = getProcessIdsViaPowerShell(processName);
        if (!pids.isEmpty()) return pids.get(0);
        // Fallback: JNA CreateToolhelp32Snapshot
        HANDLE hSnapshot = Kernel32.INSTANCE.CreateToolhelp32Snapshot(TH32CS_SNAPPROCESS, 0);
        if (hSnapshot == null) return 0;
        try {
            PROCESSENTRY32 pe32 = new PROCESSENTRY32();
            if (!Kernel32.INSTANCE.Process32First(hSnapshot, pe32)) return 0;
            do {
                if (pe32.getExeFile().equalsIgnoreCase(processName)) {
                    return pe32.th32ProcessID;
                }
            } while (Kernel32.INSTANCE.Process32Next(hSnapshot, pe32));
        } finally {
            Kernel32.INSTANCE.CloseHandle(hSnapshot);
        }
        return 0;
    }

    public List<Integer> getProcessIds(String processName) {
        // Try PowerShell first (most reliable)
        List<Integer> pids = getProcessIdsViaPowerShell(processName);
        if (!pids.isEmpty()) return pids;
        // Fallback: JNA CreateToolhelp32Snapshot
        pids = new ArrayList<>();
        HANDLE hSnapshot = Kernel32.INSTANCE.CreateToolhelp32Snapshot(TH32CS_SNAPPROCESS, 0);
        if (hSnapshot == null) return pids;
        try {
            PROCESSENTRY32 pe32 = new PROCESSENTRY32();
            if (!Kernel32.INSTANCE.Process32First(hSnapshot, pe32)) return pids;
            do {
                if (pe32.getExeFile().equalsIgnoreCase(processName)) {
                    pids.add(pe32.th32ProcessID);
                }
            } while (Kernel32.INSTANCE.Process32Next(hSnapshot, pe32));
        } finally {
            Kernel32.INSTANCE.CloseHandle(hSnapshot);
        }
        return pids;
    }

    /**
     * Enumerate process PIDs via PowerShell (Get-Process).
     * This is more reliable than CreateToolhelp32Snapshot when running without elevation.
     */
    private List<Integer> getProcessIdsViaPowerShell(String processName) {
        List<Integer> pids = new ArrayList<>();
        try {
            String baseName = processName;
            if (baseName.toLowerCase(Locale.ROOT).endsWith(".exe")) {
                baseName = baseName.substring(0, baseName.length() - 4);
            }
            ProcessBuilder pb = new ProcessBuilder(
                "powershell", "-NoProfile", "-NonInteractive",
                "-Command",
                "Get-Process " + baseName + " -ErrorAction SilentlyContinue | Select-Object -ExpandProperty Id"
            );
            pb.redirectErrorStream(true);
            Process p = pb.start();
            BufferedReader reader = new BufferedReader(new InputStreamReader(p.getInputStream(), StandardCharsets.UTF_8));
            String line;
            while ((line = reader.readLine()) != null) {
                line = line.trim();
                if (!line.isEmpty() && line.matches("\\d+")) {
                    pids.add(Integer.parseInt(line));
                }
            }
            p.waitFor();
            log.debug("[ProcessManager] PowerShell getProcessIds({}) = {}", processName, pids);
        } catch (Exception e) {
            log.warn("[ProcessManager] PowerShell process scan failed: {}", e.getMessage());
        }
        return pids;
    }

    /**
     * Get process working set size (bytes) by PID via PowerShell.
     */
    public long getProcessWorkingSetBytes(int pid) {
        if (pid <= 0) {
            return 0L;
        }
        try {
            ProcessBuilder pb = new ProcessBuilder(
                "powershell", "-NoProfile", "-NonInteractive",
                "-Command",
                "Get-Process -Id " + pid + " -ErrorAction SilentlyContinue | Select-Object -ExpandProperty WorkingSet64"
            );
            pb.redirectErrorStream(true);
            Process p = pb.start();
            BufferedReader reader = new BufferedReader(new InputStreamReader(p.getInputStream(), StandardCharsets.UTF_8));
            String line;
            long ws = 0L;
            while ((line = reader.readLine()) != null) {
                line = line.trim();
                if (!line.isEmpty() && line.matches("\\d+")) {
                    ws = Long.parseLong(line);
                    break;
                }
            }
            p.waitFor();
            return ws;
        } catch (Exception e) {
            log.debug("[ProcessManager] getProcessWorkingSetBytes({}) failed: {}", pid, e.toString());
            return 0L;
        }
    }

    public boolean isProcessRunning(String processName) {
        return getProcessId(processName) != 0;
    }

    public boolean killProcess(String processName) {
        int pid = getProcessId(processName);
        if (pid == 0) return true;
        HANDLE hProcess = Kernel32.INSTANCE.OpenProcess(PROCESS_TERMINATE, false, pid);
        if (hProcess == null) return false;
        try {
            return Kernel32.INSTANCE.TerminateProcess(hProcess, 0);
        } finally {
            Kernel32.INSTANCE.CloseHandle(hProcess);
        }
    }

    /**
     * 关闭指定进程名的所有实例。
     */
    public int killProcesses(String processName) {
        int killed = 0;
        for (Integer pid : getProcessIds(processName)) {
            HANDLE hProcess = Kernel32.INSTANCE.OpenProcess(PROCESS_TERMINATE, false, pid);
            if (hProcess == null) {
                continue;
            }
            try {
                if (Kernel32.INSTANCE.TerminateProcess(hProcess, 0)) {
                    killed++;
                }
            } finally {
                Kernel32.INSTANCE.CloseHandle(hProcess);
            }
        }
        return killed;
    }

    /**
     * 关闭所有微信进程。
     * 进程名为 WeiXin.exe（大小写敏感），taskkill 需 /f 强制终止。
     */
    public int killWeChatProcesses() {
        int killed = execTaskkill("WeiXin.exe");
        if (killed == 0) killed = execTaskkill("Weixin.exe");   // fallback
        if (killed == 0) killed = execTaskkill("WeChat.exe");   // fallback
        if (killed == 0) killed = execTaskkill("WeChatAppEx.exe"); // fallback
        return killed;
    }

    /**
     * Run "taskkill /f /im <imagename>" and return number of killed processes.
     * Uses /f for force kill, parses both English and Chinese output.
     */
    private int execTaskkill(String imageName) {
        try {
            ProcessBuilder pb = new ProcessBuilder("taskkill", "/f", "/im", imageName);
            pb.redirectErrorStream(true);
            Process p = pb.start();
            String output = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
            int exitCode = p.waitFor();
            // Count "SUCCESS" (English) or "成功" (Chinese) lines to know how many were killed
            int count = 0;
            for (String line : output.split("\n")) {
                line = line.trim();
                if (line.contains("SUCCESS") || line.contains("成功")) {
                    count++;
                }
            }
            log.info("[ProcessManager] taskkill /f /im {} -> exit={}, killed={}", imageName, exitCode, count);
            return count;
        } catch (Exception e) {
            log.warn("[ProcessManager] taskkill /f /im {} failed: {}", imageName, e.getMessage());
            return 0;
        }
    }

    public void launchWeChat(String wechatPath) throws IOException {
        ProcessBuilder pb = new ProcessBuilder(wechatPath);
        pb.directory(new File(wechatPath).getParentFile());
        pb.start();
    }

    /**
     * Wait for Wechat window and return its PID.
     * 
     * IMPORTANT: Window enumeration can match Chrome tabs with "Wechat" in the title.
     * We verify each candidate PID against known WeChat process names.
     */
    public int waitForWeChatWindow(int timeoutSeconds) {
        Set<String> wechatProcessNames = Set.of("WeiXin.exe", "WeChatAppEx.exe", "WeChat.exe", "Weixin.exe");
        long startTime = System.currentTimeMillis();
        int lastFallbackPid = 0;
        int stableFallbackCount = 0;

        while ((System.currentTimeMillis() - startTime) / 1000 < timeoutSeconds) {
            // Collect all PIDs that have visible windows with "wechat"/"微信" in title
            List<Integer> candidatePids = new ArrayList<>();
            User32.INSTANCE.EnumWindows(new EnumWindowsProc() {
                @Override
                public boolean callback(HWND hWnd, Pointer lParam) {
                    if (!User32.INSTANCE.IsWindowVisible(hWnd)) {
                        return true;
                    }
                    IntByReference pidRef = new IntByReference();
                    User32.INSTANCE.GetWindowThreadProcessId(hWnd, pidRef);
                    byte[] titleBuf = new byte[512];
                    int len = User32.INSTANCE.GetWindowTextA(hWnd, titleBuf, titleBuf.length);
                    if (len > 0) {
                        String title = Native.toString(titleBuf).trim();
                        String lowerTitle = title.toLowerCase(Locale.ROOT);
                        if (lowerTitle.contains("wechat") || title.contains("微信")) {
                            candidatePids.add(pidRef.getValue());
                        }
                    }
                    return true;
                }
            }, null);

            // Verify each candidate against known WeChat process names
            for (Integer pid : candidatePids) {
                if (isWeChatProcess(pid, wechatProcessNames)) {
                    return pid; // Found verified WeChat process
                }
            }

            // Fallback: scan process names after 3 seconds
            if ((System.currentTimeMillis() - startTime) >= 3000) {
                int fallbackPid = getProcessId("WeiXin.exe");
                if (fallbackPid == 0) fallbackPid = getProcessId("Weixin.exe");
                if (fallbackPid == 0) fallbackPid = getProcessId("WeChatAppEx.exe");
                if (fallbackPid == 0) fallbackPid = getProcessId("WeChat.exe");

                if (fallbackPid != 0) {
                    if (fallbackPid == lastFallbackPid) {
                        stableFallbackCount++;
                        if (stableFallbackCount >= 3) {
                            return fallbackPid;
                        }
                    } else {
                        lastFallbackPid = fallbackPid;
                        stableFallbackCount = 1;
                    }
                }
            }

            try { Thread.sleep(500); } catch (InterruptedException ignored) {}
        }
        return 0;
    }

    /**
     * Check if a PID belongs to a WeChat process by verifying its process name.
     */
    private boolean isWeChatProcess(int pid, Set<String> wechatNames) {
        HANDLE hSnapshot = Kernel32.INSTANCE.CreateToolhelp32Snapshot(TH32CS_SNAPPROCESS, 0);
        if (hSnapshot == null) return false;
        try {
            PROCESSENTRY32 pe32 = new PROCESSENTRY32();
            if (!Kernel32.INSTANCE.Process32First(hSnapshot, pe32)) return false;
            do {
                if (pe32.th32ProcessID == pid) {
                    String exeFile = pe32.getExeFile();
                    return wechatNames.contains(exeFile);
                }
            } while (Kernel32.INSTANCE.Process32Next(hSnapshot, pe32));
        } finally {
            Kernel32.INSTANCE.CloseHandle(hSnapshot);
        }
        return false;
    }

    public int findWeChatWindowPid(int timeoutSeconds) {
        AtomicInteger foundPid = new AtomicInteger(0);
        long startTime = System.currentTimeMillis();
        while ((System.currentTimeMillis() - startTime) / 1000 < timeoutSeconds) {
            User32.INSTANCE.EnumWindows(new EnumWindowsProc() {
                @Override
                public boolean callback(HWND hWnd, Pointer lParam) {
                    if (!User32.INSTANCE.IsWindowVisible(hWnd)) {
                        return true;
                    }
                    IntByReference pidRef = new IntByReference();
                    User32.INSTANCE.GetWindowThreadProcessId(hWnd, pidRef);
                    byte[] titleBuf = new byte[256];
                    int len = User32.INSTANCE.GetWindowTextA(hWnd, titleBuf, titleBuf.length);
                    if (len > 0) {
                        String title = Native.toString(titleBuf).trim();
                        String lowerTitle = title.toLowerCase(Locale.ROOT);
                        if (lowerTitle.contains("wechat") || title.contains("微信")) {
                            foundPid.set(pidRef.getValue());
                            return false;
                        }
                    }
                    return true;
                }
            }, null);

            if (foundPid.get() != 0) {
                return foundPid.get();
            }

            try {
                Thread.sleep(300);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return 0;
            }
        }
        return 0;
    }

    /**
     * 获取当前所有可见微信窗口对应的 PID。
     * 对齐 Go 原版：遍历所有窗口，匹配标题包含 "微信/wechat/weixin"
     */
    public List<Integer> getWeChatWindowPids() {
        LinkedHashSet<Integer> pids = new LinkedHashSet<>();
        Set<String> wechatProcessNames = Set.of("WeiXin.exe", "WeChatAppEx.exe", "WeChat.exe", "Weixin.exe");
        // 对齐 Go 原版：不检查 IsWindowVisible，直接遍历所有窗口
        User32.INSTANCE.EnumWindows(new EnumWindowsProc() {
            @Override
            public boolean callback(HWND hWnd, Pointer lParam) {
                byte[] titleBuf = new byte[512]; // 增大缓冲区
                int len = User32.INSTANCE.GetWindowTextA(hWnd, titleBuf, titleBuf.length);
                if (len <= 0) {
                    return true;
                }

                String title = Native.toString(titleBuf).trim();
                // 对齐 Go 原版：检查 "微信" 或 "wechat" 或 "weixin"
                if (!isWeChatTitle(title)) {
                    return true;
                }

                IntByReference pidRef = new IntByReference();
                User32.INSTANCE.GetWindowThreadProcessId(hWnd, pidRef);
                int pid = pidRef.getValue();
                // 必须验证进程名，避免把浏览器标签页（标题含 wechat/weixin）误判为微信窗口
                if (pid > 0 && isWeChatProcess(pid, wechatProcessNames)) {
                    pids.add(pid);
                }
                return true;
            }
        }, null);

        return new ArrayList<>(pids);
    }

    /**
     * 获取微信主界面窗口 PID，排除登录/扫码等临时窗口。
     * 对齐 Go 原版：不检查 IsWindowVisible，直接遍历所有窗口
     */
    public List<Integer> getWeChatMainWindowPids() {
        LinkedHashSet<Integer> pids = new LinkedHashSet<>();
        Set<String> wechatProcessNames = Set.of("WeiXin.exe", "WeChatAppEx.exe", "WeChat.exe", "Weixin.exe");
        // 对齐 Go 原版：直接遍历，不检查 IsWindowVisible
        User32.INSTANCE.EnumWindows(new EnumWindowsProc() {
            @Override
            public boolean callback(HWND hWnd, Pointer lParam) {
                byte[] titleBuf = new byte[512]; // 增大缓冲区
                int len = User32.INSTANCE.GetWindowTextA(hWnd, titleBuf, titleBuf.length);
                if (len <= 0) {
                    return true;
                }

                String title = Native.toString(titleBuf).trim();
                // 过滤登录/扫码窗口
                if (!isWeChatTitle(title) || isLoginLikeTitle(title)) {
                    return true;
                }

                IntByReference pidRef = new IntByReference();
                User32.INSTANCE.GetWindowThreadProcessId(hWnd, pidRef);
                int pid = pidRef.getValue();
                // 必须验证进程名，避免把浏览器标签页（标题含 wechat/weixin）误判为微信窗口
                if (pid > 0 && isWeChatProcess(pid, wechatProcessNames)) {
                    pids.add(pid);
                }
                return true;
            }
        }, null);

        return new ArrayList<>(pids);
    }

    private boolean isWeChatTitle(String title) {
        String lowerTitle = title.toLowerCase(Locale.ROOT);
        return lowerTitle.contains("wechat") || lowerTitle.contains("weixin") || title.contains("微信");
    }

    private boolean isLoginLikeTitle(String title) {
        String lowerTitle = title.toLowerCase(Locale.ROOT);
        return title.contains("登录") || title.contains("扫码") || lowerTitle.contains("login") || lowerTitle.contains("scan");
    }

    // ==================== Registry ====================

    public String readRegistryString(int hKeyRoot, String subKey, String valueName) {
        IntByReference hKey = new IntByReference();
        int ret = Advapi32.INSTANCE.RegOpenKeyExA(hKeyRoot, subKey, 0, KEY_READ, hKey);
        if (ret != 0) return null;
        try {
            IntByReference type = new IntByReference();
            IntByReference size = new IntByReference();
            ret = Advapi32.INSTANCE.RegQueryValueExA(hKey.getValue(), valueName, 0, type, null, size);
            if (ret != 0 || size.getValue() == 0) return null;
            byte[] data = new byte[size.getValue()];
            ret = Advapi32.INSTANCE.RegQueryValueExA(hKey.getValue(), valueName, 0, type, data, size);
            if (ret != 0) return null;
            String result = Native.toString(data);
            return result.isEmpty() ? null : result;
        } finally {
            Advapi32.INSTANCE.RegCloseKey(hKey.getValue());
        }
    }

    public String findWeChatPath() {
        // Uninstall registry
        String[] uninstallKeys = {
            "SOFTWARE\\Microsoft\\Windows\\CurrentVersion\\Uninstall\\WeChat",
            "SOFTWARE\\WOW6432Node\\Microsoft\\Windows\\CurrentVersion\\Uninstall\\WeChat"
        };
        for (String key : uninstallKeys) {
            String path = readRegistryString(HKEY_LOCAL_MACHINE, key, "InstallLocation");
            if (path != null && !path.isEmpty()) {
                String exe = resolveExeFromDir(path, "WeChat.exe");
                if (exe != null) return exe;
            }
        }
        // App Paths
        for (String appName : List.of("WeChat.exe", "WeiXin.exe", "Weixin.exe")) {
            String key = "SOFTWARE\\Microsoft\\Windows\\CurrentVersion\\App Paths\\" + appName;
            String path = readRegistryString(HKEY_LOCAL_MACHINE, key, "");
            if (path != null && !path.isEmpty()) {
                if (fileExists(path)) return path;
                String exe = resolveExeFromDir(path, null);
                if (exe != null) return exe;
            }
        }
        // Tencent registry
        String[][] tencentKeys = {
            {"Software\\Tencent\\WeChat", "InstallPath"},
            {"Software\\Tencent\\Weixin", "InstallPath"},
        };
        for (String[] pair : tencentKeys) {
            String path = readRegistryString(HKEY_CURRENT_USER, pair[0], pair[1]);
            if (path != null && !path.isEmpty()) {
                String exe = resolveExeFromDir(path, "WeChat.exe");
                if (exe != null) return exe;
            }
        }
        // Program Files search
        String[] searchDirs = {
            System.getenv("ProgramFiles"),
            System.getenv("ProgramFiles(x86)"),
            Paths.get(System.getenv("ProgramFiles"), "Tencent").toString(),
            Paths.get(System.getenv("ProgramFiles(x86)"), "Tencent").toString()
        };
        for (String dir : searchDirs) {
            if (dir == null) continue;
            String exe = resolveExeFromDir(dir, "WeChat.exe");
            if (exe != null) return exe;
        }
        return null;
    }

    private String resolveExeFromDir(String dir, String targetName) {
        if (dir == null || dir.isEmpty()) return null;
        Path dirPath = Paths.get(dir);
        if (!Files.isDirectory(dirPath)) {
            dirPath = dirPath.getParent();
            if (!Files.isDirectory(dirPath)) return null;
        }
        String[] candidates = {"WeChat.exe", "Weixin.exe"};
        for (String name : candidates) {
            Path exePath = dirPath.resolve(name);
            if (Files.exists(exePath)) return exePath.toString();
        }
        if (targetName != null) {
            Path target = dirPath.resolve(targetName);
            if (Files.exists(target)) return target.toString();
        }
        try (var stream = Files.walk(dirPath, 2)) {
            Optional<Path> found = stream
                .filter(p -> p.getFileName().toString().toLowerCase().matches("wechat\\.exe|weixin\\.exe"))
                .findFirst();
            if (found.isPresent()) return found.get().toString();
        } catch (IOException ignored) {}
        return null;
    }

    private boolean fileExists(String path) {
        return path != null && Files.exists(Paths.get(path));
    }
}
