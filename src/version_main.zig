const std = @import("std");
const version_manager = @import("version_manager.zig");

pub fn main() !void {
    var arena = std.heap.ArenaAllocator.init(std.heap.page_allocator);
    defer arena.deinit();
    const allocator = arena.allocator();

    const args = try std.process.argsAlloc(allocator);

    if (args.len < 2) {
        printUsage();
        return;
    }

    // Handle global help
    if (std.mem.eql(u8, args[1], "--help") or std.mem.eql(u8, args[1], "-h")) {
        printUsage();
        return;
    }

    if (args.len < 3) {
        std.debug.print("Error: Missing command\n\n", .{});
        printUsage();
        std.process.exit(1);
    }

    const manifest_path = args[1];
    const command = args[2];
    const cmd_args = args[3..];

    if (std.mem.eql(u8, command, "init")) {
        try cmdInit(allocator, manifest_path, cmd_args);
    } else if (std.mem.eql(u8, command, "deploy")) {
        try cmdDeploy(allocator, manifest_path, cmd_args);
    } else if (std.mem.eql(u8, command, "reconcile")) {
        try cmdReconcile(allocator, manifest_path, cmd_args);
    } else if (std.mem.eql(u8, command, "upgrade-failed")) {
        try cmdUpgradeFailed(allocator, manifest_path, cmd_args);
    } else if (std.mem.eql(u8, command, "list")) {
        try cmdListVersions(allocator, manifest_path, cmd_args);
    } else {
        std.debug.print("Unknown command: {s}\n", .{command});
        printUsage();
        std.process.exit(1);
    }
}

fn printUsage() void {
    std.debug.print(
        \\
        \\Utility to manage version symlink with history(and timestamps)
        \\ - allows reverting bad version, and marks it for later reference
        \\
        \\Commands:
        \\
        \\  init [index] [link] Initialize manifest with given version index and symlink path
        \\                      You should generate index first, and decide path for your symlink
        \\
        \\  deploy <version>    Deploys a new version, and adds old to history
        \\
        \\  reconcile           Ensure symlink matches manifest
        \\                      if version selection is done by external tool, or manually
        \\
        \\  upgrade-failed      Revert to previous version and mark current as failed
        \\
        \\  list                List available and recently used versions
        \\
        \\
    , .{});
}

fn cmdInit(allocator: std.mem.Allocator, manifest_path: []const u8, args: []const []const u8) !void {
    // Refuse if file already exists
    std.fs.cwd().access(manifest_path, .{}) catch |err| {
        if (err == error.FileNotFound) {
            const version_index = if (args.len > 0) args[0] else "versions.json";
            const symlink = if (args.len > 1) args[1] else "current";
            const manifest = version_manager.Manifest{
                .version_index = version_index,
                .symlink = symlink,
            };
            try manifest.save(manifest_path);
            std.debug.print("Manifest initialized at {s} with index {s} and symlink {s}\n", .{ manifest_path, version_index, symlink });
            _ = allocator;
            return;
        }
        return err;
    };

    std.debug.print("Error: Manifest already exists at {s}\n", .{manifest_path});
    std.process.exit(1);
}

fn cmdDeploy(allocator: std.mem.Allocator, manifest_path: []const u8, args: []const []const u8) !void {
    if (args.len < 1) {
        std.debug.print("Error: Missing version for 'deploy' command\n", .{});
        return;
    }
    const version = args[0];
    try version_manager.deploy(allocator, manifest_path, version);
}

fn cmdReconcile(allocator: std.mem.Allocator, manifest_path: []const u8, args: []const []const u8) !void {
    _ = args;
    _ = try version_manager.reconcile(allocator, manifest_path);
}

fn cmdUpgradeFailed(allocator: std.mem.Allocator, manifest_path: []const u8, args: []const []const u8) !void {
    _ = args;
    try version_manager.upgradeFailed(allocator, manifest_path);
}

fn cmdListVersions(allocator: std.mem.Allocator, manifest_path: []const u8, args: []const []const u8) !void {
    _ = args;
    var manifest = version_manager.Manifest.load(allocator, manifest_path) catch |err| {
        std.debug.print("Error loading manifest {s}: {any}\n", .{ manifest_path, err });
        return err;
    };
    defer manifest.deinit(allocator);

    var index = version_manager.VersionIndex.load(allocator, manifest.version_index) catch |err| {
        std.debug.print("Error loading index {s} (from manifest): {any}\n", .{ manifest.version_index, err });
        return err;
    };
    defer index.deinit(allocator);

    std.debug.print("\nAvailable versions (from {s}):\n", .{manifest.version_index});
    for (index.versions) |entry| {
        std.debug.print("  - {s}", .{entry.version});
        if (entry.timestamp) |ts| {
            std.debug.print(" [", .{});
            printTime(ts);
            std.debug.print("]", .{});
        }
        if (entry.description) |d| {
            std.debug.print(" : {s}", .{d});
        }
        std.debug.print("\n", .{});
    }

    if (manifest.current_version) |cv| {
        std.debug.print("\nCurrent version: {s}", .{cv});
        if (manifest.current_deployed_at) |ts| {
            std.debug.print(" (deployed: ", .{});
            printTime(ts);
            std.debug.print(")", .{});
        }
        std.debug.print("\n", .{});
    } else {
        std.debug.print("\nCurrent version: None\n", .{});
    }

    if (manifest.history.len > 0) {
        std.debug.print("\nRecent versions used (history):\n", .{});
        var count: usize = 0;
        var j: usize = manifest.history.len;
        while (j > 0 and count < 3) {
            j -= 1;
            const entry = manifest.history[j];
            std.debug.print("  - {s} ({s})", .{ entry.version, if (entry.failed) "FAILED" else "OK" });

            if (entry.deployed_at != null or entry.removed_at != null) {
                std.debug.print(" [", .{});
                printTime(entry.deployed_at);
                std.debug.print(" -> ", .{});
                printTime(entry.removed_at);
                std.debug.print("]", .{});
            }

            if (entry.comment) |c| {
                std.debug.print(" : {s}", .{c});
            }
            std.debug.print("\n", .{});
            count += 1;
        }
    }

    std.debug.print("\n", .{});
}

fn printTime(ts: ?i64) void {
    if (ts) |t| {
        const es = std.time.epoch.EpochSeconds{ .secs = @intCast(t) };
        const day_seconds = es.getDaySeconds();
        const epoch_day = es.getEpochDay();
        const year_day = epoch_day.calculateYearDay();
        const month_day = year_day.calculateMonthDay();

        std.debug.print("{d:0>4}-{d:0>2}-{d:0>2} {d:0>2}:{d:0>2}:{d:0>2}", .{
            year_day.year,
            month_day.month.numeric(),
            month_day.day_index + 1,
            day_seconds.getHoursIntoDay(),
            day_seconds.getMinutesIntoHour(),
            day_seconds.getSecondsIntoMinute(),
        });
    } else {
        std.debug.print("                   ", .{});
    }
}
