package com.soundvisualizer;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * Enumerates Windows audio render endpoints (output devices) and the
 * per-application audio sessions that are currently active on each device,
 * using PowerShell + the Windows Core Audio APIs via the
 * {@code System.Windows.Media} / {@code AudioSessionControl} COM interfaces.
 *
 * <p>This is used when the JVM is a <em>Windows</em> JDK (detected by
 * {@code File.separatorChar == '\\'}) so that the "Audio Source" dropdown
 * can show real application names like "Chrome", "Spotify", "VLC" instead
 * of just "Stereo Mix" or "Microphone".
 *
 * <p>Each entry returned by {@link #queryRenderEndpoints()} corresponds to
 * one Windows audio render endpoint (speaker/headphone device).  The user
 * can select any of them; we then capture it using ffmpeg WASAPI loopback
 * ({@code -f wasapi -loopback -i <endpoint-id>}).
 *
 * <p>Each entry returned by {@link #queryActiveSessions()} corresponds to
 * one active audio session (one running application).  Because WASAPI
 * loopback captures a whole render endpoint, the application sessions are
 * shown for information and the user still selects the endpoint that contains
 * them.
 */
public final class WindowsAudioSessions {

    private WindowsAudioSessions() {}

    // -----------------------------------------------------------------------
    // Data types
    // -----------------------------------------------------------------------

    /** One Windows audio render endpoint (physical output device). */
    public record RenderEndpoint(
        /** Friendly name shown in Windows Sound settings, e.g. "Headphones (Realtek)". */
        String friendlyName,
        /**
         * Windows device ID used by ffmpeg WASAPI, e.g.
         * "{0.0.0.00000000}.{xxxxxxxx-xxxx-xxxx-xxxx-xxxxxxxxxxxx}".
         */
        String deviceId,
        /** True if this is the current Windows default playback device. */
        boolean isDefault
    ) {
        @Override public String toString() {
            return friendlyName + (isDefault ? " [default]" : "");
        }
    }

    /** One active audio session (application currently producing sound). */
    public record AudioSession(
        String processName,
        int    pid,
        String endpointId   // which endpoint this session belongs to
    ) {
        @Override public String toString() {
            return processName + " (PID " + pid + ")";
        }
    }

    // -----------------------------------------------------------------------
    // PowerShell query – render endpoints
    // -----------------------------------------------------------------------

    /**
     * Runs a PowerShell script that uses the Windows MMDevice API (via
     * {@code System.Runtime.InteropServices} COM automation) to list every
     * active render endpoint.
     *
     * <p>Returns an empty list and logs a warning if PowerShell is unavailable
     * or the script fails.
     */
    public static List<RenderEndpoint> queryRenderEndpoints() {
        // PowerShell script: loads the AudioDeviceCmdlets-style COM approach
        // via the MMDeviceEnumerator.  Uses only built-in .NET / Windows APIs.
        String script = """
            Add-Type -AssemblyName System.Runtime.InteropServices
            $code = @'
            using System;
            using System.Runtime.InteropServices;
            using System.Collections.Generic;

            [ComImport, Guid("A95664D2-9614-4F35-A746-DE8DB63617E6")]
            [InterfaceType(ComInterfaceType.InterfaceIsIUnknown)]
            interface IMMDeviceEnumerator {
                [PreserveSig] int EnumAudioEndpoints(int dataFlow, int stateMask, out IMMDeviceCollection ppDevices);
                [PreserveSig] int GetDefaultAudioEndpoint(int dataFlow, int role, out IMMDevice ppEndpoint);
                void NotImpl3(); void NotImpl4(); void NotImpl5();
            }
            [ComImport, Guid("0BD7A1BE-7A1A-44DB-8397-CC5392387B5E")]
            [InterfaceType(ComInterfaceType.InterfaceIsIUnknown)]
            interface IMMDeviceCollection {
                [PreserveSig] int GetCount(out uint pcDevices);
                [PreserveSig] int Item(uint nDevice, out IMMDevice ppDevice);
            }
            [ComImport, Guid("D666063F-1587-4E43-81F1-B948E807363F")]
            [InterfaceType(ComInterfaceType.InterfaceIsIUnknown)]
            interface IMMDevice {
                [PreserveSig] int Activate(ref Guid iid, int clsCtx, IntPtr pActivationParams, out object ppInterface);
                [PreserveSig] int OpenPropertyStore(int stgmAccess, out IPropertyStore store);
                [PreserveSig] int GetId([MarshalAs(UnmanagedType.LPWStr)] out string ppstrId);
                void NotImpl4();
            }
            [ComImport, Guid("886D8EEB-8CF2-4446-8D02-CDBA1DBDCF99")]
            [InterfaceType(ComInterfaceType.InterfaceIsIUnknown)]
            interface IPropertyStore {
                [PreserveSig] int GetCount(out uint cProps);
                [PreserveSig] int GetAt(uint iProp, out PropertyKey pkey);
                [PreserveSig] int GetValue(ref PropertyKey key, out PropVariant pv);
                void SetValue(); void Commit();
            }
            [StructLayout(LayoutKind.Sequential)]
            public struct PropertyKey { public Guid fmtid; public uint pid; }
            [StructLayout(LayoutKind.Sequential)]
            public struct PropVariant { public short vt; public short r1,r2,r3; public IntPtr p; public int p2; }
            public static class AudioHelper {
                static readonly Guid CLSID_MMDevEnum = new Guid("BCDE0395-E52F-467C-8E3D-C4579291692E");
                static readonly Guid IID_IMMDevEnum  = new Guid("A95664D2-9614-4F35-A746-DE8DB63617E6");
                static readonly PropertyKey PKEY_Device_FriendlyName = new PropertyKey {
                    fmtid = new Guid("a45c254e-df1c-4efd-8020-67d146a850e0"), pid = 14 };
                public static List<string[]> GetEndpoints() {
                    var result = new List<string[]>();
                    try {
                        Type t = Type.GetTypeFromCLSID(CLSID_MMDevEnum);
                        var enumerator = (IMMDeviceEnumerator)Activator.CreateInstance(t);
                        IMMDevice defaultDev; enumerator.GetDefaultAudioEndpoint(0, 1, out defaultDev);
                        string defaultId; defaultDev.GetId(out defaultId);
                        IMMDeviceCollection col; enumerator.EnumAudioEndpoints(0, 1, out col);
                        uint count; col.GetCount(out count);
                        for (uint i = 0; i < count; i++) {
                            IMMDevice dev; col.Item(i, out dev);
                            string id; dev.GetId(out id);
                            IPropertyStore store; dev.OpenPropertyStore(0, out store);
                            var key = PKEY_Device_FriendlyName;
                            PropVariant pv; store.GetValue(ref key, out pv);
                            string name = Marshal.PtrToStringUni(pv.p);
                            result.Add(new string[]{ id, name ?? "Unknown", id == defaultId ? "1" : "0" });
                        }
                    } catch {}
                    return result;
                }
            }
            '@
            Add-Type -TypeDefinition $code -Language CSharp 2>$null
            try {
                foreach ($e in [AudioHelper]::GetEndpoints()) {
                    Write-Output ("ENDPOINT|" + $e[0] + "|" + $e[1] + "|" + $e[2])
                }
            } catch { Write-Output "ERROR|$_" }
            """;
        List<RenderEndpoint> endpoints = new ArrayList<>();
        try {
            String output = runPowerShell(script);
            CaptureLogger.raw("queryRenderEndpoints PowerShell output", output);
            for (String line : output.split("\r?\n")) {
                line = line.strip();
                if (line.startsWith("ENDPOINT|")) {
                    String[] p = line.split("\\|", 4);
                    if (p.length == 4) {
                        endpoints.add(new RenderEndpoint(p[2], p[1], "1".equals(p[3])));
                    }
                } else if (line.startsWith("ERROR|")) {
                    CaptureLogger.warn("queryRenderEndpoints error: " + line.substring(6));
                }
            }
        } catch (Exception e) {
            CaptureLogger.error("queryRenderEndpoints failed", e);
        }
        CaptureLogger.info("queryRenderEndpoints found " + endpoints.size() + " endpoint(s)");
        return endpoints;
    }

    // -----------------------------------------------------------------------
    // PowerShell query – active audio sessions
    // -----------------------------------------------------------------------

    /**
     * Uses PowerShell + the Windows Audio Session API (WASAPI) to list every
     * process that currently has an active audio session.
     */
    public static List<AudioSession> queryActiveSessions() {
        // Simpler approach: use Get-Process and the Windows Vista+ audio session
        // monitoring via AudioDeviceCmdlets if installed, falling back to a
        // basic COM approach.
        String script = """
            $ErrorActionPreference = 'SilentlyContinue'
            # Try the lightweight approach: enumerate audio graph via WMI/COM
            Add-Type -AssemblyName System.Runtime.InteropServices
            $code = @'
            using System;
            using System.Runtime.InteropServices;
            using System.Collections.Generic;
            [ComImport, Guid("BCDE0395-E52F-467C-8E3D-C4579291692E")]
            [InterfaceType(ComInterfaceType.InterfaceIsIUnknown)]
            interface IMMDeviceEnumerator2 {
                void NotImpl1();
                [PreserveSig] int EnumAudioEndpoints(int flow, int mask, out IMMDevCollection col);
                [PreserveSig] int GetDefaultAudioEndpoint(int flow, int role, out IMMDev dev);
                void NotImpl4(); void NotImpl5();
            }
            [ComImport, Guid("0BD7A1BE-7A1A-44DB-8397-CC5392387B5E")]
            [InterfaceType(ComInterfaceType.InterfaceIsIUnknown)]
            interface IMMDevCollection { [PreserveSig] int GetCount(out uint n); [PreserveSig] int Item(uint i, out IMMDev d); }
            [ComImport, Guid("D666063F-1587-4E43-81F1-B948E807363F")]
            [InterfaceType(ComInterfaceType.InterfaceIsIUnknown)]
            interface IMMDev {
                [PreserveSig] int Activate(ref Guid iid, int ctx, IntPtr p, [MarshalAs(UnmanagedType.IUnknown)] out object obj);
                void NotImpl2(); [PreserveSig] int GetId([MarshalAs(UnmanagedType.LPWStr)] out string id); void NotImpl4();
            }
            [ComImport, Guid("BFA971F1-4D5E-40BB-935E-967039BFBEE4")]
            [InterfaceType(ComInterfaceType.InterfaceIsIUnknown)]
            interface IAudioSessionManager2 {
                void NotImpl1(); void NotImpl2();
                [PreserveSig] int GetSessionEnumerator(out IAudioSessionEnumerator e);
            }
            [ComImport, Guid("E2F5BB11-0570-40CA-ACDD-3AA01277DEE8")]
            [InterfaceType(ComInterfaceType.InterfaceIsIUnknown)]
            interface IAudioSessionEnumerator { [PreserveSig] int GetCount(out int n); [PreserveSig] int GetSession(int i, out IAudioSessionControl s); }
            [ComImport, Guid("24918ACC-64B3-37C1-8CA9-74A66E9957A8")]
            [InterfaceType(ComInterfaceType.InterfaceIsIUnknown)]
            interface IAudioSessionControl { void NotImpl1(); void NotImpl2(); void NotImpl3(); void NotImpl4(); [PreserveSig] int GetState(out int s); void NotImpl6(); void NotImpl7(); void NotImpl8(); }
            [ComImport, Guid("BFB7FF88-7239-4FC9-8FA2-07C950BE9C6D")]
            [InterfaceType(ComInterfaceType.InterfaceIsIUnknown)]
            interface IAudioSessionControl2 {
                void sa1(); void sa2(); void sa3(); void sa4(); [PreserveSig] int GetState(out int s); void sa6(); void sa7(); void sa8();
                [PreserveSig] int GetSessionIdentifier([MarshalAs(UnmanagedType.LPWStr)] out string id);
                [PreserveSig] int GetSessionInstanceIdentifier([MarshalAs(UnmanagedType.LPWStr)] out string id);
                [PreserveSig] int GetProcessId(out uint pid);
                [PreserveSig] int IsSystemSoundsSession();
                void SetDuckingPreference();
            }
            public static class SessionHelper {
                static readonly Guid CLSID_MMDevEnum = new Guid("BCDE0395-E52F-467C-8E3D-C4579291692E");
                static readonly Guid IID_IAudioSessionMgr2 = new Guid("77AA99A0-1BD6-484F-8BC7-2C654C9A9B6F");
                public static List<uint[]> GetActivePids() {
                    var result = new List<uint[]>();
                    try {
                        var t = Type.GetTypeFromCLSID(CLSID_MMDevEnum);
                        var en = (IMMDeviceEnumerator2)Activator.CreateInstance(t);
                        IMMDev dev; en.GetDefaultAudioEndpoint(0, 1, out dev);
                        var iid = IID_IAudioSessionMgr2;
                        object obj; dev.Activate(ref iid, 1, IntPtr.Zero, out obj);
                        var mgr = (IAudioSessionManager2)obj;
                        IAudioSessionEnumerator senum; mgr.GetSessionEnumerator(out senum);
                        int count; senum.GetCount(out count);
                        for (int i = 0; i < count; i++) {
                            IAudioSessionControl sc; senum.GetSession(i, out sc);
                            var sc2 = sc as IAudioSessionControl2;
                            if (sc2 == null) continue;
                            int state; sc2.GetState(out state);
                            if (state != 1) continue; // AudioSessionStateActive = 1
                            uint pid; if (sc2.GetProcessId(out pid) != 0) continue;
                            if (pid == 0) continue; // system sounds pseudo-session
                            result.Add(new uint[]{ pid });
                        }
                    } catch {}
                    return result;
                }
            }
            '@
            Add-Type -TypeDefinition $code -Language CSharp 2>$null
            try {
                foreach ($r in [SessionHelper]::GetActivePids()) {
                    $pid = $r[0]
                    try {
                        $proc = Get-Process -Id $pid -ErrorAction SilentlyContinue
                        $name = if ($proc) { $proc.Name } else { "Unknown" }
                        Write-Output ("SESSION|" + $pid + "|" + $name)
                    } catch { Write-Output ("SESSION|" + $pid + "|Unknown") }
                }
            } catch { Write-Output "ERROR|$_" }
            """;
        List<AudioSession> sessions = new ArrayList<>();
        try {
            String output = runPowerShell(script);
            CaptureLogger.raw("queryActiveSessions PowerShell output", output);
            for (String line : output.split("\r?\n")) {
                line = line.strip();
                if (line.startsWith("SESSION|")) {
                    String[] p = line.split("\\|", 3);
                    if (p.length == 3) {
                        try {
                            int pid = Integer.parseInt(p[1]);
                            sessions.add(new AudioSession(p[2], pid, ""));
                        } catch (NumberFormatException ignored) {}
                    }
                } else if (line.startsWith("ERROR|")) {
                    CaptureLogger.warn("queryActiveSessions error: " + line.substring(6));
                }
            }
        } catch (Exception e) {
            CaptureLogger.error("queryActiveSessions failed", e);
        }
        CaptureLogger.info("queryActiveSessions found " + sessions.size() + " active session(s): "
            + sessions.stream().map(AudioSession::toString).toList());
        return sessions;
    }

    // -----------------------------------------------------------------------
    // ffmpeg WASAPI device list
    // -----------------------------------------------------------------------

    /**
     * Asks ffmpeg to enumerate all WASAPI audio capture endpoints.  This
     * includes loopback-capable render devices (shown as "... (loopback)")
     * and returns them as raw lines for manual parsing.
     *
     * <p>Requires ffmpeg on PATH.
     */
    public static List<String> queryFfmpegWasapiDevices() {
        List<String> devices = new ArrayList<>();
        try {
            ProcessBuilder pb = new ProcessBuilder(
                "ffmpeg", "-loglevel", "info",
                "-list_devices", "true",
                "-f", "dshow",
                "-i", "dummy"
            );
            pb.redirectErrorStream(true);
            Process p = pb.start();
            String out = new String(p.getInputStream().readAllBytes());
            p.waitFor(5, TimeUnit.SECONDS);
            CaptureLogger.raw("ffmpeg dshow device list", out);
            for (String line : out.split("\r?\n")) {
                String t = line.strip();
                // dshow audio device lines look like:  "DirectShow audio devices" or
                // '"Stereo Mix (Realtek(R) Audio)" (audio)'
                if (t.contains("(audio)") || t.toLowerCase().contains("audio")) {
                    devices.add(t);
                }
            }
        } catch (Exception e) {
            CaptureLogger.error("queryFfmpegWasapiDevices failed", e);
        }
        return devices;
    }

    // -----------------------------------------------------------------------
    // Helpers
    // -----------------------------------------------------------------------

    /**
     * Returns {@code true} when running on a Windows JDK ({@code make native}).
     */
    public static boolean isWindows() {
        return java.io.File.separatorChar == '\\';
    }

    /**
     * Runs a PowerShell command non-interactively and returns stdout as a string.
     * Times out after 10 seconds.
     */
    static String runPowerShell(String script) throws IOException, InterruptedException {
        // Encode as UTF-16LE base64 for -EncodedCommand so that double-quotes
        // inside the script are never stripped by the Windows command processor.
        byte[] utf16 = script.getBytes(StandardCharsets.UTF_16LE);
        String encoded = Base64.getEncoder().encodeToString(utf16);
        ProcessBuilder pb = new ProcessBuilder(
            "powershell.exe",
            "-NonInteractive", "-NoProfile", "-ExecutionPolicy", "Bypass",
            "-EncodedCommand", encoded
        );
        pb.redirectErrorStream(false);
        Process p = pb.start();
        // Read stdout and stderr concurrently to avoid blocking
        final String[] stdout = {""};
        final String[] stderr = {""};
        Thread outThread = new Thread(() -> {
            try { stdout[0] = new String(p.getInputStream().readAllBytes()); }
            catch (IOException ignored) {}
        });
        Thread errThread = new Thread(() -> {
            try { stderr[0] = new String(p.getErrorStream().readAllBytes()); }
            catch (IOException ignored) {}
        });
        outThread.start(); errThread.start();
        boolean finished = p.waitFor(10, TimeUnit.SECONDS);
        outThread.join(1000); errThread.join(1000);
        if (!finished) {
            p.destroyForcibly();
            CaptureLogger.warn("PowerShell timed out");
        }
        if (!stderr[0].isBlank()) {
            CaptureLogger.raw("PowerShell stderr", stderr[0]);
        }
        return stdout[0];
    }
}
