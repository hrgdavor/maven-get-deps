const std = @import("std");
const xml = @import("xml.zig");
const pom = @import("pom.zig");

pub const BOMResolver = struct {
    ptr: *anyopaque,
    load_fn: *const fn (ptr: *anyopaque, gid: []const u8, aid: []const u8, v: []const u8) anyerror!?*PomContext,

    pub fn load(self: BOMResolver, gid: []const u8, aid: []const u8, v: []const u8) anyerror!?*PomContext {
        return try self.load_fn(self.ptr, gid, aid, v);
    }
};

pub const PomContext = struct {
    allocator: std.mem.Allocator,
    model: pom.PomModel,
    parent: ?*PomContext = null,
    managed_versions: std.StringHashMap([]const u8),
    managed_scopes: std.StringHashMap([]const u8),

    pub fn init(allocator: std.mem.Allocator, model: pom.PomModel, parent: ?*PomContext, bom_resolver: BOMResolver) !PomContext {
        var self = PomContext{
            .allocator = allocator,
            .model = model,
            .parent = parent,
            .managed_versions = std.StringHashMap([]const u8).init(allocator),
            .managed_scopes = std.StringHashMap([]const u8).init(allocator),
        };

        for (model.dependency_management) |dep| {
            const gid = try self.resolveProperty(allocator, dep.group_id orelse "");
            const aid = try self.resolveProperty(allocator, dep.artifact_id orelse "");
            const scope = dep.scope;

            if (scope != null and std.mem.eql(u8, scope.?, "import") and std.mem.eql(u8, dep.type orelse "jar", "pom")) {
                const v = try self.resolveProperty(allocator, dep.version orelse "");
                if (try bom_resolver.load(gid, aid, v)) |bom_ctx| {
                    try self.mergeManaged(bom_ctx);
                    const rga = try std.fmt.allocPrint(allocator, "{s}:{s}", .{ gid, aid });
                    if (!self.managed_versions.contains(rga)) {
                        try self.managed_versions.put(rga, try allocator.dupe(u8, v));
                    } else allocator.free(rga);
                }
                allocator.free(v);
            } else {
                const v = if (dep.version) |ver| try self.resolveProperty(allocator, ver) else null;
                const s = if (dep.scope) |sc| try self.resolveProperty(allocator, sc) else null;
                const ga = try std.fmt.allocPrint(allocator, "{s}:{s}", .{ gid, aid });
                if (!self.managed_versions.contains(ga)) {
                    if (v) |val| try self.managed_versions.put(try allocator.dupe(u8, ga), try allocator.dupe(u8, val));
                }
                if (!self.managed_scopes.contains(ga)) {
                    if (s) |val| try self.managed_scopes.put(try allocator.dupe(u8, ga), try allocator.dupe(u8, val));
                }
                allocator.free(ga);
                if (v) |val| allocator.free(val);
                if (s) |val| allocator.free(val);
            }
            allocator.free(gid);
            allocator.free(aid);
        }
        return self;
    }

    pub fn deinit(self: *PomContext) void {
        var it_v = self.managed_versions.iterator();
        while (it_v.next()) |entry| {
            self.allocator.free(entry.key_ptr.*);
            self.allocator.free(entry.value_ptr.*);
        }
        self.managed_versions.deinit();

        var it_s = self.managed_scopes.iterator();
        while (it_s.next()) |entry| {
            self.allocator.free(entry.key_ptr.*);
            self.allocator.free(entry.value_ptr.*);
        }
        self.managed_scopes.deinit();
    }

    fn mergeManaged(self: *PomContext, other: *PomContext) !void {
        if (other.parent) |p| try self.mergeManaged(p);
        var it_v = other.managed_versions.iterator();
        while (it_v.next()) |entry| {
            if (!self.managed_versions.contains(entry.key_ptr.*)) {
                const ga = try self.allocator.dupe(u8, entry.key_ptr.*);
                const v = try self.allocator.dupe(u8, entry.value_ptr.*);
                try self.managed_versions.put(ga, v);
            }
        }
        var it_s = other.managed_scopes.iterator();
        while (it_s.next()) |entry| {
            if (!self.managed_scopes.contains(entry.key_ptr.*)) {
                const ga = try self.allocator.dupe(u8, entry.key_ptr.*);
                const s = try self.allocator.dupe(u8, entry.value_ptr.*);
                try self.managed_scopes.put(ga, s);
            }
        }
    }

    pub fn getManagedVersion(self: *PomContext, gid: []const u8, aid: []const u8) ?[]const u8 {
        const ga = std.fmt.allocPrint(self.allocator, "{s}:{s}", .{ gid, aid }) catch return null;
        defer self.allocator.free(ga);
        if (self.managed_versions.get(ga)) |v| {
            // std.debug.print("DEBUG: Found managed version for {s} -> {s} in {s}\n", .{ga, v, self.model.artifact_id orelse ""});
            return v;
        }
        if (self.parent) |p| return p.getManagedVersion(gid, aid);
        return null;
    }

    pub fn getManagedScope(self: *PomContext, gid: []const u8, aid: []const u8) ?[]const u8 {
        const ga = std.fmt.allocPrint(self.allocator, "{s}:{s}", .{ gid, aid }) catch return null;
        defer self.allocator.free(ga);
        if (self.managed_scopes.get(ga)) |s| return s;
        if (self.parent) |p| return p.getManagedScope(gid, aid);
        return null;
    }

    pub fn resolveProperty(self: *PomContext, allocator: std.mem.Allocator, value: []const u8) ![]const u8 {
        if (!std.mem.containsAtLeast(u8, value, 1, "${")) return try allocator.dupe(u8, value);
        var res = try allocator.dupe(u8, value);
        
        var iter: usize = 0;
        while (std.mem.containsAtLeast(u8, res, 1, "${") and iter < 10) : (iter += 1) {
            var temp = std.ArrayListUnmanaged(u8){};
            defer temp.deinit(allocator);
            
            var i: usize = 0;
            var changed = false;
            while (i < res.len) {
                if (i + 2 <= res.len and std.mem.eql(u8, res[i .. i + 2], "${")) {
                    const end = std.mem.indexOfPos(u8, res, i + 2, "}") orelse {
                        try temp.append(allocator, res[i]);
                        i += 1;
                        continue;
                    };
                    const name = res[i + 2 .. end];
                    if (try self.getPropertyValue(allocator, name)) |val| {
                        try temp.appendSlice(allocator, val);
                        allocator.free(val);
                        changed = true;
                    } else {
                        try temp.appendSlice(allocator, res[i .. end + 1]);
                    }
                    i = end + 1;
                } else {
                    try temp.append(allocator, res[i]);
                    i += 1;
                }
            }
            allocator.free(res);
            res = try temp.toOwnedSlice(allocator);
            if (!changed) break;
        }
        return res;
    }

    fn getPropertyValue(self: *PomContext, allocator: std.mem.Allocator, name: []const u8) !?[]const u8 {
        if (std.mem.eql(u8, name, "project.version") or std.mem.eql(u8, name, "version")) {
            if (self.model.version) |v| return try allocator.dupe(u8, v);
            if (self.model.parent) |p| if (p.version) |pv| return try allocator.dupe(u8, pv);
        } else if (std.mem.eql(u8, name, "project.groupId") or std.mem.eql(u8, name, "groupId")) {
            if (self.model.group_id) |v| return try allocator.dupe(u8, v);
            if (self.model.parent) |p| if (p.group_id) |pv| return try allocator.dupe(u8, pv);
        } else if (std.mem.eql(u8, name, "project.artifactId") or std.mem.eql(u8, name, "artifactId")) {
            if (self.model.artifact_id) |v| return try allocator.dupe(u8, v);
        } else if (std.mem.eql(u8, name, "project.parent.version")) {
            if (self.model.parent) |p| if (p.version) |v| return try allocator.dupe(u8, v);
        } else if (std.mem.eql(u8, name, "project.parent.groupId")) {
            if (self.model.parent) |p| if (p.group_id) |v| return try allocator.dupe(u8, v);
        } else if (std.mem.eql(u8, name, "project.parent.artifactId")) {
            if (self.model.parent) |p| if (p.artifact_id) |v| return try allocator.dupe(u8, v);
        } 
        
        if (self.model.properties.get(name)) |v| {
            return try allocator.dupe(u8, v);
        } else if (self.parent) |p| {
            return try p.getPropertyValue(allocator, name);
        }
        return null;
    }
};
