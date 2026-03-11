# version_manager: Zero-Downtime Deployment

The `version_manager` tool handles application versioning, atomic symlink swaps, and deployment history tracking.

## Manifest Format (`manifest.json`)
The manifest is the source of truth for an application's deployment state.

```json
{
  "current_version": "1.2.4",
  "version_index": ["versions.json", "archive.json"],
  "symlink": "current",
  "trigger_cmd": "systemctl restart my-app",
  "history": [
    {
      "version": "1.2.3",
      "timestamp": 1710123456,
      "comment": "Previous stable release",
      "failed": false,
      "deployed_at": 1710123000,
      "removed_at": 1710123456
    }
  ],
  "current_deployed_at": 1710123456
}
```

- `current_version`: The ID of the version that *should* be active.
- `version_index`: Array of paths to index files (or a single string for backward compatibility with load).
- `symlink`: (Optional) The path to the symlink that points to the current version. Default is "current". Can be absolute or relative to the manifest.
- `trigger_cmd`: (Optional) Shell command executed only when a version swap actually occurs.
- `history`: (Automatic) Log of previous deployment actions.
- `current_deployed_at`: (Automatic) Timestamp when the current version was deployed.

---

## Commands

### 1. init
Initializes a new manifest file.
```sh
./version_manager app-manifest.json init [indices] [symlink]
```
-   `[indices]`: (Optional) Comma-separated list of index files. Defaults to `versions.json`.
-   `[symlink]`: (Optional) The symlink path. Defaults to `current`.

### 2. deploy
Deploys a new version by updating the manifest and preparing for a symlink swap.
```sh
./version_manager app-manifest.json deploy <version> [--force]
```
-   `<version>`: The version ID to deploy. Must exist in the version index.
-   `--force`: (Optional) Deploy even if the version is marked as failed in history.

### 3. reconcile
Ensures the `current` symlink matches the version specified in the manifest.
```sh
./version_manager app-manifest.json reconcile
```

#### Atomic Symlink Swaps
When `reconcile` (or `deploy`) detects a version mismatch, it atomically replaces the `current` symlink using a temporary link and the `rename` syscall.

### 4. upgrade-failed
Reverts to the previous stable version if a new deployment is found to be unstable.
```sh
./version_manager app-manifest.json upgrade-failed
```
Promotes the most recent successful version from history and marks the current one as `"failed": true`.

### 5. list
Lists available versions from the index and provides details on the current version and recent history.
```sh
./version_manager app-manifest.json list
```
Displays index entries, current deployment time, and the last 3 history entries with timestamps and status.
