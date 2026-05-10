# gen_index: Version Index Generator

The `gen_index` tool automates the creation of the `versions.json` file by scanning directories. This index is used by `version_manager` to know where each version is physically located on disk.

Generated index entries are sorted by version name in descending order by default. Use `--sort-by-timestamp` to order entries by timestamp descending instead.

## Usage

```bash
./gen_index --folders folders.txt --output versions.json
```

### Options

| Option | Description | Default |
| :--- | :--- | :--- |
| `--folders <file>` | File containing a list of base directories to scan. | (Required) |
| `-o, --output <file>` | Output index file path. | `versions.json` |
| `--version-file <name>` | The filename to look for inside directories to extract version data. | `version.json` |
| `--relative` | Write paths as relative (`./path`) instead of absolute. | (off) |
| `--sort-by-timestamp` | Sort generated entries by timestamp descending instead of version name. | (off) |
| `--max-age-months <n>` | Skip versions older than `n` months based on timestamp or folder mtime. | (off) |

## Path modes

By default, `gen_index` resolves every folder to an **absolute path** via `realpath` before writing it to the index. This ensures the index works regardless of the working directory when it is later consumed.

With `--relative`, paths are kept as-is (or prefixed with `./` for bare names) so the index is **portable** — useful when the index file and the version directories are distributed together and the working directory at consumption time is known.

```bash
# Portable index — paths written as ./v1.0.0, ./v1.2.0, etc.
./gen_index --folders folders.txt --output versions.json --relative
```

Lines in the `--folders` file may already start with `./` or `../`; they are passed through unchanged when `--relative` is active. Bare names such as `v1.0.0` are automatically prefixed with `./`.

## Folder Scanning Logic

`gen_index` reads each directory listed in the `--folders` file, checks the directory itself, and checks only its immediate subdirectories for `version.json` (or the specified `--version-file`).

Scanning is intentionally limited to one nesting level (non-recursive).

### folders.txt example
```text
/opt/apps/my-service/deployments
/var/lib/backups/my-service
```

### version.json format
If a subdirectory contains this file, it is included in the index:
```json
{
  "name": "1.2.4",
  "version": "1.2.4",
  "timestamp": 1710123456,
  "description": "Optional metadata"
}
```

Version selection priority for generated index entries is:
1. `name`
2. `version`
3. folder name (fallback)

## Integrating with version_manager

Once generated, point your `manifest.json`'s `version_index` field to the output of `gen_index`:

```json
{
  "current_version": "1.2.4",
  "version_index": "versions.json",
  ...
}
```
