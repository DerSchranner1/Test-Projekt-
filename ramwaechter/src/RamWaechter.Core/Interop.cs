using System.Runtime.InteropServices;

namespace RamWaechter.Core.Interop;

[StructLayout(LayoutKind.Sequential)]
public struct UNICODE_STRING
{
    public ushort Length;
    public ushort MaximumLength;
    public IntPtr Buffer;
}

/// <summary>
/// Eintrag aus NtQuerySystemInformation(SystemProcessInformation) für 64-Bit-Windows.
/// Liefert für alle Prozesse ohne Adminrechte den privaten Arbeitssatz und die Hard Faults.
/// </summary>
[StructLayout(LayoutKind.Sequential)]
public struct SYSTEM_PROCESS_INFORMATION
{
    public uint NextEntryOffset;
    public uint NumberOfThreads;
    public long WorkingSetPrivateSize;
    public uint HardFaultCount;
    public uint NumberOfThreadsHighWatermark;
    public ulong CycleTime;
    public long CreateTime;
    public long UserTime;
    public long KernelTime;
    public UNICODE_STRING ImageName;
    public int BasePriority;
    public IntPtr UniqueProcessId;
    public IntPtr InheritedFromUniqueProcessId;
    public uint HandleCount;
    public uint SessionId;
    public UIntPtr UniqueProcessKey;
    public UIntPtr PeakVirtualSize;
    public UIntPtr VirtualSize;
    public uint PageFaultCount;
    public UIntPtr PeakWorkingSetSize;
    public UIntPtr WorkingSetSize;
    public UIntPtr QuotaPeakPagedPoolUsage;
    public UIntPtr QuotaPagedPoolUsage;
    public UIntPtr QuotaPeakNonPagedPoolUsage;
    public UIntPtr QuotaNonPagedPoolUsage;
    public UIntPtr PagefileUsage;
    public UIntPtr PeakPagefileUsage;
    public UIntPtr PrivatePageCount;
    public long ReadOperationCount;
    public long WriteOperationCount;
    public long OtherOperationCount;
    public long ReadTransferCount;
    public long WriteTransferCount;
    public long OtherTransferCount;
}

public static class ProcessInfoParser
{
    /// <summary>Wertet den Puffer von NtQuerySystemInformation aus. Namen werden über <paramref name="readString"/> gelesen.</summary>
    public static unsafe List<ProcessSample> Parse(byte* buffer, int length, Func<IntPtr, int, string> readString)
    {
        var list = new List<ProcessSample>(400);
        int offset = 0;
        while (offset + sizeof(SYSTEM_PROCESS_INFORMATION) <= length)
        {
            var spi = *(SYSTEM_PROCESS_INFORMATION*)(buffer + offset);
            int pid = (int)(long)spi.UniqueProcessId;
            string name = spi.ImageName.Buffer == IntPtr.Zero || spi.ImageName.Length == 0
                ? (pid == 0 ? "Idle" : "?")
                : readString(spi.ImageName.Buffer, spi.ImageName.Length / 2);
            if (name.EndsWith(".exe", StringComparison.OrdinalIgnoreCase)) name = name[..^4];

            list.Add(new ProcessSample
            {
                Pid = pid,
                ParentPid = (int)(long)spi.InheritedFromUniqueProcessId,
                CreateTime = spi.CreateTime,
                Name = name,
                PrivateWorkingSet = (ulong)Math.Max(0, spi.WorkingSetPrivateSize),
                WorkingSet = (ulong)spi.WorkingSetSize,
                PrivateBytes = (ulong)spi.PrivatePageCount,
                PeakWorkingSet = (ulong)spi.PeakWorkingSetSize,
                HardFaultCount = spi.HardFaultCount,
            });

            if (spi.NextEntryOffset == 0) break;
            offset += (int)spi.NextEntryOffset;
        }
        return list;
    }
}
