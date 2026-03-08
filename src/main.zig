const std = @import("std");
const deps_format = @import("deps_format.zig");

pub fn main() !void {
    var arena = std.heap.ArenaAllocator.init(std.heap.page_allocator);
    defer arena.deinit();
    const allocator = arena.allocator();

    const args = try std.process.argsAlloc(allocator);

    var input_file: ?[]const u8 = null;
    var convert_format: ?deps_format.FormatType = null;
    var classpath_mode: bool = false;
    var cache_dir: ?[]const u8 = null;
    var download_mode: bool = false;
    var extra_classpath_file: ?[]const u8 = null;

    var i: usize = 1;
    while (i < args.len) : (i += 1) {
        const arg = args[i];
        if (std.mem.eql(u8, arg, "--input") or std.mem.eql(u8, arg, "-i")) {
            i += 1;
            if (i < args.len) {
                input_file = args[i];
            } else {
                std.debug.print("Missing value for {s}\n", .{arg});
                std.process.exit(1);
            }
        } else if (std.mem.eql(u8, arg, "--convert-format") or std.mem.eql(u8, arg, "-cf")) {
            i += 1;
            if (i < args.len) {
                if (std.mem.eql(u8, args[i], "colon")) {
                    convert_format = .colon;
                } else if (std.mem.eql(u8, args[i], "path")) {
                    convert_format = .path;
                } else {
                    std.debug.print("Invalid --convert-format, must be 'colon' or 'path'\n", .{});
                    std.process.exit(1);
                }
            } else {
                std.debug.print("Missing value for {s}\n", .{arg});
                std.process.exit(1);
            }
        } else if (std.mem.eql(u8, arg, "--classpath") or std.mem.eql(u8, arg, "-cp")) {
            classpath_mode = true;
        } else if (std.mem.eql(u8, arg, "--extra-classpath") or std.mem.eql(u8, arg, "-ecp")) {
            i += 1;
            if (i < args.len) {
                extra_classpath_file = args[i];
            } else {
                std.debug.print("Missing value for {s}\n", .{arg});
                std.process.exit(1);
            }
        } else if (std.mem.eql(u8, arg, "--download")) {
            download_mode = true;
        } else if (std.mem.eql(u8, arg, "--cache") or std.mem.eql(u8, arg, "-c")) {
            i += 1;
            if (i < args.len) {
                cache_dir = args[i];
            } else {
                std.debug.print("Missing value for {s}\n", .{arg});
                std.process.exit(1);
            }
        }
    }

    if (input_file) |in_file| {
        if (convert_format == null and !download_mode) {
            std.debug.print("Missing --convert-format alongside --input\n", .{});
            std.process.exit(1);
        }

        const fmt = convert_format orelse .path;
        const file = try std.fs.cwd().openFile(in_file, .{ .mode = .read_only });
        defer file.close();

        const max_bytes = 10 * 1024 * 1024;
        const file_content = try file.readToEndAlloc(allocator, max_bytes);
        defer allocator.free(file_content);

        const source_repo_path = cache_dir orelse blk: {
            const env_var = if (std.fs.path.sep == '\\') "USERPROFILE" else "HOME";
            if (std.process.getEnvVarOwned(allocator, env_var)) |home| {
                break :blk try std.fs.path.join(allocator, &[_][]const u8{ home, ".m2", "repository" });
            } else |_| {
                break :blk ".m2/repository";
            }
        };

        var client = std.http.Client{ .allocator = allocator };
        defer client.deinit();

        var stdout = std.fs.File.stdout();
        var is_first: bool = true;

        var iter = std.mem.splitScalar(u8, file_content, '\n');
        while (iter.next()) |line| {
            if (try deps_format.parse(allocator, line)) |info| {
                const formatted = if (fmt == .colon)
                    try deps_format.formatColon(allocator, info)
                else
                    try deps_format.formatPath(allocator, info);

                if (download_mode and !info.isLocal()) {
                    const relative_path = try deps_format.formatPath(allocator, info);
                    defer allocator.free(relative_path);
                    const full_path = try std.fs.path.join(allocator, &[_][]const u8{ source_repo_path, relative_path });
                    defer allocator.free(full_path);

                    std.fs.cwd().access(full_path, .{}) catch |err| {
                        if (err == error.FileNotFound) {
                            const url = try deps_format.formatMavenUrl(allocator, info);
                            defer allocator.free(url);
                            std.debug.print("Downloading {s} to {s}...\n", .{ url, full_path });
                            try downloadFile(&client, url, full_path);
                        } else {
                            return err;
                        }
                    };
                }

                if (classpath_mode) {
                    if (!is_first) {
                        const delim = [_]u8{std.fs.path.delimiter};
                        try stdout.writeAll(&delim);
                    }
                    is_first = false;

                    if (fmt == .path and !info.isLocal()) {
                        try stdout.writeAll(source_repo_path);
                        if (!std.mem.endsWith(u8, source_repo_path, "/") and !std.mem.endsWith(u8, source_repo_path, "\\")) {
                            const sep = [_]u8{std.fs.path.sep};
                            try stdout.writeAll(&sep);
                        }
                    }
                    try stdout.writeAll(formatted);
                } else if (!download_mode) {
                    try stdout.writeAll(formatted);
                    try stdout.writeAll("\n");
                }
            }
        }

        if (classpath_mode) {
            if (extra_classpath_file) |ecp_file| {
                const ecp_f = try std.fs.cwd().openFile(ecp_file, .{ .mode = .read_only });
                defer ecp_f.close();
                const ecp_content = try ecp_f.readToEndAlloc(allocator, 1 * 1024 * 1024);
                defer allocator.free(ecp_content);

                var ecp_iter = std.mem.splitScalar(u8, ecp_content, '\n');
                while (ecp_iter.next()) |ecp_line| {
                    const trimmed = std.mem.trim(u8, ecp_line, " \t\r\n");
                    if (trimmed.len > 0) {
                        if (!is_first) {
                            const delim = [_]u8{std.fs.path.delimiter};
                            try stdout.writeAll(&delim);
                        }
                        is_first = false;
                        try stdout.writeAll(trimmed);
                    }
                }
            }
            try stdout.writeAll("\n");
        }

        return;
    }

    std.debug.print("Usage: maven_get_deps --input <file> [--convert-format <colon|path>] [--download] [--classpath] [--extra-classpath <file>] [--cache <dir>]\n", .{});
}

fn downloadFile(client: *std.http.Client, url: []const u8, dest_path: []const u8) !void {
    const uri = try std.Uri.parse(url);

    // Ensure directory exists
    if (std.fs.path.dirname(dest_path)) |dir| {
        try std.fs.cwd().makePath(dir);
    }

    var server_header_buffer: [8192]u8 = undefined;
    var req = try client.request(.GET, uri, .{});
    defer req.deinit();

    try req.sendBodiless();

    var response = try req.receiveHead(&server_header_buffer);

    if (response.head.status != .ok) {
        std.debug.print("Failed to download {s}: {d}\n", .{ url, @intFromEnum(response.head.status) });
        return error.DownloadFailed;
    }

    const file = try std.fs.cwd().createFile(dest_path, .{});
    defer file.close();

    var buffer: [16384]u8 = undefined;
    var reader = response.reader(&buffer);

    while (true) {
        const bytes_read = try reader.readSliceShort(&buffer);
        if (bytes_read == 0) break;
        try file.writeAll(buffer[0..bytes_read]);
    }
}
