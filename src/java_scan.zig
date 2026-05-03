const std = @import("std");

pub fn scanAndPrint(allocator: std.mem.Allocator, start_path: []const u8) !void {
    var dir = try std.fs.cwd().openDir(start_path, .{ .iterate = true });
    defer dir.close();

    var walker = try dir.walk(allocator);
    defer walker.deinit();

    while (try walker.next()) |entry| {
        if (entry.kind == .file and std.mem.endsWith(u8, entry.basename, ".java")) {
            try processFile(allocator, entry.dir, entry.basename);
        }
    }
}

fn processFile(allocator: std.mem.Allocator, dir: std.fs.Dir, basename: []const u8) !void {
    const file = try dir.openFile(basename, .{});
    defer file.close();

    const max_bytes = 10 * 1024 * 1024;
    const content = try file.readToEndAlloc(allocator, max_bytes);
    defer allocator.free(content);

    var package_name: ?[]const u8 = null;
    var class_name: ?[]const u8 = null;
    var has_main = false;

    var iter = std.mem.splitScalar(u8, content, '\n');
    while (iter.next()) |raw_line| {
        const line = std.mem.trim(u8, raw_line, " \t\r");

        if (line.len == 0 or std.mem.startsWith(u8, line, "//") or std.mem.startsWith(u8, line, "/*") or std.mem.startsWith(u8, line, "*")) {
            continue;
        }

        if (package_name == null) {
            if (std.mem.indexOf(u8, line, "package ")) |idx| {
                const after = std.mem.trim(u8, line[idx + 8 ..], " ");
                if (std.mem.indexOfScalar(u8, after, ';')) |semi_idx| {
                    package_name = try allocator.dupe(u8, after[0..semi_idx]);
                }
            }
        }

        if (class_name == null) {
            if (std.mem.indexOf(u8, line, "class ")) |idx| {
                // simple class name extraction: word after 'class '
                const after = std.mem.trim(u8, line[idx + 6 ..], " ");
                var end: usize = 0;
                while (end < after.len and (std.ascii.isAlphanumeric(after[end]) or after[end] == '_')) : (end += 1) {}
                if (end > 0) {
                    class_name = try allocator.dupe(u8, after[0..end]);
                }
            }
        }

        if (isMain(line)) {
            has_main = true;
            if (class_name != null) break;
        }
    }

    if (has_main and class_name != null) {
        // Using same pattern as deps_main.zig for stdout
        var out_buf: [1024]u8 = undefined;
        var writer_struct = std.fs.File.stdout().writer(&out_buf);
        const stdout = &writer_struct.interface;

        if (package_name) |pkg| {
            try stdout.print("{s}.{s}\n", .{ pkg, class_name.? });
        } else {
            try stdout.print("{s}\n", .{class_name.?});
        }
        try stdout.flush();
    }

    if (package_name) |p| allocator.free(p);
    if (class_name) |c| allocator.free(c);
}

fn isMain(line: []const u8) bool {
    const public_static_void_main = "public static void main";
    const idx = std.mem.indexOf(u8, line, public_static_void_main) orelse return false;
    
    const after = std.mem.trimLeft(u8, line[idx + public_static_void_main.len ..], " \t");
    if (after.len == 0 or after[0] != '(') return false;
    
    const after_paren = std.mem.trimLeft(u8, after[1..], " \t");
    if (!std.mem.startsWith(u8, after_paren, "String")) return false;
    
    return true;
}
