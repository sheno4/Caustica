using System.Text;
using CUE4Parse.MappingsProvider;
using Newtonsoft.Json.Linq;

// Schemas supplied by offline recovery of native property descriptors. The
// provider neither invents a schema nor falls back to guessed field types.
public sealed class LocalJsonMappingsProvider : JsonTypeMappingsProvider
{
    private string? _path;

    public LocalJsonMappingsProvider(string path) => Load(path);

    public override void Load(string path, StringComparer? comparer = null)
    {
        _path = path;
        Load(File.ReadAllBytes(path), comparer);
    }

    public override void Load(byte[] bytes, StringComparer? comparer = null)
    {
        MappingsForGame = new TypeMappings();
        var token = JToken.Parse(Encoding.UTF8.GetString(bytes));
        if (token is JArray)
            AddStructs(token.ToString());
        else
        {
            AddStructs(token["structs"]?.ToString() ?? throw new InvalidDataException("JSON mappings have no structs array"));
            if (token["enums"] is JArray enums)
                AddEnums(enums.ToString());
        }
    }

    public override void Reload()
    {
        if (_path is null)
            throw new InvalidOperationException("No local mappings file path");
        Load(_path);
    }
}
