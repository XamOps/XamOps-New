package com.xammer.cloud.autospotting;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Where each AutoSpotting API prefix lands, in prod and in dev (GR-08, core §6.6).
 *
 * <p>Prod: the edge gateway ({@code api-gateway/nginx-prod.conf}) proxies some prefixes straight
 * to a service and sends everything else ({@code location /}) to the frontend container, whose
 * own nginx ({@code frontend-app/nginx.conf}) proxies {@code /api/} to xamops-service. Dev: the
 * Vite proxy ({@code frontend-app/vite.config.ts}). This test parses the three files and resolves
 * each prefix the way nginx and Vite do, so a later edit to any of them can't strand an endpoint.
 *
 * <p>Later phases add their prefixes to {@link #routes()}.
 */
class RoutingContractTest {

    enum Service { XAMOPS_SERVICE, BILLOPS_SERVICE }

    /** An API prefix, the service that must answer it, and how many proxies a prod request crosses. */
    record Route(String prefix, Service service, int prodHops) {
        @Override
        public String toString() {
            return prefix;
        }
    }

    static List<Route> routes() {
        return List.of(
                // Admin dashboard reads (NO-02): gateway location /api/xamops/, straight to the service.
                new Route("/api/xamops/autospotting-admin/", Service.XAMOPS_SERVICE, 1),
                // v2 API (core §11): gateway location / to the frontend container, whose nginx
                // proxies /api/ to xamops-service.
                new Route("/api/autospotting/v2/", Service.XAMOPS_SERVICE, 2),
                // BillOps AutoSpotting screens (ledger §11.4): gateway location /api/billops/.
                new Route("/api/billops/autospotting/", Service.BILLOPS_SERVICE, 1));
    }

    /** Containers as the nginx files name them, to the service behind each. */
    private static final Map<String, Service> NGINX_SERVICES = Map.of(
            "xamops-service:8080", Service.XAMOPS_SERVICE,
            "billops-service:8082", Service.BILLOPS_SERVICE);
    /** The frontend container. Its nginx is the second hop. */
    private static final String FRONTEND_CONTAINER = "frontend-app:80";
    /** Vite dev-proxy targets, to the local service on that port. */
    private static final Map<String, Service> VITE_SERVICES = Map.of(
            "http://localhost:8080", Service.XAMOPS_SERVICE,
            "http://localhost:8082", Service.BILLOPS_SERVICE);

    private static Nginx gateway;
    private static Nginx frontendNginx;
    private static List<ViteProxy> viteProxies;

    @BeforeAll
    static void parseRoutingFiles() throws IOException {
        Path root = repoRoot();
        gateway = Nginx.parse(Files.readString(root.resolve("api-gateway/nginx-prod.conf")));
        frontendNginx = Nginx.parse(Files.readString(root.resolve("frontend-app/nginx.conf")));
        viteProxies = ViteProxy.parse(Files.readString(root.resolve("frontend-app/vite.config.ts")));
    }

    private static Path repoRoot() {
        for (Path dir = Path.of("").toAbsolutePath(); dir != null; dir = dir.getParent()) {
            if (Files.isRegularFile(dir.resolve("api-gateway/nginx-prod.conf"))
                    && Files.isRegularFile(dir.resolve("frontend-app/nginx.conf"))) {
                return dir;
            }
        }
        throw new IllegalStateException("repository root not found above " + Path.of("").toAbsolutePath());
    }

    /** Follows a request through the gateway and, when it goes to the frontend container, its nginx. */
    private static List<Hop> resolveProd(String path) {
        List<Hop> hops = new ArrayList<>();
        Hop first = gateway.resolve(path);
        hops.add(first);
        if (FRONTEND_CONTAINER.equals(first.target())) {
            hops.add(frontendNginx.resolve(first.forwardedPath()));
        }
        return hops;
    }

    @ParameterizedTest(name = "prod: {0}")
    @MethodSource("routes")
    void prod_eachPrefixReachesItsService_withThePathUnchanged(Route route) {
        String path = route.prefix() + "probe";

        List<Hop> hops = resolveProd(path);

        Hop last = hops.get(hops.size() - 1);
        assertThat(hops).as("hops for %s", path).hasSize(route.prodHops());
        assertThat(last.target()).as("proxy target for %s via %s", path, hops).isNotNull();
        assertThat(NGINX_SERVICES.get(last.target())).as("service for %s via %s", path, hops)
                .isEqualTo(route.service());
        assertThat(last.forwardedPath()).as("path arriving at the service, via %s", hops).isEqualTo(path);
        if (route.prodHops() == 2) {
            assertThat(hops.get(0).location()).isEqualTo("/");
            assertThat(hops.get(0).target()).isEqualTo(FRONTEND_CONTAINER);
        }
    }

    @ParameterizedTest(name = "dev: {0}")
    @MethodSource("routes")
    void dev_eachPrefixReachesItsService(Route route) {
        ViteProxy proxy = ViteProxy.match(viteProxies, route.prefix() + "probe");

        assertThat(proxy).as("Vite proxy entry for %s", route.prefix()).isNotNull();
        assertThat(VITE_SERVICES.get(proxy.target())).as("Vite entry %s", proxy).isEqualTo(route.service());
    }

    /** Why the admin reads moved (NO-02): this prefix belongs to billops on both paths. */
    @Test
    void oldAdminPrefix_goesToBillops_soNothingOnXamopsServiceMayUseIt() {
        String path = "/api/admin/autospotting/total-savings";

        List<Hop> hops = resolveProd(path);
        ViteProxy proxy = ViteProxy.match(viteProxies, path);

        assertThat(hops).hasSize(1);
        assertThat(hops.get(0).target()).isNotNull();
        assertThat(NGINX_SERVICES.get(hops.get(0).target())).isEqualTo(Service.BILLOPS_SERVICE);
        assertThat(proxy).isNotNull();
        assertThat(VITE_SERVICES.get(proxy.target())).isEqualTo(Service.BILLOPS_SERVICE);
    }

    // ── nginx ───────────────────────────────────────────────────────────────

    /** One proxy hop: the location that matched, the container it went to, and the path it sent. */
    record Hop(String location, String target, String forwardedPath) {
    }

    private record Token(String text, boolean quoted) {
        boolean is(String symbol) {
            return !quoted && text.equals(symbol);
        }
    }

    private record Directive(String name, List<String> args) {
    }

    private record Block(String name, List<String> args, List<Object> children) {
    }

    /** The subset of nginx this needs: upstream blocks and one server block's locations. */
    static final class Nginx {
        private final Map<String, String> upstreams;
        private final List<Block> locations;

        private Nginx(Map<String, String> upstreams, List<Block> locations) {
            this.upstreams = upstreams;
            this.locations = locations;
        }

        static Nginx parse(String text) {
            List<Object> top = parseNodes(tokenize(text), new int[] { 0 }, true);
            Map<String, String> upstreams = new java.util.HashMap<>();
            List<Block> servers = new ArrayList<>();
            for (Object node : top) {
                if (!(node instanceof Block block)) continue;
                if (block.name().equals("upstream")) {
                    List<Directive> members = directives(block, "server");
                    if (members.size() != 1) {
                        throw new IllegalStateException("upstream " + block.args() + " has " + members.size() + " servers");
                    }
                    upstreams.put(block.args().get(0), members.get(0).args().get(0));
                } else if (block.name().equals("server")) {
                    servers.add(block);
                }
            }
            if (servers.size() != 1) {
                throw new IllegalStateException("expected one server block, found " + servers.size());
            }
            List<Block> locations = new ArrayList<>();
            for (Object node : servers.get(0).children()) {
                if (node instanceof Block block && block.name().equals("location")) {
                    locations.add(block);
                }
            }
            return new Nginx(upstreams, locations);
        }

        /** nginx's choice: exact match, else the longest prefix unless a regex matches first. */
        Hop resolve(String path) {
            Block longest = null;
            String longestPrefix = null;
            for (Block location : locations) {
                List<String> args = location.args();
                if (args.size() == 2 && args.get(0).equals("=") && args.get(1).equals(path)) {
                    return hop(location, args.get(1), path);
                }
                boolean prefix = args.size() == 1 || (args.size() == 2 && args.get(0).equals("^~"));
                String value = args.get(args.size() - 1);
                if (prefix && path.startsWith(value) && (longestPrefix == null || value.length() > longestPrefix.length())) {
                    longest = location;
                    longestPrefix = value;
                }
            }
            boolean stopRegex = longest != null && longest.args().size() == 2;
            if (!stopRegex) {
                for (Block location : locations) {
                    List<String> args = location.args();
                    if (args.size() == 2 && (args.get(0).equals("~") || args.get(0).equals("~*"))) {
                        int flags = args.get(0).equals("~*") ? Pattern.CASE_INSENSITIVE : 0;
                        if (Pattern.compile(args.get(1), flags).matcher(path).find()) {
                            return hop(location, null, path);
                        }
                    }
                }
            }
            if (longest == null) {
                throw new IllegalStateException("no location matches " + path);
            }
            return hop(longest, longestPrefix, path);
        }

        private Hop hop(Block location, String prefix, String path) {
            String locationText = String.join(" ", location.args());
            List<Directive> proxyPass = directives(location, "proxy_pass");
            if (proxyPass.size() != 1) {
                return new Hop(locationText, null, path);
            }
            Matcher url = Pattern.compile("https?://([^/]+)(/.*)?").matcher(proxyPass.get(0).args().get(0));
            if (!url.matches()) {
                throw new IllegalStateException("unreadable proxy_pass in location " + locationText);
            }
            String host = url.group(1);
            String target = host.contains(":") ? host : upstreams.get(host);
            if (target == null) {
                throw new IllegalStateException("proxy_pass to unknown upstream " + host);
            }
            String uri = url.group(2);
            if (uri != null && prefix == null) {
                throw new IllegalStateException("proxy_pass with a URI in regex location " + locationText);
            }
            // With a URI part, nginx replaces the matched location prefix with it.
            String forwarded = uri == null ? path : uri + path.substring(prefix.length());
            return new Hop(locationText, target, forwarded);
        }

        private static List<Directive> directives(Block block, String name) {
            List<Directive> found = new ArrayList<>();
            for (Object node : block.children()) {
                if (node instanceof Directive directive && directive.name().equals(name)) {
                    found.add(directive);
                }
            }
            return found;
        }

        private static List<Token> tokenize(String text) {
            List<Token> tokens = new ArrayList<>();
            int i = 0;
            while (i < text.length()) {
                char c = text.charAt(i);
                if (Character.isWhitespace(c)) {
                    i++;
                } else if (c == '#') {
                    while (i < text.length() && text.charAt(i) != '\n') i++;
                } else if (c == '{' || c == '}' || c == ';') {
                    tokens.add(new Token(String.valueOf(c), false));
                    i++;
                } else if (c == '"' || c == '\'') {
                    StringBuilder value = new StringBuilder();
                    int j = i + 1;
                    while (j < text.length() && text.charAt(j) != c) {
                        if (text.charAt(j) == '\\' && j + 1 < text.length()) j++;
                        value.append(text.charAt(j++));
                    }
                    tokens.add(new Token(value.toString(), true));
                    i = j + 1;
                } else {
                    int j = i;
                    while (j < text.length() && !Character.isWhitespace(text.charAt(j)) && "{};".indexOf(text.charAt(j)) < 0) j++;
                    tokens.add(new Token(text.substring(i, j), false));
                    i = j;
                }
            }
            return tokens;
        }

        private static List<Object> parseNodes(List<Token> tokens, int[] pos, boolean topLevel) {
            List<Object> nodes = new ArrayList<>();
            while (pos[0] < tokens.size()) {
                if (tokens.get(pos[0]).is("}")) {
                    if (topLevel) throw new IllegalStateException("unbalanced }");
                    pos[0]++;
                    return nodes;
                }
                List<String> words = new ArrayList<>();
                while (true) {
                    if (pos[0] >= tokens.size()) throw new IllegalStateException("unterminated directive " + words);
                    Token token = tokens.get(pos[0]++);
                    if (token.is(";")) {
                        nodes.add(new Directive(words.get(0), List.copyOf(words.subList(1, words.size()))));
                        break;
                    }
                    if (token.is("{")) {
                        List<String> args = List.copyOf(words.subList(1, words.size()));
                        nodes.add(new Block(words.get(0), args, parseNodes(tokens, pos, false)));
                        break;
                    }
                    words.add(token.text());
                }
            }
            if (!topLevel) throw new IllegalStateException("unterminated block");
            return nodes;
        }
    }

    // ── Vite ────────────────────────────────────────────────────────────────

    /** One {@code server.proxy} entry: its context key and the target it resolves to. */
    record ViteProxy(String key, String target) {

        /** Vite's rule: the first key, in declaration order, that the URL starts with ({@code ^}: regex). */
        static ViteProxy match(List<ViteProxy> proxies, String url) {
            for (ViteProxy proxy : proxies) {
                boolean matches = proxy.key().startsWith("^")
                        ? Pattern.compile(proxy.key()).matcher(url).find()
                        : url.startsWith(proxy.key());
                if (matches) return proxy;
            }
            return null;
        }

        static List<ViteProxy> parse(String source) {
            Matcher authPort = Pattern.compile("const\\s+authPort\\s*=\\s*process\\.env\\.AUTH_PORT\\s*\\|\\|\\s*'(\\d+)'")
                    .matcher(source);
            String authTarget = authPort.find() ? "http://localhost:" + authPort.group(1) : null;

            Matcher start = Pattern.compile("\\bproxy\\s*:\\s*\\{").matcher(source);
            if (!start.find()) throw new IllegalStateException("no server.proxy block in vite.config.ts");
            JsScanner scanner = new JsScanner(source, start.end());
            List<ViteProxy> proxies = new ArrayList<>();
            while (true) {
                scanner.skipSpaceAndComments();
                if (scanner.peek() == '}') break;
                String key = scanner.readKey();
                scanner.skipSpaceAndComments();
                scanner.expect(':');
                scanner.skipSpaceAndComments();
                String value = scanner.readValue();
                if (value.startsWith("{")) {
                    Matcher target = Pattern.compile("\\btarget\\s*:\\s*(?:'([^']*)'|\"([^\"]*)\"|([A-Za-z_$][\\w$]*))")
                            .matcher(JsScanner.withoutComments(value));
                    if (target.find()) {
                        String resolved = target.group(1) != null ? target.group(1)
                                : target.group(2) != null ? target.group(2)
                                : "authTarget".equals(target.group(3)) ? authTarget : null;
                        if (resolved == null) {
                            throw new IllegalStateException("unresolved target for Vite proxy " + key);
                        }
                        proxies.add(new ViteProxy(key, resolved));
                    }
                }
                scanner.skipSpaceAndComments();
                if (scanner.peek() == ',') scanner.advance();
            }
            return proxies;
        }
    }

    /** Just enough JavaScript scanning to walk an object literal: strings, comments and nesting. */
    private static final class JsScanner {
        private final String src;
        private int pos;

        JsScanner(String src, int pos) {
            this.src = src;
            this.pos = pos;
        }

        char peek() {
            if (pos >= src.length()) throw new IllegalStateException("unexpected end of vite.config.ts");
            return src.charAt(pos);
        }

        void advance() {
            pos++;
        }

        void expect(char c) {
            if (peek() != c) throw new IllegalStateException("expected '" + c + "' at offset " + pos);
            pos++;
        }

        void skipSpaceAndComments() {
            while (pos < src.length()) {
                char c = src.charAt(pos);
                if (Character.isWhitespace(c)) {
                    pos++;
                } else if (src.startsWith("//", pos)) {
                    while (pos < src.length() && src.charAt(pos) != '\n') pos++;
                } else if (src.startsWith("/*", pos)) {
                    int end = src.indexOf("*/", pos + 2);
                    pos = end < 0 ? src.length() : end + 2;
                } else {
                    return;
                }
            }
        }

        String readKey() {
            char c = peek();
            if (c == '\'' || c == '"') {
                int from = pos + 1;
                skipString();
                return src.substring(from, pos - 1);
            }
            int from = pos;
            while (pos < src.length() && (Character.isLetterOrDigit(src.charAt(pos)) || src.charAt(pos) == '_'
                    || src.charAt(pos) == '$')) pos++;
            if (from == pos) throw new IllegalStateException("expected a key at offset " + pos);
            return src.substring(from, pos);
        }

        /** The value's source text, up to the ',' or '}' that ends it at this nesting level. */
        String readValue() {
            int from = pos;
            int depth = 0;
            while (pos < src.length()) {
                char c = src.charAt(pos);
                if (c == '\'' || c == '"' || c == '`') {
                    skipString();
                } else if (src.startsWith("//", pos) || src.startsWith("/*", pos)) {
                    skipSpaceAndComments();
                } else if (c == '{' || c == '(' || c == '[') {
                    depth++;
                    pos++;
                } else if (c == '}' || c == ')' || c == ']') {
                    if (depth == 0) break;
                    depth--;
                    pos++;
                } else if (c == ',' && depth == 0) {
                    break;
                } else {
                    pos++;
                }
            }
            return src.substring(from, pos).trim();
        }

        private void skipString() {
            char quote = src.charAt(pos++);
            while (pos < src.length() && src.charAt(pos) != quote) {
                if (src.charAt(pos) == '\\') pos++;
                pos++;
            }
            pos++;
        }

        static String withoutComments(String text) {
            StringBuilder out = new StringBuilder();
            JsScanner scanner = new JsScanner(text, 0);
            while (scanner.pos < text.length()) {
                char c = text.charAt(scanner.pos);
                if (c == '\'' || c == '"' || c == '`') {
                    int from = scanner.pos;
                    scanner.skipString();
                    out.append(text, from, Math.min(scanner.pos, text.length()));
                } else if (text.startsWith("//", scanner.pos) || text.startsWith("/*", scanner.pos)) {
                    scanner.skipSpaceAndComments();
                    out.append(' ');
                } else {
                    out.append(c);
                    scanner.pos++;
                }
            }
            return out.toString();
        }
    }
}
