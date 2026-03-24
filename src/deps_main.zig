const std = @import("std");
const deps_format = @import("deps_format.zig");
const mimic = @import("mimic/resolver.zig");

pub fn main() !void {
    var arena = std.heap.ArenaAllocator.init(std.heap.page_allocator);
    defer arena.deinit();
    const allocator = arena.allocator();

    const args = try std.process.argsAlloc(allocator);

    if (args.len < 2) {
        printUsage();
        return;
    }

    const command = args[1];
    if (std.mem.eql(u8, command, "deps")) {
        try cmdDeps(allocator, args[2..]);
    } else if (std.mem.eql(u8, command, "mimic")) {
        try cmdMimic(allocator, args[2..]);
    } else if (std.mem.eql(u8, command, "--help") or std.mem.eql(u8, command, "-h")) {
        printUsage();
        return;
    } else if (std.mem.eql(u8, command, "--input") or std.mem.eql(u8, command, "-i")) {
        // Backward compatibility: default to 'deps' if first arg looks like old flag
        try cmdDeps(allocator, args[1..]);
    } else {
        std.debug.print("Unknown command: {s}\n", .{command});
        printUsage();
        std.process.exit(1);
    }
}

fn printUsage() void {
    std.debug.print(
        \\Usage: maven_get_deps <command> [options]
        \\
        \\Commands:
        \\  deps            Resolve Maven dependencies (default if --input is first)
        \\
        \\Deps Options:
        \\  --input <file>              Input file
        \\  --convert-format <type>     'colon' or 'path'
        \\  --download                  Download missing jars
        \\  --classpath                 Output as classpath string
        \\  --extra-classpath <file>    Append extra entries
        \\  --cache <dir>               Local repo path
        \\  --extended, -E              Extended output format
        \\  --skip-siblings, -ss        Skip reactor siblings (Mimic only)
        \\  --debug-match, -dm          Filter debug traces (e.g. 'jakarta.inject')
        \\
    , .{});
}

fn cmdDeps(allocator: std.mem.Allocator, args: []const []const u8) !void {
    var input_file: ?[]const u8 = null;
    var convert_format: ?deps_format.FormatType = null;
    var classpath_mode: bool = false;
    var cache_dir: ?[]const u8 = null;
    var download_mode: bool = false;
    var extra_classpath_file: ?[]const u8 = null;

    var i: usize = 0;
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

        var out_buf: [4096]u8 = undefined;
        var writer_struct = std.fs.File.stdout().writer(&out_buf);
        const stdout = &writer_struct.interface;
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
        try stdout.flush();
        return;
    }

    std.debug.print("Error: Missing --input for 'deps' command\n", .{});
    printUsage();
}

fn cmdMimic(allocator: std.mem.Allocator, args: []const []const u8) !void {
    var pom_path: ?[]const u8 = null;
    var cache_dir: ?[]const u8 = null;
    var reactor_paths = std.array_list.AlignedManaged([]const u8, null).init(allocator);
    defer reactor_paths.deinit();
    var extra_repos = std.array_list.AlignedManaged([]const u8, null).init(allocator);
    defer extra_repos.deinit();
    var scopes = std.array_list.AlignedManaged([]const u8, null).init(allocator);
    defer scopes.deinit();
    var cache_tree: bool = false;
    var local_only: bool = false;
    var print_count: bool = false;
    var extended_format: bool = false;
    var skip_siblings: bool = false;
    var debug_match: ?[]const u8 = null;

    var i: usize = 0;
    while (i < args.len) : (i += 1) {
        const arg = args[i];
        if (std.mem.eql(u8, arg, "--pom") or std.mem.eql(u8, arg, "-p")) {
            i += 1;
            if (i < args.len) pom_path = args[i];
        } else if (std.mem.eql(u8, arg, "--cache") or std.mem.eql(u8, arg, "-c")) {
            i += 1;
            if (i < args.len) cache_dir = args[i];
        } else if (std.mem.eql(u8, arg, "--reactor") or std.mem.eql(u8, arg, "-r")) {
            i += 1;
            if (i < args.len) try reactor_paths.append(args[i]);
        } else if (std.mem.eql(u8, arg, "--repo")) {
            i += 1;
            if (i < args.len) try extra_repos.append(args[i]);
        } else if (std.mem.eql(u8, arg, "--scope") or std.mem.eql(u8, arg, "-s")) {
            i += 1;
            if (i < args.len) try scopes.append(args[i]);
        } else if (std.mem.eql(u8, arg, "--cache-tree")) {
            cache_tree = true;
        } else if (std.mem.eql(u8, arg, "--local-only")) {
            local_only = true;
        } else if (std.mem.eql(u8, arg, "--count")) {
            print_count = true;
        } else if (std.mem.eql(u8, arg, "--extended") or std.mem.eql(u8, arg, "-E")) {
            extended_format = true;
        } else if (std.mem.eql(u8, arg, "--skip-siblings") or std.mem.eql(u8, arg, "-ss")) {
            skip_siblings = true;
        } else if (std.mem.eql(u8, arg, "--debug-match") or std.mem.eql(u8, arg, "-dm")) {
            i += 1;
            if (i < args.len) debug_match = args[i];
        }
    }

    if (pom_path == null) {
        std.debug.print("Error: Missing --pom for 'mimic' command\n", .{});
        printUsage();
        std.process.exit(1);
    }

    const source_repo_path = cache_dir orelse blk: {
        const env_var = if (std.fs.path.sep == '\\') "USERPROFILE" else "HOME";
        if (std.process.getEnvVarOwned(allocator, env_var)) |home| {
            break :blk try std.fs.path.join(allocator, &[_][]const u8{ home, ".m2", "repository" });
        } else |_| {
            break :blk ".m2/repository";
        }
    };

    var resolver = mimic.MimicResolver.init(allocator, source_repo_path);
    defer resolver.deinit();
    resolver.cache_enabled = cache_tree;
    resolver.local_only = local_only;
    resolver.skip_siblings = skip_siblings;
    if (debug_match) |dm| resolver.debug_match = try allocator.dupe(u8, dm);

    for (reactor_paths.items) |rp| try resolver.scanReactor(rp);
    for (extra_repos.items) |url| try resolver.addRepository(url);

    if (scopes.items.len == 0) {
        try scopes.append("compile");
        try scopes.append("runtime");
    } else {
        var has_runtime = false;
        var has_compile = false;
        for (scopes.items) |s| {
            if (std.mem.eql(u8, s, "runtime")) has_runtime = true;
            if (std.mem.eql(u8, s, "compile")) has_compile = true;
        }
        if (has_runtime and !has_compile) try scopes.append("compile");
    }

    const start_ms = std.time.milliTimestamp();
    const result = try resolver.resolvePom(pom_path.?, scopes.items);
    std.debug.print("DEBUG: result.artifacts.len = {d}\n", .{ result.artifacts.len });
    const end_ms = std.time.milliTimestamp();
    
    std.debug.print("DEBUG: result.artifacts.len = {d}\n", .{result.artifacts.len});

    if (print_count) {
        std.debug.print("{d}\n", .{result.artifacts.len});
    } else {
        if (result.artifacts.len > 0) {
        for (result.artifacts) |ad| {
            if (extended_format) {
                std.debug.print("   {s}:{s}:{s}:{s}:{s} ({s})\n", .{ ad.group_id, ad.artifact_id, ad.type, ad.version, ad.scope, ad.path });
            } else {
                std.debug.print("{s}:{s}:{s}:{s}:{s}\n", .{ ad.group_id, ad.artifact_id, ad.type, ad.version, ad.scope });
            }
        }
    }

    std.debug.print("\nResolution completed in {d} ms (cache: {})\n", .{ end_ms - start_ms, cache_tree });

    for (result.errors) |err| {
        std.debug.print("Error: {s}\n", .{err});
    }
    }
}

fn downloadFile(client: *std.http.Client, url: []const u8, dest_path: []const u8) !void {
    const uri = try std.Uri.parse(url);

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

    var reader_buf: [8192]u8 = undefined;
    var reader = response.reader(&reader_buf);

    var file_out_buf: [8192]u8 = undefined;
    var writer_struct = file.writer(&file_out_buf);
    const writer = &writer_struct.interface;

    _ = try reader.streamRemaining(writer);
    try writer.flush();
}
