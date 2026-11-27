using System.Runtime.InteropServices;
using System.Text;
using RamWaechter.Core;
using RamWaechter.Core.Interop;

namespace RamWaechter.Core.Tests;

public class InteropTests
{
    [Fact]
    public void SystemProcessInformation_HasWindowsX64Layout()
    {
        // Werte aus den Windows-Headern (phnt / winternl.h), sizeof = 0x100.
        Assert.Equal(256, Marshal.SizeOf<SYSTEM_PROCESS_INFORMATION>());
        Assert.Equal(8, Off(nameof(SYSTEM_PROCESS_INFORMATION.WorkingSetPrivateSize)));
        Assert.Equal(16, Off(nameof(SYSTEM_PROCESS_INFORMATION.HardFaultCount)));
        Assert.Equal(32, Off(nameof(SYSTEM_PROCESS_INFORMATION.CreateTime)));
        Assert.Equal(56, Off(nameof(SYSTEM_PROCESS_INFORMATION.ImageName)));
        Assert.Equal(80, Off(nameof(SYSTEM_PROCESS_INFORMATION.UniqueProcessId)));
        Assert.Equal(88, Off(nameof(SYSTEM_PROCESS_INFORMATION.InheritedFromUniqueProcessId)));
        Assert.Equal(128, Off(nameof(SYSTEM_PROCESS_INFORMATION.PageFaultCount)));
        Assert.Equal(144, Off(nameof(SYSTEM_PROCESS_INFORMATION.WorkingSetSize)));
        Assert.Equal(184, Off(nameof(SYSTEM_PROCESS_INFORMATION.PagefileUsage)));
        Assert.Equal(200, Off(nameof(SYSTEM_PROCESS_INFORMATION.PrivatePageCount)));
    }

    private static int Off(string field) => (int)Marshal.OffsetOf<SYSTEM_PROCESS_INFORMATION>(field);

    [Fact]
    public unsafe void Parser_ReadsChainedEntries()
    {
        int size = Marshal.SizeOf<SYSTEM_PROCESS_INFORMATION>();
        int entry = size + 64; // echte Einträge haben danach noch Thread-Infos
        var buf = new byte[entry * 2];
        var names = new[] { "rider64.exe", "sqlservr.exe" };
        var handles = names.Select(n => GCHandle.Alloc(Encoding.Unicode.GetBytes(n), GCHandleType.Pinned)).ToArray();
        try
        {
            fixed (byte* b = buf)
            {
                for (int i = 0; i < 2; i++)
                {
                    var spi = new SYSTEM_PROCESS_INFORMATION
                    {
                        NextEntryOffset = i == 0 ? (uint)entry : 0,
                        WorkingSetPrivateSize = (i + 1) * 1000,
                        HardFaultCount = (uint)(i + 7),
                        CreateTime = 42 + i,
                        UniqueProcessId = (IntPtr)(100 + i),
                        InheritedFromUniqueProcessId = (IntPtr)4,
                        WorkingSetSize = (UIntPtr)5000,
                        PrivatePageCount = (UIntPtr)9000,
                        ImageName = new UNICODE_STRING
                        {
                            Buffer = handles[i].AddrOfPinnedObject(),
                            Length = (ushort)(names[i].Length * 2),
                            MaximumLength = (ushort)(names[i].Length * 2),
                        },
                    };
                    *(SYSTEM_PROCESS_INFORMATION*)(b + i * entry) = spi;
                }
                var list = ProcessInfoParser.Parse(b, buf.Length, (p, len) => Marshal.PtrToStringUni(p, len));
                Assert.Equal(2, list.Count);
                Assert.Equal("rider64", list[0].Name);
                Assert.Equal("sqlservr", list[1].Name);
                Assert.Equal(101, list[1].Pid);
                Assert.Equal(4, list[1].ParentPid);
                Assert.Equal(2000UL, list[1].PrivateWorkingSet);
                Assert.Equal(8u, list[1].HardFaultCount);
                Assert.Equal(9000UL - 2000UL, list[1].NotResident);
            }
        }
        finally
        {
            foreach (var h in handles) h.Free();
        }
    }
}

public class CatalogTests
{
    [Theory]
    [InlineData("rider64", "rider")]
    [InlineData("Rider.Backend", "rider")]
    [InlineData("sqlservr", AppCatalog.SqlServerKey)]
    [InlineData("vmmemWSL", AppCatalog.WslKey)]
    [InlineData("vmwp", AppCatalog.HyperVKey)]
    [InlineData("ServiceHub.RoslynCodeAnalysisService", "visualstudio")]
    [InlineData("Memory Compression", AppCatalog.CompressionKey)]
    [InlineData("msedgewebview2", "webview2")]
    [InlineData("msedge", "edge")]
    public void Match_FindsKnownApps(string process, string key) =>
        Assert.Equal(key, AppCatalog.Match(process)?.Key);

    [Fact]
    public void Match_UnknownReturnsNull() => Assert.Null(AppCatalog.Match("meinprogramm"));

    [Fact]
    public void Keys_AreUnique() =>
        Assert.Equal(AppCatalog.All.Count, AppCatalog.All.Select(a => a.Key).Distinct().Count());

    [Fact]
    public void EveryEntry_HasTips() =>
        Assert.All(AppCatalog.All, a => Assert.NotEmpty(a.Tips));
}

internal static class Make
{
    public const ulong GB = 1UL << 30;
    public const ulong MB = 1UL << 20;

    public static TrackedProcess Proc(string name, ulong privateWs, int pid = 1, ulong privateBytes = 0, int parent = 0) =>
        new(new ProcessSample
        {
            Pid = pid,
            ParentPid = parent,
            Name = name,
            PrivateWorkingSet = privateWs,
            WorkingSet = privateWs,
            PrivateBytes = privateBytes == 0 ? privateWs : privateBytes,
        }, 0, 0, false, null);

    public static SystemSample Sys(double totalGb, double availGb, double standbyGb = 0, double pagesIn = 0, double commitFrac = 0.5,
                                   DateTime? time = null, double pageReads = -1, double diskLatency = double.NaN) =>
        new()
        {
            Time = time ?? new DateTime(2026, 1, 1, 12, 0, 0),
            Installed = (ulong)(totalGb * GB),
            Total = (ulong)(totalGb * GB),
            Available = (ulong)(availGb * GB),
            Standby = (ulong)(standbyGb * GB),
            FreeZero = (ulong)((availGb - standbyGb) * GB),
            CommitLimit = (ulong)(totalGb * 1.5 * GB),
            Committed = (ulong)(totalGb * 1.5 * commitFrac * GB),
            PagesInputPerSec = pagesIn,
            PageReadsPerSec = pageReads < 0 ? pagesIn / 4 : pageReads,
            DiskSecPerRead = diskLatency,
        };

    public static History Hist(params SystemSample[] samples)
    {
        var h = new History();
        foreach (var s in samples) h.Add(s);
        return h;
    }

    public static History Steady(Func<DateTime, SystemSample> f, int count = 40, int stepSec = 2)
    {
        var h = new History();
        var t = new DateTime(2026, 1, 1, 12, 0, 0);
        for (int i = 0; i < count; i++) h.Add(f(t.AddSeconds(i * stepSec)));
        return h;
    }
}

public class GrouperTests
{
    [Fact]
    public void GroupsRiderProcesses_AndSorts()
    {
        var procs = new[]
        {
            Make.Proc("rider64", 2 * Make.GB, 10),
            Make.Proc("Rider.Backend", 3 * Make.GB, 11),
            Make.Proc("notepad", 20 * Make.MB, 12),
        };
        var groups = AppGrouper.Build(procs, Array.Empty<VmSample>(), Array.Empty<SqlSample>(), 32 * Make.GB);
        Assert.Equal("rider", groups[0].Key);
        Assert.Equal(2, groups[0].Processes.Count);
        Assert.Equal(5 * Make.GB, groups[0].PrivateWs);
    }

    [Fact]
    public void Sql_LockedPages_AreCountedAsHidden_AndMissingLimitIsReported()
    {
        var procs = new[] { Make.Proc("sqlservr", 300 * Make.MB, 20) };
        var sql = new[] { new SqlSample("MSSQLSERVER", 12 * Make.GB, 28 * Make.GB, 5000) };
        var g = AppGrouper.Build(procs, Array.Empty<VmSample>(), sql, 32 * Make.GB).Single(x => x.Key == AppCatalog.SqlServerKey);
        Assert.Equal(12 * Make.GB - 300 * Make.MB, g.HiddenBytes);
        Assert.Equal(12 * Make.GB, g.TotalRam);
        Assert.Contains(g.Notes, n => n.Contains("kein Limit"));
        Assert.Contains(g.Notes, n => n.Contains("Lock Pages"));
    }

    [Fact]
    public void Sql_WithoutLockedPages_HasNoHiddenMemory()
    {
        var procs = new[] { Make.Proc("sqlservr", 4 * Make.GB, 20) };
        var sql = new[] { new SqlSample("MSSQLSERVER", 4 * Make.GB, 4 * Make.GB, 900) };
        var g = AppGrouper.Build(procs, Array.Empty<VmSample>(), sql, 32 * Make.GB).Single(x => x.Key == AppCatalog.SqlServerKey);
        Assert.Equal(0UL, g.HiddenBytes);
        Assert.DoesNotContain(g.Notes, n => n.Contains("kein Limit"));
    }

    [Fact]
    public void HyperV_ClassicVms_AreHidden_WslInstanceNotDoubleCounted()
    {
        var procs = new[] { Make.Proc("vmmemWSL", 6 * Make.GB, 30), Make.Proc("vmwp", 30 * Make.MB, 31) };
        var vms = new[]
        {
            new VmSample("_Total", 99 * Make.GB),
            new VmSample("WSL", 6 * Make.GB),
            new VmSample("Dev-Server", 8 * Make.GB),
        };
        var groups = AppGrouper.Build(procs, vms, Array.Empty<SqlSample>(), 32 * Make.GB);
        var hv = groups.Single(g => g.Key == AppCatalog.HyperVKey);
        Assert.Equal(8 * Make.GB, hv.HiddenBytes);
        Assert.Contains(hv.Notes, n => n.Contains("Dev-Server"));
        Assert.Equal(6 * Make.GB, groups.Single(g => g.Key == AppCatalog.WslKey).PrivateWs);
    }

    [Fact]
    public void WebView2_ShowsParentApp()
    {
        var parent = Make.Proc("ms-teams", 100 * Make.MB, 50);
        var child = new TrackedProcess(Make.Proc("msedgewebview2", 300 * Make.MB, 51, parent: 50).Sample, 0, 0, false, "ms-teams");
        var g = AppGrouper.Build(new[] { parent, child }, Array.Empty<VmSample>(), Array.Empty<SqlSample>(), 16 * Make.GB)
            .Single(x => x.Key == "webview2");
        Assert.Contains(g.Notes, n => n.Contains("ms-teams"));
    }
}

public class BreakdownTests
{
    [Fact]
    public void Segments_AddUpToInstalled()
    {
        var s = Make.Sys(32, 12, standbyGb: 10) with
        {
            Installed = 32 * Make.GB + 512 * Make.MB,
            Modified = 300 * Make.MB,
            NonPagedPool = 400 * Make.MB,
            PagedPoolResident = 500 * Make.MB,
            SystemCacheResident = 200 * Make.MB,
            Compressed = 700 * Make.MB,
        };
        var groups = AppGrouper.Build(new[] { Make.Proc("chrome", 4 * Make.GB), Make.Proc("vmmem", 3 * Make.GB, 2) },
            Array.Empty<VmSample>(), Array.Empty<SqlSample>(), s.Total);
        var items = Breakdown.Compute(s, groups);
        ulong sum = (ulong)items.Sum(i => (decimal)i.Bytes);
        Assert.Equal(s.Installed, sum);
        Assert.Equal(3 * Make.GB, items.Single(i => i.Segment == Segment.VirtuelleMaschinen).Bytes);
        Assert.Equal(4 * Make.GB, items.Single(i => i.Segment == Segment.Programme).Bytes);
        Assert.Equal(512 * Make.MB, items.Single(i => i.Segment == Segment.Hardware).Bytes);
    }
}

public class TrackerTests
{
    [Fact]
    public void ComputesHardFaultsPerSecond()
    {
        var tr = new ProcessTracker();
        var t = new DateTime(2026, 1, 1);
        tr.Update(new[] { new ProcessSample { Pid = 5, CreateTime = 1, Name = "x", HardFaultCount = 100 } }, t);
        var r = tr.Update(new[] { new ProcessSample { Pid = 5, CreateTime = 1, Name = "x", HardFaultCount = 300 } }, t.AddSeconds(2));
        Assert.Equal(100, r[0].HardFaultsPerSec, 3);
    }

    [Fact]
    public void DetectsSteadyGrowthAsLeak()
    {
        var tr = new ProcessTracker();
        var t = new DateTime(2026, 1, 1);
        IReadOnlyList<TrackedProcess> r = Array.Empty<TrackedProcess>();
        for (int i = 0; i <= 60; i++) // 10 Minuten, alle 10 s, +50 MB/min
        {
            var p = new ProcessSample { Pid = 7, CreateTime = 1, Name = "leaky", PrivateBytes = (ulong)((1000 + i * 50.0 / 6) * Make.MB) };
            r = tr.Update(new[] { p }, t.AddSeconds(i * 10));
        }
        Assert.True(r[0].LeakSuspect);
        Assert.Equal(50, r[0].GrowthMbPerMin, 0);
    }

    [Fact]
    public void FlatMemoryIsNoLeak()
    {
        var tr = new ProcessTracker();
        var t = new DateTime(2026, 1, 1);
        IReadOnlyList<TrackedProcess> r = Array.Empty<TrackedProcess>();
        for (int i = 0; i <= 60; i++)
        {
            var p = new ProcessSample { Pid = 7, CreateTime = 1, Name = "sql", PrivateBytes = (ulong)(8000 + (i % 3) * 5) * Make.MB };
            r = tr.Update(new[] { p }, t.AddSeconds(i * 10));
        }
        Assert.False(r[0].LeakSuspect);
    }

    [Fact]
    public void NewProcessWithSamePidIsNotConfused()
    {
        var tr = new ProcessTracker();
        var t = new DateTime(2026, 1, 1);
        tr.Update(new[] { new ProcessSample { Pid = 5, CreateTime = 1, Name = "a", HardFaultCount = 1000 } }, t);
        var r = tr.Update(new[] { new ProcessSample { Pid = 5, CreateTime = 2, Name = "b", HardFaultCount = 10 } }, t.AddSeconds(2));
        Assert.Equal(0, r[0].HardFaultsPerSec);
    }
}

public class AnalyzerTests
{
    private static readonly IReadOnlyList<AppGroup> NoGroups = Array.Empty<AppGroup>();

    [Fact]
    public void HighUsageWithBigStandbyIsFine()
    {
        var h = Make.Steady(t => Make.Sys(32, 14, standbyGb: 13, pagesIn: 5, time: t));
        var v = RamAnalyzer.Evaluate(h.Samples[^1], h, NoGroups);
        Assert.Equal(Level.Gut, v.Level);
        Assert.Contains(v.Reasons, r => r.Contains("Standby"));
    }

    [Fact]
    public void LowAvailableWithHeavyPagingIsCritical()
    {
        var h = Make.Steady(t => Make.Sys(16, 0.5, pagesIn: 800, time: t));
        var v = RamAnalyzer.Evaluate(h.Samples[^1], h, NoGroups);
        Assert.Equal(Level.Kritisch, v.Level);
    }

    [Fact]
    public void CommitNearLimitIsCritical()
    {
        var h = Make.Steady(t => Make.Sys(16, 6, commitFrac: 0.97, time: t));
        Assert.Equal(Level.Kritisch, RamAnalyzer.Evaluate(h.Samples[^1], h, NoGroups).Level);
    }

    [Fact]
    public void ManyReadsWithFreeRamAreNotBlamedOnRam()
    {
        var h = Make.Steady(t => Make.Sys(32, 20, pagesIn: 2000, pageReads: 300, time: t));
        var ram = RamAnalyzer.Evaluate(h.Samples[^1], h, NoGroups);
        var paging = PagingAnalyzer.Evaluate(h.Samples[^1], h, PagefileInfo.Empty, NoGroups);
        Assert.Equal(Level.Gut, ram.Level);
        Assert.Equal(Level.Gut, paging.Level);
        Assert.Contains(ram.Reasons, r => r.Contains("kein RAM-Mangel"));
    }

    [Fact]
    public void FileLoadingWithFreeRam_IsNotCalledAPagingPenalty()
    {
        // Wie auf dem Build-Runner: 80 % frei, viele Lesevorgänge durch Programmstarts.
        var h = Make.Steady(t => Make.Sys(16, 12.8, pagesIn: 90, pageReads: 75, diskLatency: 0.001, time: t));
        var v = PagingAnalyzer.Evaluate(h.Samples[^1], h, PagefileInfo.Empty, NoGroups);
        Assert.Equal(Level.Gut, v.Level);
        Assert.DoesNotContain(v.Reasons, r => r.Contains("deutlich") || r.Contains("spürbar"));
        Assert.Contains(v.Reasons, r => r.Contains("keiner"));
    }

    [Fact]
    public void StallEstimateUsesMeasuredLatency()
    {
        var h = Make.Steady(t => Make.Sys(16, 1, pagesIn: 2000, pageReads: 500, diskLatency: 0.0005, time: t));
        var est = PagingAnalyzer.EstimateStall(h);
        Assert.True(est.LatencyMeasured);
        Assert.Equal(0.25, est.StallFraction, 3);
        Assert.Equal(Level.Kritisch, PagingAnalyzer.Evaluate(h.Samples[^1], h, PagefileInfo.Empty, NoGroups).Level);
    }

    [Fact]
    public void PressureTimeIsAccumulated()
    {
        var t = new DateTime(2026, 1, 1, 12, 0, 0);
        var h = Make.Hist(
            Make.Sys(16, 8, time: t),
            Make.Sys(16, 8, time: t.AddSeconds(2)),
            Make.Sys(16, 0.5, pagesIn: 500, time: t.AddSeconds(4)),
            Make.Sys(16, 0.5, pagesIn: 500, time: t.AddSeconds(6)));
        Assert.Equal(TimeSpan.FromSeconds(6), h.Measured);
        Assert.Equal(TimeSpan.FromSeconds(4), h.PressureTime);
    }
}

public class HardwareTests
{
    private static MemoryModule Mod(string slot, string bank, int gb, int type = 26, string pn = "CMK16GX4M2B3200C16", uint speed = 3200, uint conf = 3200) =>
        new() { Slot = slot, Bank = bank, Capacity = (ulong)gb * Make.GB, SmbiosType = type, FormFactor = 8, Speed = speed, ConfiguredSpeed = conf, PartNumber = pn };

    [Theory]
    [InlineData("DIMM_A1", "BANK 0", "A")]
    [InlineData("ChannelB-DIMM0", "BANK 2", "B")]
    [InlineData("Controller1-ChannelA-DIMM0", "BANK 0", "1A")]
    [InlineData("DIMM 0", "P0 CHANNEL B", "B")]
    [InlineData("A2", "", "A")]
    public void ChannelOf_ParsesCommonLabels(string slot, string bank, string expected) =>
        Assert.Equal(expected, Hardware.ChannelOf(Mod(slot, bank, 8)));

    [Fact]
    public void SingleStick_IsSingleChannel()
    {
        var hw = new MemoryHardware { Modules = new[] { Mod("DIMM_A1", "", 16) }, SlotCount = 2 };
        var r = Hardware.AnalyzeChannels(hw);
        Assert.True(r.SingleChannel);
    }

    [Fact]
    public void IntelControllers_AreDualChannel()
    {
        var hw = new MemoryHardware
        {
            Modules = new[] { Mod("Controller0-ChannelA-DIMM1", "BANK 0", 16, 34), Mod("Controller1-ChannelA-DIMM1", "BANK 0", 16, 34) },
            SlotCount = 4,
        };
        Assert.Equal("Dual-Channel", Hardware.AnalyzeChannels(hw).Mode);
    }

    [Fact]
    public void MixedSizes_AreFlexMode()
    {
        var hw = new MemoryHardware { Modules = new[] { Mod("DIMM_A2", "", 8), Mod("DIMM_B2", "", 16, pn: "X") }, SlotCount = 4 };
        Assert.Contains("Flex", Hardware.AnalyzeChannels(hw).Mode);
    }

    [Fact]
    public void VirtualMachine_IsNotJudgedLikeRealHardware()
    {
        var hw = new MemoryHardware
        {
            Modules = new[] { Mod("M0001", "None", 1, type: 0), Mod("M0002", "None", 15, type: 0, pn: "None") },
            SlotCount = 2,
            SystemName = "Microsoft Corporation Virtual Machine",
        };
        var r = Hardware.AnalyzeChannels(hw);
        Assert.Equal("virtuelle Maschine", r.Mode);
        Assert.DoesNotContain(r.Notes, n => n.Contains("Flex"));
        var a = UpgradeAdvisor.Advise(hw, new UsageProfile(16 * Make.GB, 15 * Make.GB, 0.2, TimeSpan.FromHours(5), 0));
        Assert.Single(a.Options);
        Assert.Contains("VM-Einstellungen", a.Options[0]);
    }

    [Fact]
    public void BelowRatedSpeed_SuggestsXmp()
    {
        var hw = new MemoryHardware { Modules = new[] { Mod("DIMM_A2", "", 16, conf: 2133), Mod("DIMM_B2", "", 16, conf: 2133) }, SlotCount = 4 };
        Assert.Contains(Hardware.AnalyzeChannels(hw).Notes, n => n.Contains("XMP"));
    }
}

public class UpgradeTests
{
    private static MemoryModule Mod(string slot, int gb, int type = 26) =>
        new() { Slot = slot, Capacity = (ulong)gb * Make.GB, SmbiosType = type, FormFactor = 8, Speed = 3200, ConfiguredSpeed = 3200, PartNumber = "KIT-123" };

    private static UsageProfile Use(double installedGb, double demandGb, double pressure, double hours = 40, double limitableGb = 0) =>
        new((ulong)(installedGb * Make.GB), (ulong)(demandGb * Make.GB), pressure, TimeSpan.FromHours(hours), (ulong)(limitableGb * Make.GB));

    [Fact]
    public void PlentyOfRam_NoUpgrade()
    {
        var hw = new MemoryHardware { Modules = new[] { Mod("DIMM_A2", 16), Mod("DIMM_B2", 16) }, SlotCount = 4, MaxCapacity = 128 * Make.GB };
        var a = UpgradeAdvisor.Advise(hw, Use(32, 14, 0));
        Assert.Equal(UpgradeNeed.KeinBedarf, a.Need);
        Assert.Empty(a.Options);
    }

    [Fact]
    public void FullRamWithPressure_RecommendsPurchasableTarget()
    {
        var hw = new MemoryHardware { Modules = new[] { Mod("DIMM_A2", 8), Mod("DIMM_B2", 8) }, SlotCount = 4, MaxCapacity = 128 * Make.GB };
        var a = UpgradeAdvisor.Advise(hw, Use(16, 19, 0.06, limitableGb: 6));
        Assert.Equal(UpgradeNeed.Dringend, a.Need);
        Assert.Equal(32, a.TargetGb); // DDR4: 24 GB gibt es nicht sinnvoll, also 32
        Assert.Contains(a.Options, o => o.Contains("Ergänzen") && o.Contains("32 GB"));
        Assert.Contains(a.Options, o => o.Contains("2× 16 GB"));
        Assert.Contains(a.Reasons, r => r.Contains("begrenzen"));
    }

    [Fact]
    public void Ddr5_CanTarget48()
    {
        var hw = new MemoryHardware { Modules = new[] { Mod("A2", 16, 34), Mod("B2", 16, 34) }, SlotCount = 2, MaxCapacity = 128 * Make.GB };
        var a = UpgradeAdvisor.Advise(hw, Use(32, 33, 0.03));
        Assert.Equal(48, a.TargetGb);
        Assert.Contains(a.Options, o => o.Contains("2× 24 GB"));
    }

    [Fact]
    public void SolderedRam_CannotBeUpgraded()
    {
        var hw = new MemoryHardware { Modules = new[] { Mod("", 8, 35), Mod("", 8, 35) }, SlotCount = 2 };
        var a = UpgradeAdvisor.Advise(hw, Use(16, 18, 0.2));
        Assert.Contains(a.Options, o => o.Contains("verlötet"));
    }

    [Fact]
    public void SingleStick_SuggestsSecondStickEvenWithoutCapacityNeed()
    {
        var hw = new MemoryHardware { Modules = new[] { Mod("DIMM_A2", 16) }, SlotCount = 2, MaxCapacity = 64 * Make.GB };
        var a = UpgradeAdvisor.Advise(hw, Use(16, 6, 0));
        Assert.Equal(UpgradeNeed.KeinBedarf, a.Need);
        Assert.Contains(a.Options, o => o.Contains("zweiten") && o.Contains("Dual-Channel"));
    }

    [Fact]
    public void ShortMeasurement_IsFlagged()
    {
        var hw = new MemoryHardware { Modules = new[] { Mod("DIMM_A2", 16), Mod("DIMM_B2", 16) }, SlotCount = 4 };
        var a = UpgradeAdvisor.Advise(hw, Use(32, 10, 0, hours: 0.2));
        Assert.Contains(a.Caveats, c => c.Contains("Erst"));
    }

    [Fact]
    public void AtMaxCapacity_SaysSo()
    {
        var hw = new MemoryHardware { Modules = new[] { Mod("A", 8), Mod("B", 8) }, SlotCount = 2, MaxCapacity = 16 * Make.GB };
        var a = UpgradeAdvisor.Advise(hw, Use(16, 18, 0.2));
        Assert.Contains("Maximum", a.Headline);
    }
}

public class PeakStoreTests
{
    [Fact]
    public void PersistsAndReloads()
    {
        string path = Path.Combine(Path.GetTempPath(), "rw-test-" + Guid.NewGuid() + ".json");
        try
        {
            var t = new DateTime(2026, 3, 1, 10, 0, 0);
            var store = new PeakStore(path);
            store.Add(Make.Sys(16, 8, time: t));
            store.Add(Make.Sys(16, 2, time: t.AddSeconds(2)));
            store.Add(Make.Sys(16, 0.5, pagesIn: 500, time: t.AddSeconds(4)));
            store.Save();

            var again = new PeakStore(path);
            ulong installed = 16 * Make.GB;
            Assert.Equal((ulong)(15.5 * Make.GB), again.PeakDemand(installed));
            Assert.Equal(TimeSpan.FromSeconds(4), again.Measured(installed));
            Assert.Equal(0.5, again.PressureFraction(installed), 3);
            Assert.Equal(0UL, again.PeakDemand(32 * Make.GB)); // nach Upgrade neue Statistik
        }
        finally
        {
            File.Delete(path);
        }
    }
}

public class ReportTests
{
    [Fact]
    public void ReportContainsAllSections()
    {
        var h = Make.Steady(t => Make.Sys(16, 4, time: t));
        var s = h.Samples[^1];
        var groups = AppGrouper.Build(new[] { Make.Proc("sqlservr", 3 * Make.GB) }, Array.Empty<VmSample>(), Array.Empty<SqlSample>(), s.Total);
        var hw = new MemoryHardware { Modules = new[] { new MemoryModule { Slot = "A1", Capacity = 16 * Make.GB, SmbiosType = 26 } }, SlotCount = 2 };
        var text = ReportBuilder.Build(s, RamAnalyzer.Evaluate(s, h, groups), PagingAnalyzer.Evaluate(s, h, PagefileInfo.Empty, groups),
            Breakdown.Compute(s, groups), groups, hw,
            UpgradeAdvisor.Advise(hw, new UsageProfile(16 * Make.GB, 12 * Make.GB, 0, TimeSpan.FromHours(5), 0)), PagefileInfo.Empty);
        Assert.Contains("Bremst der RAM", text);
        Assert.Contains("SQL Server", text);
        Assert.Contains("Single-Channel", text);
        Assert.Contains("Upgrade", text);
    }
}
