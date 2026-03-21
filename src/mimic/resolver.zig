const std = @import("std");
const xml = @import("xml.zig");
const pom = @import("pom.zig");
const context = @import("context.zig");
const deps_format = @import("../deps_format.zig");

pub const ArtifactDescriptor = struct {
    group_id: []const u8,
    artifact_id: []const u8,
    version: []const u8,
    scope: []const u8 = "compile",
    classifier: ?[]const u8 = null,
    type: []const u8 = "jar",

    pub fn formatGA(self: ArtifactDescriptor, allocator: std.mem.Allocator) ![]const u8 {
        return try std.fmt.allocPrint(allocator, "{s}:{s}", .{ self.group_id, self.artifact_id });
    }
};

pub const ResolutionResult = struct {
    artifacts: []const ArtifactDescriptor,
    artifact_files: std.StringHashMap([]const u8),
    errors: [][]const u8,

    pub fn deinit(self: *ResolutionResult, allocator: std.mem.Allocator) void {
        allocator.free(self.artifacts);
        self.artifact_files.deinit();
        for (self.errors) |err| allocator.free(err);
        allocator.free(self.errors);
    }
};

const Node = struct {
    allocator: std.mem.Allocator,
    ad: ArtifactDescriptor,
    depth: usize,
    parent: ?*Node = null,
    exclusions: std.StringHashMap(void),

    pub fn init(allocator: std.mem.Allocator, ad: ArtifactDescriptor, depth: usize, parent: ?*Node) !*Node {
        const self = try allocator.create(Node);
        self.* = .{
            .allocator = allocator,
            .ad = ad,
            .depth = depth,
            .parent = parent,
            .exclusions = std.StringHashMap(void).init(allocator),
        };
        if (parent) |p| {
            var it = p.exclusions.keyIterator();
            while (it.next()) |k| try self.exclusions.put(try allocator.dupe(u8, k.*), {});
        }
        return self;
    }

    pub fn deinit(self: *Node) void {
        var it = self.exclusions.keyIterator();
        while (it.next()) |k| self.allocator.free(k.*);
        self.exclusions.deinit();
    }
};

pub const MimicResolver = struct {
    allocator: std.mem.Allocator,
    local_repo: []const u8,
    repo_urls: std.ArrayListUnmanaged([]const u8),
    reactor_poms: std.StringHashMap([]const u8),
    pom_cache: std.StringHashMap(pom.PomModel),
    pom_content_cache: std.StringHashMap([]const u8),
    context_cache: std.StringHashMap(*context.PomContext),
    resolved_tree_cache: std.StringHashMap(ResolutionResult),
    cache_enabled: bool = false,
    local_only: bool = false,
    http_client: std.http.Client,

    pub fn init(allocator: std.mem.Allocator, local_repo: []const u8) MimicResolver {
        var self = MimicResolver{
            .allocator = allocator,
            .local_repo = localRepoCleanup(local_repo),
            .repo_urls = std.ArrayListUnmanaged([]const u8){},
            .reactor_poms = std.StringHashMap([]const u8).init(allocator),
            .pom_cache = std.StringHashMap(pom.PomModel).init(allocator),
            .pom_content_cache = std.StringHashMap([]const u8).init(allocator),
            .context_cache = std.StringHashMap(*context.PomContext).init(allocator),
            .resolved_tree_cache = std.StringHashMap(ResolutionResult).init(allocator),
            .http_client = std.http.Client{ .allocator = allocator },
        };
        self.repo_urls.append(allocator, allocator.dupe(u8, "https://repo1.maven.org/maven2/") catch "") catch {};
        return self;
    }

    fn localRepoCleanup(in: []const u8) []const u8 {
        if (std.mem.startsWith(u8, in, "file:///")) return in[8..];
        return in;
    }

    pub fn deinit(self: *MimicResolver) void {
        for (self.repo_urls.items) |url| self.allocator.free(url);
        self.repo_urls.deinit(self.allocator);
        self.reactor_poms.deinit();
        var it_pom = self.pom_content_cache.iterator();
        while (it_pom.next()) |entry| {
            self.allocator.free(entry.key_ptr.*);
            self.allocator.free(entry.value_ptr.*);
        }
        self.pom_content_cache.deinit();

        var it_ctx = self.context_cache.iterator();
        while (it_ctx.next()) |entry| {
            self.allocator.free(entry.key_ptr.*);
            entry.value_ptr.*.deinit();
            self.allocator.destroy(entry.value_ptr.*);
        }
        self.context_cache.deinit();
        self.resolved_tree_cache.deinit();
        self.http_client.deinit();
        self.pom_cache.deinit();
    }

    pub fn addRepository(self: *MimicResolver, url: []const u8) !void {
        const clean_url = try self.allocator.dupe(u8, url);
        try self.repo_urls.append(self.allocator, clean_url);
    }

    pub fn scanReactor(self: *MimicResolver, root: []const u8) !void {
        try self.scanReactorInternal(root, 0);
    }

    fn scanReactorInternal(self: *MimicResolver, root: []const u8, depth: usize) !void {
        if (depth > 20) return;
        var dir = std.fs.cwd().openDir(root, .{ .iterate = true }) catch return;
        defer dir.close();

        var iter = dir.iterate();
        while (try iter.next()) |entry| {
            if (entry.kind == .directory) {
                if (entry.name[0] == '.') continue;
                const path = try std.fs.path.join(self.allocator, &[_][]const u8{ root, entry.name });
                try self.scanReactorInternal(path, depth + 1);
            } else if (std.mem.eql(u8, entry.name, "pom.xml")) {
                const path = try std.fs.path.join(self.allocator, &[_][]const u8{ root, entry.name });
                const content = try std.fs.cwd().readFileAlloc(self.allocator, path, 10 * 1024 * 1024);
                
                const model = try pom.PomModel.parse(self.allocator, content);
                const gid = if (model.group_id) |g| g else (if (model.parent) |p| p.group_id orelse "" else "");
                const aid = model.artifact_id orelse "";
                if (gid.len > 0 and aid.len > 0) {
                    const ga = try std.fmt.allocPrint(self.allocator, "{s}:{s}", .{ gid, aid });
                    try self.reactor_poms.put(ga, path);
                }
                try self.pom_content_cache.put(try self.allocator.dupe(u8, path), content);
            }
        }
    }

    pub fn resolve(
        self: *MimicResolver, 
        initial_artifacts: []const ArtifactDescriptor, 
        effective_scopes: []const []const u8, 
        root_ctx: ?*context.PomContext,
        project_versions: std.StringHashMap([]const u8)
    ) !ResolutionResult {
        var result_list = std.ArrayListUnmanaged(ArtifactDescriptor){};
        var artifact_files = std.StringHashMap([]const u8).init(self.allocator);
        var errors = std.ArrayListUnmanaged([]const u8){};

        var resolved_versions = std.StringHashMap([]const u8).init(self.allocator);
        var resolved_depths = std.StringHashMap(usize).init(self.allocator);
        var resolved_scopes = std.StringHashMap([]const u8).init(self.allocator);
        defer {
            var it = resolved_versions.iterator();
            while (it.next()) |entry| { self.allocator.free(entry.key_ptr.*); self.allocator.free(entry.value_ptr.*); }
            resolved_versions.deinit();
            var it_d = resolved_depths.iterator();
            while (it_d.next()) |entry| self.allocator.free(entry.key_ptr.*);
            resolved_depths.deinit();
            var it_s = resolved_scopes.iterator();
            while (it_s.next()) |entry| self.allocator.free(entry.key_ptr.*);
            resolved_scopes.deinit();
        }


        var queue = std.ArrayListUnmanaged(*Node){};
        defer {
            for (queue.items) |node| { node.deinit(); self.allocator.destroy(node); }
            queue.deinit(self.allocator);
        }

        for (initial_artifacts) |ad| {
            const ga = try ad.formatGA(self.allocator);
            defer self.allocator.free(ga);
            const node = try Node.init(self.allocator, ad, 0, null);
            try queue.append(self.allocator, node);
        }

        var head: usize = 0;
        while (head < queue.items.len) {
            const current = queue.items[head];
            head += 1;
            const ad = current.ad;
            const ga = try ad.formatGA(self.allocator);
            defer self.allocator.free(ga);

            const is_relevant = isScopeRelevant(ad.scope, effective_scopes, current.depth == 0);

            // Skip if not in effective scope
            if (!is_relevant) continue;

            const existing_depth = resolved_depths.get(ga);
            // Maven nearest-wins: skip if we've already resolved this artifact at shallower depth
            if (existing_depth) |d| {
                if (current.depth > d) continue;
                if (current.depth == d) {
                    // Same depth: check if current scope is "stronger" (e.g., compile vs runtime)
                    const existing_scope = resolved_scopes.get(ga) orelse "compile";
                    if (!isScopeStronger(ad.scope, existing_scope)) continue;
                }
            }

            // Register this artifact
            if (existing_depth == null) {
                try resolved_depths.put(try self.allocator.dupe(u8, ga), current.depth);
            } else {
                try resolved_depths.put(ga, current.depth); // Update depth (same GA pointer)
            }

            if (resolved_versions.get(ga)) |vo| self.allocator.free(vo);
            try resolved_versions.put(try self.allocator.dupe(u8, ga), try self.allocator.dupe(u8, ad.version));
            
            if (resolved_scopes.get(ga)) |so| self.allocator.free(so);
            try resolved_scopes.put(try self.allocator.dupe(u8, ga), try self.allocator.dupe(u8, ad.scope));

            const jar_file = self.getOrDownload(ad, ad.type) catch |err| blk: {
                if (err == error.ArtifactNotFound) break :blk @as([]const u8, "NOT_FOUND");
                break :blk null;
            };
            if (jar_file) |jf| try artifact_files.put(try self.allocator.dupe(u8, ga), try self.allocator.dupe(u8, jf));

            const pf = self.getOrDownload(ad, "pom") catch |err| {
                std.debug.print("DEBUG: Failed to get POM for {s} err={}\n", .{ ga, err });
                continue;
            };
                const ctx = self.loadPomContext(pf) catch |err| {
                    std.debug.print("DEBUG: Failed to load POM for {s} at {s} err={}\n", .{ ga, pf, err });
                    continue;
                };
                // std.debug.print("DEBUG: Processing {s} (depth={d}) deps={d}\n", .{ ga, current.depth, ctx.model.dependencies.len });
                
                var head_ctx: ?*context.PomContext = ctx;
                while (head_ctx) |cur| {
                    for (cur.model.dependencies) |dep| {
                        if (dep.optional) continue;
                        const d_gid = try cur.resolveProperty(self.allocator, dep.group_id orelse "");
                        const d_aid = try cur.resolveProperty(self.allocator, dep.artifact_id orelse "");
                        const d_ga = try std.fmt.allocPrint(self.allocator, "{s}:{s}", .{ d_gid, d_aid });
                        defer self.allocator.free(d_ga);
                        const d_ga_wild = try std.fmt.allocPrint(self.allocator, "{s}:*", .{d_gid});
                        defer self.allocator.free(d_ga_wild);
                        if (current.exclusions.contains(d_ga) or current.exclusions.contains(d_ga_wild)) continue;

                        const declared_scope = if (dep.scope) |s| try cur.resolveProperty(self.allocator, s) else "compile";
                        var scope = propagateScope(ad.scope, declared_scope);
                        
                        var m_scope: ?[]const u8 = null;
                        if (root_ctx) |rctx| m_scope = rctx.getManagedScope(d_gid, d_aid);
                        if (m_scope == null) m_scope = cur.getManagedScope(d_gid, d_aid);
                        if (m_scope) |ms| {
                            const resolved_ms = try cur.resolveProperty(self.allocator, ms);
                            scope = propagateScope(ad.scope, resolved_ms);
                            self.allocator.free(resolved_ms);
                        }

                        var version: ?[]const u8 = null;
                        if (root_ctx) |rctx| { if (rctx.getManagedVersion(d_gid, d_aid)) |mv| version = try rctx.resolveProperty(self.allocator, mv); }
                        if (version == null) { if (cur.getManagedVersion(d_gid, d_aid)) |mv| version = try cur.resolveProperty(self.allocator, mv); }
                        if (version == null) { if (dep.version) |v_raw| version = try cur.resolveProperty(self.allocator, v_raw); }
                        if (version == null) continue;

                        if (std.mem.startsWith(u8, version.?, "[") or std.mem.startsWith(u8, version.?, "(")) {
                            if (std.mem.indexOfScalar(u8, version.?, ',')) |comma| {
                                const clean = version.?[1..comma];
                                const old = version.?;
                                version = try self.allocator.dupe(u8, clean);
                                self.allocator.free(old);
                            }
                        }

                        if (project_versions.get(d_ga)) |p_version| { if (version) |old| self.allocator.free(old); version = try self.allocator.dupe(u8, p_version); }

                    if (isScopeRelevant(scope, effective_scopes, false)) {
                            const trans_ad = ArtifactDescriptor{ .group_id = d_gid, .artifact_id = d_aid, .version = version.?, .scope = scope, .type = dep.type orelse "jar" };
                            const next_node = try Node.init(self.allocator, trans_ad, current.depth + 1, current);
                            // Exclusions were already copied in Node.init from parent, 
                            // now add exclusions specific to THIS dependency
                            for (dep.exclusions) |ex| {
                                const ex_gid = try cur.resolveProperty(self.allocator, ex.group_id orelse "");
                                const ex_aid = try cur.resolveProperty(self.allocator, ex.artifact_id orelse "");
                                const ex_ga = try std.fmt.allocPrint(self.allocator, "{s}:{s}", .{ ex_gid, ex_aid });
                                if (!next_node.exclusions.contains(ex_ga)) {
                                    try next_node.exclusions.put(ex_ga, {});
                                } else self.allocator.free(ex_ga);
                                self.allocator.free(ex_gid);
                                self.allocator.free(ex_aid);
                            }
                            try queue.append(self.allocator, next_node);
                        } else if (version) |v| self.allocator.free(v);
                    }
                    head_ctx = cur.parent;
                }
        }
        // end BFS

        var it = resolved_versions.iterator();
        while (it.next()) |entry| {
            var it_p = std.mem.tokenizeAny(u8, entry.key_ptr.*, ":");
            const gid = it_p.next() orelse "";
            const aid = it_p.next() orelse "";
            const scope = resolved_scopes.get(entry.key_ptr.*) orelse "compile";
            try result_list.append(self.allocator, ArtifactDescriptor{ .group_id = try self.allocator.dupe(u8, gid), .artifact_id = try self.allocator.dupe(u8, aid), .version = try self.allocator.dupe(u8, entry.value_ptr.*), .scope = try self.allocator.dupe(u8, scope) });
        }
        return ResolutionResult{ .artifacts = try result_list.toOwnedSlice(self.allocator), .artifact_files = artifact_files, .errors = try errors.toOwnedSlice(self.allocator) };
    }

    fn propagateScope(parent_scope: []const u8, child_scope: []const u8) []const u8 {
        if (std.mem.eql(u8, child_scope, "system") or std.mem.eql(u8, child_scope, "provided")) return child_scope;
        if (std.mem.eql(u8, parent_scope, "compile")) return child_scope;
        if (std.mem.eql(u8, parent_scope, "runtime")) {
            if (std.mem.eql(u8, child_scope, "compile")) return "runtime";
            return child_scope;
        }
        if (std.mem.eql(u8, parent_scope, "test") or std.mem.eql(u8, parent_scope, "provided")) {
            if (std.mem.eql(u8, child_scope, "compile") or std.mem.eql(u8, child_scope, "runtime")) return "test";
            return child_scope;
        }
        return child_scope;
    }

    fn isScopeStronger(new_scope: []const u8, old_scope: []const u8) bool {
        const order = struct {
            fn get(s: []const u8) i32 {
                if (std.mem.eql(u8, s, "compile")) return 10;
                if (std.mem.eql(u8, s, "runtime")) return 5;
                if (std.mem.eql(u8, s, "provided")) return 2;
                if (std.mem.eql(u8, s, "system")) return 2;
                if (std.mem.eql(u8, s, "test")) return 1;
                return 0;
            }
        }.get;
        return order(new_scope) > order(old_scope);
    }

    pub fn resolvePom(self: *MimicResolver, path: []const u8, effective_scopes: []const []const u8) !ResolutionResult {
        const ctx = try self.loadPomContext(path);
        var initial_ads = std.ArrayListUnmanaged(ArtifactDescriptor){};
        defer initial_ads.deinit(self.allocator);
        var project_versions = std.StringHashMap([]const u8).init(self.allocator);
        defer {
            var it_v = project_versions.iterator();
            while (it_v.next()) |entry| { self.allocator.free(entry.key_ptr.*); self.allocator.free(entry.value_ptr.*); }
            project_versions.deinit();
        }
        for (ctx.model.dependencies) |dep| {
            const gid = try ctx.resolveProperty(self.allocator, dep.group_id orelse "");
            const aid = try ctx.resolveProperty(self.allocator, dep.artifact_id orelse "");
            const ga = try std.fmt.allocPrint(self.allocator, "{s}:{s}", .{ gid, aid });
            var scope: []const u8 = if (dep.scope) |s| try ctx.resolveProperty(self.allocator, s) else "compile";
            if (ctx.getManagedScope(gid, aid)) |ms| scope = try ctx.resolveProperty(self.allocator, ms);
            var v_raw = dep.version;
            if (v_raw == null) v_raw = ctx.getManagedVersion(gid, aid);
            if (v_raw == null) continue;
            var v = try ctx.resolveProperty(self.allocator, v_raw.?);
            if (std.mem.startsWith(u8, v, "[") or std.mem.startsWith(u8, v, "(")) {
                if (std.mem.indexOfScalar(u8, v, ',')) |comma| {
                    const clean = v[1..comma];
                    const old = v;
                    v = try self.allocator.dupe(u8, clean);
                    self.allocator.free(old);
                }
            }
            if (!project_versions.contains(ga)) {
                try project_versions.put(try self.allocator.dupe(u8, ga), try self.allocator.dupe(u8, v));
                if (isScopeRelevant(scope, effective_scopes, true)) {
                    try initial_ads.append(self.allocator, ArtifactDescriptor{ .group_id = gid, .artifact_id = aid, .version = try self.allocator.dupe(u8, v), .scope = scope, .type = dep.type orelse "jar" });
                }
            }
            self.allocator.free(v);
            self.allocator.free(ga);
        }
        return try self.resolve(initial_ads.items, effective_scopes, ctx, project_versions);
    }

    fn isScopeRelevant(scope: []const u8, effective_scopes: []const []const u8, is_direct: bool) bool {
        // provided and system are NOT transitive
        if (!is_direct and (std.mem.eql(u8, scope, "provided") or std.mem.eql(u8, scope, "system"))) return false;
        // test is NOT transitive
        if (!is_direct and std.mem.eql(u8, scope, "test")) return false;

        for (effective_scopes) |s| {
            if (std.mem.eql(u8, s, scope)) return true;
            if (std.mem.eql(u8, s, "compile")) {
                 if (is_direct and (std.mem.eql(u8, scope, "provided") or std.mem.eql(u8, scope, "system"))) return true;
            }
            if (std.mem.eql(u8, s, "runtime")) {
                if (std.mem.eql(u8, scope, "compile")) return true;
            }
        }
        return false;
    }

    fn loadPomContext(self: *MimicResolver, path: []const u8) anyerror!*context.PomContext {
        if (self.context_cache.get(path)) |ctx| return ctx;
        const content = try std.fs.cwd().readFileAlloc(self.allocator, path, 10 * 1024 * 1024);
        try self.pom_content_cache.put(try self.allocator.dupe(u8, path), content);
        const model = try pom.PomModel.parse(self.allocator, content);
        var parent_ctx: ?*context.PomContext = null;
        if (model.parent) |p| {
            var p_path: ?[]const u8 = null;
            if (p.relative_path != null and p.relative_path.?.len > 0) {
                const dir = std.fs.path.dirname(path) orelse ".";
                const abs_rp = try std.fs.path.join(self.allocator, &[_][]const u8{ dir, p.relative_path.? });
                defer self.allocator.free(abs_rp);
                if (std.fs.cwd().openDir(abs_rp, .{})) |dir_obj| { var d = dir_obj; defer d.close(); p_path = try std.fs.path.join(self.allocator, &[_][]const u8{ abs_rp, "pom.xml" }); } else |_| p_path = try self.allocator.dupe(u8, abs_rp);
            }
            if (p_path == null) {
                const bom_resolver_dummy = context.BOMResolver{ .ptr = self, .load_fn = struct { fn load(_: *anyopaque, _: []const u8, _: []const u8, _: []const u8) anyerror!?*context.PomContext { return null; } }.load };
                var dummy_ctx = try context.PomContext.init(self.allocator, model, null, bom_resolver_dummy);
                defer dummy_ctx.deinit();
                const gid = try dummy_ctx.resolveProperty(self.allocator, p.group_id orelse "");
                const aid = try dummy_ctx.resolveProperty(self.allocator, p.artifact_id orelse "");
                const v = try dummy_ctx.resolveProperty(self.allocator, p.version orelse "");
                defer { self.allocator.free(gid); self.allocator.free(aid); self.allocator.free(v); }
                const ad = ArtifactDescriptor{ .group_id = gid, .artifact_id = aid, .version = v, .type = "pom" };
                if (self.getOrDownload(ad, "pom") catch null) |pf| p_path = try self.allocator.dupe(u8, pf);
            }
            if (p_path) |pp| parent_ctx = self.loadPomContext(pp) catch null;
        }
        const bom_resolver = context.BOMResolver{ .ptr = self, .load_fn = struct { fn load(ptr: *anyopaque, gid: []const u8, aid: []const u8, v: []const u8) anyerror!?*context.PomContext { const self_ptr: *MimicResolver = @ptrCast(@alignCast(ptr)); const ad = ArtifactDescriptor{ .group_id = gid, .artifact_id = aid, .version = v, .type = "pom" }; const pf = try self_ptr.getOrDownload(ad, "pom"); return try self_ptr.loadPomContext(pf); } }.load };
        const ctx_ptr = try self.allocator.create(context.PomContext);
        ctx_ptr.* = try context.PomContext.init(self.allocator, model, parent_ctx, bom_resolver);
        try self.context_cache.put(try self.allocator.dupe(u8, path), ctx_ptr);
        return ctx_ptr;
    }

    fn getOrDownload(self: *MimicResolver, ad: ArtifactDescriptor, extension: []const u8) ![]const u8 {
        const ga = try ad.formatGA(self.allocator);
        defer self.allocator.free(ga);
        if (std.mem.eql(u8, extension, "pom")) if (self.reactor_poms.get(ga)) |path| return path;
        
        var ver = ad.version;
        if (std.mem.startsWith(u8, ver, "[") or std.mem.startsWith(u8, ver, "(")) {
            if (std.mem.indexOfScalar(u8, ver, ',')) |comma| {
                ver = ver[1..comma];
            }
        }
        
        const rel_path = try deps_format.formatPath(self.allocator, .{ .group_id = ad.group_id, .artifact_id = ad.artifact_id, .version = ver, .extension = extension });
        defer self.allocator.free(rel_path);
        const full_path = try std.fs.path.join(self.allocator, &[_][]const u8{ self.local_repo, rel_path });
        std.fs.cwd().access(full_path, .{}) catch |err| { if (err == error.FileNotFound) { if (self.local_only) { self.allocator.free(full_path); return error.ArtifactNotFound; } try self.downloadFromRepos(rel_path, full_path); } else return err; };
        return full_path;
    }

    fn downloadFromRepos(self: *MimicResolver, rel_path: []const u8, full_path: []const u8) !void {
        if (self.repo_urls.items.len == 0) return error.ArtifactNotFound;

        if (std.fs.path.dirname(full_path)) |dir| {
            try std.fs.cwd().makePath(dir);
        }

        for (self.repo_urls.items) |repo_url| {
            const url = try std.fmt.allocPrint(self.allocator, "{s}{s}", .{ repo_url, rel_path });
            defer self.allocator.free(url);
            
            const uri = std.Uri.parse(url) catch continue;
            var server_header_buffer: [8192]u8 = undefined;
            var req = self.http_client.request(.GET, uri, .{}) catch continue;
            defer req.deinit();

            req.sendBodiless() catch continue;
            var response = req.receiveHead(&server_header_buffer) catch continue;

            if (response.head.status == .ok) {
                const file = try std.fs.cwd().createFile(full_path, .{});
                defer file.close();
                
                var reader_buf: [8192]u8 = undefined;
                var reader = response.reader(&reader_buf);
                
                var file_out_buf: [8192]u8 = undefined;
                var writer_struct = file.writer(&file_out_buf);
                const writer = &writer_struct.interface;

                _ = try reader.streamRemaining(writer);
                try writer.flush();
                std.debug.print("Downloaded: {s}\n", .{url});
                return;
            }
        }
        return error.ArtifactNotFound;
    }
};
