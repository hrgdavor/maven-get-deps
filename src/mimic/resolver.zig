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
    optional: bool = false,
    exclusions: ?[]const pom.Exclusion = null,
    path: []const u8,
};

pub const CachedDependency = struct {
    group_id: []const u8,
    artifact_id: []const u8,
    version: []const u8,
    classifier: ?[]const u8,
    type: []const u8,
    scope: []const u8,
    optional: bool,
    exclusions: []const []const u8,

    pub fn deinit(self: CachedDependency, allocator: std.mem.Allocator) void {
        allocator.free(self.group_id);
        allocator.free(self.artifact_id);
        allocator.free(self.version);
        if (self.classifier) |c| allocator.free(c);
        allocator.free(self.type);
        allocator.free(self.scope);
        for (self.exclusions) |ex| allocator.free(ex);
        allocator.free(self.exclusions);
    }
};

pub const ResolutionResult = struct {
    artifacts: []const ArtifactDescriptor,
    artifact_files: std.StringHashMap([]const u8),
    errors: [][]const u8,

    pub fn deinit(self: *ResolutionResult, allocator: std.mem.Allocator) void {
        for (self.artifacts) |ad| {
            allocator.free(ad.group_id);
            allocator.free(ad.artifact_id);
            allocator.free(ad.version);
            allocator.free(ad.scope);
            if (ad.classifier) |c| allocator.free(c);
            allocator.free(ad.type);
            allocator.free(ad.path);
        }
        allocator.free(self.artifacts);
        var it = self.artifact_files.iterator();
        while (it.next()) |entry| {
            allocator.free(entry.key_ptr.*);
            allocator.free(entry.value_ptr.*);
        }
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
    path: []const u8,
    exclusions: std.StringHashMap(void),

    pub fn init(allocator: std.mem.Allocator, ad: ArtifactDescriptor, depth: usize, parent: ?*Node, path: []const u8) !*Node {
        const self = try allocator.create(Node);
        self.* = .{
            .allocator = allocator,
            .ad = ArtifactDescriptor{
                .group_id = try allocator.dupe(u8, ad.group_id),
                .artifact_id = try allocator.dupe(u8, ad.artifact_id),
                .version = try allocator.dupe(u8, ad.version),
                .scope = try allocator.dupe(u8, ad.scope),
                .type = try allocator.dupe(u8, ad.type),
                .classifier = if (ad.classifier) |c| try allocator.dupe(u8, c) else null,
                .optional = ad.optional,
                .path = try allocator.dupe(u8, ad.path),
            },
            .depth = depth,
            .parent = parent,
            .path = try allocator.dupe(u8, path),
            .exclusions = std.StringHashMap(void).init(allocator),
        };
        if (parent) |p| {
            var it = p.exclusions.keyIterator();
            while (it.next()) |k| try self.exclusions.put(try allocator.dupe(u8, k.*), {});
        }
        if (ad.exclusions) |exS| {
            for (exS) |ex| {
                const ex_gid = ex.group_id orelse "*";
                const ex_aid = ex.artifact_id orelse "*";
                const ex_ga = try std.fmt.allocPrint(allocator, "{s}:{s}", .{ ex_gid, ex_aid });
                if (!self.exclusions.contains(ex_ga)) {
                    try self.exclusions.put(ex_ga, {});
                } else {
                    allocator.free(ex_ga);
                }
            }
        }
        return self;
    }

    pub fn deinit(self: *Node) void {
        self.allocator.free(self.ad.group_id);
        self.allocator.free(self.ad.artifact_id);
        self.allocator.free(self.ad.version);
        self.allocator.free(self.ad.scope);
        self.allocator.free(self.ad.type);
        if (self.ad.classifier) |c| self.allocator.free(c);
        self.allocator.free(self.ad.path);

        self.allocator.free(self.path);
        var it = self.exclusions.keyIterator();
        while (it.next()) |k| self.allocator.free(k.*);
        self.exclusions.deinit();
        self.allocator.destroy(self);
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
    skip_siblings: bool = false,
    debug_match: ?[]const u8 = null,
    reactor_properties: std.StringHashMap([]const u8),
    http_client: std.http.Client,

    pub fn init(allocator: std.mem.Allocator, local_repo: []const u8) MimicResolver {
        var self = MimicResolver{
            .allocator = allocator,
            .local_repo = localRepoCleanup(local_repo),
            .repo_urls = std.ArrayListUnmanaged([]const u8){},
            .reactor_poms = std.StringHashMap([]const u8).init(allocator),
            .reactor_properties = std.StringHashMap([]const u8).init(allocator),
            .pom_cache = std.StringHashMap(pom.PomModel).init(allocator),
            .pom_content_cache = std.StringHashMap([]const u8).init(allocator),
            .context_cache = std.StringHashMap(*context.PomContext).init(allocator),
            .resolved_tree_cache = std.StringHashMap(ResolutionResult).init(allocator),
            .debug_match = null,
            .http_client = std.http.Client{ .allocator = allocator },
        };
        self.repo_urls.append(allocator, allocator.dupe(u8, "https://repo1.maven.org/maven2/") catch "") catch {};
        return self;
    }

    pub fn isDebugMatch(self: *const MimicResolver, gid: []const u8, aid: []const u8) bool {
        const filter = self.debug_match orelse return false;
        if (filter.len == 0) return false;
        if (std.mem.eql(u8, filter, "ALL")) return true;
        return std.mem.indexOf(u8, gid, filter) != null or std.mem.indexOf(u8, aid, filter) != null;
    }

    fn localRepoCleanup(in: []const u8) []const u8 {
        if (std.mem.startsWith(u8, in, "file:///")) return in[8..];
        return in;
    }

    pub fn deinit(self: *MimicResolver) void {
        for (self.repo_urls.items) |url| self.allocator.free(url);
        self.repo_urls.deinit(self.allocator);
        if (self.debug_match) |dm| self.allocator.free(dm);
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
            while (it_s.next()) |entry| {
                self.allocator.free(entry.key_ptr.*);
                self.allocator.free(entry.value_ptr.*);
            }
            resolved_scopes.deinit();
        }
        var resolved_optionals = std.StringHashMap(void).init(self.allocator);
        defer {
            var it_o = resolved_optionals.iterator();
            while (it_o.next()) |entry| self.allocator.free(entry.key_ptr.*);
            resolved_optionals.deinit();
        }
        var resolved_paths = std.StringHashMap([]const u8).init(self.allocator);
        defer {
            var it_p = resolved_paths.iterator();
            while (it_p.next()) |entry| {
                self.allocator.free(entry.key_ptr.*);
                self.allocator.free(entry.value_ptr.*);
            }
            resolved_paths.deinit();
        }

        var head: usize = 0;
        var queue = std.ArrayListUnmanaged(*Node){};
        defer {
            for (queue.items[head..]) |node| node.deinit();
            queue.deinit(self.allocator);
        }

        for (initial_artifacts) |ad| {
            const ga = try std.fmt.allocPrint(self.allocator, "{s}:{s}", .{ ad.group_id, ad.artifact_id });
            defer self.allocator.free(ga);
            const initial_node = try Node.init(self.allocator, ad, 0, null, ad.path);
            if (ad.exclusions) |exS| {
                if (root_ctx) |rctx| {
                    for (exS) |ex| {
                        const ex_gid = try rctx.resolveProperty(self.allocator, ex.group_id orelse "");
                        const ex_aid = try rctx.resolveProperty(self.allocator, ex.artifact_id orelse "");
                        const ex_ga = try std.fmt.allocPrint(self.allocator, "{s}:{s}", .{ ex_gid, ex_aid });
                        if (!initial_node.exclusions.contains(ex_ga)) {
                            try initial_node.exclusions.put(ex_ga, {});
                        } else self.allocator.free(ex_ga);
                        self.allocator.free(ex_gid);
                        self.allocator.free(ex_aid);
                    }
                }
            }
            try queue.append(self.allocator, initial_node);
        }

        var arena_instance = std.heap.ArenaAllocator.init(self.allocator);
        defer arena_instance.deinit();
        const arena = arena_instance.allocator();

        while (head < queue.items.len) {
            _ = arena_instance.reset(.retain_capacity);
            const current = queue.items[head];
            head += 1;
            defer current.deinit();
            
            const ad = current.ad;
            const ga = try std.fmt.allocPrint(arena, "{s}:{s}", .{ ad.group_id, ad.artifact_id });

            const is_relevant = isScopeRelevant(ad.scope, effective_scopes, current.depth == 0);

            const existing_depth = resolved_depths.get(ga);
            if (existing_depth) |old_depth| {
                const existing_scope = resolved_scopes.get(ga) orelse "compile";
                const is_existing_optional = resolved_optionals.contains(ga);

                if (old_depth == 0 and std.mem.eql(u8, existing_scope, "provided")) continue;

                const scope_promoted = isScopeStronger(ad.scope, existing_scope);
                const optional_promoted = (is_existing_optional and !ad.optional);

                if (current.depth > old_depth) {
                    // Only allow promotion if scope is STRONGER
                    if (!scope_promoted) continue;
                } else if (current.depth == old_depth) {
                    if (!scope_promoted and !optional_promoted) continue;
                }

                if (self.isDebugMatch(ad.group_id, ad.artifact_id)) {
                     std.debug.print("MIMIC: [PROMOTE] {s} from {s} to {s} (depth {d}->{d})\n", .{ ga, existing_scope, ad.scope, old_depth, current.depth });
                }
                
                try resolved_scopes.put(try self.allocator.dupe(u8, ga), try self.allocator.dupe(u8, ad.scope));
                
                // If we promoted from a 'blocking' scope (one not in target) to a relevant one,
                // we might need to re-expand. Or if we promoted from provided to compile.
                const was_relevant = isScopeRelevant(existing_scope, effective_scopes, old_depth == 0);
                const is_now_relevant = isScopeRelevant(ad.scope, effective_scopes, current.depth == 0);
                
                if (!was_relevant and is_now_relevant) {
                    if (self.isDebugMatch(ad.group_id, ad.artifact_id)) std.debug.print("MIMIC: [RE-EXPAND] {s} after promotion\n", .{ga});
                    // Proceed to expand
                } else {
                    continue; // Already expanded or still blocked
                }
            }

            // Update maps with memory safety
            if (existing_depth == null) {
                try resolved_depths.put(try self.allocator.dupe(u8, ga), current.depth);
            } else if (current.depth < existing_depth.?) {
                const gop = try resolved_depths.getOrPut(try self.allocator.dupe(u8, ga));
                if (gop.found_existing) self.allocator.free(gop.key_ptr.*);
                gop.value_ptr.* = current.depth;
            }

            {
                const gop = try resolved_versions.getOrPut(try self.allocator.dupe(u8, ga));
                if (gop.found_existing) {
                    self.allocator.free(gop.key_ptr.*);
                    self.allocator.free(gop.value_ptr.*);
                }
                gop.value_ptr.* = try self.allocator.dupe(u8, ad.version);
            }
            {
                const gop = try resolved_scopes.getOrPut(try self.allocator.dupe(u8, ga));
                if (gop.found_existing) {
                    self.allocator.free(gop.key_ptr.*);
                    self.allocator.free(gop.value_ptr.*);
                }
                gop.value_ptr.* = try self.allocator.dupe(u8, ad.scope);
            }
            {
                const gop = try resolved_paths.getOrPut(try self.allocator.dupe(u8, ga));
                if (gop.found_existing) {
                    self.allocator.free(gop.key_ptr.*);
                    self.allocator.free(gop.value_ptr.*);
                }
                gop.value_ptr.* = try self.allocator.dupe(u8, current.path);
            }

            if (ad.optional) {
                if (!resolved_optionals.contains(ga)) {
                    try resolved_optionals.put(try self.allocator.dupe(u8, ga), {});
                }
            } else {
                if (resolved_optionals.fetchRemove(ga)) |kv| {
                    self.allocator.free(kv.key);
                }
            }

            if (!is_relevant) continue;
            if (ad.optional and current.depth > 0) continue;

            const jar_type = if (std.mem.eql(u8, ad.type, "test-jar")) "jar" else ad.type;
            const jar_file = self.getOrDownload(ad, jar_type) catch |err| blk: {
                if (err == error.ArtifactNotFound) break :blk @as([]const u8, "NOT_FOUND");
                break :blk null;
            };
            if (jar_file) |jf| try artifact_files.put(try self.allocator.dupe(u8, ga), try self.allocator.dupe(u8, jf));

            const pf_res = self.getOrDownload(ad, "pom");
            if (pf_res) |pf| {
                defer self.allocator.free(pf);
                
                var direct_deps: ?[]const CachedDependency = null;
                const is_reactor = self.reactor_poms.get(ga) != null;
                const pom_hash = if (is_reactor) self.calculateWyhash64(pf) else @as(i64, -1);

                if (self.cache_enabled and !is_reactor) {
                    const cache_file = try self.getCacheFile(pf, effective_scopes);
                    defer self.allocator.free(cache_file);
                    direct_deps = try self.loadCache(cache_file, pom_hash);
                    if (direct_deps != null and self.isDebugMatch(ad.group_id, ad.artifact_id)) {
                        std.debug.print("MIMIC: [CACHE-HIT] {s}:{s}:{s} (hash={d})\n", .{ ad.group_id, ad.artifact_id, ad.version, pom_hash });
                    }
                }

                if (direct_deps == null) {
                    const ctx_res = self.loadPomContext(pf, ad, &self.reactor_properties);
                    if (ctx_res) |ctx| {
                        var deps_list = std.ArrayListUnmanaged(CachedDependency){};
                        
                        // We need to collect all dependencies including those from parents, 
                        // matching Java's ctx.getEffectiveDependencies()
                        var head_ctx: ?*context.PomContext = ctx;
                        while (head_ctx) |cur| {
                            for (cur.model.dependencies) |dep| {
                                const d_gid = try cur.resolveProperty(self.allocator, dep.group_id orelse "");
                                const d_aid = try cur.resolveProperty(self.allocator, dep.artifact_id orelse "");
                                
                                var d_v_raw = dep.version;
                                if (d_v_raw == null) d_v_raw = cur.getManagedVersion(d_gid, d_aid);
                                if (d_v_raw == null) {
                                    self.allocator.free(d_gid);
                                    self.allocator.free(d_aid);
                                    continue;
                                }
                                const d_v_prop = try cur.resolveProperty(self.allocator, d_v_raw.?);
                                const d_v = try self.resolveVersionRange(ArtifactDescriptor{ .group_id = d_gid, .artifact_id = d_aid, .version = d_v_prop, .scope = "", .path = "" });
                                self.allocator.free(d_v_prop);

                                 var d_s_raw = dep.scope;
                                 if (d_s_raw == null) d_s_raw = cur.getManagedScope(d_gid, d_aid);
                                 const d_s = try cur.resolveProperty(self.allocator, d_s_raw orelse "compile");

                                const is_opt = blk_opt: {
                                    const opt_raw = try cur.resolveProperty(self.allocator, if (dep.optional) "true" else "false");
                                    defer self.allocator.free(opt_raw);
                                    break :blk_opt std.mem.eql(u8, opt_raw, "true");
                                };

                                var ex_list = std.ArrayListUnmanaged([]const u8){};
                                for (dep.exclusions) |ex| {
                                    const ex_gid = try cur.resolveProperty(self.allocator, ex.group_id orelse "");
                                    const ex_aid = try cur.resolveProperty(self.allocator, ex.artifact_id orelse "");
                                    const ex_ga = try std.fmt.allocPrint(self.allocator, "{s}:{s}", .{ ex_gid, ex_aid });
                                    try ex_list.append(self.allocator, ex_ga);
                                    self.allocator.free(ex_gid);
                                    self.allocator.free(ex_aid);
                                }

                                try deps_list.append(self.allocator, .{
                                    .group_id = d_gid,
                                    .artifact_id = d_aid,
                                    .version = d_v,
                                    .classifier = if (dep.classifier) |c| try cur.resolveProperty(self.allocator, c) else null,
                                    .type = try cur.resolveProperty(self.allocator, dep.type orelse "jar"),
                                    .scope = d_s,
                                    .optional = is_opt,
                                    .exclusions = try ex_list.toOwnedSlice(self.allocator),
                                });
                            }
                            head_ctx = cur.parent;
                        }
                        
                        const final_deps = try deps_list.toOwnedSlice(self.allocator);
                        direct_deps = final_deps;
                        if (self.cache_enabled and !is_reactor) {
                            const cache_file = try self.getCacheFile(pf, effective_scopes);
                            defer self.allocator.free(cache_file);
                            try self.saveCache(cache_file, direct_deps.?, pom_hash);
                        }
                    } else |_| {}
                }

                if (direct_deps) |deps| {
                    defer {
                        for (deps) |d| d.deinit(self.allocator);
                        self.allocator.free(deps);
                    }
                    for (deps) |cd| {
                        const ga_trans = try std.fmt.allocPrint(arena, "{s}:{s}", .{ cd.group_id, cd.artifact_id });
                        if (current.exclusions.contains(ga_trans) or current.exclusions.contains(try std.fmt.allocPrint(arena, "{s}:*", .{cd.group_id}))) {
                            continue;
                        }

                        const s_opt = propagateScope(ad.scope, cd.scope);
                        if (s_opt == null) continue;
                        const s = s_opt.?;

                        var v = cd.version;
                        var final_s = s;
                        
                        // Fix: integrate project_versions override
                        if (project_versions.get(ga_trans)) |p_version| {
                            v = p_version;
                        }

                        if (root_ctx) |rctx| {
                            if (rctx.getManagedVersion(cd.group_id, cd.artifact_id)) |mv| {
                                const mv_res = try rctx.resolveProperty(arena, mv);
                                const v_old = v;
                                v = try self.resolveVersionRange(ArtifactDescriptor{ .group_id = cd.group_id, .artifact_id = cd.artifact_id, .version = mv_res, .scope = "", .path = "" });
                                if (self.isDebugMatch(cd.group_id, cd.artifact_id)) std.debug.print("MIMIC: [MANAGED-VERSION] {s}:{s} override from {s} to {s} (via {s})\n", .{ cd.group_id, cd.artifact_id, v_old, v, mv_res });
                            }
                            if (rctx.getManagedScope(cd.group_id, cd.artifact_id)) |ms| {
                                const ms_res = try rctx.resolveProperty(arena, ms);
                                if (propagateScope(ad.scope, ms_res)) |ps| {
                                    if (self.isDebugMatch(cd.group_id, cd.artifact_id)) std.debug.print("MIMIC: [MANAGED-SCOPE] {s}:{s} override from {s} to {s} (via {s})\n", .{ cd.group_id, cd.artifact_id, final_s, ps, ms_res });
                                    final_s = ps;
                                }
                            }
                        }

                        if (self.isDebugMatch(cd.group_id, cd.artifact_id)) {
                             std.debug.print("MIMIC: [TRANS-EXPAND] {s}:{s}:{s} (s={s}, parent_s={s}) depth={d}\n", .{ cd.group_id, cd.artifact_id, v, final_s, ad.scope, current.depth + 1 });
                        }

                        const next_path = try std.fmt.allocPrint(self.allocator, "{s} -> {s}:{s}:{s}", .{ current.path, cd.group_id, cd.artifact_id, v });
                        
                        var res_exclusions = std.ArrayListUnmanaged(pom.Exclusion){};
                        for (cd.exclusions) |ex_ga| {
                            var ex_it = std.mem.splitScalar(u8, ex_ga, ':');
                            const ex_gid = ex_it.next() orelse continue;
                            const ex_aid = ex_it.next() orelse continue;
                            try res_exclusions.append(self.allocator, .{ .group_id = try self.allocator.dupe(u8, ex_gid), .artifact_id = try self.allocator.dupe(u8, ex_aid) });
                        }

                        const trans_ad = ArtifactDescriptor{
                            .group_id = try self.allocator.dupe(u8, cd.group_id),
                            .artifact_id = try self.allocator.dupe(u8, cd.artifact_id),
                            .version = try self.allocator.dupe(u8, v),
                            .scope = try self.allocator.dupe(u8, final_s),
                            .type = try self.allocator.dupe(u8, cd.type),
                            .classifier = if (cd.classifier) |c| try self.allocator.dupe(u8, c) else null,
                            .exclusions = try res_exclusions.toOwnedSlice(self.allocator),
                            .path = next_path,
                            .optional = cd.optional,
                        };

                        const skip_rel = !isScopeRelevant(final_s, effective_scopes, false) or trans_ad.optional;
                        if (self.isDebugMatch(cd.group_id, cd.artifact_id)) {
                            std.debug.print("MIMIC: [TRANS-CHECK] {s}:{s}:{s} (s={s}, opt={any}) skip={any} depth={d}\n", .{ cd.group_id, cd.artifact_id, v, final_s, trans_ad.optional, skip_rel, current.depth + 1 });
                        }

                        if (!skip_rel) {
                            const next_node = try Node.init(self.allocator, trans_ad, current.depth + 1, current, next_path);
                            try queue.append(self.allocator, next_node);
                            // Cleanup dupe
                            self.allocator.free(trans_ad.group_id);
                            self.allocator.free(trans_ad.artifact_id);
                            self.allocator.free(trans_ad.version);
                            self.allocator.free(trans_ad.scope);
                            self.allocator.free(trans_ad.type);
                            if (trans_ad.classifier) |c| self.allocator.free(c);
                            if (trans_ad.exclusions) |exs| {
                                for (exs) |ex| { self.allocator.free(ex.group_id.?); self.allocator.free(ex.artifact_id.?); }
                                self.allocator.free(exs);
                            }
                            self.allocator.free(trans_ad.path);
                        } else {
                            for (trans_ad.exclusions.?) |ex| { self.allocator.free(ex.group_id.?); self.allocator.free(ex.artifact_id.?); }
                            self.allocator.free(trans_ad.exclusions.?);
                            self.allocator.free(trans_ad.path);
                        }
                    }
                }
            } else |_| {}
        }

        var res_list = std.ArrayListUnmanaged(ArtifactDescriptor){};
        var it_v = resolved_versions.iterator();
        while (it_v.next()) |entry| {
            const ga_val = entry.key_ptr.*;
            if (self.skip_siblings and self.reactor_poms.contains(ga_val)) continue;
            
            const depth = resolved_depths.get(ga_val) orelse {
                if (self.isDebugMatch(ga_val, "")) std.debug.print("MIMIC: [FINALIZE-SKIP] {s} no depth in map\n", .{ga_val});
                continue;
            };
            const scope = resolved_scopes.get(ga_val) orelse {
                if (self.isDebugMatch(ga_val, "")) std.debug.print("MIMIC: [FINALIZE-SKIP] {s} no scope in map\n", .{ga_val});
                continue;
            };
            const res_path = resolved_paths.get(ga_val) orelse "";

            if (resolved_optionals.contains(ga_val) and depth > 0) continue;
            if (!isScopeRelevant(scope, effective_scopes, depth == 0)) {
                if (self.isDebugMatch(ga_val, "")) std.debug.print("MIMIC: [FINALIZE-SKIP] {s} scope {s} not relevant at depth {d}\n", .{ga_val, scope, depth});
                continue;
            }

            var it_p = std.mem.splitScalar(u8, ga_val, ':');
            const gid = it_p.next() orelse "";
            const aid = it_p.next() orelse "";

            try res_list.append(self.allocator, .{
                .group_id = try self.allocator.dupe(u8, gid),
                .artifact_id = try self.allocator.dupe(u8, aid),
                .version = try self.allocator.dupe(u8, entry.value_ptr.*),
                .scope = try self.allocator.dupe(u8, scope),
                .path = try self.allocator.dupe(u8, res_path),
            });
        }

        return ResolutionResult{
            .artifacts = try res_list.toOwnedSlice(self.allocator),
            .artifact_files = artifact_files,
            .errors = try errors.toOwnedSlice(self.allocator),
        };
    }

    fn propagateScope(parent_scope: []const u8, child_scope_raw: []const u8) ?[]const u8 {
        const child_scope = if (child_scope_raw.len == 0) "compile" else child_scope_raw;
        const p_scope = if (parent_scope.len == 0) "compile" else parent_scope;

        if (std.mem.eql(u8, child_scope, "provided") or std.mem.eql(u8, child_scope, "test")) return null;

        if (std.mem.eql(u8, p_scope, "compile")) {
            return child_scope;
        }
        if (std.mem.eql(u8, p_scope, "runtime")) {
            if (std.mem.eql(u8, child_scope, "compile") or std.mem.eql(u8, child_scope, "runtime")) return "runtime";
            return null;
        }
        if (std.mem.eql(u8, p_scope, "provided")) {
            if (std.mem.eql(u8, child_scope, "compile") or std.mem.eql(u8, child_scope, "runtime")) return "provided";
            return null;
        }
        if (std.mem.eql(u8, p_scope, "test")) {
            if (std.mem.eql(u8, child_scope, "compile") or std.mem.eql(u8, child_scope, "runtime")) return "test";
            return null;
        }
        return "compile";
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
        const ctx = try self.loadPomContext(path, null, null);
        var initial_ads = std.ArrayListUnmanaged(ArtifactDescriptor){};
        defer initial_ads.deinit(self.allocator);
        var project_versions = std.StringHashMap([]const u8).init(self.allocator);
        defer {
            var it_v = project_versions.iterator();
            while (it_v.next()) |entry| { self.allocator.free(entry.key_ptr.*); self.allocator.free(entry.value_ptr.*); }
            project_versions.deinit();
        }
        
        var cur_ctx: ?*context.PomContext = ctx;
        while (cur_ctx) |c| {
            for (c.model.dependencies) |dep| {
                const gid = try c.resolveProperty(self.allocator, dep.group_id orelse "");
                const aid = try c.resolveProperty(self.allocator, dep.artifact_id orelse "");
                const ga = try std.fmt.allocPrint(self.allocator, "{s}:{s}", .{ gid, aid });
                defer self.allocator.free(ga);

                std.debug.print("MIMIC: [DIRECT-DEP-SCAN] {s} in {s}\n", .{ ga, c.model.artifact_id orelse "???" });

                if (project_versions.contains(ga)) {
                    self.allocator.free(gid);
                    self.allocator.free(aid);
                    continue;
                }

                var scope: []const u8 = "compile";
                if (dep.scope) |s| {
                    scope = try c.resolveProperty(self.allocator, s);
                } else if (ctx.getManagedScope(gid, aid)) |ms| {
                    scope = try ctx.resolveProperty(self.allocator, ms);
                }
                
                var v_raw = dep.version;
                if (v_raw == null) v_raw = ctx.getManagedVersion(gid, aid);
                if (v_raw == null) {
                    self.allocator.free(gid);
                    self.allocator.free(aid);
                    self.allocator.free(scope);
                    continue;
                }
                const v_prop = try c.resolveProperty(self.allocator, v_raw.?);
                const v = try self.resolveVersionRange(ArtifactDescriptor{ .group_id = gid, .artifact_id = aid, .version = v_prop, .scope = "", .path = "" });
                self.allocator.free(v_prop);

                try project_versions.put(try self.allocator.dupe(u8, ga), try self.allocator.dupe(u8, v));

                if (true) {
                    const is_opt = blk_opt: {
                        const opt_raw = try c.resolveProperty(self.allocator, if (dep.optional) "true" else "false");
                        defer self.allocator.free(opt_raw);
                        break :blk_opt std.mem.eql(u8, opt_raw, "true");
                    };

                    var ex_list = std.ArrayListUnmanaged(pom.Exclusion){};
                    for (dep.exclusions) |ex| {
                        try ex_list.append(self.allocator, .{ 
                            .group_id = try c.resolveProperty(self.allocator, ex.group_id orelse ""), 
                            .artifact_id = try c.resolveProperty(self.allocator, ex.artifact_id orelse "") 
                        });
                    }
                    try initial_ads.append(self.allocator, .{ 
                        .group_id = gid, 
                        .artifact_id = aid, 
                        .version = v, 
                        .scope = scope, 
                        .classifier = if (dep.classifier) |cl| try c.resolveProperty(self.allocator, cl) else null,
                        .type = try c.resolveProperty(self.allocator, dep.type orelse "jar"),
                        .optional = is_opt,
                        .exclusions = try ex_list.toOwnedSlice(self.allocator),
                        .path = try self.allocator.dupe(u8, ga)
                    });
                } else {
                    self.allocator.free(gid); self.allocator.free(aid); self.allocator.free(scope); self.allocator.free(v);
                }
            }
            cur_ctx = c.parent;
        }
        std.debug.print("MIMIC: [DIRECT-DEPS-COUNT] {d}\n", .{initial_ads.items.len});
        return try self.resolve(initial_ads.items, effective_scopes, ctx, project_versions);
    }

    fn isScopeRelevant(scope: []const u8, effective_scopes: []const []const u8, is_direct: bool) bool {
        _ = is_direct;
        for (effective_scopes) |s| {
            if (std.mem.eql(u8, s, scope)) return true;
        }
        // Nuance #37/4: provided/system are relevant if direct, BUT ONLY for compile-time resolution.
        // If effective_scopes contains 'provided', it was explicitly requested (e.g. by -Dscope=provided or -Dscope=compile)
        // Actually, Maven 'compile' scope resolution includes provided/system if direct.
        // BUT 'runtime' scope resolution DOES NOT.
        // We can detect 'compile' requested if 'compile' is in effective_scopes AND 'runtime' is NOT?
        // No, both are often there.
        // Better: check if 'provided' itself is in effective_scopes. 
        // For -Dscope=runtime, we only have ["runtime", "compile"].
        // For -Dscope=compile, Maven adds provided/system to the list.
        return false;
    }

    fn resolveVersionRange(self: *MimicResolver, ad: ArtifactDescriptor) ![]const u8 {
        const version = ad.version;
        if (!std.mem.startsWith(u8, version, "[") and !std.mem.startsWith(u8, version, "(")) return try self.allocator.dupe(u8, version);

        const rel_dir = try std.fmt.allocPrint(self.allocator, "{s}/{s}", .{ try std.mem.replaceOwned(u8, self.allocator, ad.group_id, ".", "/"), ad.artifact_id });
        defer self.allocator.free(rel_dir);
        const full_dir = try std.fs.path.join(self.allocator, &[_][]const u8{ self.local_repo, rel_dir });
        defer self.allocator.free(full_dir);

        var dir = std.fs.cwd().openDir(full_dir, .{ .iterate = true }) catch return try self.extractBaseVersion(version);
        defer dir.close();

        var best_v: ?[]const u8 = null;
        var iter = dir.iterate();
        while (try iter.next()) |entry| {
            if (entry.kind == .directory) {
                if (self.isVersionInRange(entry.name, version)) {
                    if (best_v == null or compareVersions(entry.name, best_v.?) > 0) {
                        if (best_v) |old| self.allocator.free(old);
                        best_v = try self.allocator.dupe(u8, entry.name);
                    }
                }
            }
        }
        return best_v orelse try self.extractBaseVersion(version);
    }

    fn extractBaseVersion(self: *MimicResolver, version: []const u8) ![]const u8 {
        const v = version[1..];
        if (std.mem.indexOfScalar(u8, v, ',')) |comma| {
            return try self.allocator.dupe(u8, std.mem.trim(u8, v[0..comma], " \t\r\n"));
        } else {
            var end: usize = v.len;
            if (std.mem.endsWith(u8, v, ")") or std.mem.endsWith(u8, v, "]")) end -= 1;
            return try self.allocator.dupe(u8, std.mem.trim(u8, v[0..end], " \t\r\n"));
        }
    }

    fn isVersionInRange(self: *MimicResolver, v_name: []const u8, range: []const u8) bool {
        _ = self;
        if (range.len < 3) return false;
        const r = range[1 .. range.len - 1];
        var iter = std.mem.splitScalar(u8, r, ',');
        const lower = std.mem.trim(u8, iter.next() orelse "", " \t\r\n");
        const upper = std.mem.trim(u8, iter.next() orelse "", " \t\r\n");
        const lower_incl = std.mem.startsWith(u8, range, "[");
        const upper_incl = std.mem.endsWith(u8, range, "]");

        var match = true;
        if (lower.len > 0) {
            const cmp = compareVersions(v_name, lower);
            match = if (lower_incl) cmp >= 0 else cmp > 0;
        }
        if (match and upper.len > 0) {
            const cmp = compareVersions(v_name, upper);
            match = if (upper_incl) cmp <= 0 else cmp < 0;
        }
        return match;
    }

    fn compareVersions(v1: []const u8, v2: []const u8) i32 {
        var it1 = std.mem.tokenizeAny(u8, v1, ".-");
        var it2 = std.mem.tokenizeAny(u8, v2, ".-");
        while (true) {
            const s1 = it1.next();
            const s2 = it2.next();
            if (s1 == null and s2 == null) return 0;
            const p1 = s1 orelse "0";
            const p2 = s2 orelse "0";
            const n1 = std.fmt.parseInt(i32, p1, 10) catch -1;
            const n2 = std.fmt.parseInt(i32, p2, 10) catch -1;
            if (n1 != -1 and n2 != -1) {
                if (n1 != n2) return if (n1 > n2) 1 else -1;
            } else {
                const cmp = std.mem.order(u8, p1, p2);
                if (cmp != .eq) return if (cmp == .gt) 1 else -1;
            }
        }
    }

    fn loadPomContext(self: *MimicResolver, path: []const u8, ad: ?ArtifactDescriptor, reactor_properties: ?*const std.StringHashMap([]const u8)) anyerror!*context.PomContext {
        if (self.context_cache.get(path)) |ctx| return ctx;

        const content = try std.fs.cwd().readFileAlloc(self.allocator, path, 10 * 1024 * 1024);
        const path_owned = try self.allocator.dupe(u8, path);
        errdefer self.allocator.free(path_owned);

        try self.pom_content_cache.put(path_owned, content);
        const model = try pom.PomModel.parse(self.allocator, content);
        var parent_ctx: ?*context.PomContext = null;
        if (model.parent) |p| {
            var p_path: ?[]const u8 = null;
            const bom_resolver_dummy = context.BOMResolver{ .ptr = self, .load_fn = struct { fn load(_: *anyopaque, _: []const u8, _: []const u8, _: []const u8, _: ?*const std.StringHashMap([]const u8), _: ?[]const u8) anyerror!?*context.PomContext { return null; } }.load };
            var dummy_ctx = try context.PomContext.init(self.allocator, model, null, bom_resolver_dummy, null, null, null, reactor_properties, self.debug_match);
            defer dummy_ctx.deinit();
            const p_gid = try dummy_ctx.resolveProperty(self.allocator, p.group_id orelse "");
            const p_aid = try dummy_ctx.resolveProperty(self.allocator, p.artifact_id orelse "");
            const p_v = try dummy_ctx.resolveProperty(self.allocator, p.version orelse "");
            const p_rp_raw = try dummy_ctx.resolveProperty(self.allocator, p.relative_path orelse "");
            defer { self.allocator.free(p_gid); self.allocator.free(p_aid); self.allocator.free(p_v); self.allocator.free(p_rp_raw); }

            if (p_rp_raw.len > 0) {
                const dir = std.fs.path.dirname(path) orelse ".";
                const abs_rp = try std.fs.path.join(self.allocator, &[_][]const u8{ dir, p_rp_raw });
                defer self.allocator.free(abs_rp);
                if (std.mem.endsWith(u8, abs_rp, "pom.xml")) {
                    p_path = try self.allocator.dupe(u8, abs_rp);
                } else if (std.fs.cwd().openDir(abs_rp, .{})) |dir_obj| {
                    var d = dir_obj;
                    defer d.close();
                    p_path = try std.fs.path.join(self.allocator, &[_][]const u8{ abs_rp, "pom.xml" });
                } else |_| {
                    p_path = try self.allocator.dupe(u8, abs_rp);
                }
            }
            if (p_path == null) {
                const p_ad = ArtifactDescriptor{ .group_id = p_gid, .artifact_id = p_aid, .version = p_v, .type = "pom", .path = "" };
                if (self.getOrDownload(p_ad, "pom")) |pf_res| {
                    p_path = pf_res;
                } else |_| {}
            }
            if (p_path) |pp| {
                const p_ctx_res = self.loadPomContext(pp, ArtifactDescriptor{ .group_id = p_gid, .artifact_id = p_aid, .version = p_v, .type = "pom", .path = "" }, reactor_properties);
                var p_ctx: ?*context.PomContext = if (p_ctx_res) |pc| pc else |_| null;
                if (p_ctx == null) {
                    const gav_ad = ArtifactDescriptor{ .group_id = p_gid, .artifact_id = p_aid, .version = p_v, .type = "pom", .path = "" };
                    if (self.getOrDownload(gav_ad, "pom")) |path_from_gav| {
                        defer self.allocator.free(path_from_gav);
                        const lp_res = self.loadPomContext(path_from_gav, gav_ad, reactor_properties);
                        p_ctx = if (lp_res) |pc| pc else |err| blk: {
                             std.debug.print("MIMIC: [PARENT-ERROR] Failed to load from gav {s}:{s}:{s}: {}\n", .{ p_gid, p_aid, p_v, err });
                             break :blk null;
                        };
                    } else |err| {
                        std.debug.print("MIMIC: [PARENT-ERROR] Failed to download {s}:{s}:{s}: {}\n", .{ p_gid, p_aid, p_v, err });
                    }
                }
                parent_ctx = p_ctx;
                self.allocator.free(pp);
            }
        }
        const bom_resolver = context.BOMResolver{ .ptr = self, .load_fn = struct { 
            fn load(ptr: *anyopaque, gid: []const u8, aid: []const u8, v: []const u8, inherited_props: ?*const std.StringHashMap([]const u8), debug_match: ?[]const u8) anyerror!?*context.PomContext { 
                const self_ptr: *MimicResolver = @ptrCast(@alignCast(ptr)); 
                const bom_ad = ArtifactDescriptor{ .group_id = gid, .artifact_id = aid, .version = v, .type = "pom", .path = "" }; 
                const pf = try self_ptr.getOrDownload(bom_ad, "pom"); 
                defer self_ptr.allocator.free(pf);
                _ = debug_match;
                return try self_ptr.loadPomContext(pf, bom_ad, inherited_props); 
            } 
        }.load };
        const r_gid = if (ad) |a| a.group_id else model.group_id;
        const r_aid = if (ad) |a| a.artifact_id else model.artifact_id;
        const r_v = if (ad) |a| a.version else model.version;
        const ctx_ptr = try context.PomContext.init(self.allocator, model, parent_ctx, bom_resolver, r_gid, r_aid, r_v, reactor_properties, self.debug_match);
        try self.context_cache.put(try self.allocator.dupe(u8, path), ctx_ptr);
        return ctx_ptr;
    }

    fn downloadFromRepos(self: *MimicResolver, rel_path: []const u8, full_path: []const u8) !void {
        if (std.fs.path.dirname(full_path)) |dir| {
            try std.fs.cwd().makePath(dir);
        }

        for (self.repo_urls.items) |repo_url| {
            const url = try std.fmt.allocPrint(self.allocator, "{s}{s}", .{ repo_url, rel_path });
            defer self.allocator.free(url);
            
            const cmd = try std.fmt.allocPrint(self.allocator, "Invoke-WebRequest -Uri '{s}' -OutFile '{s}' -ErrorAction SilentlyContinue", .{ url, full_path });
            defer self.allocator.free(cmd);
            
            var child = std.process.Child.init(&[_][]const u8{ "powershell", "-Command", cmd }, self.allocator);
            child.stdout_behavior = .Ignore;
            child.stderr_behavior = .Ignore;
            const term = child.spawnAndWait() catch continue;
            if (term == .Exited and term.Exited == 0) {
                return;
            }
        }
        return error.ArtifactNotFound;
    }

    fn getOrDownload(self: *MimicResolver, ad: ArtifactDescriptor, extension: []const u8) ![]const u8 {
        if (ad.group_id.len == 0 or ad.artifact_id.len == 0 or ad.version.len == 0 or std.mem.eql(u8, ad.version, "-")) {
            return error.ArtifactNotFound;
        }
        const ga = try std.fmt.allocPrint(self.allocator, "{s}:{s}", .{ ad.group_id, ad.artifact_id });
        defer self.allocator.free(ga);
        if (std.mem.eql(u8, extension, "pom")) if (self.reactor_poms.get(ga)) |path| return try self.allocator.dupe(u8, path);
        
        var ver = ad.version;
        if (std.mem.startsWith(u8, ver, "[") or std.mem.startsWith(u8, ver, "(")) {
            if (std.mem.indexOfScalar(u8, ver, ',')) |comma| {
                ver = ver[1..comma];
            }
        }
        
        const rel_path_raw = try deps_format.formatPath(self.allocator, .{ .group_id = ad.group_id, .artifact_id = ad.artifact_id, .version = ver, .extension = extension });
        defer self.allocator.free(rel_path_raw);
        
        const rel_path = if (std.fs.path.sep == '\\') blk: {
            const copy = try self.allocator.dupe(u8, rel_path_raw);
            std.mem.replaceScalar(u8, copy, '/', '\\');
            break :blk copy;
        } else rel_path_raw;
        defer if (std.fs.path.sep == '\\') self.allocator.free(rel_path);

        const full_path = try std.fs.path.join(self.allocator, &[_][]const u8{ self.local_repo, rel_path });
        
        std.fs.cwd().access(full_path, .{}) catch |err| { 
            if (err == error.FileNotFound) { 
                if (self.local_only) {
                    self.allocator.free(full_path);
                    return error.ArtifactNotFound;
                }
                self.downloadFromRepos(rel_path_raw, full_path) catch {
                    self.allocator.free(full_path);
                    return error.ArtifactNotFound;
                };
            } else {
                 self.allocator.free(full_path);
                 return err;
            }
        };
        return full_path;
    }

    fn getCacheFile(self: *MimicResolver, pom_file: []const u8, scopes: []const []const u8) ![]const u8 {
        var sorted_scopes = std.ArrayListUnmanaged([]const u8){};
        defer sorted_scopes.deinit(self.allocator);
        try sorted_scopes.appendSlice(self.allocator, scopes);
        std.mem.sort([]const u8, sorted_scopes.items, {}, struct {
            fn lessThan(_: void, a: []const u8, b: []const u8) bool {
                return std.mem.order(u8, a, b) == .lt;
            }
        }.lessThan);

        var scope_key = std.ArrayListUnmanaged(u8){};
        defer scope_key.deinit(self.allocator);
        for (sorted_scopes.items, 0..) |s, i| {
            if (i > 0) try scope_key.append(self.allocator, ',');
            try scope_key.appendSlice(self.allocator, s);
        }
        if (scope_key.items.len == 0) try scope_key.appendSlice(self.allocator, "all");

        const dir = std.fs.path.dirname(pom_file) orelse ".";
        const base = std.fs.path.basename(pom_file);
        return try std.fmt.allocPrint(self.allocator, "{s}{c}{s}.{s}.get-deps.v2.cache", .{ dir, std.fs.path.sep, base, scope_key.items });
    }

    fn loadCache(self: *MimicResolver, cache_file: []const u8, current_hash: i64) !?[]CachedDependency {
        const file = std.fs.cwd().openFile(cache_file, .{}) catch return null;
        defer file.close();

        const content = try file.readToEndAlloc(self.allocator, 10 * 1024 * 1024);
        defer self.allocator.free(content);

        var iter = std.mem.splitScalar(u8, content, '\n');
        var stored_hash: i64 = -1;

        if (iter.next()) |first_line| {
            const line = std.mem.trim(u8, first_line, " \t\r\n");
            if (std.mem.startsWith(u8, line, "# pomHash=")) {
                stored_hash = std.fmt.parseInt(i64, line[10..], 10) catch -1;
                if (stored_hash != current_hash) return null;
            } else if (current_hash != -1) {
                return null;
            } else {
                iter = std.mem.splitScalar(u8, content, '\n');
            }
        }

        var deps = std.ArrayListUnmanaged(CachedDependency){};
        while (iter.next()) |raw_line| {
            const line = std.mem.trim(u8, raw_line, " \t\r\n");
            if (line.len == 0 or line[0] == '#') continue;

            var parts = std.mem.splitScalar(u8, line, ':');
            const gid = parts.next() orelse continue;
            const aid = parts.next() orelse continue;
            const ver = parts.next() orelse continue;
            const classifier = parts.next() orelse continue;
            const dtype = parts.next() orelse continue;
            const scope = parts.next() orelse continue;
            const opt_str = parts.next() orelse continue;
            const ex_str = parts.next() orelse "";

            var ex_list = std.ArrayListUnmanaged([]const u8){};
            if (ex_str.len > 0) {
                var ex_it = std.mem.splitScalar(u8, ex_str, ',');
                while (ex_it.next()) |ex| try ex_list.append(self.allocator, try self.allocator.dupe(u8, ex));
            }

            try deps.append(self.allocator, .{
                .group_id = try self.allocator.dupe(u8, gid),
                .artifact_id = try self.allocator.dupe(u8, aid),
                .version = try self.allocator.dupe(u8, ver),
                .classifier = if (classifier.len > 0) try self.allocator.dupe(u8, classifier) else null,
                .type = try self.allocator.dupe(u8, dtype),
                .scope = try self.allocator.dupe(u8, scope),
                .optional = std.mem.eql(u8, opt_str, "true"),
                .exclusions = try ex_list.toOwnedSlice(self.allocator),
            });
        }
        return try deps.toOwnedSlice(self.allocator);
    }

    fn saveCache(self: *MimicResolver, cache_file: []const u8, dependencies: []const CachedDependency, pom_hash: i64) !void {
        _ = self;
        const file = try std.fs.cwd().createFile(cache_file, .{});
        defer file.close();
        var write_buf: [4096]u8 = undefined;
        var writer_struct = file.writer(&write_buf);
        const writer = &writer_struct.interface;

        if (pom_hash != -1) {
            try writer.print("# pomHash={d}\n", .{pom_hash});
        }

        for (dependencies) |cd| {
            try writer.print("{s}:{s}:{s}:{s}:{s}:{s}:{any}:", .{
                cd.group_id,
                cd.artifact_id,
                cd.version,
                cd.classifier orelse "",
                cd.type,
                cd.scope,
                cd.optional,
            });
            for (cd.exclusions, 0..) |ex, i| {
                if (i > 0) try writer.writeAll(",");
                try writer.writeAll(ex);
            }
            try writer.writeAll("\n");
        }
    }

    fn calculateWyhash64(self: *MimicResolver, path: []const u8) i64 {
        const content = std.fs.cwd().readFileAlloc(self.allocator, path, 10 * 1024 * 1024) catch return 0;
        defer self.allocator.free(content);
        return @as(i64, @bitCast(std.hash.Wyhash.hash(0, content)));
    }
};

test "load cache compatibility" {
    const allocator = std.testing.allocator;
    var res = MimicResolver.init(allocator, ".");
    defer res.deinit();

    const cache_content = 
        \\# pomHash=12345
        \\org.example:example-lib:1.0.0::jar:compile:false:ex.group:ex.artifact,other.group:other.artifact
        \\org.test:test-tool:2.1.0:debug:exe:test:true:
        \\
    ;

    const tmp_cache = "test_cache.v2.cache";
    try std.fs.cwd().writeFile(.{ .sub_path = tmp_cache, .data = cache_content });
    defer std.fs.cwd().deleteFile(tmp_cache) catch {};

    const deps_opt = try res.loadCache(tmp_cache, 12345);
    try std.testing.expect(deps_opt != null);
    const deps = deps_opt.?;
    defer {
        for (deps) |d| d.deinit(allocator);
        allocator.free(deps);
    }

    try std.testing.expectEqual(@as(usize, 2), deps.len);
    
    try std.testing.expectEqualStrings("org.example", deps[0].group_id);
    try std.testing.expectEqualStrings("example-lib", deps[0].artifact_id);
    try std.testing.expectEqualStrings("1.0.0", deps[0].version);
    try std.testing.expect(deps[0].classifier == null);
    try std.testing.expectEqualStrings("jar", deps[0].type);
    try std.testing.expectEqualStrings("compile", deps[0].scope);
    try std.testing.expectEqual(false, deps[0].optional);
    try std.testing.expectEqual(@as(usize, 2), deps[0].exclusions.len);
    try std.testing.expectEqualStrings("ex.group:ex.artifact", deps[0].exclusions[0]);
    try std.testing.expectEqualStrings("other.group:other.artifact", deps[0].exclusions[1]);

    try std.testing.expectEqualStrings("org.test", deps[1].group_id);
    try std.testing.expectEqualStrings("test-tool", deps[1].artifact_id);
    try std.testing.expectEqualStrings("2.1.0", deps[1].version);
    try std.testing.expectEqualStrings("debug", deps[1].classifier.?);
    try std.testing.expectEqualStrings("exe", deps[1].type);
    try std.testing.expectEqualStrings("test", deps[1].scope);
    try std.testing.expectEqual(true, deps[1].optional);
    try std.testing.expectEqual(@as(usize, 0), deps[1].exclusions.len);
}

test "wyhash64 matching" {
    const allocator = std.testing.allocator;
    var res = MimicResolver.init(allocator, ".");
    defer res.deinit();

    const test_file = "test_hash.txt";
    try std.fs.cwd().writeFile(.{ .sub_path = test_file, .data = "hello world" });
    defer std.fs.cwd().deleteFile(test_file) catch {};

    const hash = res.calculateWyhash64(test_file);
    try std.testing.expect(hash != 0);
}
