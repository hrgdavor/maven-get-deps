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
                        const in_deps = isInside(path, "dependencies");
                        const in_plugin = isInside(path, "plugin") or isInside(path, "plugins");

                        if (in_deps and !in_plugin) {
                            const dep = try parseDependency(allocator, &scanner, name);
                            if (is_managed) {
                                try m_deps.append(allocator, dep);
                            } else {
                                try deps.append(allocator, dep);
                            }
                        } else {
                            try skipTag(&scanner);
                        }
                        _ = current_path.pop();
                    } else if (std.mem.eql(u8, name, "parent")) {
                        model.parent = try parseDependency(allocator, &scanner, name);
                        _ = current_path.pop();
                    } else if (std.mem.eql(u8, name, "repository")) {
                        try repos.append(allocator, try parseRepository(allocator, &scanner, name));
                        _ = current_path.pop();
                    }
                },
                .tag_close => |_| {
                    if (current_path.items.len > 0) _ = current_path.pop();
                },
                .text => |val| {
                    const path = current_path.items;
                    if (path.len == 2 and std.mem.eql(u8, path[0], "project")) {
                        if (std.mem.eql(u8, path[1], "groupId")) model.group_id = val;
                        if (std.mem.eql(u8, path[1], "artifactId")) model.artifact_id = val;
                        if (std.mem.eql(u8, path[1], "version")) model.version = val;
                    } else if (path.len == 3 and std.mem.eql(u8, path[0], "project") and std.mem.eql(u8, path[1], "properties")) {
                        try model.properties.put(path[2], val);
                    }
                },
            }
        }

        model.dependencies = try deps.toOwnedSlice(allocator);
        model.dependency_management = try m_deps.toOwnedSlice(allocator);
        model.repositories = try repos.toOwnedSlice(allocator);

        return model;
    }

    fn isInside(path: []const []const u8, name: []const u8) bool {
        for (path) |p| {
            if (std.mem.eql(u8, p, name)) return true;
        }
        return false;
    }

    fn parseDependency(allocator: std.mem.Allocator, scanner: *xml.Scanner, end_tag: []const u8) !Dependency {
        var dep = Dependency{};
        var exclusions = std.ArrayListUnmanaged(Exclusion){};

        while (true) {
            const token = try scanner.next();
            switch (token) {
                .tag_open => |name| {
                    if (std.mem.eql(u8, name, "groupId")) {
                        dep.group_id = try nextText(scanner);
                    } else if (std.mem.eql(u8, name, "artifactId")) {
                        dep.artifact_id = try nextText(scanner);
                    } else if (std.mem.eql(u8, name, "version")) {
                        dep.version = try nextText(scanner);
                    } else if (std.mem.eql(u8, name, "scope")) {
                        dep.scope = try nextText(scanner);
                    } else if (std.mem.eql(u8, name, "type")) {
                        dep.type = try nextText(scanner);
                    } else if (std.mem.eql(u8, name, "classifier")) {
                        dep.classifier = try nextText(scanner);
                    } else if (std.mem.eql(u8, name, "optional")) {
                        const opt = try nextText(scanner);
                        dep.optional = std.mem.eql(u8, opt, "true");
                    } else if (std.mem.eql(u8, name, "relativePath")) {
                        dep.relative_path = try nextText(scanner);
                    } else if (std.mem.eql(u8, name, "exclusion")) {
                        try exclusions.append(allocator, try parseExclusion(scanner));
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
        dep.exclusions = try exclusions.toOwnedSlice(allocator);
        return dep;
    }

    fn parseExclusion(scanner: *xml.Scanner) !Exclusion {
        var exc = Exclusion{};
        while (true) {
            const token = try scanner.next();
            switch (token) {
                .tag_open => |name| {
                    if (std.mem.eql(u8, name, "groupId")) {
                        exc.group_id = try nextText(scanner);
                    } else if (std.mem.eql(u8, name, "artifactId")) {
                        exc.artifact_id = try nextText(scanner);
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
                        repo.id = try nextText(scanner);
                    } else if (std.mem.eql(u8, name, "url")) {
                        repo.url = try nextText(scanner);
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
        _ = allocator;
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
