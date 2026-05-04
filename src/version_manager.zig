const std = @import("std");

const TimestampRange = struct {
    start: usize,
    end: usize,
};

const VersionIndexSortContext = struct {};

pub const Manifest = struct {
    current_version: ?[]const u8 = null,
    version_index: [][]const u8,
    symlink: []const u8 = "current",
    trigger_cmd: ?[]const u8 = null,
    history: []VersionHistory = &.{},
    current_deployed_at: ?i64 = null,

    pub const VersionHistory = struct {
        version: []const u8,
        timestamp: i64,
        comment: ?[]const u8 = null,
        failed: bool = false,
        deployed_at: ?i64 = null,
        removed_at: ?i64 = null,
    };

    pub fn load(allocator: std.mem.Allocator, path: []const u8) !Manifest {
        const file = try std.fs.cwd().openFile(path, .{});
        defer file.close();

        const content = try file.readToEndAlloc(allocator, 1024 * 1024);
        defer allocator.free(content);

        const parsed = try std.json.parseFromSlice(Manifest, allocator, content, .{ .ignore_unknown_fields = true });
        defer parsed.deinit();

        return try cloneManifest(allocator, parsed.value);
    }

    fn cloneManifest(allocator: std.mem.Allocator, source: Manifest) !Manifest {
        const current_version = if (source.current_version) |cv| try allocator.dupe(u8, cv) else null;
        var version_index = try allocator.alloc([]const u8, source.version_index.len);
        for (source.version_index, 0..) |idx, i| {
            version_index[i] = try allocator.dupe(u8, idx);
        }
        const symlink = try allocator.dupe(u8, source.symlink);
        const trigger_cmd = if (source.trigger_cmd) |cmd| try allocator.dupe(u8, cmd) else null;

        var history = try allocator.alloc(VersionHistory, source.history.len);
        for (source.history, 0..) |item, i| {
            history[i] = .{
                .version = try allocator.dupe(u8, item.version),
                .timestamp = item.timestamp,
                .comment = if (item.comment) |c| try allocator.dupe(u8, c) else null,
                .failed = item.failed,
                .deployed_at = item.deployed_at,
                .removed_at = item.removed_at,
            };
        }

        return Manifest{
            .current_version = current_version,
            .version_index = version_index,
            .symlink = symlink,
            .trigger_cmd = trigger_cmd,
            .history = history,
            .current_deployed_at = source.current_deployed_at,
        };
    }

    pub fn save(self: Manifest, path: []const u8) !void {
        const file = try std.fs.cwd().createFile(path, .{});
        defer file.close();

        var out_buf: [4096]u8 = undefined;
        var writer_struct = file.writer(&out_buf);
        const writer = &writer_struct.interface;
        try std.json.Stringify.value(self, .{ .whitespace = .indent_2 }, writer);
        try writer.flush();
    }

    /// Finds the path for a given version by searching all indices.
    /// Caller owns the returned memory.
    pub fn findVersionPath(self: Manifest, allocator: std.mem.Allocator, version: []const u8) !?[]const u8 {
        for (self.version_index) |idx_path| {
            var index = VersionIndex.load(allocator, idx_path) catch |err| {
                std.debug.print("Warning: Failed to load index {s}: {any}\n", .{ idx_path, err });
                continue;
            };
            defer index.deinit(allocator);

            if (index.findVersion(version)) |p| {
                if (std.fs.path.isAbsolute(p)) {
                    return try allocator.dupe(u8, p);
                }
                const idx_dir = std.fs.path.dirname(idx_path) orelse ".";
                return try std.fs.path.join(allocator, &[_][]const u8{ idx_dir, p });
            }
        }
        return null;
    }

    pub fn deinit(self: *Manifest, allocator: std.mem.Allocator) void {
        if (self.current_version) |cv| allocator.free(cv);
        for (self.version_index) |idx| allocator.free(idx);
        allocator.free(self.version_index);
        allocator.free(self.symlink);
        if (self.trigger_cmd) |cmd| allocator.free(cmd);
        for (self.history) |item| {
            allocator.free(item.version);
            if (item.comment) |c| allocator.free(c);
        }
        allocator.free(self.history);
    }
};

pub const VersionIndex = struct {
    versions: []const VersionEntry,

    pub const VersionEntry = struct {
        version: []const u8,
        path: []const u8,
        timestamp: ?i64 = null,
        description: ?[]const u8 = null,
        metadata: ?std.json.Value = null,
    };

    pub fn load(allocator: std.mem.Allocator, path: []const u8) !VersionIndex {
        const file = try std.fs.cwd().openFile(path, .{});
        defer file.close();

        const content = try file.readToEndAlloc(allocator, 1024 * 1024);
        defer allocator.free(content);

        const parsed = try std.json.parseFromSlice(VersionIndex, allocator, content, .{ .ignore_unknown_fields = true });
        defer parsed.deinit();
        return try cloneVersionIndex(allocator, parsed.value);
    }

    fn cloneVersionIndex(allocator: std.mem.Allocator, source: VersionIndex) !VersionIndex {
        var versions = try allocator.alloc(VersionEntry, source.versions.len);
        for (source.versions, 0..) |entry, i| {
            versions[i] = .{
                .version = try allocator.dupe(u8, entry.version),
                .path = try allocator.dupe(u8, entry.path),
                .timestamp = entry.timestamp,
                .description = if (entry.description) |d| try allocator.dupe(u8, d) else null,
                .metadata = null, // metadata cloning is complex, skip for now if not needed
            };
        }
        return VersionIndex{ .versions = versions };
    }

    pub fn findVersion(self: VersionIndex, version: ?[]const u8) ?[]const u8 {
        if (version == null) return null;
        for (self.versions) |entry| {
            if (std.mem.eql(u8, entry.version, version.?)) return entry.path;
        }
        return null;
    }

    pub fn save(self: VersionIndex, path: []const u8) !void {
        const file = try std.fs.cwd().createFile(path, .{});
        defer file.close();

        var out_buf: [4096]u8 = undefined;
        var writer_struct = file.writer(&out_buf);
        const writer = &writer_struct.interface;
        try std.json.Stringify.value(self, .{ .whitespace = .indent_2 }, writer);
        try writer.flush();
    }

    pub fn deinit(self: *VersionIndex, allocator: std.mem.Allocator) void {
        for (self.versions) |entry| {
            allocator.free(entry.version);
            allocator.free(entry.path);
            if (entry.description) |d| allocator.free(d);
        }
        allocator.free(self.versions);
    }
};

pub fn generateIndex(allocator: std.mem.Allocator, folders_file_path: []const u8, version_file_name: []const u8, relative: bool, max_age_months: ?u64) !VersionIndex {
    const file = try std.fs.cwd().openFile(folders_file_path, .{});
    defer file.close();

    const content = try file.readToEndAlloc(allocator, 1024 * 1024);
    defer allocator.free(content);

    var cutoff_timestamp: ?i64 = null;
    if (max_age_months != null) {
        const months: i64 = @intCast(max_age_months.?);
        const now = std.time.timestamp();
        cutoff_timestamp = now - months * 2592000;
    }

    var entries = std.ArrayList(VersionIndex.VersionEntry).empty;
    errdefer {
        for (entries.items) |entry| {
            allocator.free(entry.version);
            allocator.free(entry.path);
            if (entry.description) |d| allocator.free(d);
        }
        entries.deinit(allocator);
    }

    var line_iter = std.mem.splitScalar(u8, content, '\n');
    while (line_iter.next()) |raw_line| {
        const line = std.mem.trim(u8, raw_line, " \t\r\n");
        if (line.len == 0) continue;

        const folder_path = if (relative) blk: {
            if (std.mem.startsWith(u8, line, "./") or std.mem.startsWith(u8, line, "../") or std.fs.path.isAbsolute(line)) {
                break :blk try allocator.dupe(u8, line);
            } else {
                break :blk try std.fmt.allocPrint(allocator, "./{s}", .{line});
            }
        } else try std.fs.cwd().realpathAlloc(allocator, line);
        defer allocator.free(folder_path);

        try scanFolder(allocator, &entries, folder_path, version_file_name, cutoff_timestamp);
    }

    std.sort.pdq(VersionIndex.VersionEntry, entries.items, VersionIndexSortContext{}, versionEntryNameDescending);

    return VersionIndex{ .versions = try entries.toOwnedSlice(allocator) };
}

fn versionEntryNameDescending(_: VersionIndexSortContext, lhs: VersionIndex.VersionEntry, rhs: VersionIndex.VersionEntry) bool {
    const min_len = if (lhs.version.len < rhs.version.len) lhs.version.len else rhs.version.len;
    var i: usize = 0;
    while (i < min_len) : (i += 1) {
        if (lhs.version[i] != rhs.version[i]) {
            return lhs.version[i] > rhs.version[i];
        }
    }
    return lhs.version.len > rhs.version.len;
}

fn scanFolder(allocator: std.mem.Allocator, entries: *std.ArrayList(VersionIndex.VersionEntry), folder_path: []const u8, version_file_name: []const u8, cutoff_timestamp: ?i64) !void {
    var dir = try std.fs.cwd().openDir(folder_path, .{ .iterate = true });
    defer dir.close();

    // Check if this folder itself is a version folder
    if (try processVersionFolder(allocator, entries, folder_path, version_file_name, cutoff_timestamp)) {
        return;
    }

    // Otherwise, scan subfolders
    var iter = dir.iterate();
    while (try iter.next()) |entry| {
        if (entry.kind == .directory) {
            const sub_path = try std.fs.path.join(allocator, &[_][]const u8{ folder_path, entry.name });
            defer allocator.free(sub_path);
            _ = try processVersionFolder(allocator, entries, sub_path, version_file_name, cutoff_timestamp);
        }
    }
}

fn processVersionFolder(allocator: std.mem.Allocator, entries: *std.ArrayList(VersionIndex.VersionEntry), folder_path: []const u8, version_file_name: []const u8, cutoff_timestamp: ?i64) !bool {
    var dir = std.fs.cwd().openDir(folder_path, .{}) catch return false;
    defer dir.close();

    const v_file = dir.openFile(version_file_name, .{}) catch return false;
    defer v_file.close();

    const v_content = try v_file.readToEndAlloc(allocator, 64 * 1024);
    defer allocator.free(v_content);

    var v_info_version: ?[]const u8 = null;
    var v_info_timestamp: ?i64 = null;
    var v_info_description: ?[]const u8 = null;

    if (std.json.parseFromSlice(VersionInfo, allocator, v_content, .{ .ignore_unknown_fields = true })) |parsed| {
        defer parsed.deinit();
        if (parsed.value.version) |v| v_info_version = try allocator.dupe(u8, v);
        v_info_timestamp = parsed.value.timestamp;
        if (parsed.value.description) |d| v_info_description = try allocator.dupe(u8, d);
    } else |err| {
        std.debug.print("Failed to parse {s} in {s}: {any}. Using fallbacks.\n", .{ version_file_name, folder_path, err });
    }
    defer {
        if (v_info_version) |v| allocator.free(v);
        if (v_info_description) |d| allocator.free(d);
    }

    const folder_name = std.fs.path.basename(folder_path);

    const version_name = if (v_info_version) |v| try allocator.dupe(u8, v) else try allocator.dupe(u8, folder_name);
    errdefer allocator.free(version_name);

    const path = try allocator.dupe(u8, folder_path);
    errdefer allocator.free(path);

    const timestamp = if (v_info_timestamp) |ts| ts else blk: {
        const stat = try v_file.stat();
        break :blk @as(i64, @intCast(@divTrunc(stat.mtime, std.time.ns_per_s)));
    };

    const description = if (v_info_description) |d| try allocator.dupe(u8, d) else null;
    errdefer if (description) |d| allocator.free(d);

    if (cutoff_timestamp != null and timestamp < cutoff_timestamp.?) {
        return false;
    }

    try entries.append(allocator, .{
        .version = version_name,
        .path = path,
        .timestamp = timestamp,
        .description = description,
    });

    return true;
}

pub const VersionInfo = struct {
    version: ?[]const u8 = null,
    timestamp: ?i64 = null,
    description: ?[]const u8 = null,
};

pub fn atomicSwap(allocator: std.mem.Allocator, target_link: []const u8, new_path: []const u8) !void {
    const tmp_link = try std.fmt.allocPrint(allocator, "{s}.tmp", .{target_link});
    defer allocator.free(tmp_link);

    // Remove tmp_link if it exists
    std.fs.cwd().deleteFile(tmp_link) catch {};

    // Create new symlink at tmp_link
    try std.fs.cwd().symLink(new_path, tmp_link, .{ .is_directory = true });

    // Rename tmp_link to target_link (atomic swap)
    try std.fs.rename(std.fs.cwd(), tmp_link, std.fs.cwd(), target_link);
}

pub fn runTrigger(allocator: std.mem.Allocator, trigger_cmd: []const u8) !void {
    const argv = if (std.fs.path.sep == '\\')
        &[_][]const u8{ "cmd", "/c", trigger_cmd }
    else
        &[_][]const u8{ "/bin/sh", "-c", trigger_cmd };

    var child = std.process.Child.init(argv, allocator);
    _ = try child.spawnAndWait();
}

pub fn reconcile(allocator: std.mem.Allocator, manifest_path: []const u8) !bool {
    var manifest = try Manifest.load(allocator, manifest_path);
    defer manifest.deinit(allocator);

    const target_version = manifest.current_version orelse {
        std.debug.print("No version currently active in manifest. Skipping reconciliation.\n", .{});
        return false;
    };

    const target_path_val = try manifest.findVersionPath(allocator, target_version) orelse {
        std.debug.print("Version {s} not found in any indices ({any})\n", .{ target_version, manifest.version_index });
        return error.VersionNotFound;
    };
    defer allocator.free(target_path_val);

    const manifest_dir = std.fs.path.dirname(manifest_path) orelse ".";
    const current_link = if (std.fs.path.isAbsolute(manifest.symlink))
        try allocator.dupe(u8, manifest.symlink)
    else
        try std.fs.path.join(allocator, &[_][]const u8{ manifest_dir, manifest.symlink });
    defer allocator.free(current_link);

    var needs_swap = false;
    var buf: [std.fs.max_path_bytes]u8 = undefined;
    const real_path = std.fs.cwd().readLink(current_link, &buf) catch |err| blk: {
        if (err == error.FileNotFound) {
            needs_swap = true;
            break :blk "";
        }
        return err;
    };

    if (!needs_swap and !std.mem.eql(u8, real_path, target_path_val)) {
        needs_swap = true;
    }

    if (needs_swap) {
        std.debug.print("Reconciling: Swapping {s} to {s}\n", .{ current_link, target_path_val });
        try atomicSwap(allocator, current_link, target_path_val);
        if (manifest.trigger_cmd) |cmd| {
            std.debug.print("Executing trigger: {s}\n", .{cmd});
            try runTrigger(allocator, cmd);
        }
        return true;
    }

    return false;
}

pub fn deploy(allocator: std.mem.Allocator, manifest_path: []const u8, version: []const u8, force: bool) !void {
    var manifest = try Manifest.load(allocator, manifest_path);
    defer manifest.deinit(allocator);

    // Check if version is marked as failed (only look at the latest entry for this version)
    if (!force) {
        var i: usize = manifest.history.len;
        while (i > 0) {
            i -= 1;
            const item = manifest.history[i];
            if (std.mem.eql(u8, item.version, version)) {
                if (item.failed) {
                    std.debug.print("Error: The most recent record for version {s} in history is marked as FAILED. Use --force to deploy anyway.\n", .{version});
                    return error.VersionFailed;
                }
                break;
            }
        }
    }

    if (manifest.current_version) |cv| {
        if (std.mem.eql(u8, cv, version)) {
            std.debug.print("Version {s} is already current.\n", .{version});
            return;
        }
    }

    const target_path = try manifest.findVersionPath(allocator, version) orelse {
        std.debug.print("Version {s} not found in any indices.\n", .{version});
        return error.VersionNotFound;
    };
    defer allocator.free(target_path);

    // Update manifest
    const now = std.time.timestamp();

    if (manifest.current_version) |cv| {
        const old_deployed_at = manifest.current_deployed_at;

        // Add to history
        var new_history = try allocator.alloc(Manifest.VersionHistory, manifest.history.len + 1);
        for (manifest.history, 0..) |item, i| {
            new_history[i] = .{
                .version = try allocator.dupe(u8, item.version),
                .timestamp = item.timestamp,
                .comment = if (item.comment) |c| try allocator.dupe(u8, c) else null,
                .failed = item.failed,
                .deployed_at = item.deployed_at,
                .removed_at = item.removed_at,
            };
        }
        new_history[manifest.history.len] = .{
            .version = try allocator.dupe(u8, cv),
            .timestamp = now,
            .comment = try std.fmt.allocPrint(allocator, "Deployed {s}", .{version}),
            .failed = false,
            .deployed_at = old_deployed_at,
            .removed_at = now,
        };

        // Replace history
        for (manifest.history) |item| {
            allocator.free(item.version);
            if (item.comment) |c| allocator.free(c);
        }
        allocator.free(manifest.history);
        manifest.history = new_history;

        allocator.free(cv);
    }

    manifest.current_version = try allocator.dupe(u8, version);
    manifest.current_deployed_at = now;

    try manifest.save(manifest_path);
    _ = try reconcile(allocator, manifest_path);
}

pub fn touchVersionFile(version_file_path: []const u8) !void {
    const read_file = try std.fs.cwd().openFile(version_file_path, .{});
    defer read_file.close();

    const content = try read_file.readToEndAlloc(std.heap.page_allocator, 64 * 1024);
    defer std.heap.page_allocator.free(content);

    var arena = std.heap.ArenaAllocator.init(std.heap.page_allocator);
    defer arena.deinit();
    const allocator = arena.allocator();

    const parsed = try std.json.parseFromSlice(std.json.Value, allocator, content, .{ .ignore_unknown_fields = true });
    defer parsed.deinit();

    switch (parsed.value) {
        .object => {},
        else => return error.InvalidArgument,
    }

    const range = findTimestampValueRange(content) orelse return error.InvalidArgument;
    const now = std.time.timestamp();
    const now_str = try std.fmt.allocPrint(std.heap.page_allocator, "{d}", .{now});
    defer std.heap.page_allocator.free(now_str);

    const out_len = content.len - (range.end - range.start) + now_str.len;
    const out = try std.heap.page_allocator.alloc(u8, out_len);
    defer std.heap.page_allocator.free(out);

    std.mem.copyForwards(u8, out[0..range.start], content[0..range.start]);
    std.mem.copyForwards(u8, out[range.start .. range.start + now_str.len], now_str);
    std.mem.copyForwards(u8, out[range.start + now_str.len ..], content[range.end..]);

    const write_file = try std.fs.cwd().createFile(version_file_path, .{ .truncate = true });
    defer write_file.close();
    try write_file.writeAll(out);
}

fn isWhitespace(c: u8) bool {
    return c == ' ' or c == '\t' or c == '\r' or c == '\n';
}

fn skipString(content: []const u8, start: usize) ?usize {
    var i = start;
    var escape = false;
    while (i < content.len) {
        const c = content[i];
        if (escape) {
            escape = false;
        } else if (c == '\\') {
            escape = true;
        } else if (c == '"') {
            return i + 1;
        }
        i += 1;
    }
    return null;
}

fn skipNumber(content: []const u8, start: usize) ?usize {
    var i = start;
    if (i < content.len and content[i] == '-') {
        i += 1;
    }
    if (i >= content.len) return null;
    if (content[i] == '0') {
        i += 1;
    } else if (content[i] >= '1' and content[i] <= '9') {
        while (i < content.len and content[i] >= '0' and content[i] <= '9') {
            i += 1;
        }
    } else {
        return null;
    }
    if (i < content.len and content[i] == '.') {
        i += 1;
        if (i >= content.len or content[i] < '0' or content[i] > '9') return null;
        while (i < content.len and content[i] >= '0' and content[i] <= '9') {
            i += 1;
        }
    }
    if (i < content.len and (content[i] == 'e' or content[i] == 'E')) {
        i += 1;
        if (i < content.len and (content[i] == '+' or content[i] == '-')) {
            i += 1;
        }
        if (i >= content.len or content[i] < '0' or content[i] > '9') return null;
        while (i < content.len and content[i] >= '0' and content[i] <= '9') {
            i += 1;
        }
    }
    return i;
}

fn skipLiteral(content: []const u8, start: usize, literal: []const u8) ?usize {
    if (start + literal.len > content.len) return null;
    if (!std.mem.eql(u8, content[start .. start + literal.len], literal)) return null;
    return start + literal.len;
}

fn skipValue(content: []const u8, start: usize) ?usize {
    if (start >= content.len) return null;
    const c = content[start];
    if (c == '"') {
        return skipString(content, start + 1);
    }
    if (c == '{' or c == '[') {
        var depth: usize = 1;
        var i = start + 1;
        var in_string = false;
        var escape = false;
        while (i < content.len) {
            const ch = content[i];
            if (in_string) {
                if (escape) {
                    escape = false;
                } else if (ch == '\\') {
                    escape = true;
                } else if (ch == '"') {
                    in_string = false;
                }
            } else {
                if (ch == '"') {
                    in_string = true;
                } else if (ch == '{' or ch == '[') {
                    depth += 1;
                } else if (ch == '}' or ch == ']') {
                    depth -= 1;
                    if (depth == 0) return i + 1;
                }
            }
            i += 1;
        }
        return null;
    }
    if (c == 't') return skipLiteral(content, start, "true");
    if (c == 'f') return skipLiteral(content, start, "false");
    if (c == 'n') return skipLiteral(content, start, "null");
    return skipNumber(content, start);
}

fn findTimestampValueRange(content: []const u8) ?TimestampRange {
    var i: usize = 0;
    while (i < content.len and isWhitespace(content[i])) i += 1;
    if (i >= content.len or content[i] != '{') return null;
    i += 1;

    while (true) {
        while (i < content.len and isWhitespace(content[i])) i += 1;
        if (i >= content.len) return null;
        if (content[i] == '}') return null;
        if (content[i] != '"') return null;
        const key_start = i + 1;
        const key_end = skipString(content, key_start) orelse return null;
        const key = content[key_start .. key_end - 1];
        i = key_end;
        while (i < content.len and isWhitespace(content[i])) i += 1;
        if (i >= content.len or content[i] != ':') return null;
        i += 1;
        while (i < content.len and isWhitespace(content[i])) i += 1;
        const value_start = i;
        if (std.mem.eql(u8, key, "timestamp")) {
            const value_end = skipValue(content, value_start) orelse return null;
            return TimestampRange{ .start = value_start, .end = value_end };
        }
        const value_end = skipValue(content, value_start) orelse return null;
        i = value_end;
        while (i < content.len and isWhitespace(content[i])) i += 1;
        if (i >= content.len) return null;
        if (content[i] == ',') {
            i += 1;
            continue;
        }
        if (content[i] == '}') return null;
        return null;
    }
}

pub fn upgradeFailed(allocator: std.mem.Allocator, manifest_path: []const u8) !void {
    var manifest = try Manifest.load(allocator, manifest_path);
    defer manifest.deinit(allocator);

    if (manifest.history.len == 0) {
        std.debug.print("Error: No history to revert to.\n", .{});
        return error.NoHistory;
    }

    const failed_version = if (manifest.current_version) |cv| try allocator.dupe(u8, cv) else null;
    defer if (failed_version) |fv| allocator.free(fv);
    const failed_deployed_at = manifest.current_deployed_at;
    const now = std.time.timestamp();

    // Last successful version from history
    const last_entry = manifest.history[manifest.history.len - 1];
    const revert_version = try allocator.dupe(u8, last_entry.version);
    defer allocator.free(revert_version);
    const revert_deployed_at = last_entry.deployed_at;

    std.debug.print("Marking {?s} as failed and reverting to {s}...\n", .{ failed_version, revert_version });

    // New history: copy all existing + append failed_version
    var new_history = try allocator.alloc(Manifest.VersionHistory, manifest.history.len + 1);
    for (manifest.history, 0..) |item, i| {
        new_history[i] = .{
            .version = try allocator.dupe(u8, item.version),
            .timestamp = item.timestamp,
            .comment = if (item.comment) |c| try allocator.dupe(u8, c) else null,
            .failed = item.failed,
            .deployed_at = item.deployed_at,
            .removed_at = item.removed_at,
        };
    }

    new_history[manifest.history.len] = .{
        .version = if (failed_version) |fv| try allocator.dupe(u8, fv) else try allocator.dupe(u8, "unknown"),
        .timestamp = now,
        .comment = try std.fmt.allocPrint(allocator, "Failed, reverted to {s}", .{revert_version}),
        .failed = true,
        .deployed_at = failed_deployed_at,
        .removed_at = now,
    };

    // Replace current version
    if (manifest.current_version) |cv| allocator.free(cv);
    manifest.current_version = try allocator.dupe(u8, revert_version);
    manifest.current_deployed_at = revert_deployed_at;

    // Replace history
    for (manifest.history) |item| {
        allocator.free(item.version);
        if (item.comment) |c| allocator.free(c);
    }
    allocator.free(manifest.history);
    manifest.history = new_history;

    try manifest.save(manifest_path);
    _ = try reconcile(allocator, manifest_path);
}
