using System.Security.Cryptography;
using System.Text.Json;
using System.Text.RegularExpressions;
using CUE4Parse;
using CUE4Parse.Encryption.Aes;
using CUE4Parse.FileProvider;
using CUE4Parse.MappingsProvider;
using CUE4Parse.MappingsProvider.Usmap;
using CUE4Parse.UE4.Assets.Exports.Texture;
using CUE4Parse.UE4.IO.Objects;
using CUE4Parse.UE4.Objects.UObject;
using CUE4Parse.UE4.Readers;
using CUE4Parse.UE4.Objects.Core.Misc;
using CUE4Parse.UE4.Versions;
using Newtonsoft.Json;
using Serilog;

// Local files only. Keys are read from private JSON, never command arguments or
// logging. No game binary is loaded, launched, injected, or read as a process.
// Do not call OodleHelper.Initialize: the default managed decoder is sufficient
// for supported Oodle streams and cannot auto-download a native DLL.
try
{
    if (args.Length < 5)
    {
        Console.WriteLine("usage: OfflineSkyExtract <paks-directory> <private-key-json> <request-json> <private-output-directory> <GAME_UE5_x> [local-mappings.usmap]");
        return 2;
    }
    using var silentLogger = new LoggerConfiguration().MinimumLevel.Fatal().CreateLogger();
    Log.Logger = silentLogger;
    CUE4ParseLog.UseLogger(silentLogger);
    using var keyDocument = JsonDocument.Parse(File.ReadAllText(args[1]));
    var keyRecord = keyDocument.RootElement.ValueKind == JsonValueKind.Array
        ? keyDocument.RootElement[0] : keyDocument.RootElement;
    var keyText = keyRecord.GetProperty("key").GetString()
        ?? throw new InvalidDataException("Private key JSON has no key value");
    var requests = System.Text.Json.JsonSerializer.Deserialize<string[]>(File.ReadAllText(args[2]))
        ?? throw new InvalidDataException("Request JSON is not a path array");
    if (requests.Length is < 1 or > 64)
        throw new InvalidDataException("Request must contain between 1 and 64 exact package paths");
    var gameVersion = Enum.Parse<EGame>(args[4]);
    var outputRoot = Path.GetFullPath(args[3]);
    EnsureOutsideRepository(outputRoot);
    Directory.CreateDirectory(outputRoot);
    using var provider = new DefaultFileProvider(args[0], SearchOption.TopDirectoryOnly,
        new VersionContainer(gameVersion), StringComparer.OrdinalIgnoreCase);
    if (args.Length > 5)
        provider.MappingsContainer = Path.GetExtension(args[5]).Equals(".json", StringComparison.OrdinalIgnoreCase)
            ? new LocalJsonMappingsProvider(args[5])
            : new FileUsmapTypeMappingsProvider(args[5]);
    provider.Initialize();
    var key = new FAesKey(keyText);
    var requiredGuids = provider.RequiredKeys.ToArray();
    foreach (var guid in requiredGuids)
        provider.SubmitKey(guid, key);
    provider.Mount();
    var results = new List<object>();
    var success = 0;
    var rawFilesExtracted = 0;
    for (var requestIndex = 0; requestIndex < requests.Length; requestIndex++)
    {
        var requestedPath = requests[requestIndex];
        var packageDirectory = Path.Combine(outputRoot, requestIndex.ToString("D3"));
        Directory.CreateDirectory(packageDirectory);
        var rawRecords = new List<object>();
        var textureRecords = new List<object>();
        string? packageError = null;
        string? exportError = null;
        try
        {
            var file = provider[requestedPath];
            if (file.Size > 256L * 1024 * 1024)
                throw new InvalidDataException("Requested package exceeds the 256 MiB bound");
            foreach (var payload in provider.SavePackage(file))
            {
                if (payload.Value.LongLength > 256L * 1024 * 1024)
                    throw new InvalidDataException("Requested payload exceeds the 256 MiB bound");
                var extension = Path.GetExtension(payload.Key);
                var rawPath = Path.Combine(packageDirectory, "payload_" + rawRecords.Count.ToString("D2") + extension);
                File.WriteAllBytes(rawPath, payload.Value);
                rawRecords.Add(new { sourcePath = payload.Key, output = rawPath,
                    bytes = payload.Value.LongLength, sha256 = Convert.ToHexString(SHA256.HashData(payload.Value)).ToLowerInvariant() });
                if (extension == ".uasset")
                    SaveNativeHeader(payload.Value, provider, gameVersion, packageDirectory);
            }
            rawFilesExtracted++;
            if (!file.IsUePackage)
            {
                results.Add(new { requestedPath, raw = rawRecords, textures = textureRecords, packageError, exportError });
                continue;
            }
            try
            {
                var package = provider.LoadPackage(file);
                var exports = package.GetExports().ToArray();
                var exportsPath = Path.Combine(packageDirectory, "exports.private.json");
                File.WriteAllText(exportsPath, JsonConvert.SerializeObject(exports, Formatting.Indented,
                    new JsonSerializerSettings { ReferenceLoopHandling = ReferenceLoopHandling.Ignore }));
                foreach (var texture in exports.OfType<UTexture>())
                {
                    var mip = texture.GetFirstMip();
                    var bytes = mip?.BulkData?.Data;
                    if (mip is null || bytes is null)
                    {
                        textureRecords.Add(new { name = texture.Name, type = texture.GetType().Name,
                            state = "no-readable-mip", format = texture.Format.ToString() });
                        continue;
                    }
                    var mipPath = Path.Combine(packageDirectory, "texture_" + textureRecords.Count.ToString("D2") + ".mip.bin");
                    File.WriteAllBytes(mipPath, bytes);
                    textureRecords.Add(new { name = texture.Name, type = texture.GetType().Name,
                        format = texture.Format.ToString(), sizeX = mip.SizeX, sizeY = mip.SizeY,
                        sizeZ = mip.SizeZ, byteCount = bytes.Length, output = mipPath,
                        addressX = texture.GetTextureAddressX().ToString(),
                        addressY = texture.GetTextureAddressY().ToString(),
                        addressZ = texture.GetTextureAddressZ().ToString(),
                        sha256 = Convert.ToHexString(SHA256.HashData(bytes)).ToLowerInvariant() });
                }
                success++;
            }
            catch (Exception error)
            {
                exportError = Sanitize(error);
            }
        }
        catch (Exception error)
        {
            packageError = Sanitize(error);
        }
        results.Add(new { requestedPath, raw = rawRecords, textures = textureRecords, packageError, exportError });
    }
    var summaryPath = Path.Combine(outputRoot, "extraction-summary.private.json");
    File.WriteAllText(summaryPath, System.Text.Json.JsonSerializer.Serialize(new
    {
        parserEngineSetting = gameVersion.ToString(), actualEngineMinorVerified = false,
        mappingsSupplied = args.Length > 5,
        mountedContainers = provider.MountedVfs.Count, mountedFiles = provider.Files.Count,
        requests = requests.Length, rawFilesExtracted, propertyExportsParsed = success, results
    }, new JsonSerializerOptions { WriteIndented = true }));
    Console.WriteLine(System.Text.Json.JsonSerializer.Serialize(new
    {
        requested = requests.Length, rawFilesExtracted, propertyExportsParsed = success,
        mountedContainers = provider.MountedVfs.Count,
        mountedFiles = provider.Files.Count, privateSummary = summaryPath
    }));
    return rawFilesExtracted > 0 ? 0 : 1;
}
catch (Exception error)
{
    Console.WriteLine(System.Text.Json.JsonSerializer.Serialize(new { failure = Sanitize(error) }));
    return 1;
}

static string Sanitize(Exception error)
{
    var message = error.GetType().Name + ": " + error.Message;
    return Regex.Replace(message, @"(?i)(?:0x)?[0-9a-f]{64}", "[redacted]");
}

static void EnsureOutsideRepository(string outputRoot)
{
    for (var directory = new DirectoryInfo(outputRoot); directory is not null; directory = directory.Parent)
    {
        var gitMarker = Path.Combine(directory.FullName, ".git");
        if (Directory.Exists(gitMarker) || File.Exists(gitMarker))
            throw new InvalidDataException("Private exports must be written outside any Git checkout");
    }
}

static void SaveNativeHeader(byte[] raw, DefaultFileProvider provider, EGame parserVersion, string directory)
{
    using var archive = new FByteArchive("private-package-header", raw, new VersionContainer(parserVersion));
    var summary = new FZenPackageSummary(archive);
    FZenPackageVersioningInfo? version = summary.bHasVersioningInfo == 0 ? null : new FZenPackageVersioningInfo(archive);
    var names = FNameEntrySerialized.LoadNameBatch(archive);
    var nameEnd = archive.Position;
    var classRecords = new List<object>();
    var exportCount = (summary.ExportBundleEntriesOffset - summary.ExportMapOffset) / FExportMapEntry.Size;
    archive.Position = summary.ExportMapOffset;
    for (var index = 0; index < exportCount; index++)
    {
        var entry = new FExportMapEntry(archive);
        string? className = null;
        string? outerName = null;
        if (provider.GlobalData?.ScriptObjectEntriesMap.TryGetValue(entry.ClassIndex, out var script) == true)
        {
            className = provider.GlobalData.GlobalNameMap[script.ObjectName.NameIndex].Name;
            if (provider.GlobalData.ScriptObjectEntriesMap.TryGetValue(script.OuterIndex, out var outer))
                outerName = provider.GlobalData.GlobalNameMap[outer.ObjectName.NameIndex].Name;
        }
        classRecords.Add(new { index, objectName = new FName(entry.ObjectName, names).Text,
            className, outerName, classIndex = entry.ClassIndex.TypeAndId.ToString("x16"),
            serialSize = entry.CookedSerialSize, serialOffset = entry.CookedSerialOffset });
    }
    // Detect the native data-resource table by exact structural coverage; no
    // unversioned property names, types, or values are guessed here.
    var bulkLayouts = new List<object>();
    foreach (var hasPadding in new[] { false, true })
    {
        try
        {
            archive.Position = nameEnd;
            ulong padding = 0;
            if (hasPadding)
            {
                padding = archive.Read<ulong>();
                if (padding > 4096) continue;
                archive.Position += (long)padding;
            }
            var mapBytes = archive.Read<long>();
            if (mapBytes < 0 || mapBytes % FBulkDataMapEntry.Size != 0 ||
                archive.Position + mapBytes != summary.ImportedPublicExportHashesOffset) continue;
            var map = archive.ReadArray<FBulkDataMapEntry>((int)(mapBytes / FBulkDataMapEntry.Size));
            bulkLayouts.Add(new { hasPadding, paddingBytes = padding, entries = map.Select((entry, index) => new
            {
                index, serialOffset = entry.SerialOffset, serialSize = entry.SerialSize,
                flags = entry.Flags, cookedIndex = entry.CookedIndex.Value
            }).ToArray() });
        }
        catch { }
    }
    File.WriteAllText(Path.Combine(directory, "native-header.private.json"),
        System.Text.Json.JsonSerializer.Serialize(new
        {
            headerSize = summary.HeaderSize, cookedHeaderSize = summary.CookedHeaderSize,
            packageFlags = summary.PackageFlags.ToString(),
            hasVersioningInfo = summary.bHasVersioningInfo != 0,
            versioningInfo = version is null ? null : new
            {
                zenVersion = version.Value.ZenVersion.ToString(),
                ue4 = version.Value.PackageVersion.FileVersionUE4,
                ue5 = version.Value.PackageVersion.FileVersionUE5,
                licensee = version.Value.LicenseeVersion
            },
            nameMapEnd = nameEnd, names = names.Select(name => name.Name).ToArray(),
            exports = classRecords, bulkLayouts
        }, new JsonSerializerOptions { WriteIndented = true }));
}
