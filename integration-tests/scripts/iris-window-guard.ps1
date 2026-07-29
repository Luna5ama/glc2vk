if ($null -eq ("Vibris.Windows.IrisWindowGuard" -as [type]))
{
    Add-Type -TypeDefinition @'
using System;
using System.ComponentModel;
using System.Runtime.InteropServices;
using System.Threading;

namespace Vibris.Windows
{
    public sealed class IrisWindowGuard : IDisposable
    {
        private const uint Synchronize = 0x00100000;
        private const uint WaitObject0 = 0x00000000;
        private const uint WaitTimeout = 0x00000102;
        private const int HideWindow = 0;
        private const int ExtendedWindowStyle = -20;
        private const long NoActivateStyle = 0x08000000L;
        private const uint NoActivateWindowPosition = 0x00004017;

        private readonly uint processId;
        private readonly IntPtr processHandle;
        private readonly ManualResetEvent stop = new ManualResetEvent(false);
        private readonly Thread thread;
        private int disposeStarted;
        private int stopped;
        private int matchedWindowCount;
        private int suppressionCount;
        private int foregroundHitCount;
        private int inputFocusHitCount;
        private int postSuppressionInputFocusCount;
        private int hiddenObservationCount;
        private int lastWin32Error;
        private IntPtr guardedWindow;
        private uint guardedThreadId;

        public IrisWindowGuard(int processId)
        {
            if (processId <= 0) throw new ArgumentOutOfRangeException("processId");
            this.processId = unchecked((uint) processId);
            processHandle = OpenProcess(Synchronize, false, this.processId);
            if (processHandle == IntPtr.Zero) throw new Win32Exception(Marshal.GetLastWin32Error());

            thread = new Thread(Run);
            thread.IsBackground = true;
            thread.Name = "Vibris Iris window guard";
            thread.Start();
        }

        public int MatchedWindowCount { get { return Volatile.Read(ref matchedWindowCount); } }
        public int SuppressionCount { get { return Volatile.Read(ref suppressionCount); } }
        public int ForegroundHitCount { get { return Volatile.Read(ref foregroundHitCount); } }
        public int InputFocusHitCount { get { return Volatile.Read(ref inputFocusHitCount); } }
        public int PostSuppressionInputFocusCount {
            get { return Volatile.Read(ref postSuppressionInputFocusCount); }
        }
        public int HiddenObservationCount { get { return Volatile.Read(ref hiddenObservationCount); } }
        public bool IsInputFocused {
            get {
                IntPtr window = guardedWindow;
                return window != IntPtr.Zero && IsWindow(window) && HasInputFocus(window, guardedThreadId);
            }
        }
        public int LastWin32Error { get { return Volatile.Read(ref lastWin32Error); } }
        public bool IsStopped { get { return Volatile.Read(ref stopped) != 0; } }

        public void Dispose()
        {
            if (Interlocked.Exchange(ref disposeStarted, 1) != 0) return;
            stop.Set();
            if (!thread.Join(TimeSpan.FromSeconds(5)))
            {
                throw new TimeoutException("The Iris window guard did not stop within five seconds.");
            }
            stop.Dispose();
        }

        private void Run()
        {
            try
            {
                while (!stop.WaitOne(0))
                {
                    uint processState = WaitForSingleObject(processHandle, 0);
                    if (processState == WaitObject0) break;
                    if (processState != WaitTimeout)
                    {
                        Volatile.Write(ref lastWin32Error, Marshal.GetLastWin32Error());
                        break;
                    }

                    if (!EnumWindows(InspectWindow, IntPtr.Zero))
                    {
                        int error = Marshal.GetLastWin32Error();
                        if (error != 0) Volatile.Write(ref lastWin32Error, error);
                    }
                    SuppressWindow();
                    stop.WaitOne(15);
                }
            }
            finally
            {
                CloseHandle(processHandle);
                Volatile.Write(ref stopped, 1);
            }
        }

        private bool InspectWindow(IntPtr window, IntPtr ignored)
        {
            uint windowProcessId;
            uint windowThreadId = GetWindowThreadProcessId(window, out windowProcessId);
            if (windowThreadId == 0 || windowProcessId != processId ||
                (!IsWindowVisible(window) && guardedWindow != window))
            {
                return true;
            }

            if (guardedWindow == IntPtr.Zero)
            {
                guardedWindow = window;
                guardedThreadId = windowThreadId;
            }
            if (guardedWindow != window) return true;
            Interlocked.Increment(ref matchedWindowCount);
            return true;
        }

        private void SuppressWindow()
        {
            IntPtr window = guardedWindow;
            if (window == IntPtr.Zero || !IsWindow(window)) return;
            if (GetForegroundWindow() == window) Interlocked.Increment(ref foregroundHitCount);
            bool focusedBefore = HasInputFocus(window, guardedThreadId);
            if (focusedBefore) Interlocked.Increment(ref inputFocusHitCount);

            long style = GetWindowLongPtr(window, ExtendedWindowStyle).ToInt64();
            SetLastError(0);
            IntPtr previous = SetWindowLongPtr(window, ExtendedWindowStyle, new IntPtr(style | NoActivateStyle));
            int styleError = Marshal.GetLastWin32Error();
            bool styled = previous != IntPtr.Zero || styleError == 0;
            bool positioned = SetWindowPos(window, IntPtr.Zero, 0, 0, 0, 0, NoActivateWindowPosition);
            bool hidden = !IsWindowVisible(window) || ShowWindowAsync(window, HideWindow);
            if (styled && positioned && hidden)
            {
                Interlocked.Increment(ref suppressionCount);
            }
            else
            {
                int error = Marshal.GetLastWin32Error();
                if (error != 0) Volatile.Write(ref lastWin32Error, error);
            }
            if (!IsWindowVisible(window))
            {
                Interlocked.Increment(ref hiddenObservationCount);
                if (HasInputFocus(window, guardedThreadId))
                {
                    Interlocked.Increment(ref postSuppressionInputFocusCount);
                }
            }
        }

        private bool HasInputFocus(IntPtr window, uint threadId)
        {
            GuiThreadInfo info = new GuiThreadInfo();
            info.Size = Marshal.SizeOf(typeof(GuiThreadInfo));
            if (!GetGUIThreadInfo(threadId, ref info))
            {
                int error = Marshal.GetLastWin32Error();
                if (error != 0) Volatile.Write(ref lastWin32Error, error);
                return true;
            }
            return info.Focus == window || info.Capture == window;
        }

        private delegate bool EnumWindowsCallback(IntPtr window, IntPtr parameter);

        [DllImport("user32.dll", SetLastError = true)]
        private static extern bool EnumWindows(EnumWindowsCallback callback, IntPtr parameter);

        [DllImport("user32.dll", SetLastError = true)]
        private static extern uint GetWindowThreadProcessId(IntPtr window, out uint processId);

        [DllImport("user32.dll")]
        private static extern bool IsWindowVisible(IntPtr window);

        [DllImport("user32.dll")]
        private static extern bool IsWindow(IntPtr window);

        [DllImport("user32.dll")]
        private static extern IntPtr GetForegroundWindow();

        [DllImport("user32.dll", SetLastError = true)]
        private static extern bool ShowWindowAsync(IntPtr window, int command);

        [DllImport("user32.dll", SetLastError = true)]
        private static extern bool SetWindowPos(
            IntPtr window, IntPtr insertAfter, int x, int y, int width, int height, uint flags);

        [DllImport("user32.dll", EntryPoint = "GetWindowLongPtrW", SetLastError = true)]
        private static extern IntPtr GetWindowLongPtr(IntPtr window, int index);

        [DllImport("user32.dll", EntryPoint = "SetWindowLongPtrW", SetLastError = true)]
        private static extern IntPtr SetWindowLongPtr(IntPtr window, int index, IntPtr value);

        [DllImport("user32.dll", SetLastError = true)]
        private static extern bool GetGUIThreadInfo(uint threadId, ref GuiThreadInfo info);

        [DllImport("kernel32.dll")]
        private static extern void SetLastError(uint errorCode);

        [DllImport("kernel32.dll", SetLastError = true)]
        private static extern IntPtr OpenProcess(uint desiredAccess, bool inheritHandle, uint processId);

        [DllImport("kernel32.dll", SetLastError = true)]
        private static extern uint WaitForSingleObject(IntPtr handle, uint milliseconds);

        [DllImport("kernel32.dll")]
        private static extern bool CloseHandle(IntPtr handle);

        [StructLayout(LayoutKind.Sequential)]
        private struct GuiThreadInfo
        {
            public int Size;
            public uint Flags;
            public IntPtr Active;
            public IntPtr Focus;
            public IntPtr Capture;
            public IntPtr MenuOwner;
            public IntPtr MoveSize;
            public IntPtr Caret;
            public Rect CaretRect;
        }

        [StructLayout(LayoutKind.Sequential)]
        private struct Rect
        {
            public int Left;
            public int Top;
            public int Right;
            public int Bottom;
        }
    }
}
'@
}

function Start-IrisWindowGuard
{
    param([Parameter(Mandatory)] [object] $Scope)

    if ($null -ne $Scope.WindowGuard) { throw "The Iris window guard is already running." }
    Assert-IrisOwnedRuntime -Scope $Scope
    $Scope.WindowGuardEvidence = $null
    $Scope.WindowGuard = [Vibris.Windows.IrisWindowGuard]::new($Scope.RuntimePid)
}

function Assert-IrisWindowGuardActive
{
    param([Parameter(Mandatory)] [object] $Scope)

    $guard = $Scope.WindowGuard
    if ($null -eq $guard) { throw "The Iris window guard is not running." }
    $stopped = $guard.IsStopped
    $matched = $guard.MatchedWindowCount
    $suppressed = $guard.SuppressionCount
    $foreground = $guard.ForegroundHitCount
    $inputFocus = $guard.InputFocusHitCount
    $postSuppressionFocus = $guard.PostSuppressionInputFocusCount
    $hidden = $guard.HiddenObservationCount
    $focusedNow = $guard.IsInputFocused
    $errorCode = $guard.LastWin32Error
    if ($stopped -or $matched -le 0 -or $suppressed -le 0 -or $hidden -le 0 -or
        $postSuppressionFocus -ne 0 -or $focusedNow -or $errorCode -ne 0)
    {
        throw "Iris window guard did not actively suppress the runtime window: stopped=$stopped " +
            "matched=$matched suppressed=$suppressed foreground=$foreground focus_hits=$inputFocus " +
            "post_suppression_focus=$postSuppressionFocus hidden=$hidden focused_now=$focusedNow " +
            "win32_error=$errorCode."
    }
}

function Stop-IrisWindowGuard
{
    param([Parameter(Mandatory)] [object] $Scope)

    if ($null -eq $Scope.WindowGuard) { return }
    $guard = $Scope.WindowGuard
    $guard.Dispose()
    $Scope.WindowGuardEvidence = [pscustomobject] @{
        RuntimePid = $Scope.RuntimePid
        RuntimeCreated = $Scope.RuntimeCreated
        MatchedWindowCount = $guard.MatchedWindowCount
        SuppressionCount = $guard.SuppressionCount
        ForegroundHitCount = $guard.ForegroundHitCount
        InputFocusHitCount = $guard.InputFocusHitCount
        PostSuppressionInputFocusCount = $guard.PostSuppressionInputFocusCount
        HiddenObservationCount = $guard.HiddenObservationCount
        IsInputFocused = $guard.IsInputFocused
        LastWin32Error = $guard.LastWin32Error
        Stopped = $guard.IsStopped
    }
    $Scope.WindowGuard = $null
}