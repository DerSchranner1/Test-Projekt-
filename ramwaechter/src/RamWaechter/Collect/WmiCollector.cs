using System.Management;
using RamWaechter.Core;

namespace RamWaechter.Collect;

/// <summary>Hardware- und Auslagerungsdatei-Infos über WMI (langsamer, daher selten abgefragt).</summary>
internal static class WmiCollector
{
    private static readonly int[] LaptopChassis = { 8, 9, 10, 11, 12, 14, 18, 21, 30, 31, 32 };

    public static MemoryHardware ReadHardware()
    {
        var modules = new List<MemoryModule>();
        foreach (var o in Query("SELECT * FROM Win32_PhysicalMemory"))
        {
            int smbios = (int)U(o, "SMBIOSMemoryType");
            if (smbios is 0 or 1 or 2) smbios = (int)U(o, "MemoryType");
            modules.Add(new MemoryModule
            {
                Slot = S(o, "DeviceLocator"),
                Bank = S(o, "BankLabel"),
                Capacity = U(o, "Capacity"),
                SmbiosType = smbios,
                FormFactor = (int)U(o, "FormFactor"),
                Speed = (uint)U(o, "Speed"),
                ConfiguredSpeed = (uint)U(o, "ConfiguredClockSpeed"),
                Manufacturer = S(o, "Manufacturer"),
                PartNumber = S(o, "PartNumber"),
                Serial = S(o, "SerialNumber"),
                ConfiguredVoltageMv = (uint)U(o, "ConfiguredVoltage"),
            });
        }

        int slots = 0;
        ulong maxKb = 0;
        foreach (var o in Query("SELECT * FROM Win32_PhysicalMemoryArray"))
        {
            // Use = 3: Systemspeicher (nicht Cache/Grafik)
            if (U(o, "Use") is not (3 or 0)) continue;
            slots += (int)U(o, "MemoryDevices");
            ulong ex = U(o, "MaxCapacityEx");
            maxKb += ex > 0 ? ex : U(o, "MaxCapacity");
        }

        string system = "", board = "";
        foreach (var o in Query("SELECT Manufacturer, Model FROM Win32_ComputerSystem"))
            system = $"{S(o, "Manufacturer")} {S(o, "Model")}".Trim();
        foreach (var o in Query("SELECT Manufacturer, Product FROM Win32_BaseBoard"))
            board = $"{S(o, "Manufacturer")} {S(o, "Product")}".Trim();

        bool laptop = false;
        foreach (var o in Query("SELECT ChassisTypes FROM Win32_SystemEnclosure"))
            if (o["ChassisTypes"] is ushort[] types && types.Any(t => LaptopChassis.Contains(t))) laptop = true;

        return new MemoryHardware
        {
            Modules = modules,
            SlotCount = Math.Max(slots, modules.Count),
            MaxCapacity = maxKb * 1024,
            SystemName = system,
            Board = board,
            IsLaptop = laptop,
        };
    }

    public static PagefileInfo ReadPagefile()
    {
        var files = new List<PagefileEntry>();
        foreach (var o in Query("SELECT Name, AllocatedBaseSize, CurrentUsage, PeakUsage FROM Win32_PageFileUsage"))
        {
            const ulong mb = 1UL << 20;
            files.Add(new PagefileEntry(S(o, "Name"), U(o, "AllocatedBaseSize") * mb, U(o, "CurrentUsage") * mb, U(o, "PeakUsage") * mb));
        }
        bool auto = false;
        foreach (var o in Query("SELECT AutomaticManagedPagefile FROM Win32_ComputerSystem"))
            auto = o["AutomaticManagedPagefile"] is bool b && b;
        return new PagefileInfo(files, auto);
    }

    private static List<ManagementBaseObject> Query(string wql)
    {
        try
        {
            using var searcher = new ManagementObjectSearcher(@"root\CIMV2", wql);
            return searcher.Get().Cast<ManagementBaseObject>().ToList();
        }
        catch (Exception)
        {
            return new List<ManagementBaseObject>();
        }
    }

    private static string S(ManagementBaseObject o, string p)
    {
        try { return o[p]?.ToString()?.Trim() ?? ""; }
        catch (ManagementException) { return ""; }
    }

    private static ulong U(ManagementBaseObject o, string p)
    {
        try { return o[p] is null ? 0 : Convert.ToUInt64(o[p]); }
        catch (Exception) { return 0; }
    }
}
