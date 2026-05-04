const std = @import("std");
const config = @import("config");

pub fn printHeader(name: []const u8) void {
    std.debug.print("{s} {s}\n", .{ name, config.version });
}
