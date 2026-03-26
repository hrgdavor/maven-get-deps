const std = @import("std");
const xml = @import("xml.zig");
const pom = @import("pom.zig");

pub const BOMResolver = struct {
    ptr: *anyopaque,
    load_fn: *const fn (ptr: *anyopaque, gid: []const u8, aid: []const u8, v: []const u8, props: ?*const std.StringHashMap([]const u8), debug_match: ?[]const u8) anyerror!?*PomContext,

    pub fn load(self: BOMResolver, gid: []const u8, aid: []const u8, v: []const u8, props: ?*const std.StringHashMap([]const u8), debug_match: ?[]const u8) anyerror!?*PomContext {
        return try self.load_fn(self.ptr, gid, aid, v, props, debug_match);
    }
};

pub const PomContext = struct {
    allocator: std.mem.Allocator,
    model: pom.PomModel,
    parent: ?*PomContext = null,
    managed_versions: std.StringHashMap([]const u8),
    managed_scopes: std.StringHashMap([]const u8),
    resolved_group_id: ?[]const u8 = null,
    resolved_artifact_id: ?[]const u8 = null,
    resolved_version: ?[]const u8 = null,
    properties: std.StringHashMap([]const u8),
    reactor_properties: ?*const std.StringHashMap([]const u8),
    repositories: std.ArrayListUnmanaged(pom.Repository),
    imported_boms: std.ArrayListUnmanaged(*PomContext),
    debug_match: ?[]const u8 = null,

    pub fn init(allocator: std.mem.Allocator, model: pom.PomModel, parent: ?*PomContext, bom_resolver: BOMResolver, r_gid: ?[]const u8, r_aid: ?[]const u8, r_v: ?[]const u8, reactor_properties: ?*const std.StringHashMap([]const u8), debug_match: ?[]const u8) !*PomContext {
        const self = try allocator.create(PomContext);
        self.* = PomContext{
            .allocator = allocator,
            .model = model,
            .parent = parent,
            .managed_versions = std.StringHashMap([]const u8).init(allocator),
            .managed_scopes = std.StringHashMap([]const u8).init(allocator),
            .properties = std.StringHashMap([]const u8).init(allocator),
            .reactor_properties = reactor_properties,
            .resolved_group_id = if (r_gid) |g| try allocator.dupe(u8, g) else null,
            .resolved_artifact_id = if (r_aid) |a| try allocator.dupe(u8, a) else null,
            .resolved_version = if (r_v) |v| try allocator.dupe(u8, v) else null,
            .repositories = .{},
            .imported_boms = .{},
            .debug_match = if (debug_match) |dm| try allocator.dupe(u8, dm) else null,
        };

        // 1. Parent properties (Inherit)
        if (parent) |p| {
            var it = p.properties.iterator();
            while (it.next()) |entry| {
                try self.properties.put(try allocator.dupe(u8, entry.key_ptr.*), try allocator.dupe(u8, entry.value_ptr.*));
            }
        }

        // 2. Local model properties (Override parent)
        var it_m = model.properties.iterator();
        while (it_m.next()) |entry| {
            const val = entry.value_ptr.*;
            try self.properties.put(try allocator.dupe(u8, entry.key_ptr.*), try allocator.dupe(u8, val));
        }

        // Project properties
        if (model.group_id) |g| try self.properties.put(try allocator.dupe(u8, "project.groupId"), try allocator.dupe(u8, g));
        if (model.artifact_id) |a| try self.properties.put(try allocator.dupe(u8, "project.artifactId"), try allocator.dupe(u8, a));
        if (model.version) |v| try self.properties.put(try allocator.dupe(u8, "project.version"), try allocator.dupe(u8, v));
        // Also parent properties if exists
        if (model.parent) |p| {
            if (p.group_id) |g| try self.properties.put(try allocator.dupe(u8, "project.parent.groupId"), try allocator.dupe(u8, g));
            if (p.artifact_id) |a| try self.properties.put(try allocator.dupe(u8, "project.parent.artifactId"), try allocator.dupe(u8, a));
            if (p.version) |v| try self.properties.put(try allocator.dupe(u8, "project.parent.version"), try allocator.dupe(u8, v));
        }

        // Now populate managed dependencies from parent
        if (parent) |p| {
            var it = p.managed_versions.iterator();
            while (it.next()) |entry| {
                try self.managed_versions.put(try allocator.dupe(u8, entry.key_ptr.*), try allocator.dupe(u8, entry.value_ptr.*));
            }
            var it_s = p.managed_scopes.iterator();
            while (it_s.next()) |entry| {
                try self.managed_scopes.put(try allocator.dupe(u8, entry.key_ptr.*), try allocator.dupe(u8, entry.value_ptr.*));
            }

            for (p.repositories.items) |repo| {
                try self.repositories.append(allocator, .{
                    .id = if (repo.id) |id| try allocator.dupe(u8, id) else null,
                    .url = if (repo.url) |url| try allocator.dupe(u8, url) else null,
                });
            }
        }

        for (model.repositories) |repo| {
            var found = false;
            for (self.repositories.items) |existing| {
                if (std.mem.eql(u8, existing.id orelse "", repo.id orelse "")) {
                    found = true;
                    break;
                }
            }
            if (!found) {
                try self.repositories.append(allocator, .{
                    .id = if (repo.id) |id| try allocator.dupe(u8, id) else null,
                    .url = if (repo.url) |url| try allocator.dupe(u8, url) else null,
                });
            }
        }

        // Managed dependencies
        for (model.dependency_management) |dep| {
            const gid = try self.resolveProperty(allocator, dep.group_id orelse "");
            const aid = try self.resolveProperty(allocator, dep.artifact_id orelse "");
            const v_raw = dep.version orelse "";
            const scope_raw = dep.scope;

            const scope_resolved = if (scope_raw) |s| try self.resolveProperty(allocator, s) else try allocator.dupe(u8, "compile");
            defer allocator.free(scope_resolved);

            const type_raw = dep.type orelse "jar";
            if (std.mem.eql(u8, scope_resolved, "import") and (std.mem.eql(u8, type_raw, "pom") or std.mem.eql(u8, type_raw, "jar"))) {
                const v_resolved = try self.resolveProperty(allocator, v_raw);
                defer allocator.free(v_resolved);

                const bom_ctx_res = bom_resolver.load(gid, aid, v_resolved, &self.properties, self.debug_match);
                if (bom_ctx_res) |bom_opt| {
                    if (bom_opt) |bom_ctx| {
                        std.debug.print("MIMIC: [IMPORT] {s}:{s}:{s} into {s}\n", .{ gid, aid, v_resolved, self.model.artifact_id orelse "unknown" });
                        try self.mergeManaged(bom_ctx);
                        try self.addImportedBOM(bom_ctx);
                    }
                } else |err| {
                    std.debug.print("MIMIC: [BOM-ERROR] Failed to load BOM {s}:{s}:{s}: {}\n", .{ gid, aid, v_resolved, err });
                }
            } else {
                const ga = try std.fmt.allocPrint(allocator, "{s}:{s}", .{ gid, aid });
                defer allocator.free(ga);
                if (v_raw.len > 0) {
                    // Child/nearest dependencyManagement wins over parent imports (override parent value)
                    if (self.managed_versions.contains(ga)) {
                        if (self.managed_versions.get(ga)) |oldValue| {
                            allocator.free(oldValue);
                            const newValue = try allocator.dupe(u8, v_raw);
                            // update map by removing and putting new to avoid invalidating internal refs
                            _ = self.managed_versions.remove(ga);
                            try self.managed_versions.put(try allocator.dupe(u8, ga), newValue);
                        }
                    } else {
                        const ga_owned = try allocator.dupe(u8, ga);
                        const v_owned = try allocator.dupe(u8, v_raw);
                        try self.managed_versions.put(ga_owned, v_owned);
                    }
                }
                if (scope_raw) |sr| {
                    if (self.managed_scopes.contains(ga)) {
                        if (self.managed_scopes.get(ga)) |oldScope| {
                            allocator.free(oldScope);
                            const newScope = try allocator.dupe(u8, sr);
                            _ = self.managed_scopes.remove(ga);
                            try self.managed_scopes.put(try allocator.dupe(u8, ga), newScope);
                        }
                    } else {
                        const ga_owned = try allocator.dupe(u8, ga);
                        const s_owned = try allocator.dupe(u8, sr);
                        try self.managed_scopes.put(ga_owned, s_owned);
                    }
                }
            }
            allocator.free(gid);
            allocator.free(aid);
        }
        return self;
    }

    pub fn isDebugMatch(self: *const PomContext, gid: []const u8, aid: []const u8) bool {
        const filter = self.debug_match orelse return false;
        if (filter.len == 0) return false;
        if (std.mem.eql(u8, filter, "ALL")) return true;
        if (gid.len > 0 and std.mem.indexOf(u8, gid, filter) != null) return true;
        if (aid.len > 0 and std.mem.indexOf(u8, aid, filter) != null) return true;
        return false;
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
        if (self.resolved_group_id) |v| self.allocator.free(v);
        if (self.resolved_artifact_id) |v| self.allocator.free(v);
        if (self.resolved_version) |v| self.allocator.free(v);
        if (self.debug_match) |dm| self.allocator.free(dm);
        for (self.repositories.items) |repo| {
            if (repo.id) |id| self.allocator.free(id);
            if (repo.url) |url| self.allocator.free(url);
        }
        self.repositories.deinit(self.allocator);
        self.imported_boms.deinit(self.allocator);
    }

    pub fn addImportedBOM(self: *PomContext, bom: *PomContext) !void {
        for (self.imported_boms.items) |existing| {
            if (existing == bom) return;
        }
        try self.imported_boms.append(self.allocator, bom);
    }

    fn mergeManaged(self: *PomContext, other: *PomContext) !void {
        if (other.parent) |p| try self.mergeManaged(p);
        var it_v = other.managed_versions.iterator();
        while (it_v.next()) |entry| {
            if (!self.managed_versions.contains(entry.key_ptr.*)) {
                if (other.resolveProperty(self.allocator, entry.value_ptr.*)) |v| {
                    const ga = try self.allocator.dupe(u8, entry.key_ptr.*);
                    try self.managed_versions.put(ga, v);
                } else |_| continue;
            }
        }
        var it_s = other.managed_scopes.iterator();
        while (it_s.next()) |entry| {
            if (!self.managed_scopes.contains(entry.key_ptr.*)) {
                if (other.resolveProperty(self.allocator, entry.value_ptr.*)) |s| {
                    const ga = try self.allocator.dupe(u8, entry.key_ptr.*);
                    try self.managed_scopes.put(ga, s);
                } else |_| continue;
            }
        }
    }

    pub fn getManagedVersion(self: *PomContext, gid: []const u8, aid: []const u8) ?[]const u8 {
        const ga = std.fmt.allocPrint(self.allocator, "{s}:{s}", .{ gid, aid }) catch return null;
        defer self.allocator.free(ga);
        if (self.managed_versions.get(ga)) |v| {
            return v;
        }
        if (self.parent) |p| return p.getManagedVersion(gid, aid);
        return null;
    }

    pub fn getManagedScope(self: *PomContext, gid: []const u8, aid: []const u8) ?[]const u8 {
        const ga = std.fmt.allocPrint(self.allocator, "{s}:{s}", .{ gid, aid }) catch return null;
        defer self.allocator.free(ga);
        if (self.managed_scopes.get(ga)) |s| {
            return s;
        }
        if (self.parent) |p| return p.getManagedScope(gid, aid);
        return null;
    }

    pub fn resolveProperty(self: *PomContext, allocator: std.mem.Allocator, value: []const u8) ![]const u8 {
        if (!std.mem.containsAtLeast(u8, value, 1, "${")) return try allocator.dupe(u8, value);
        const original = try allocator.dupe(u8, value);
        defer allocator.free(original);
        var res = try allocator.dupe(u8, value);
        while (std.mem.indexOf(u8, res, "${")) |_| {
            var changed = false;
            var temp = std.ArrayListUnmanaged(u8){};
            defer temp.deinit(allocator);
            var i: usize = 0;
            while (i < res.len) {
                if (i + 3 < res.len and std.mem.eql(u8, res[i .. i + 2], "${")) {
                    const start = i + 2;
                    const end_rel = std.mem.indexOf(u8, res[start..], "}") orelse {
                        try temp.appendSlice(allocator, res[i..]);
                        break;
                    };
                    const end = end_rel + start;
                    const name = res[start..end];

                    if (try self.getPropertyValue(allocator, name)) |val| {
                        try temp.appendSlice(allocator, val);
                        allocator.free(val);
                        changed = true;
                    } else {
                        try temp.appendSlice(allocator, "${");
                        try temp.appendSlice(allocator, name);
                        try temp.appendSlice(allocator, "}");
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
        if (!std.mem.eql(u8, original, res)) {
            if (self.isDebugMatch(original, "")) {
                std.debug.print("MIMIC: [PROP-RESOLVE] {s} -> {s} in context {s}\n", .{ original, res, self.model.artifact_id orelse "unknown" });
            }
        }
        return res;
    }

    fn getPropertyValue(self: *PomContext, allocator: std.mem.Allocator, name: []const u8) !?[]const u8 {
        if (std.mem.eql(u8, name, "project.version") or std.mem.eql(u8, name, "version") or std.mem.eql(u8, name, "pom.version")) {
            if (self.resolved_version) |v| return try allocator.dupe(u8, v);
            if (self.model.version) |v| return try allocator.dupe(u8, v);
            if (self.model.parent) |p| if (p.version) |pv| return try allocator.dupe(u8, pv);
            if (self.parent) |p| return p.getPropertyValue(allocator, name);
        } else if (std.mem.eql(u8, name, "project.groupId") or std.mem.eql(u8, name, "groupId") or std.mem.eql(u8, name, "pom.groupId")) {
            if (self.resolved_group_id) |v| return try allocator.dupe(u8, v);
            if (self.model.group_id) |v| return try allocator.dupe(u8, v);
            if (self.model.parent) |p| if (p.group_id) |pv| return try allocator.dupe(u8, pv);
            if (self.parent) |p| return p.getPropertyValue(allocator, name);
        } else if (std.mem.eql(u8, name, "project.artifactId") or std.mem.eql(u8, name, "artifactId") or std.mem.eql(u8, name, "pom.artifactId")) {
            if (self.resolved_artifact_id) |v| return try allocator.dupe(u8, v);
            if (self.model.artifact_id) |v| return try allocator.dupe(u8, v);
        } else if (std.mem.eql(u8, name, "project.parent.version") or std.mem.eql(u8, name, "pom.parent.version")) {
            if (self.model.parent) |p| if (p.version) |v| return try allocator.dupe(u8, v);
        } else if (std.mem.eql(u8, name, "project.parent.groupId") or std.mem.eql(u8, name, "pom.parent.groupId")) {
            if (self.model.parent) |p| if (p.group_id) |v| return try allocator.dupe(u8, v);
        } else if (std.mem.eql(u8, name, "project.parent.artifactId") or std.mem.eql(u8, name, "pom.parent.artifactId")) {
            if (self.model.parent) |p| if (p.artifact_id) |v| return try allocator.dupe(u8, v);
        }

        if (self.properties.get(name)) |v| {
            return try allocator.dupe(u8, v);
        }

        if (self.parent) |p| {
            if (try p.getPropertyValue(allocator, name)) |v| {
                return v;
            }
        }

        if (self.reactor_properties) |rp| {
            if (rp.get(name)) |v| return try allocator.dupe(u8, v);
        }

        return null;
    }
};
