const std = @import("std");

pub const Manifest = struct {
    current_version: []const u8,
    version_index: []const u8,
    trigger_cmd: ?[]const u8 = null,
    history: []VersionHistory = &.{},

    pub const VersionHistory = struct {
        version: []const u8,
        timestamp: i64,
        comment: ?[]const u8 = null,
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

    pub fn save(self: Manifest, path: []const u8) !void {
        const file = try std.fs.cwd().createFile(path, .{});
        defer file.close();

        var out_buf: [4096]u8 = undefined;
        var writer_struct = file.writer(&out_buf);
        const writer = &writer_struct.interface;
        try std.json.Stringify.value(self, .{ .whitespace = .indent_2 }, writer);
        try writer.flush();
    }

    fn cloneManifest(allocator: std.mem.Allocator, source: Manifest) !Manifest {
        const current_version = try allocator.dupe(u8, source.current_version);
        const version_index = try allocator.dupe(u8, source.version_index);
        const trigger_cmd = if (source.trigger_cmd) |cmd| try allocator.dupe(u8, cmd) else null;

        var history = try allocator.alloc(VersionHistory, source.history.len);
        for (source.history, 0..) |item, i| {
            history[i] = .{
                .version = try allocator.dupe(u8, item.version),
                .timestamp = item.timestamp,
                .comment = if (item.comment) |c| try allocator.dupe(u8, c) else null,
            };
        }

        return Manifest{
            .current_version = current_version,
            .version_index = version_index,
            .trigger_cmd = trigger_cmd,
            .history = history,
        };
    }

    pub fn deinit(self: *Manifest, allocator: std.mem.Allocator) void {
        allocator.free(self.current_version);
        allocator.free(self.version_index);
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

    pub fn findVersion(self: VersionIndex, version: []const u8) ?[]const u8 {
        for (self.versions) |entry| {
            if (std.mem.eql(u8, entry.version, version)) return entry.path;
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

pub fn generateIndex(allocator: std.mem.Allocator, folders_file_path: []const u8, version_file_name: []const u8) !VersionIndex {
    const file = try std.fs.cwd().openFile(folders_file_path, .{});
    defer file.close();

    const content = try file.readToEndAlloc(allocator, 1024 * 1024);
    defer allocator.free(content);

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

        const folder_path = try std.fs.cwd().realpathAlloc(allocator, line);
        defer allocator.free(folder_path);

        try scanFolder(allocator, &entries, folder_path, version_file_name);
    }

    return VersionIndex{ .versions = try entries.toOwnedSlice(allocator) };
}

fn scanFolder(allocator: std.mem.Allocator, entries: *std.ArrayList(VersionIndex.VersionEntry), folder_path: []const u8, version_file_name: []const u8) !void {
    var dir = try std.fs.openDirAbsolute(folder_path, .{ .iterate = true });
    defer dir.close();

    // Check if this folder itself is a version folder
    if (try processVersionFolder(allocator, entries, folder_path, version_file_name)) {
        return;
    }

    // Otherwise, scan subfolders
    var iter = dir.iterate();
    while (try iter.next()) |entry| {
        if (entry.kind == .directory) {
            const sub_path = try std.fs.path.join(allocator, &[_][]const u8{ folder_path, entry.name });
            defer allocator.free(sub_path);
            _ = try processVersionFolder(allocator, entries, sub_path, version_file_name);
        }
    }
}

fn processVersionFolder(allocator: std.mem.Allocator, entries: *std.ArrayList(VersionIndex.VersionEntry), folder_path: []const u8, version_file_name: []const u8) !bool {
    var dir = std.fs.openDirAbsolute(folder_path, .{}) catch return false;
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

    var index = try VersionIndex.load(allocator, manifest.version_index);
    defer index.deinit(allocator);

    const target_path = index.findVersion(manifest.current_version) orelse {
        std.debug.print("Version {s} not found in index {s}\n", .{ manifest.current_version, manifest.version_index });
        return error.VersionNotFound;
    };

    const manifest_dir = std.fs.path.dirname(manifest_path) orelse ".";
    const current_link = try std.fs.path.join(allocator, &[_][]const u8{ manifest_dir, "current" });
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

    if (!needs_swap and !std.mem.eql(u8, real_path, target_path)) {
        needs_swap = true;
    }

    if (needs_swap) {
        std.debug.print("Reconciling: Swapping {s} to {s}\n", .{ current_link, target_path });
        try atomicSwap(allocator, current_link, target_path);
        if (manifest.trigger_cmd) |cmd| {
            std.debug.print("Executing trigger: {s}\n", .{cmd});
            try runTrigger(allocator, cmd);
        }
        return true;
    }

    return false;
}

pub fn deploy(allocator: std.mem.Allocator, manifest_path: []const u8, version: []const u8) !void {
    var manifest = try Manifest.load(allocator, manifest_path);
    defer manifest.deinit(allocator);

    var index = try VersionIndex.load(allocator, manifest.version_index);
    defer index.deinit(allocator);

    _ = index.findVersion(version) orelse {
        std.debug.print("Version {s} not found in index {s}\n", .{ version, manifest.version_index });
        return error.VersionNotFound;
    };

    if (std.mem.eql(u8, manifest.current_version, version)) {
        std.debug.print("Version {s} already active. Reconciling anyway...\n", .{version});
    }

    // Update manifest
    const old_version = try allocator.dupe(u8, manifest.current_version);
    defer allocator.free(old_version);

    allocator.free(manifest.current_version);
    manifest.current_version = try allocator.dupe(u8, version);

    // Add to history
    var new_history = try allocator.alloc(Manifest.VersionHistory, manifest.history.len + 1);
    for (manifest.history, 0..) |item, i| {
        new_history[i] = .{
            .version = try allocator.dupe(u8, item.version),
            .timestamp = item.timestamp,
            .comment = if (item.comment) |c| try allocator.dupe(u8, c) else null,
        };
    }
    new_history[manifest.history.len] = .{
        .version = try allocator.dupe(u8, old_version),
        .timestamp = std.time.timestamp(),
        .comment = try std.fmt.allocPrint(allocator, "Deployed {s}", .{version}),
    };

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
