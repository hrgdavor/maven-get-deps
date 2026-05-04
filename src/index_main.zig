const std = @import("std");
const version_manager = @import("version_manager.zig");
const app_version = @import("app_version.zig");

pub fn main() !void {
    var arena = std.heap.ArenaAllocator.init(std.heap.page_allocator);
    defer arena.deinit();
    const allocator = arena.allocator();

    const args = try std.process.argsAlloc(allocator);

    if (args.len < 2) {
        printUsage();
        return;
    }

    // Check for help flags
    for (args) |arg| {
        if (std.mem.eql(u8, arg, "--help") or std.mem.eql(u8, arg, "-h")) {
            printUsage();
            return;
        }
    }

    try cmdGenIndex(allocator, args[1..]);
}

fn printUsage() void {
    app_version.printHeader("gen_index");
    std.debug.print(
        \\Usage: gen_index [options]
        \\
        \\Options:
        \\  --folders <file>            File containing list of folders to scan
        \\  --output <file>             Output index file path (default: versions.json)
        \\  --version-file <name>       Version file name to look for (default: version.json)
        \\  --relative                  Write paths as relative ("./path") instead of absolute        \  --max-age-months <n>        Skip versions older than n months based on timestamp        \\
    , .{});
}

fn cmdGenIndex(allocator: std.mem.Allocator, args: []const []const u8) !void {
    var folders_file: ?[]const u8 = null;
    var output_file: []const u8 = "versions.json";
    var version_file_name: []const u8 = "version.json";
    var relative: bool = false;
    var max_age_months: ?u64 = null;

    var i: usize = 0;
    while (i < args.len) : (i += 1) {
        const arg = args[i];
        if (std.mem.eql(u8, arg, "--folders")) {
            i += 1;
            if (i < args.len) {
                folders_file = args[i];
            } else {
                std.debug.print("Missing value for {s}\n", .{arg});
                std.process.exit(1);
            }
        } else if (std.mem.eql(u8, arg, "--output") or std.mem.eql(u8, arg, "-o")) {
            i += 1;
            if (i < args.len) {
                output_file = args[i];
            } else {
                std.debug.print("Missing value for {s}\n", .{arg});
                std.process.exit(1);
            }
        } else if (std.mem.eql(u8, arg, "--version-file")) {
            i += 1;
            if (i < args.len) {
                version_file_name = args[i];
            } else {
                std.debug.print("Missing value for {s}\n", .{arg});
                std.process.exit(1);
            }
        } else if (std.mem.eql(u8, arg, "--relative")) {
            relative = true;
        } else if (std.mem.eql(u8, arg, "--max-age-months")) {
            i += 1;
            if (i < args.len) {
                max_age_months = try std.fmt.parseInt(u64, args[i], 10);
            } else {
                std.debug.print("Missing value for {s}\n", .{arg});
                std.process.exit(1);
            }
        }
    }

    const ff = folders_file orelse {
        std.debug.print("Error: --folders <file> is required\n", .{});
        std.process.exit(1);
    };

    var index = try version_manager.generateIndex(allocator, ff, version_file_name, relative, max_age_months);
    defer index.deinit(allocator);

    try index.save(output_file);
    std.debug.print("Version index generated and saved to {s}\n", .{output_file});
}
