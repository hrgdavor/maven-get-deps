const std = @import("std");

pub const Token = union(enum) {
    tag_open: []const u8,
    tag_close: []const u8,
    text: []const u8,
    eof,
};

pub const Scanner = struct {
    input: []const u8,
    pos: usize = 0,
    pending_close: ?[]const u8 = null,

    pub fn init(input: []const u8) Scanner {
        return .{ .input = input };
    }

    pub fn next(self: *Scanner) anyerror!Token {
        if (self.pending_close) |name| {
            self.pending_close = null;
            return .{ .tag_close = name };
        }

        self.skipWhitespace();
        if (self.pos >= self.input.len) return .eof;

        if (self.input[self.pos] == '<') {
            if (std.mem.startsWith(u8, self.input[self.pos..], "<!--")) {
                const end = std.mem.indexOf(u8, self.input[self.pos..], "-->") orelse return error.InvalidXml;
                self.pos += end + 3;
                return self.next();
            }
            if (std.mem.startsWith(u8, self.input[self.pos..], "<![CDATA[")) {
                const start = self.pos + 9;
                const end = std.mem.indexOf(u8, self.input[self.pos..], "]]>") orelse return error.InvalidXml;
                const text = self.input[start .. self.pos + end];
                self.pos += end + 3;
                return .{ .text = text };
            }
            if (std.mem.startsWith(u8, self.input[self.pos..], "<?")) {
                const end = std.mem.indexOf(u8, self.input[self.pos..], "?>") orelse return error.InvalidXml;
                self.pos += end + 2;
                return self.next();
            }

            self.pos += 1; // skip <
            if (self.pos < self.input.len and self.input[self.pos] == '/') {
                self.pos += 1; // skip /
                const start = self.pos;
                while (self.pos < self.input.len and self.input[self.pos] != '>') {
                    self.pos += 1;
                }
                const name = std.mem.trim(u8, self.input[start..self.pos], " \t\r\n");
                if (self.pos < self.input.len) self.pos += 1; // skip >
                return .{ .tag_close = name };
            } else {
                const start = self.pos;
                var in_attr: ?u8 = null;
                while (self.pos < self.input.len) {
                    const c = self.input[self.pos];
                    if (in_attr) |quote| {
                        if (c == quote) in_attr = null;
                    } else {
                        if (c == '\"' or c == '\'') {
                            in_attr = c;
                        } else if (c == ' ' or c == '\t' or c == '\r' or c == '\n' or c == '>' or c == '/') {
                            break;
                        }
                    }
                    self.pos += 1;
                }
                const name = self.input[start..self.pos];
                
                var self_closing = false;
                while (self.pos < self.input.len and self.input[self.pos] != '>') {
                    const c = self.input[self.pos];
                    if (in_attr) |quote| {
                        if (c == quote) in_attr = null;
                    } else {
                        if (c == '\"' or c == '\'') {
                            in_attr = c;
                        } else if (c == '/') {
                            // Check if next is >
                            if (self.pos + 1 < self.input.len and self.input[self.pos + 1] == '>') {
                                self_closing = true;
                            }
                        }
                    }
                    self.pos += 1;
                }
                if (self.pos < self.input.len) self.pos += 1; // skip >
                
                if (self_closing) {
                    // std.debug.print("SCANNER: Self-closing tag identified: {s}\n", .{name});
                    self.pending_close = name;
                }
                return .{ .tag_open = name };
            }
        } else {
            const start = self.pos;
            while (self.pos < self.input.len and self.input[self.pos] != '<') {
                self.pos += 1;
            }
            return .{ .text = std.mem.trim(u8, self.input[start..self.pos], " \t\r\n") };
        }
    }

    fn skipWhitespace(self: *Scanner) void {
        while (self.pos < self.input.len) {
            const c = self.input[self.pos];
            if (c == ' ' or c == '\t' or c == '\r' or c == '\n') {
                self.pos += 1;
            } else {
                break;
            }
        }
    }
};
