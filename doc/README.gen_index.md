# gen_index: Version Index Generator

The `gen_index` tool automates the creation of the `versions.json` file by scanning directories. This index is used by `version_manager` to know where each version is physically located on disk.

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

## Path modes

By default, `gen_index` resolves every folder to an **absolute path** via `realpath` before writing it to the index. This ensures the index works regardless of the working directory when it is later consumed.

With `--relative`, paths are kept as-is (or prefixed with `./` for bare names) so the index is **portable** — useful when the index file and the version directories are distributed together and the working directory at consumption time is known.

```bash
# Portable index — paths written as ./v1.0.0, ./v1.2.0, etc.
./gen_index --folders folders.txt --output versions.json --relative
```

Lines in the `--folders` file may already start with `./` or `../`; they are passed through unchanged when `--relative` is active. Bare names such as `v1.0.0` are automatically prefixed with `./`.

## Folder Scanning Logic

`gen_index` reads each directory listed in the `--folders` file and looks for immediate subdirectories that contain a `version.json` (or the specified `--version-file`).

### folders.txt example
```text
/opt/apps/my-service/deployments
/var/lib/backups/my-service
```

### version.json format
If a subdirectory contains this file, it is included in the index:
```json
{
  "version": "1.2.4",
  "timestamp": 1710123456,
  "description": "Optional metadata"
}
```

## Integrating with version_manager

Once generated, point your `manifest.json`'s `version_index` field to the output of `gen_index`:

```json
{
  "current_version": "1.2.4",
  "version_index": "versions.json",
  ...
}
```
