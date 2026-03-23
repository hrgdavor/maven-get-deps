const std = @import("std");

pub const FormatType = enum {
    colon,
    path,
};

pub const DependencyFormatInfo = struct {
    group_id: ?[]const u8 = null,
    artifact_id: ?[]const u8 = null,
    version: ?[]const u8 = null,
    classifier: ?[]const u8 = null,
    extension: []const u8 = "jar",
    scope: []const u8 = "compile",
    local_path: ?[]const u8 = null,

    pub fn isLocal(self: DependencyFormatInfo) bool {
        return self.local_path != null;
    }
};

pub fn parse(allocator: std.mem.Allocator, line: []const u8) !?DependencyFormatInfo {
    var trimmed = std.mem.trim(u8, line, " \t\r\n");
    if (trimmed.len == 0 or trimmed[0] == '#') return null;

    // Handle [INFO] prefix often found in Maven output
    if (std.mem.startsWith(u8, trimmed, "[INFO]")) {
        trimmed = std.mem.trimLeft(u8, trimmed["[INFO]".len..], " \t");
    }

    if (std.mem.startsWith(u8, trimmed, "./") or std.mem.startsWith(u8, trimmed, ".\\")) {
        return DependencyFormatInfo{ .local_path = trimmed };
    }

    // A Maven dependency string has at least 2 colons (g:a:v)
    // and usually doesn't contain "---" or spaces within the GAV parts.
    if (std.mem.indexOf(u8, trimmed, "---") != null) return null;

    var it_check = std.mem.splitScalar(u8, trimmed, ':');
    var part_idx: usize = 0;
    var total_colons: usize = 0;
    while (it_check.next()) |part| {
        const p = std.mem.trim(u8, part, " \t");
        if (part_idx < 3 and std.mem.indexOfScalar(u8, p, ' ') != null) {
            // Lenient space check: allow one space if it contains a dot
            if (part_idx == 0 and std.mem.indexOfScalar(u8, p, '.') != null) {
                // ok
            } else {
                return null;
            }
        }
        part_idx += 1;
    }
    total_colons = if (part_idx > 0) part_idx - 1 else 0;

    if (total_colons >= 2) {
        return try parseColonFormat(trimmed);
    } else if (std.mem.indexOfScalar(u8, trimmed, '/') != null or std.mem.indexOfScalar(u8, trimmed, '\\') != null) {
        return try parsePathFormat(allocator, trimmed);
    }
    return null;
}

fn parseColonFormat(line: []const u8) !DependencyFormatInfo {
    var extension: []const u8 = "jar";
    var rest = line;

    const at_index = std.mem.indexOfScalar(u8, rest, '@');
    if (at_index) |idx| {
        extension = rest[idx + 1 ..];
        rest = rest[0..idx];
    }

    var it = std.mem.splitScalar(u8, rest, ':');
    var parts_buf: [8][]const u8 = undefined;
    var count: usize = 0;
    while (it.next()) |part| {
        if (count < 8) {
            parts_buf[count] = std.mem.trim(u8, part, " \t");
            count += 1;
        }
    }

    if (count > 0) {
        // Remove trailing fluff from the last part (e.g. "-- module ...")
        const last = parts_buf[count - 1];
        if (std.mem.indexOfScalar(u8, last, ' ')) |space_idx| {
            parts_buf[count - 1] = last[0..space_idx];
        }
    }

    if (count < 3) return error.InvalidColonFormat;

    const group_id = parts_buf[0];
    const artifact_id = parts_buf[1];
    var version: []const u8 = undefined;
    var classifier: ?[]const u8 = null;
    var scope: []const u8 = "compile";

    // Maven dependency:list format is groupId:artifactId:type:[classifier:]version:scope
    if (count == 3) {
        // g:a:v
        version = parts_buf[2];
    } else if (count == 4) {
        // g:a:v:c (for backward compatibility with existing tests)
        version = parts_buf[2];
        classifier = parts_buf[3];
    } else if (count == 5) {
        // g:a:t:v:s (dependency:list format)
        extension = parts_buf[2];
        version = parts_buf[3];
        scope = parts_buf[4];
    } else if (count >= 6) {
        // g:a:t:c:v:s (dependency:list format with classifier)
        extension = parts_buf[2];
        classifier = parts_buf[3];
        version = parts_buf[4];
        scope = parts_buf[5];
    }

    return DependencyFormatInfo{
        .group_id = group_id,
        .artifact_id = artifact_id,
        .version = version,
        .classifier = if (classifier != null and classifier.?.len > 0) classifier else null,
        .extension = extension,
        .scope = scope,
    };
}

fn parsePathFormat(allocator: std.mem.Allocator, line_in: []const u8) !DependencyFormatInfo {
    // We replace backslashes but we don't want to mutate original string unless necessary,
    // so we can just look for either slash. But for ease we allocate a copy if it has backslash.
    var line = line_in;
    if (std.mem.indexOfScalar(u8, line_in, '\\') != null) {
        const copy = try allocator.dupe(u8, line_in);
        std.mem.replaceScalar(u8, copy, '\\', '/');
        line = copy;
    }

    const last_slash = std.mem.lastIndexOfScalar(u8, line, '/') orelse return error.InvalidPathFormat;
    const dir_path = line[0..last_slash];
    const file_name = line[last_slash + 1 ..];

    var extension: []const u8 = "jar";
    var base_name = file_name;
    const ext_dot = std.mem.lastIndexOfScalar(u8, file_name, '.');
    if (ext_dot) |idx| {
        extension = file_name[idx + 1 ..];
        base_name = file_name[0..idx];
    }

    const version_slash = std.mem.lastIndexOfScalar(u8, dir_path, '/') orelse return error.InvalidPathFormat;
    const version = dir_path[version_slash + 1 ..];
    const parent_dir_path = dir_path[0..version_slash];

    const artifact_slash = std.mem.lastIndexOfScalar(u8, parent_dir_path, '/') orelse return error.InvalidPathFormat;
    const artifact_id = parent_dir_path[artifact_slash + 1 ..];

    // group id replaces / with .
    const group_id_str = try allocator.dupe(u8, parent_dir_path[0..artifact_slash]);
    std.mem.replaceScalar(u8, group_id_str, '/', '.');

    const expected_prefix = try std.fmt.allocPrint(allocator, "{s}-{s}", .{ artifact_id, version });
    defer allocator.free(expected_prefix);

    var classifier: ?[]const u8 = null;
    if (std.mem.startsWith(u8, base_name, expected_prefix)) {
        if (base_name.len > expected_prefix.len and base_name[expected_prefix.len] == '-') {
            classifier = base_name[expected_prefix.len + 1 ..];
        }
    }

    return DependencyFormatInfo{
        .group_id = group_id_str,
        .artifact_id = artifact_id,
        .version = version,
        .classifier = classifier,
        .extension = extension,
    };
}

pub fn formatColon(allocator: std.mem.Allocator, info: DependencyFormatInfo) ![]const u8 {
    if (info.isLocal()) {
        return try allocator.dupe(u8, info.local_path.?);
    }

    var list: std.ArrayList(u8) = .empty;
    defer list.deinit(allocator);

    try list.appendSlice(allocator, info.group_id.?);
    try list.append(allocator, ':');
    try list.appendSlice(allocator, info.artifact_id.?);
    try list.append(allocator, ':');
    try list.appendSlice(allocator, info.version.?);

    if (info.classifier) |c| {
        if (c.len > 0) {
            try list.append(allocator, ':');
            try list.appendSlice(allocator, c);
        }
    }

    if (!std.mem.eql(u8, info.extension, "jar")) {
        try list.append(allocator, '@');
        try list.appendSlice(allocator, info.extension);
    }

    return try list.toOwnedSlice(allocator);
}

pub fn formatPath(allocator: std.mem.Allocator, info: DependencyFormatInfo) ![]const u8 {
    if (info.isLocal()) {
        return try allocator.dupe(u8, info.local_path.?);
    }

    var list: std.ArrayList(u8) = .empty;
    defer list.deinit(allocator);

    const g_id = info.group_id.?;
    const group_id_path = try allocator.dupe(u8, g_id);
    defer allocator.free(group_id_path);
    std.mem.replaceScalar(u8, group_id_path, '.', '/');

    try list.appendSlice(allocator, group_id_path);
    try list.append(allocator, '/');
    try list.appendSlice(allocator, info.artifact_id.?);
    try list.append(allocator, '/');
    try list.appendSlice(allocator, info.version.?);
    try list.append(allocator, '/');
    try list.appendSlice(allocator, info.artifact_id.?);
    try list.append(allocator, '-');
    try list.appendSlice(allocator, info.version.?);

    if (info.classifier) |c| {
        if (c.len > 0) {
            try list.append(allocator, '-');
            try list.appendSlice(allocator, c);
        }
    }

    try list.append(allocator, '.');
    try list.appendSlice(allocator, info.extension);

    return try list.toOwnedSlice(allocator);
}

pub fn formatMavenUrl(allocator: std.mem.Allocator, info: DependencyFormatInfo) ![]const u8 {
    if (info.isLocal()) return error.LocalPathCannotBeMavenUrl;
    const path = try formatPath(allocator, info);
    defer allocator.free(path);
    return try std.fmt.allocPrint(allocator, "https://repo1.maven.org/maven2/{s}", .{path});
}

test "format converter test" {
    const testing = std.testing;
    const allocator = testing.allocator;

    {
        const info = try parse(allocator, "./local/foo.jar");
        try testing.expect(info.?.isLocal());
        try testing.expectEqualStrings("./local/foo.jar", info.?.local_path.?);
    }
    {
        const info = try parse(allocator, "org.example:my-lib:1.2.3");
        try testing.expect(!info.?.isLocal());
        try testing.expectEqualStrings("org.example", info.?.group_id.?);
        try testing.expectEqualStrings("my-lib", info.?.artifact_id.?);
        try testing.expectEqualStrings("1.2.3", info.?.version.?);
        try testing.expect(info.?.classifier == null);
        try testing.expectEqualStrings("jar", info.?.extension);
    }
    {
        const info = try parse(allocator, "org.example:my-lib:1.2.3:jdk8@zip");
        try testing.expectEqualStrings("org.example", info.?.group_id.?);
        try testing.expectEqualStrings("my-lib", info.?.artifact_id.?);
        try testing.expectEqualStrings("1.2.3", info.?.version.?);
        try testing.expectEqualStrings("jdk8", info.?.classifier.?);
        try testing.expectEqualStrings("zip", info.?.extension);
    }
    {
        const info_opt = try parse(allocator, "org/example/my-lib/1.2.3/my-lib-1.2.3.jar");
        const info = info_opt.?;
        defer allocator.free(info.group_id.?);
        try testing.expectEqualStrings("org.example", info.group_id.?);
        try testing.expectEqualStrings("my-lib", info.artifact_id.?);
        try testing.expectEqualStrings("1.2.3", info.version.?);
        try testing.expect(info.classifier == null);
        try testing.expectEqualStrings("jar", info.extension);
    }
    {
        const info_opt = try parse(allocator, "org/example/my-lib/1.2.3/my-lib-1.2.3-jdk8.zip");
        const info = info_opt.?;
        defer allocator.free(info.group_id.?);
        try testing.expectEqualStrings("org.example", info.group_id.?);
        try testing.expectEqualStrings("my-lib", info.artifact_id.?);
        try testing.expectEqualStrings("1.2.3", info.version.?);
        try testing.expectEqualStrings("jdk8", info.classifier.?);
        try testing.expectEqualStrings("zip", info.extension);

        const colon = try formatColon(allocator, info);
        defer allocator.free(colon);
        try testing.expectEqualStrings("org.example:my-lib:1.2.3:jdk8@zip", colon);

        const path = try formatPath(allocator, info);
        defer allocator.free(path);
        try testing.expectEqualStrings("org/example/my-lib/1.2.3/my-lib-1.2.3-jdk8.zip", path);
    }
    {
        // Test [INFO] prefix and dependency:list 5-part format
        const info_opt = try parse(allocator, "[INFO]    org.apache.commons:commons-lang3:jar:3.12.0:compile");
        const info = info_opt.?;
        try testing.expectEqualStrings("org.apache.commons", info.group_id.?);
        try testing.expectEqualStrings("commons-lang3", info.artifact_id.?);
        try testing.expectEqualStrings("3.12.0", info.version.?);
        try testing.expectEqualStrings("jar", info.extension);
        try testing.expect(info.classifier == null);
    }
    {
        // Test dependency:list 6-part format with classifier
        const info_opt = try parse(allocator, "org.example:my-lib:jar:jdk8:1.2.3:compile");
        const info = info_opt.?;
        try testing.expectEqualStrings("org.example", info.group_id.?);
        try testing.expectEqualStrings("my-lib", info.artifact_id.?);
        try testing.expectEqualStrings("1.2.3", info.version.?);
        try testing.expectEqualStrings("jar", info.extension);
        try testing.expectEqualStrings("jdk8", info.classifier.?);
    }
    {
        // Test descriptive line is skipped
        const info_opt = try parse(allocator, "[INFO] --- dependency:3.6.0:list (default-cli) @ core ---");
        try testing.expect(info_opt == null);
    }
}

test "load complex1/core dependency-list.txt" {
    const testing = std.testing;
    const allocator = testing.allocator;

    const file = try std.fs.cwd().openFile("test/deps/complex1/core/dependency-list.txt", .{ .mode = .read_only });
    defer file.close();

    const max_bytes = 10 * 1024 * 1024;
    const file_content = try file.readToEndAlloc(allocator, max_bytes);
    defer allocator.free(file_content);

    var count: usize = 0;
    var iter = std.mem.splitScalar(u8, file_content, '\n');
    while (iter.next()) |line| {
        const info = try parse(allocator, line);
        if (info) |_| {
            count += 1;
        }
    }
    try testing.expectEqual(@as(usize, 183), count);
}

test "load complex1/core dependency-list-raw.txt" {
    const testing = std.testing;
    const allocator = testing.allocator;

    const file = try std.fs.cwd().openFile("test/deps/complex1/core/dependency-list-raw.txt", .{ .mode = .read_only });
    defer file.close();

    const max_bytes = 10 * 1024 * 1024;
    const file_content = try file.readToEndAlloc(allocator, max_bytes);
    defer allocator.free(file_content);

    var count: usize = 0;
    var iter = std.mem.splitScalar(u8, file_content, '\n');
    while (iter.next()) |line| {
        const info = try parse(allocator, line);
        if (info) |_| {
            count += 1;
        }
    }
    try testing.expectEqual(@as(usize, 212), count);
}
