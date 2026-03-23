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
            if (existing_depth) |d| {
                if (current.depth > d) {
                    // Fix 3: Optional vs Required Intersection (Item 35)
                    const is_existing_optional = resolved_optionals.contains(ga);
                    if (!is_existing_optional or ad.optional) continue;
                    // Proceed to override optional with required
                } else if (current.depth == d) {
                    const existing_scope = resolved_scopes.get(ga) orelse "compile";
                    const is_existing_optional = resolved_optionals.contains(ga);

                    const scope_promoted = isScopeStronger(ad.scope, existing_scope);
                    const optional_promoted = (is_existing_optional and !ad.optional);

                    if (scope_promoted) {
                        if (self.isDebugMatch(ad.group_id, ad.artifact_id)) {
                             std.debug.print("MIMIC: [SCOPE-PROMOTE] {s} from {s} to {s} at depth {d} via {s}\n", .{ ga, existing_scope, ad.scope, current.depth, current.path });
                        }
                    }

                    if (!scope_promoted and !optional_promoted) continue;

                    // Fix 4: Root Provided Protection (Item 36)
                    if (d == 0 and std.mem.eql(u8, existing_scope, "provided") and scope_promoted) {
                        // Prevent promotion if root was provided
                        if (!optional_promoted) continue;
                    }
                }
            }

            if (existing_depth == null) {
                try resolved_depths.put(try self.allocator.dupe(u8, ga), current.depth);
            }
            
            // Record version/scope/path (always update on promotion or first encounter)
            try resolved_versions.put(try self.allocator.dupe(u8, ga), try self.allocator.dupe(u8, ad.version));
            try resolved_scopes.put(try self.allocator.dupe(u8, ga), try self.allocator.dupe(u8, ad.scope));
            try resolved_paths.put(try self.allocator.dupe(u8, ga), try self.allocator.dupe(u8, current.path));

            const ga_owned = try self.allocator.dupe(u8, ga);
            if (ad.optional) {
                try resolved_optionals.put(ga_owned, {});
            } else {
                if (resolved_optionals.fetchRemove(ga)) |kv| {
                    self.allocator.free(kv.key);
                }
                self.allocator.free(ga_owned);
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
            if (self.isDebugMatch(ad.group_id, ad.artifact_id)) {
                if (pf_res) |pf| {
                    std.debug.print("MIMIC: [POM-CHECK] {s}:{s}:{s} pomFile={s} exists=true\n", .{ ad.group_id, ad.artifact_id, ad.version, pf });
                } else |err| {
                    std.debug.print("MIMIC: [POM-CHECK] {s}:{s}:{s} pomFile=null exists=false ({s})\n", .{ ad.group_id, ad.artifact_id, ad.version, @errorName(err) });
                }
            }
            if (pf_res) |pf| {
                defer self.allocator.free(pf);
                const ctx_res = self.loadPomContext(pf, ad, &self.reactor_properties);
                if (ctx_res) |ctx| {
                    var head_ctx: ?*context.PomContext = ctx;
                    while (head_ctx) |cur| {
                        for (cur.model.dependencies) |dep| {
                            const d_gid = try cur.resolveProperty(self.allocator, dep.group_id orelse "");
                            const d_aid = try cur.resolveProperty(self.allocator, dep.artifact_id orelse "");
                            const d_ga = try std.fmt.allocPrint(self.allocator, "{s}:{s}", .{ d_gid, d_aid });
                            defer self.allocator.free(d_ga);
                            const d_ga_wild = try std.fmt.allocPrint(self.allocator, "{s}:*", .{d_gid});
                            defer self.allocator.free(d_ga_wild);

                            if (current.exclusions.contains(d_ga) or current.exclusions.contains(d_ga_wild)) {
                                if (self.isDebugMatch(d_gid, d_aid)) {
                                    std.debug.print("MIMIC: [EXCLUDE-SKIP] {s} in {s}:{s}\n", .{ d_ga, ad.group_id, ad.artifact_id });
                                }
                                continue;
                            }

                            // Scope resolution
                            var scope_opt: ?[]const u8 = null;
                            var is_skipped = false;
                            if (dep.scope) |s| {
                                const s_res = try cur.resolveProperty(self.allocator, s);
                                scope_opt = propagateScope(ad.scope, s_res);
                                self.allocator.free(s_res);
                                if (scope_opt == null) is_skipped = true;
                            } else {
                                // 1. Root management (High priority)
                                if (root_ctx) |rctx| {
                                    var ms: ?[]const u8 = null;
                                    var curr_m: ?*context.PomContext = root_ctx;
                                    while (curr_m) |c| : (curr_m = c.parent) {
                                        if (c.getManagedScope(d_gid, d_aid)) |s| {
                                            ms = s;
                                            break;
                                        }
                                    }
                                    if (ms) |scope_raw| {
                                        const ms_res = try rctx.resolveProperty(self.allocator, scope_raw);
                                        scope_opt = propagateScope(ad.scope, ms_res);
                                        self.allocator.free(ms_res);
                                        if (scope_opt == null) is_skipped = true;
                                    }
                                }
                                // 2. Local management (Fallback)
                                if (scope_opt == null and !is_skipped) {
                                    if (cur.getManagedScope(d_gid, d_aid)) |ms| {
                                        const ms_res = try cur.resolveProperty(self.allocator, ms);
                                        scope_opt = propagateScope(ad.scope, ms_res);
                                        self.allocator.free(ms_res);
                                        if (scope_opt == null) is_skipped = true;
                                    }
                                }
                                // 3. Default to compile
                                if (scope_opt == null and !is_skipped) {
                                    scope_opt = propagateScope(ad.scope, "compile");
                                    if (scope_opt == null) is_skipped = true;
                                }
                            }

                            if (is_skipped or scope_opt == null) continue;
                            const scope = scope_opt.?;

                            // Version resolution
                            var version_range: ?[]const u8 = null;
                            // 1. Root management (High priority)
                            if (root_ctx) |rctx| {
                                var mv: ?[]const u8 = null;
                                var curr_v: ?*context.PomContext = root_ctx;
                                while (curr_v) |c| : (curr_v = c.parent) {
                                    if (c.getManagedVersion(d_gid, d_aid)) |v| {
                                        mv = v;
                                        break;
                                    }
                                }
                                if (mv) |version_raw| version_range = try rctx.resolveProperty(self.allocator, version_raw);
                            }
                            // 2. Local management (Fallback)
                            if (version_range == null) {
                                if (cur.getManagedVersion(d_gid, d_aid)) |mv| version_range = try cur.resolveProperty(self.allocator, mv);
                            }
                            // 3. Explicit version
                            if (version_range == null) {
                                if (dep.version) |v_raw| version_range = try cur.resolveProperty(self.allocator, v_raw);
                            }
                            if (version_range == null) continue;

                            var version = try self.resolveVersionRange(ArtifactDescriptor{ .group_id = d_gid, .artifact_id = d_aid, .version = version_range.?, .scope = "", .path = "" });

                            if (project_versions.get(d_ga)) |p_version| {
                                self.allocator.free(version);
                                version = try self.allocator.dupe(u8, p_version);
                            }

                            const next_path = try std.fmt.allocPrint(self.allocator, "{s} -> {s}:{s}:{s}", .{ current.path, d_gid, d_aid, version });
                            var res_exclusions = std.ArrayListUnmanaged(pom.Exclusion){};
                            for (dep.exclusions) |ex| {
                                const ex_gid = try cur.resolveProperty(self.allocator, ex.group_id orelse "");
                                const ex_aid = try cur.resolveProperty(self.allocator, ex.artifact_id orelse "");
                                try res_exclusions.append(self.allocator, .{ .group_id = ex_gid, .artifact_id = ex_aid });
                            }

                            const trans_ad = ArtifactDescriptor{
                                .group_id = d_gid,
                                .artifact_id = d_aid,
                                .version = version,
                                .scope = scope,
                                .type = dep.type orelse "jar",
                                .exclusions = try res_exclusions.toOwnedSlice(self.allocator),
                                .path = next_path,
                                .optional = dep.optional,
                            };

                            // Fix 1: Block Transitive Masking (Item 41)
                            // Only expand subtree if scope is relevant and NOT optional
                            if (isScopeRelevant(scope, effective_scopes, false) and !trans_ad.optional) {
                                if (self.isDebugMatch(d_gid, d_aid)) {
                                    std.debug.print("MIMIC: [TRANS-ADD] {s}:{s}:{s} (s={s}) depth={d} parent: {s}:{s}:{s}\n", .{ d_gid, d_aid, version, scope, current.depth + 1, ad.group_id, ad.artifact_id, ad.version });
                                }
                                const next_node = try Node.init(self.allocator, trans_ad, current.depth + 1, current, next_path);
                                try queue.append(self.allocator, next_node);
                            } else {
                                if (self.isDebugMatch(d_gid, d_aid)) {
                                    std.debug.print("MIMIC: [TRANS-SKIP-SCOPE] {s}:{s}:{s} (s={s}, isOpt={any}) depth={d} parent: {s}:{s}:{s}\n", .{ d_gid, d_aid, version, scope, trans_ad.optional, current.depth + 1, ad.group_id, ad.artifact_id, ad.version });
                                }
                            }
                        }
                        head_ctx = cur.parent;
                    }
                } else |_| {}
            } else |_| {}
        }

        var it = resolved_versions.iterator();
        while (it.next()) |entry| {
            const ga_val = entry.key_ptr.*;
            if (self.skip_siblings and self.reactor_poms.contains(ga_val)) continue;

            var it_p = std.mem.tokenizeAny(u8, ga_val, ":");
            const gid = it_p.next() orelse "";
            const aid = it_p.next() orelse "";
            const depth = resolved_depths.get(ga_val) orelse continue; 
            const scope = resolved_scopes.get(ga_val) orelse "compile";
            const res_path = resolved_paths.get(ga_val) orelse "";
            
            if (std.mem.eql(u8, gid, "org.mockito") or std.mem.eql(u8, gid, "org.junit.jupiter")) {
                const relevant = isScopeRelevant(scope, effective_scopes, depth == 0);
                std.debug.print("DEBUG: [FINAL-LIST] {s}:{s} v={s} scope={s} depth={} relevant={}\n", .{ gid, aid, entry.value_ptr.*, scope, depth, relevant });
            }

            if (resolved_optionals.contains(ga_val)) {
                if (depth > 0) continue;
            }

            if (!isScopeRelevant(scope, effective_scopes, depth == 0)) continue;

            try result_list.append(self.allocator, ArtifactDescriptor{ 
                .group_id = try self.allocator.dupe(u8, gid), 
                .artifact_id = try self.allocator.dupe(u8, aid), 
                .version = try self.allocator.dupe(u8, entry.value_ptr.*), 
                .scope = try self.allocator.dupe(u8, scope),
                .type = "jar",
                .path = try self.allocator.dupe(u8, res_path)
            });
        }
        return ResolutionResult{ .artifacts = try result_list.toOwnedSlice(self.allocator), .artifact_files = artifact_files, .errors = try errors.toOwnedSlice(self.allocator) };
    }



    fn propagateScope(parent_scope: []const u8, child_scope_raw: []const u8) ?[]const u8 {
        const child_scope = if (child_scope_raw.len == 0) "compile" else child_scope_raw;
        const p_scope = if (parent_scope.len == 0) "compile" else parent_scope;

        if (std.mem.eql(u8, child_scope, "provided") or std.mem.eql(u8, child_scope, "test")) return null;

        if (std.mem.eql(u8, p_scope, "compile")) {
            if (std.mem.eql(u8, child_scope, "compile")) return "compile";
            if (std.mem.eql(u8, child_scope, "runtime")) return "runtime";
            if (std.mem.eql(u8, child_scope, "provided")) return "provided";
            if (std.mem.eql(u8, child_scope, "test")) return "test";
            if (std.mem.eql(u8, child_scope, "system")) return "system";
            return "compile";
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
        for (ctx.repositories.items) |repo| {
            if (repo.url) |url| {
                const normalized_url: []const u8 = if (std.mem.endsWith(u8, url, "/")) try self.allocator.dupe(u8, url) else try std.fmt.allocPrint(self.allocator, "{s}/", .{url});
                var found = false;
                for (self.repo_urls.items) |existing| {
                    if (std.mem.eql(u8, existing, normalized_url)) {
                        found = true;
                        break;
                    }
                }
                if (!found) {
                    try self.repo_urls.append(self.allocator, normalized_url);
                } else {
                    self.allocator.free(normalized_url);
                }
            }
        }

        for (ctx.model.dependencies) |dep| {
            const gid = try ctx.resolveProperty(self.allocator, dep.group_id orelse "");
            const aid = try ctx.resolveProperty(self.allocator, dep.artifact_id orelse "");
            const ga = try std.fmt.allocPrint(self.allocator, "{s}:{s}", .{ gid, aid });
            var scope: []const u8 = "compile";
            if (dep.scope) |s| {
                scope = try ctx.resolveProperty(self.allocator, s);
            } else if (ctx.getManagedScope(gid, aid)) |ms| {
                scope = try ctx.resolveProperty(self.allocator, ms);
            }
            var v_raw = dep.version;
            if (v_raw == null) v_raw = ctx.getManagedVersion(gid, aid);
            if (v_raw == null) continue;
            const v_prop = try ctx.resolveProperty(self.allocator, v_raw.?);
            defer self.allocator.free(v_prop);
            const v = try self.resolveVersionRange(ArtifactDescriptor{ .group_id = gid, .artifact_id = aid, .version = v_prop, .scope = "", .path = "" });
            if (!project_versions.contains(ga)) {
                try project_versions.put(try self.allocator.dupe(u8, ga), try self.allocator.dupe(u8, v));
                const relevant = isScopeRelevant(scope, effective_scopes, true);
                if (relevant) {
                    var res_exclusions = std.ArrayListUnmanaged(pom.Exclusion){};
                    for (dep.exclusions) |ex| {
                        const ex_gid = try ctx.resolveProperty(self.allocator, ex.group_id orelse "");
                        const ex_aid = try ctx.resolveProperty(self.allocator, ex.artifact_id orelse "");
                        try res_exclusions.append(self.allocator, .{ .group_id = ex_gid, .artifact_id = ex_aid });
                    }
                    
                    try initial_ads.append(self.allocator, ArtifactDescriptor{ 
                        .group_id = gid, 
                        .artifact_id = aid, 
                        .version = v, 
                        .scope = scope, 
                        .type = dep.type orelse "jar",
                        .exclusions = try res_exclusions.toOwnedSlice(self.allocator),
                        .path = try self.allocator.dupe(u8, ga)
                    });
                } else self.allocator.free(v);
            } else self.allocator.free(v);
            self.allocator.free(ga);
        }
        return try self.resolve(initial_ads.items, effective_scopes, ctx, project_versions);
    }

    fn isScopeRelevant(scope: []const u8, effective_scopes: []const []const u8, is_direct: bool) bool {
        for (effective_scopes) |s| {
            if (std.mem.eql(u8, s, scope)) return true;
            if (std.mem.eql(u8, s, "runtime")) {
                if (std.mem.eql(u8, scope, "compile") or std.mem.eql(u8, scope, "runtime")) return true;
            }
            if (std.mem.eql(u8, s, "compile")) {
                if (std.mem.eql(u8, scope, "compile") or (is_direct and std.mem.eql(u8, scope, "provided"))) return true;
            }
            if (std.mem.eql(u8, s, "test")) return true;
        }
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
                    const pf_rd = self.getOrDownload(gav_ad, "pom");
                    if (pf_rd) |path_from_gav| {
                        const lp_res = self.loadPomContext(path_from_gav, gav_ad, reactor_properties);
                        p_ctx = if (lp_res) |pc| pc else |_| null;
                        self.allocator.free(path_from_gav);
                    } else |_| {}
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
        std.debug.print("DEBUG: [LOAD-CTX] {s}:{s}:{s} parent={s}\n", .{ r_gid orelse "", r_aid orelse "", r_v orelse "", if (parent_ctx) |p| p.model.artifact_id orelse "unknown" else "null" });
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
            
            // Use powershell for robust downloading on Windows
            const cmd = try std.fmt.allocPrint(self.allocator, "Invoke-WebRequest -Uri '{s}' -OutFile '{s}' -ErrorAction SilentlyContinue", .{ url, full_path });
            defer self.allocator.free(cmd);
            
            var child = std.process.Child.init(&[_][]const u8{ "powershell", "-Command", cmd }, self.allocator);
            child.stdout_behavior = .Ignore;
            child.stderr_behavior = .Ignore;
            const term = child.spawnAndWait() catch continue;
            if (term == .Exited and term.Exited == 0) {
                // std.debug.print("Downloaded (powershell): {s}\n", .{url});
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
};
