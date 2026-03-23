const std = @import("std");
const xml = @import("xml.zig");

pub const Dependency = struct {
    group_id: ?[]const u8 = null,
    artifact_id: ?[]const u8 = null,
    version: ?[]const u8 = null,
    scope: ?[]const u8 = null,
    type: ?[]const u8 = null,
    classifier: ?[]const u8 = null,
    optional: bool = false,
    relative_path: ?[]const u8 = null,
    exclusions: []const Exclusion = &.{},
};

pub const Exclusion = struct {
    group_id: ?[]const u8 = null,
    artifact_id: ?[]const u8 = null,
};

pub const Repository = struct {
    id: ?[]const u8 = null,
    url: ?[]const u8 = null,
};

pub const PomModel = struct {
    group_id: ?[]const u8 = null,
    artifact_id: ?[]const u8 = null,
    version: ?[]const u8 = null,
    parent: ?Dependency = null,
    properties: std.StringHashMap([]const u8),
    dependencies: []const Dependency,
    dependency_management: []const Dependency,
    repositories: []const Repository,

    pub fn parse(allocator: std.mem.Allocator, input: []const u8) !PomModel {
        var scanner = xml.Scanner.init(input);
        
        var model = PomModel{
            .properties = std.StringHashMap([]const u8).init(allocator),
            .dependencies = &.{},
            .dependency_management = &.{},
            .repositories = &.{},
        };

        var current_path = std.ArrayListUnmanaged([]const u8){};
        defer current_path.deinit(allocator);

        var deps = std.ArrayListUnmanaged(Dependency){};
        var m_deps = std.ArrayListUnmanaged(Dependency){};
        var repos = std.ArrayListUnmanaged(Repository){};

        while (true) {
            const token = scanner.next() catch break;
            switch (token) {
                .eof => break,
                .tag_open => |name| {
                    try current_path.append(allocator, name);
                    const path = current_path.items;
                    
                    if (std.mem.eql(u8, name, "dependency")) {
                        const is_managed = isInside(path, "dependencyManagement");
                        const is_plugin = isInside(path, "plugin") or isInside(path, "plugins");
                        
                        const dep = try parseDependency(allocator, &scanner, name);
                        if (!is_plugin) {
                            if (is_managed) {
                                try m_deps.append(allocator, dep);
                            } else {
                                try deps.append(allocator, dep);
                            }
                        }
                        _ = current_path.pop();
                    } else if (std.mem.eql(u8, name, "parent")) {
                        model.parent = try parseDependency(allocator, &scanner, name);
                        _ = current_path.pop();
                    } else if (std.mem.eql(u8, name, "repository")) {
                        try repos.append(allocator, try parseRepository(allocator, &scanner, name));
                        _ = current_path.pop();
                    } else if (std.mem.eql(u8, name, "properties") or std.mem.eql(u8, name, "dependencies") 
                               or std.mem.eql(u8, name, "dependencyManagement") or std.mem.eql(u8, name, "project")
                               or std.mem.eql(u8, name, "groupId") or std.mem.eql(u8, name, "artifactId") or std.mem.eql(u8, name, "version")
                               or std.mem.eql(u8, name, "modules") or std.mem.eql(u8, name, "module") or std.mem.eql(u8, name, "name") or std.mem.eql(u8, name, "packaging") or std.mem.eql(u8, name, "modelVersion") or std.mem.eql(u8, name, "build") or std.mem.eql(u8, name, "plugins") or std.mem.eql(u8, name, "plugin") or std.mem.eql(u8, name, "configuration") or std.mem.eql(u8, name, "relativePath") or std.mem.eql(u8, name, "exclusions")) {
                        // Keep on path, will be popped by tag_close
                    } else if (path.len >= 3 and std.mem.eql(u8, path[path.len-2], "properties")) {
                         // Individual property tag - keep on path
                    } else {
                        // Unknown tag - skip it and pop from path
                        try skipTag(&scanner);
                        _ = current_path.pop();
                    }
                },
                .tag_close => |_| {
                    if (current_path.items.len > 0) _ = current_path.pop();
                },
                .text => |val| {
                    const val_trimmed = std.mem.trim(u8, val, " \t\r\n");
                    if (val_trimmed.len == 0) continue;
                    const path = current_path.items;
                    if (path.len == 2 and std.mem.eql(u8, path[0], "project")) {
                        if (std.mem.eql(u8, path[1], "groupId")) model.group_id = try allocator.dupe(u8, val_trimmed);
                        if (std.mem.eql(u8, path[1], "artifactId")) model.artifact_id = try allocator.dupe(u8, val_trimmed);
                        if (std.mem.eql(u8, path[1], "version")) model.version = try allocator.dupe(u8, val_trimmed);
                    } else if (path.len >= 3 and std.mem.eql(u8, path[path.len-2], "properties")) {
                        const prop_name = path[path.len-1];
                        try model.properties.put(try allocator.dupe(u8, prop_name), try allocator.dupe(u8, val_trimmed));
                        // std.debug.print("DEBUG: [POM-PROP] {s}={s}\n", .{ prop_name, val_trimmed });
                    }
                },
            }
        }

        model.dependencies = try deps.toOwnedSlice(allocator);
        model.dependency_management = try m_deps.toOwnedSlice(allocator);
        model.repositories = try repos.toOwnedSlice(allocator);
        // // std.debug.print("DEBUG: Parsed model {s}: deps={} m_deps={} repos={}\n", .{ model.artifact_id orelse "unknown", model.dependencies.len, model.dependency_management.len, model.repositories.len });
        return model;
    }

    fn isInside(path: []const []const u8, name: []const u8) bool {
        if (path.len < 2) return false;
        // Project dependencies are project/dependencies/dependency (len 3, [1] == "dependencies")
        // or project/profiles/profile/dependencies/dependency (len 5, [3] == "dependencies")
        // Managed are project/dependencyManagement/dependencies/dependency (len 4, [1] == "dependencyManagement")
        
        for (path, 0..) |p, i| {
            if (std.mem.eql(u8, p, name)) {
                // Check if we are inside build/plugins/plugin
                for (path[0..i]) |prev| {
                    if (std.mem.eql(u8, prev, "plugins")) return false;
                }
                return true;
            }
        }
        return false;
    }

    fn parseDependency(allocator: std.mem.Allocator, scanner: *xml.Scanner, end_tag: []const u8) !Dependency {
        var dep = Dependency{};
        var exclusions = std.ArrayListUnmanaged(Exclusion){};

        while (true) {
            const token = try scanner.next();
            // // std.debug.print("DEBUG: parseDependency TOKEN: {}\n", .{ token });
            switch (token) {
                .tag_open => |name| {
                    // std.debug.print("DEBUG: parseDependency OPEN: {s}\n", .{ name });
                    if (std.mem.eql(u8, name, "groupId")) {
                        dep.group_id = try allocator.dupe(u8, try nextText(scanner));
                    } else if (std.mem.eql(u8, name, "artifactId")) {
                        dep.artifact_id = try allocator.dupe(u8, try nextText(scanner));
                    } else if (std.mem.eql(u8, name, "version")) {
                        dep.version = try allocator.dupe(u8, try nextText(scanner));
                    } else if (std.mem.eql(u8, name, "scope")) {
                        dep.scope = try allocator.dupe(u8, try nextText(scanner));
                    } else if (std.mem.eql(u8, name, "type")) {
                        dep.type = try allocator.dupe(u8, try nextText(scanner));
                    } else if (std.mem.eql(u8, name, "classifier")) {
                        dep.classifier = try allocator.dupe(u8, try nextText(scanner));
                    } else if (std.mem.eql(u8, name, "optional")) {
                        const opt = try nextText(scanner);
                        dep.optional = std.mem.eql(u8, opt, "true");
                    } else if (std.mem.eql(u8, name, "relativePath")) {
                        dep.relative_path = try allocator.dupe(u8, try nextText(scanner));
                    } else if (std.mem.eql(u8, name, "exclusions")) {
                        // the next tags will be 'exclusion'
                    } else if (std.mem.eql(u8, name, "exclusion")) {
                        try exclusions.append(allocator, try parseExclusion(allocator, scanner));
                    } else {
                        try skipTag(scanner);
                    }
                    // if (std.mem.eql(u8, name, "version")) {
                    //     // std.debug.print("DEBUG: parseDependency version: {s} for {s}\n", .{ if (dep.version) |v| v else "null", if (dep.artifact_id) |ai| ai else "unknown" });
                    // }
                },
                .tag_close => |name| {
                    if (std.mem.eql(u8, name, end_tag)) break;
                },
                else => {},
            }
        }
        dep.exclusions = try exclusions.toOwnedSlice(allocator);
        return dep;
    }

    fn parseExclusion(allocator: std.mem.Allocator, scanner: *xml.Scanner) !Exclusion {
        var exc = Exclusion{};
        while (true) {
            const token = try scanner.next();
            switch (token) {
                .tag_open => |name| {
                    if (std.mem.eql(u8, name, "groupId")) {
                        exc.group_id = try allocator.dupe(u8, try nextText(scanner));
                    } else if (std.mem.eql(u8, name, "artifactId")) {
                        exc.artifact_id = try allocator.dupe(u8, try nextText(scanner));
                    } else {
                        try skipTag(scanner);
                    }
                },
                .tag_close => |name| {
                    if (std.mem.eql(u8, name, "exclusion")) break;
                },
                else => {},
            }
        }
        return exc;
    }

    fn parseRepository(allocator: std.mem.Allocator, scanner: *xml.Scanner, end_tag: []const u8) !Repository {
        var repo = Repository{};
        while (true) {
            const token = try scanner.next();
            switch (token) {
                .tag_open => |name| {
                    if (std.mem.eql(u8, name, "id")) {
                        repo.id = try allocator.dupe(u8, try nextText(scanner));
                    } else if (std.mem.eql(u8, name, "url")) {
                        repo.url = try allocator.dupe(u8, try nextText(scanner));
                    } else {
                        try skipTag(scanner);
                    }
                },
                .tag_close => |name| {
                    if (std.mem.eql(u8, name, end_tag)) break;
                },
                else => {},
            }
        }
        return repo;
    }

    fn nextText(scanner: *xml.Scanner) ![]const u8 {
        const t1 = try scanner.next();
        var val: []const u8 = "";
        if (t1 == .text) {
            val = std.mem.trim(u8, t1.text, " \t\r\n");
            _ = try scanner.next(); // consume tag_close
        }
        return val;
    }

    fn skipTag(scanner: *xml.Scanner) !void {
        var depth: usize = 1;
        while (depth > 0) {
            const token = try scanner.next();
            switch (token) {
                .tag_open => depth += 1,
                .tag_close => depth -= 1,
                .eof => break,
                else => {},
            }
        }
    }
};
