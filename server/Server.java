import com.google.gson.*;
import com.jlc.openapi.core.cipher.auth.Authenticator;
import com.jlc.openapi.core.cipher.signer.SignAlgorithmType;
import com.jlc.openapi.core.client.JopApiConfiguration;
import com.jlc.openapi.core.client.profile.AuthProfile;
import com.jlc.openapi.core.client.profile.HttpProfile;
import com.jlc.openapi.core.http.RuntimeOptions;
import com.jlc.overseas.openapi.tdp.client.TdpOrderApiClient;
import com.jlc.overseas.openapi.tdp.request.GoodsCalculatePriceRequest;
import com.jlc.overseas.openapi.tdp.request.UploadTdpFileRequest;
import com.jlc.overseas.openapi.tdp.request.data.CustomerAddress;
import com.jlc.overseas.openapi.tdp.request.data.FileAnalysisResultRequest;
import com.jlc.overseas.openapi.tdp.response.GoodsCalculatePriceResponse;
import com.jlc.overseas.openapi.tdp.response.TdpFileUploadResponse;
import com.jlc.overseas.openapi.tdp.response.data.*;
import com.sun.net.httpserver.*;

import java.io.*;
import java.math.*;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.MessageDigest;
import java.time.*;
import java.util.zip.GZIPOutputStream;
import java.security.SecureRandom;
import java.util.*;
import java.util.concurrent.*;
import java.util.regex.*;

/**
 * 3DShop.lk quote server.
 * Run from the 3dshop folder:   java -cp "server/lib/*" server/Server.java
 * Secrets live ONLY in server/config.properties and server/keys/ - never in the website files.
 */
public class Server {
    static final Path ROOT = Paths.get("").toAbsolutePath();               // website folder (run from here)
    static final Path SRV = ROOT.resolve("server");
    static final Path DATA = Paths.get(System.getenv().getOrDefault("DATA_DIR", SRV.resolve("data").toString())).toAbsolutePath();
    static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    static final Gson COMPACT = new Gson();
    static final Properties CFG = new Properties();
    static final SecureRandom RNG = new SecureRandom();
    static final Set<String> STATIC_EXT = Set.of("html", "css", "js", "png", "jpg", "jpeg", "webp", "svg", "ico", "txt", "webmanifest");
    static final Set<String> TEXT_EXT = Set.of("html", "css", "js", "svg", "txt", "xml", "webmanifest", "json");
    static final String SITE_CSP = "default-src 'self'; script-src 'self'; style-src 'self' 'unsafe-inline' https://fonts.googleapis.com; "
            + "font-src https://fonts.gstatic.com; img-src 'self' data: blob:; connect-src 'self'; worker-src 'self' blob:; "
            + "object-src 'none'; base-uri 'self'; form-action 'self'; frame-ancestors 'none'";
    static final String ADMIN_CSP = "default-src 'none'; script-src 'self'; style-src 'self'; img-src 'self' data:; connect-src 'self'; "
            + "object-src 'none'; base-uri 'none'; form-action 'self'; frame-ancestors 'none'";
    static final Semaphore UPLOAD_SLOTS = new Semaphore(4);                    // concurrent big uploads held in memory
    static final long SESSION_MAX_MS = 8 * 3600_000L, SESSION_IDLE_MS = 2 * 3600_000L;
    static final Set<String> MODEL_EXT = Set.of("stl", "obj", "3mf", "step", "stp");
    static final Set<String> QUOTE_FILE_EXT = Set.of("stl", "obj", "3mf", "step", "stp", "pdf", "png", "jpg", "jpeg", "webp");
    static final int MAX_QUOTE_FILES = 5;
    static final long MAX_QUOTE_TOTAL_BYTES = 100L * 1024 * 1024;
    static final Set<String> PRODUCT_IMAGE_EXT = Set.of("png", "jpg", "jpeg", "webp");
    static volatile JsonObject shopCatalog;

    // ---- admin-editable settings (persisted to server/data/settings.json)
    static class Settings {
        boolean quotesEnabled = true;
        double usdToLkr = 320;            // exchange rate applied to the JLC price
        double markupPercent = 40;        // your margin on top of the JLC price
        double fixedFeeLkr = 500;         // handling / set-up fee added per quote
        double flatShippingLkr = 600;     // added once per quote (0 to hide)
        double minOrderLkr = 1500;        // never quote below this
        int roundToLkr = 10;              // round the final price to the nearest N rupees
        int maxQty = 100;
        int maxFileMB = 50;
        int maxUploadsPerHourPerIp = 12;
        int maxQuotesPerHourPerIp = 80;
        int validityHours = 48;
        String customerNote = "Estimated price. We confirm the final price on WhatsApp before printing.";
        Map<String, Double> materialMarkup = new HashMap<>();   // materialAccessId -> markup % override
        List<String> disabledMaterials = new ArrayList<>();     // materialAccessIds hidden from customers
        Map<String, Double> materialCostLkrPerGram = new HashMap<>();
        double printerCostLkrPerMinute = 2.51;
        double estimatorPrintVolumeCm3PerMinute;
        double printingMargin = 1.40;
        double fastenerMargin = 1.30;
        double fastenerCostLkrPerPart;
        double labourMonthlySalaryLkr = 65000;
        double labourCostLkrPerMinute = 6;
        double estimatorSetupFeeLkr;
        double estimatorMinJobLkr = 1500;
        int estimatorRoundToLkr = 10;
        Settings() {
            materialCostLkrPerGram.put("plaPlus", 4.90);
            materialCostLkrPerGram.put("absPlus", 4.60);
            materialCostLkrPerGram.put("tpu", 9.00);
            materialCostLkrPerGram.put("petg", 5.00);
            materialCostLkrPerGram.put("asa", 6.20);
        }
    }
    static volatile Settings settings = new Settings();
    static final Object LOCK = new Object();

    static TdpOrderApiClient jlc;
    static final Map<String, JsonObject> materialsSeen = new ConcurrentHashMap<>();   // materialAccessId -> {id,name}
    static final Map<String, long[]> sessions = new ConcurrentHashMap<>();            // admin token -> {absolute expiry, last seen}
    static final Map<String, Deque<Long>> hits = new ConcurrentHashMap<>();

    public static void main(String[] a) throws Exception {
        Files.createDirectories(DATA);
        try (var in = Files.newInputStream(SRV.resolve("config.properties"))) { CFG.load(in); }
        catch (NoSuchFileException e) { System.err.println("Missing server/config.properties - copy config.example.properties and fill it in."); System.exit(1); }
        if (Files.exists(DATA.resolve("settings.json")))
            settings = GSON.fromJson(Files.readString(DATA.resolve("settings.json")), Settings.class);
        if (Files.exists(DATA.resolve("materials.json")))
            for (var e : JsonParser.parseString(Files.readString(DATA.resolve("materials.json"))).getAsJsonObject().entrySet())
                materialsSeen.put(e.getKey(), e.getValue().getAsJsonObject());
        Files.createDirectories(ROOT.resolve("images").resolve("products"));
        Path catalogFile = DATA.resolve("shop-catalog.json");
        if (Files.exists(catalogFile)) shopCatalog = JsonParser.parseString(Files.readString(catalogFile)).getAsJsonObject();
        else {
            shopCatalog = JsonParser.parseString(Files.readString(SRV.resolve("shop-seed.json"))).getAsJsonObject();
            Files.writeString(catalogFile, GSON.toJson(shopCatalog), StandardOpenOption.CREATE_NEW);
        }
        initJlc();

        int port = Integer.parseInt(System.getenv().getOrDefault("PORT", CFG.getProperty("port", "8080")));
        String bind = System.getenv().getOrDefault("BIND", CFG.getProperty("bind", "0.0.0.0"));
        var http = HttpServer.create(new InetSocketAddress(bind, port), 0);
        http.setExecutor(Executors.newFixedThreadPool(16));
        http.createContext("/api/", Server::api);
        http.createContext("/admin", Server::adminPage);
        http.createContext("/", Server::staticFile);
        http.start();
        var janitor = Executors.newSingleThreadScheduledExecutor(r -> { var t = new Thread(r, "janitor"); t.setDaemon(true); return t; });
        janitor.scheduleAtFixedRate(Server::cleanup, 5, 5, TimeUnit.MINUTES);
        String adminPw = System.getenv().getOrDefault("ADMIN_PASSWORD", CFG.getProperty("adminPassword", ""));
        if (adminPw.length() < 12) System.err.println("WARNING: admin password is shorter than 12 characters - admin login is DISABLED until you set a longer one.");
        if (!Boolean.parseBoolean(CFG.getProperty("cookieSecure", "false"))) System.err.println("NOTE: cookieSecure=false. Set it to true once the site is served over HTTPS.");
        System.out.println("3DShop server on http://localhost:" + port + "  (admin: /admin)");
    }

    static void initJlc() throws IOException {
        String keyFile = CFG.getProperty("privateKeyFile", "keys/private.pem");
        Path kp = SRV.resolve(keyFile);
        if (!Files.exists(kp)) { System.err.println("WARNING: private key not found at " + kp + " - JLC calls will fail until you add it."); return; }
        String key = Files.readString(kp).replaceAll("-----(BEGIN|END)[A-Z ]*-----", "").replaceAll("\\s", "");
        var auth = new AuthProfile.Builder()
                .appId(CFG.getProperty("appId"))
                .authenticator(Authenticator.JOP)
                .signAlgorithm(SignAlgorithmType.RSA_SHA256)
                .accessKey(CFG.getProperty("accessKey"))
                .secretKey(key)
                .build();
        var opts = new RuntimeOptions.Builder().connectTimeoutInSeconds(15).readTimeoutInSeconds(60).writeTimeoutInSeconds(120).build();
        var httpP = new HttpProfile.Builder().endpoint(CFG.getProperty("endpoint", "https://openapi.jlc.com")).runtimeOptions(opts).build();
        jlc = new TdpOrderApiClient(new JopApiConfiguration.Builder().auth(auth).http(httpP).debug(false).build());
    }

    // =============================== API router
    static class BadRequest extends RuntimeException {
        final int code;
        BadRequest(String message, int code) { super(message); this.code = code; }
    }

    static void api(HttpExchange ex) throws IOException {
        try {
            secure(ex, false);
            String p = ex.getRequestURI().getPath(), m = ex.getRequestMethod();
            if (m.equals("OPTIONS")) { cors(ex); ex.sendResponseHeaders(204, -1); return; }
            if (!allow(ip(ex), "api", 600, 60_000L)) { json(ex, 429, err("Too many requests. Please slow down.")); return; }
            boolean adminRoute = p.startsWith("/api/admin/");
            if (adminRoute && !adminIpAllowed(ex)) { json(ex, 404, err("Not found")); return; }
            if (!m.equals("GET") && !m.equals("HEAD")) {
                if (!originOk(ex)) { json(ex, 403, err("Cross-site request blocked.")); return; }
                if (adminRoute && !p.equals("/api/admin/login") && ex.getRequestHeaders().getFirst("X-Requested-With") == null) {
                    json(ex, 403, err("Missing request header.")); return;
                }
            }
            if (m.equals("GET") && p.startsWith("/api/admin/quote-files/")) {
                if (admin(ex)) adminQuoteFile(ex, p);
                return;
            }
            switch (m + " " + p) {
                case "GET /api/config" -> publicConfig(ex);
                case "GET /api/shop/catalog" -> json(ex, 200, shopCatalog);
                case "GET /api/shop/catalog.js" -> publicShopCatalogScript(ex);
                case "POST /api/estimate" -> estimate(ex);
                case "POST /api/quote-requests" -> createQuoteRequest(ex);
                case "POST /api/upload" -> upload(ex);
                case "POST /api/quote" -> quote(ex);
                case "POST /api/admin/login" -> adminLogin(ex);
                case "POST /api/admin/logout" -> adminLogout(ex);
                case "GET /api/admin/settings" -> { if (admin(ex)) json(ex, 200, withMaterials()); }
                case "PUT /api/admin/settings" -> { if (admin(ex)) saveSettings(ex); }
                case "GET /api/admin/shop/catalog" -> { if (admin(ex)) json(ex, 200, shopCatalog); }
                case "PUT /api/admin/shop/catalog" -> { if (admin(ex)) saveShopCatalog(ex); }
                case "POST /api/admin/shop/image" -> { if (admin(ex)) uploadProductImage(ex); }
                case "GET /api/admin/quotes" -> { if (admin(ex)) recentQuotes(ex); }
                case "GET /api/admin/quote-requests" -> { if (admin(ex)) recentQuoteRequests(ex); }
                default -> json(ex, 404, err("Not found"));
            }
        } catch (BadRequest e) {
            json(ex, e.code, err(e.getMessage()));
        } catch (JsonParseException | ClassCastException | IllegalStateException | NumberFormatException | UnsupportedOperationException | NullPointerException e) {
            json(ex, 400, err("Invalid request."));
        } catch (IOException e) {
            if (e.getMessage() != null && e.getMessage().startsWith("Malformed")) json(ex, 400, err("Invalid upload."));
            else { e.printStackTrace(); json(ex, 500, err("Server error. Please try again.")); }
        } catch (Exception e) {
            e.printStackTrace();
            json(ex, 500, err("Server error. Please try again."));
        }
    }

    // ---- security helpers
    static void secure(HttpExchange ex, boolean adminPage) {
        var h = ex.getResponseHeaders();
        h.set("X-Content-Type-Options", "nosniff");
        h.set("X-Frame-Options", "DENY");
        h.set("Referrer-Policy", "strict-origin-when-cross-origin");
        h.set("Permissions-Policy", "camera=(), microphone=(), geolocation=(), payment=(), usb=(), interest-cohort=()");
        h.set("Content-Security-Policy", adminPage ? ADMIN_CSP : SITE_CSP);
        if (secureCookies(ex)) h.set("Strict-Transport-Security", "max-age=31536000; includeSubDomains");
    }

    static boolean secureCookies(HttpExchange ex) {
        if (Boolean.parseBoolean(CFG.getProperty("cookieSecure", "false"))) return true;
        return trustProxy() && "https".equalsIgnoreCase(String.valueOf(ex.getRequestHeaders().getFirst("X-Forwarded-Proto")));
    }

    static boolean trustProxy() { return Boolean.parseBoolean(CFG.getProperty("trustProxy", "false")); }

    static String site() { return CFG.getProperty("siteUrl", "https://3dshop.lk").trim().replaceAll("/+$", ""); }

    /** Browsers send Origin on cross-site writes; reject it unless it is this site. */
    static boolean originOk(HttpExchange ex) {
        String origin = ex.getRequestHeaders().getFirst("Origin");
        if (origin == null || origin.isBlank()) return true;
        try {
            var u = java.net.URI.create(origin);
            String host = u.getHost();
            if (host == null) return false;
            String hostPort = u.getPort() > 0 ? host + ":" + u.getPort() : host;
            String reqHost = ex.getRequestHeaders().getFirst("Host");
            if (reqHost != null && reqHost.equalsIgnoreCase(hostPort)) return true;
            String cors = CFG.getProperty("corsOrigin", "");
            if (!cors.isBlank() && cors.equals(origin)) return true;
            String siteHost = java.net.URI.create(site()).getHost();
            return siteHost != null && (host.equalsIgnoreCase(siteHost) || host.equalsIgnoreCase("www." + siteHost));
        } catch (RuntimeException e) { return false; }
    }

    /** Optional: adminAllowIps=203.0.113.5,::1 restricts /admin and /api/admin to those addresses. */
    static boolean adminIpAllowed(HttpExchange ex) {
        String list = CFG.getProperty("adminAllowIps", "").trim();
        if (list.isEmpty()) return true;
        String ip = ip(ex);
        for (String allowed : list.split(",")) if (allowed.trim().equals(ip)) return true;
        return false;
    }

    static JsonObject jsonBody(HttpExchange ex, int max) throws IOException {
        if (tooLarge(ex, max)) throw new BadRequest("Request is too large.", 413);
        byte[] b = readLimited(ex.getRequestBody(), max);
        if (b == null) throw new BadRequest("Request is too large.", 413);
        try {
            JsonElement e = JsonParser.parseString(new String(b, StandardCharsets.UTF_8));
            if (!e.isJsonObject()) throw new BadRequest("Invalid request.", 400);
            return e.getAsJsonObject();
        } catch (JsonParseException e) { throw new BadRequest("Invalid request.", 400); }
    }

    static boolean tooLarge(HttpExchange ex, long max) {
        String cl = ex.getRequestHeaders().getFirst("Content-Length");
        if (cl == null) return false;
        try { return Long.parseLong(cl.trim()) > max; } catch (NumberFormatException e) { return true; }
    }

    static byte[] sha256(String v) {
        try { return MessageDigest.getInstance("SHA-256").digest(v.getBytes(StandardCharsets.UTF_8)); }
        catch (java.security.NoSuchAlgorithmException e) { throw new IllegalStateException(e); }
    }

    static void cleanup() {
        long now = System.currentTimeMillis();
        sessions.entrySet().removeIf(e -> e.getValue()[0] < now || now - e.getValue()[1] > SESSION_IDLE_MS);
        hits.entrySet().removeIf(e -> {
            var q = e.getValue();
            synchronized (q) { while (!q.isEmpty() && now - q.peekFirst() > 3600_000L) q.pollFirst(); return q.isEmpty(); }
        });
    }

    static void publicConfig(HttpExchange ex) throws IOException {
        var s = settings;
        JsonObject o = new JsonObject();
        o.addProperty("enabled", s.quotesEnabled && jlc != null);
        o.addProperty("maxFileMB", s.maxFileMB);
        o.addProperty("maxQty", s.maxQty);
        o.addProperty("validityHours", s.validityHours);
        o.addProperty("note", s.customerNote);
        o.add("formats", COMPACT.toJsonTree(MODEL_EXT));
        json(ex, 200, o);
    }

    static void publicShopCatalogScript(HttpExchange ex) throws IOException {
        String catalog = GSON.toJson(shopCatalog).replace("<", "\\u003c").replace("\u2028", "\\u2028").replace("\u2029", "\\u2029");
        byte[] body = ("window.SHOP_CATALOG=" + catalog + ";").getBytes(StandardCharsets.UTF_8);
        ex.getResponseHeaders().set("Content-Type", "text/javascript; charset=utf-8");
        ex.getResponseHeaders().add("Cache-Control", "no-store");
        
        ex.sendResponseHeaders(200, body.length);
        try (var out = ex.getResponseBody()) { out.write(body); }
    }

    static void saveShopCatalog(HttpExchange ex) throws IOException {
        JsonObject next = jsonBody(ex, 1_000_000);
        String catalogError = shopCatalogValidationError(next);
        if (catalogError != null) { json(ex, 400, err(catalogError)); return; }
        next = cleanCatalog(next);
        synchronized (LOCK) {
            writeAtomic(DATA.resolve("shop-catalog.json"), GSON.toJson(next));
            shopCatalog = next;
        }
        json(ex, 200, ok());
    }

    /** Rebuilds the catalog from known fields only, so nothing unexpected is ever stored or published. */
    static JsonObject cleanCatalog(JsonObject in) {
        JsonObject out = new JsonObject(), cats = new JsonObject();
        for (var e : in.getAsJsonObject("categories").entrySet()) {
            JsonArray subs = new JsonArray();
            for (JsonElement sub : e.getValue().getAsJsonArray()) subs.add(plain(sub.getAsString()));
            cats.add(plain(e.getKey()), subs);
        }
        JsonArray products = new JsonArray();
        for (JsonElement element : in.getAsJsonArray("products")) {
            JsonObject p = element.getAsJsonObject(), c = new JsonObject();
            c.addProperty("id", p.get("id").getAsInt());
            c.addProperty("name", plain(str(p, "name")));
            c.addProperty("cat", plain(str(p, "cat")));
            c.addProperty("sub", plain(str(p, "sub")));
            c.addProperty("price", number(p, "price"));
            double old = p.has("old") ? number(p, "old") : 0;
            if (old > 0) c.addProperty("old", old);
            JsonArray tags = new JsonArray();
            for (JsonElement tag : p.getAsJsonArray("tags")) tags.add(tag.getAsString());
            c.add("tags", tags);
            c.addProperty("stock", str(p, "stock"));
            c.addProperty("desc", plain(str(p, "desc")));
            String img = str(p, "img");
            if (!img.isBlank()) c.addProperty("img", img);
            double rating = number(p, "rating"), reviews = number(p, "reviews"), sold = number(p, "sold");
            if (rating > 0 && rating <= 5) c.addProperty("rating", rating);
            if (reviews > 0 && reviews < 1e9) c.addProperty("reviews", (long) reviews);
            if (sold > 0 && sold < 1e9) c.addProperty("sold", (long) sold);
            if (p.has("free") && p.get("free").isJsonPrimitive() && p.get("free").getAsJsonPrimitive().isBoolean() && p.get("free").getAsBoolean()) c.addProperty("free", true);
            products.add(c);
        }
        out.add("categories", cats);
        out.add("products", products);
        return out;
    }

    /** Strips control characters and trims; output is always HTML-escaped where it is rendered. */
    static String plain(String v) { return v == null ? "" : v.replaceAll("[\\p{Cntrl}&&[^\n]]", "").trim(); }

    static String shopCatalogValidationError(JsonObject catalog) {
        if (!catalog.has("categories") || !catalog.get("categories").isJsonObject()
                || !catalog.has("products") || !catalog.get("products").isJsonArray()) return "Catalog must include categories and products.";
        JsonObject categories = catalog.getAsJsonObject("categories");
        JsonArray products = catalog.getAsJsonArray("products");
        if (categories.size() < 1 || categories.size() > 50 || products.size() > 500) return "Catalog supports 1-50 categories and up to 500 products.";
        Set<String> categoryNames = new HashSet<>();
        for (var entry : categories.entrySet()) {
            String name = entry.getKey().trim();
            if (name.isBlank() || name.length() > 80 || !entry.getValue().isJsonArray()) return "Category names must be 1-80 characters, with a subcategory list.";
            categoryNames.add(name);
            JsonArray subs = entry.getValue().getAsJsonArray();
            if (subs.size() > 50) return "Each category can have at most 50 subcategories.";
            Set<String> uniqueSubs = new HashSet<>();
            for (JsonElement sub : subs) {
                if (!sub.isJsonPrimitive() || !sub.getAsJsonPrimitive().isString()) return "Subcategories must be text.";
                String value = sub.getAsString().trim();
                if (value.isBlank() || value.length() > 80 || !uniqueSubs.add(value)) return "Subcategories must be unique and 1-80 characters.";
            }
        }
        Set<Integer> ids = new HashSet<>();
        for (JsonElement element : products) {
            if (!element.isJsonObject()) return "Each product must be an object.";
            JsonObject product = element.getAsJsonObject();
            if (!product.has("id") || !product.get("id").isJsonPrimitive() || !product.getAsJsonPrimitive("id").isNumber()) return "Each product needs a numeric ID.";
            int id = product.get("id").getAsInt();
            String name = str(product, "name").trim(), cat = str(product, "cat").trim(), sub = str(product, "sub").trim();
            String stock = str(product, "stock");
            double price = number(product, "price"), old = product.has("old") ? number(product, "old") : 0;
            if (id < 1 || !ids.add(id)) return "Product IDs must be unique positive numbers.";
            if (name.isBlank() || name.length() > 160) return "Product names must be 1-160 characters.";
            if (!categoryNames.contains(cat)) return "Product category not found: " + cat;
            if (!Set.of("in", "out", "pre").contains(stock)) return "Stock must be in, out or pre-order.";
            if (!Double.isFinite(price) || price <= 0) return "Product prices must be greater than zero.";
            if (!Double.isFinite(old) || old < 0) return "Previous prices must be zero or greater.";
            if (!product.has("tags") || !product.get("tags").isJsonArray()) return "Product tags must be a list.";
            if (!sub.isBlank()) {
                boolean subcategoryFound = false;
                for (JsonElement candidate : categories.getAsJsonArray(cat))
                    if (candidate.isJsonPrimitive() && candidate.getAsString().equals(sub)) { subcategoryFound = true; break; }
                if (!subcategoryFound) return "Product subcategory not found: " + sub;
            }
            if (product.has("desc") && (str(product, "desc").length() > 1000)) return "Product descriptions must be 1,000 characters or fewer.";
            for (JsonElement tag : product.getAsJsonArray("tags"))
                if (!tag.isJsonPrimitive() || !Set.of("hot", "new", "best").contains(tag.getAsString())) return "Product tags must be hot, new or best.";
            String image = str(product, "img");
            if (!image.isBlank() && (!image.equals(Paths.get(image).getFileName().toString())
                    || !PRODUCT_IMAGE_EXT.contains(image.substring(image.lastIndexOf('.') + 1).toLowerCase(Locale.ROOT)))) return "Product images must be PNG, JPG or WEBP filenames.";
        }
        return null;
    }

    static void uploadProductImage(HttpExchange ex) throws IOException {
        String contentType = ex.getRequestHeaders().getFirst("Content-Type");
        if (contentType == null || !contentType.toLowerCase(Locale.ROOT).startsWith("multipart/form-data") || !contentType.contains("boundary=")) {
            json(ex, 400, err("Expected an image upload.")); return;
        }
        String boundary = contentType.substring(contentType.indexOf("boundary=") + 9).replace("\"", "");
        long imageLimit = 5L * 1024 * 1024 + 64 * 1024;
        if (tooLarge(ex, imageLimit)) { json(ex, 413, err("Product images must be 5 MB or smaller.")); return; }
        byte[] body = readLimited(ex.getRequestBody(), imageLimit);
        if (body == null) { json(ex, 413, err("Product images must be 5 MB or smaller.")); return; }
        FormPart image = parseMultipart(body, boundary).stream().filter(part -> part.fileName() != null).findFirst().orElse(null);
        if (image == null || image.data().length == 0) { json(ex, 400, err("Choose an image to upload.")); return; }
        String original = Paths.get(image.fileName().replace('\\', '/')).getFileName().toString();
        String ext = original.contains(".") ? original.substring(original.lastIndexOf('.') + 1).toLowerCase(Locale.ROOT) : "";
        byte[] bytes = image.data();
        if (!validProductImage(ext, bytes)) { json(ex, 400, err("Use a valid PNG, JPG or WEBP image.")); return; }
        String filename = "product-" + UUID.randomUUID() + "." + ext;
        Path dir = ROOT.resolve("images").resolve("products").normalize();
        Files.createDirectories(dir);
        Files.write(dir.resolve(filename), bytes, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE);
        JsonObject result = new JsonObject(); result.addProperty("img", filename);
        json(ex, 201, result);
    }

    static boolean validProductImage(String ext, byte[] bytes) {
        if (!PRODUCT_IMAGE_EXT.contains(ext)) return false;
        if (ext.equals("png")) return bytes.length >= 8 && (bytes[0] & 255) == 0x89 && bytes[1] == 0x50 && bytes[2] == 0x4e && bytes[3] == 0x47;
        if (ext.equals("jpg") || ext.equals("jpeg")) return bytes.length >= 3 && (bytes[0] & 255) == 0xff && (bytes[1] & 255) == 0xd8 && (bytes[2] & 255) == 0xff;
        return bytes.length >= 12 && bytes[0] == 'R' && bytes[1] == 'I' && bytes[2] == 'F' && bytes[3] == 'F'
                && bytes[8] == 'W' && bytes[9] == 'E' && bytes[10] == 'B' && bytes[11] == 'P';
    }

    static void estimate(HttpExchange ex) throws IOException {
        var s = settings;
        if (!allow(ip(ex), "estimate", s.maxQuotesPerHourPerIp)) { json(ex, 429, err("Too many estimate requests. Please try again later.")); return; }
        JsonObject in = jsonBody(ex, 10_000);
        String materialKey = str(in, "materialKey"), styleKey = str(in, "styleKey");
        if (!Set.of("plaPlus", "absPlus", "tpu", "petg", "asa").contains(materialKey)) { json(ex, 400, err("Choose a valid material.")); return; }
        if (!Set.of("standard", "thin", "strong", "solid").contains(styleKey)) { json(ex, 400, err("Choose a valid build style.")); return; }
        int qty = in.has("qty") ? in.get("qty").getAsInt() : 1;
        if (qty < 1 || qty > s.maxQty) { json(ex, 400, err("Quantity must be between 1 and " + s.maxQty + ".")); return; }
        if (!in.has("parts") || !in.get("parts").isJsonArray() || in.getAsJsonArray("parts").isEmpty() || in.getAsJsonArray("parts").size() > 10) {
            json(ex, 400, err("Add between 1 and 10 STL files.")); return;
        }
        if (s.estimatorPrintVolumeCm3PerMinute <= 0) {
            json(ex, 422, err("Print throughput has not been set in the admin panel yet.")); return;
        }

        double shellMm, fill;
        switch (styleKey) {
            case "thin" -> { shellMm = 1.2; fill = 0.05; }
            case "strong" -> { shellMm = 2.0; fill = 0.30; }
            case "solid" -> { shellMm = 0; fill = 1.0; }
            default -> { shellMm = 1.6; fill = 0.15; }
        }
        double density = switch (materialKey) {
            case "absPlus" -> 1.04;
            case "tpu" -> 1.21;
            case "petg" -> 1.27;
            case "asa" -> 1.07;
            default -> 1.24;
        };
        JsonArray estimates = new JsonArray();
        double jobCost = 0, totalMinutes = 0, totalWeightG = 0, totalMaterialCost = 0;
        for (JsonElement element : in.getAsJsonArray("parts")) {
            if (!element.isJsonObject()) { json(ex, 400, err("Invalid STL geometry.")); return; }
            JsonObject part = element.getAsJsonObject();
            String units = str(part, "units");
            if (!Set.of("mm", "in").contains(units)) { json(ex, 400, err("Choose millimeters or inches.")); return; }
            double volumeNative = number(part, "volume"), areaNative = number(part, "area");
                if (!Double.isFinite(volumeNative) || !Double.isFinite(areaNative) || volumeNative < 0 || areaNative < 0
                    || (volumeNative == 0 && areaNative == 0) || volumeNative > 1e12 || areaNative > 1e14) {
                json(ex, 400, err("An STL's geometry cannot be estimated.")); return;
            }
            double unitScale = units.equals("in") ? 25.4 : 1;
            double volumeCc = volumeNative * Math.pow(unitScale, 3) / 1000;
            double areaCm2 = areaNative * Math.pow(unitScale, 2) / 100;
                double effectiveShellMm = shellMm == 0 && volumeCc == 0 ? 1.6 : shellMm;
                double shellCc = volumeCc > 0
                    ? (effectiveShellMm == 0 ? volumeCc : Math.min(areaCm2 * effectiveShellMm / 10, volumeCc * 0.92))
                    : areaCm2 * effectiveShellMm / 10;
                double printedCc = volumeCc > 0 ? shellCc + Math.max(0, volumeCc - shellCc) * fill : shellCc;
            double weightG = printedCc * density;
            double materialCost = weightG * s.materialCostLkrPerGram.getOrDefault(materialKey, 0.0);
            double minutes = printedCc / s.estimatorPrintVolumeCm3PerMinute;
            double machineCost = minutes * s.printerCostLkrPerMinute;
            double labourCost = minutes * s.labourCostLkrPerMinute;
            double partCost = (materialCost + machineCost + labourCost) * s.printingMargin
                    + s.fastenerCostLkrPerPart * s.fastenerMargin;
            jobCost += partCost * qty;
            totalMinutes += minutes * qty;
            totalWeightG += weightG * qty;
            totalMaterialCost += materialCost * qty;
            JsonObject estimate = new JsonObject();
            estimate.addProperty("name", str(part, "name"));
            estimate.addProperty("estimatedMinutes", Math.ceil(minutes));
            estimate.addProperty("estimatedWeightG", weightG);
            estimates.add(estimate);
        }
        jobCost += s.estimatorSetupFeeLkr;
        long totalPrice = roundTo(Math.max(s.estimatorMinJobLkr, jobCost), s.estimatorRoundToLkr);
        JsonObject out = new JsonObject();
        out.addProperty("totalPriceLkr", totalPrice);
        out.addProperty("estimatedMinutes", Math.ceil(totalMinutes));
        out.addProperty("estimatedWeightG", totalWeightG);
        out.addProperty("materialCostLkr", totalMaterialCost);
        out.addProperty("qty", qty);
        out.add("parts", estimates);
        json(ex, 200, out);
    }

    static void createQuoteRequest(HttpExchange ex) throws Exception {
        var s = settings;
        if (!allow(ip(ex), "manual-quote", 10) || !allow("all", "manual-quote-global", 300)) { json(ex, 429, err("Too many quote requests. Please try again later.")); return; }
        String contentType = ex.getRequestHeaders().getFirst("Content-Type");
        if (contentType == null || !contentType.toLowerCase(Locale.ROOT).startsWith("multipart/form-data") || !contentType.contains("boundary=")) {
            json(ex, 400, err("Expected a quote form submission.")); return;
        }
        String boundary = contentType.substring(contentType.indexOf("boundary=") + 9).replace("\"", "");
        if (boundary.isBlank() || boundary.length() > 200) { json(ex, 400, err("Invalid form boundary.")); return; }
        long bodyLimit = MAX_QUOTE_TOTAL_BYTES + 1024L * 1024;
        if (tooLarge(ex, bodyLimit)) { json(ex, 413, err("Quote request files exceed the 100 MB total limit.")); return; }
        if (!UPLOAD_SLOTS.tryAcquire()) { json(ex, 503, err("The server is busy. Please try again in a minute.")); return; }
        List<FormPart> parts;
        try {
            byte[] body = readLimited(ex.getRequestBody(), bodyLimit);
            if (body == null) { json(ex, 413, err("Quote request files exceed the 100 MB total limit.")); return; }
            parts = parseMultipart(body, boundary);
        } finally { UPLOAD_SLOTS.release(); }
        Map<String, String> fields = new HashMap<>();
        List<FormPart> files = new ArrayList<>();
        long totalFileBytes = 0;
        for (FormPart part : parts) {
            if (part.fileName() == null) {
                if (part.data().length > 8_000) { json(ex, 400, err("A quote field is too long.")); return; }
                fields.put(part.fieldName(), new String(part.data(), StandardCharsets.UTF_8).trim());
                continue;
            }
            if (part.fileName().isBlank() || part.data().length == 0) continue;
            if (files.size() >= MAX_QUOTE_FILES) { json(ex, 400, err("Attach no more than 5 files.")); return; }
            String originalName = Paths.get(part.fileName().replace('\\', '/')).getFileName().toString();
            String ext = originalName.contains(".") ? originalName.substring(originalName.lastIndexOf('.') + 1).toLowerCase(Locale.ROOT) : "";
            if (!QUOTE_FILE_EXT.contains(ext)) { json(ex, 400, err("Unsupported attachment type. Use STL, OBJ, 3MF, STEP, PDF, PNG, JPG or WEBP.")); return; }
            if (!attachmentLooksValid(ext, part.data())) { json(ex, 400, err("The file \"" + originalName.replaceAll("[^A-Za-z0-9._ -]", "_") + "\" does not look like a valid ." + ext + " file.")); return; }
            if (part.data().length > s.maxFileMB * 1024L * 1024L) { json(ex, 413, err("Each file must be no larger than " + s.maxFileMB + " MB.")); return; }
            totalFileBytes += part.data().length;
            if (totalFileBytes > MAX_QUOTE_TOTAL_BYTES) { json(ex, 413, err("Quote request files exceed the 100 MB total limit.")); return; }
            files.add(new FormPart("files", originalName, part.data()));
        }
        if (!fields.getOrDefault("website", "").isBlank()) {            // honeypot: real visitors never fill this in
            JsonObject fake = new JsonObject(); fake.addProperty("ref", "R" + Long.toString(System.currentTimeMillis() / 1000, 36).toUpperCase(Locale.ROOT));
            json(ex, 201, fake); return;
        }
        String name = fields.getOrDefault("name", "").trim();
        String phone = fields.getOrDefault("phone", "").trim();
        String details = fields.getOrDefault("details", "").trim();
        String type = fields.getOrDefault("type", "Print my model").trim();
        String material = fields.getOrDefault("material", "Not sure").trim();
        String colour = fields.getOrDefault("colour", "Not sure").trim();
        if (material.length() > 80 || colour.length() > 80) { json(ex, 400, err("Choose a valid material and colour.")); return; }
        name = plain(name); phone = plain(phone); material = plain(material); colour = plain(colour);
        if (name.isBlank() || name.length() > 120 || phone.isBlank() || phone.length() > 60 || details.isBlank() || details.length() > 4_000) {
            json(ex, 400, err("Enter your name, phone number and a short description (maximum 4,000 characters).")); return;
        }
        if (!Set.of("Print my model", "Design and print").contains(type)) { json(ex, 400, err("Choose a valid request type.")); return; }

        String id = UUID.randomUUID().toString();
        String ref = "R" + Long.toString(System.currentTimeMillis() / 1000, 36).toUpperCase(Locale.ROOT) + String.format("%02X", RNG.nextInt(256));
        Path fileDir = DATA.resolve("quote-files");
        Files.createDirectories(fileDir);
        JsonArray fileRecords = new JsonArray();
        List<Path> written = new ArrayList<>();
        try {
            for (int i = 0; i < files.size(); i++) {
                FormPart file = files.get(i);
                String safeName = file.fileName().replaceAll("[^A-Za-z0-9._ -]", "_");
                String ext = safeName.substring(safeName.lastIndexOf('.') + 1).toLowerCase(Locale.ROOT);
                String storedName = id + "-" + i + "." + ext;
                Path storedPath = fileDir.resolve(storedName).normalize();
                if (!storedPath.startsWith(fileDir.normalize())) throw new IOException("Invalid attachment path.");
                Files.write(storedPath, file.data(), StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE);
                written.add(storedPath);
                JsonObject f = new JsonObject();
                f.addProperty("name", safeName);
                f.addProperty("storedName", storedName);
                f.addProperty("size", file.data().length);
                fileRecords.add(f);
            }
            JsonObject request = new JsonObject();
            request.addProperty("id", id);
            request.addProperty("ref", ref);
            request.addProperty("time", java.time.Instant.now().toString());
            request.addProperty("name", name);
            request.addProperty("phone", phone);
            request.addProperty("type", type);
            request.addProperty("material", material);
            request.addProperty("colour", colour);
            request.addProperty("details", details);
            request.add("files", fileRecords);
            synchronized (LOCK) {
                Files.writeString(DATA.resolve("quote-requests.jsonl"), COMPACT.toJson(request) + "\n", StandardOpenOption.CREATE, StandardOpenOption.APPEND);
            }
            JsonObject result = new JsonObject();
            result.addProperty("ref", ref);
            json(ex, 201, result);
        } catch (Exception e) {
            for (Path path : written) Files.deleteIfExists(path);
            throw e;
        }
    }

    static boolean attachmentLooksValid(String ext, byte[] b) {
        return switch (ext) {
            case "pdf" -> b.length >= 5 && b[0] == '%' && b[1] == 'P' && b[2] == 'D' && b[3] == 'F' && b[4] == '-';
            case "png", "jpg", "jpeg", "webp" -> validProductImage(ext, b);
            case "3mf" -> b.length >= 4 && b[0] == 'P' && b[1] == 'K';          // 3MF is a zip container
            default -> true;                                                       // STL / OBJ / STEP have no reliable signature
        };
    }

    // =============================== customer: upload STL -> analysis -> materials
    static void upload(HttpExchange ex) throws Exception {
        var s = settings;
        if (!s.quotesEnabled || jlc == null) { json(ex, 503, err("Online quotes are paused. Please request a quote on WhatsApp.")); return; }
        if (!allow(ip(ex), "up", s.maxUploadsPerHourPerIp)) { json(ex, 429, err("Too many uploads. Please try again later or message us on WhatsApp.")); return; }
        long max = s.maxFileMB * 1024L * 1024L;
        String ct = ex.getRequestHeaders().getFirst("Content-Type");
        if (ct == null || !ct.toLowerCase(Locale.ROOT).startsWith("multipart/form-data") || !ct.contains("boundary=")) { json(ex, 400, err("Expected a file upload.")); return; }
        if (tooLarge(ex, max + 64 * 1024)) { json(ex, 413, err("File is too large (max " + s.maxFileMB + " MB).")); return; }
        if (!UPLOAD_SLOTS.tryAcquire()) { json(ex, 503, err("The server is busy. Please try again in a minute.")); return; }
        byte[] body;
        try { body = readLimited(ex.getRequestBody(), max + 64 * 1024); } finally { UPLOAD_SLOTS.release(); }
        if (body == null) { json(ex, 413, err("File is too large (max " + s.maxFileMB + " MB).")); return; }
        String boundary = ct.substring(ct.indexOf("boundary=") + 9).replace("\"", "");
        Part part = parseFile(body, boundary);
        if (part == null || part.data.length == 0) { json(ex, 400, err("No file received.")); return; }
        String name = part.name.replaceAll("[^A-Za-z0-9._ -]", "_");
        if (name.isBlank()) name = "model.stl";
        String ext = name.contains(".") ? name.substring(name.lastIndexOf('.') + 1).toLowerCase() : "";
        if (!MODEL_EXT.contains(ext)) { json(ex, 400, err("Unsupported file type. Please upload " + String.join(", ", new TreeSet<>(MODEL_EXT)).toUpperCase() + ".")); return; }

        Path tmp = Files.createTempFile("model-", "." + ext);
        try {
            Files.write(tmp, part.data);
            var req = new UploadTdpFileRequest();
            req.setFile(tmp.toFile());
            req.setFileName(name);
            var up = jlc.uploadTdpFile(req);
            if (up == null || !up.isSuccessful() || up.getData() == null) {
                System.err.println("JLC upload failed: " + (up == null ? "null" : up.getCode() + " " + up.getMessage()));
                json(ex, 502, err("Could not process this file. Please check it and try again.")); return;
            }
            String fileId = up.getData();
            TdpFileUploadResult r = null;
            for (int i = 0; i < 40; i++) {
                TdpFileUploadResponse an = jlc.fileAnalysisResult(analysisReq(fileId));
                if (an != null && an.isSuccessful() && an.getData() != null && Boolean.TRUE.equals(an.getData().getFinishFlag())) { r = an.getData(); break; }
                Thread.sleep(1500);
            }
            if (r == null) { json(ex, 504, err("Analysis is taking too long. Please try again in a moment.")); return; }

            JsonObject o = new JsonObject();
            o.addProperty("fileAccessId", fileId);
            o.addProperty("modelAccessId", r.getModelAccessId());
            o.addProperty("fileName", name);
            o.addProperty("sizeX", num(r.getSizeX())); o.addProperty("sizeY", num(r.getSizeY())); o.addProperty("sizeZ", num(r.getSizeZ()));
            o.addProperty("volumeCm3", r.getModelVolume() == null ? 0 : r.getModelVolume().doubleValue() / 1000.0);
            o.addProperty("thumbnailUrl", r.getThumbnailUrl());
            JsonArray mats = new JsonArray();
            if (r.getThreeDimensionsMaterials() != null) for (var mt : r.getThreeDimensionsMaterials()) {
                if (mt.getMaterialAccessId() == null) continue;
                JsonObject seen = new JsonObject();
                seen.addProperty("id", mt.getMaterialAccessId());
                seen.addProperty("name", mt.getMaterialName());
                rememberMaterial(seen);
                if (s.disabledMaterials.contains(mt.getMaterialAccessId())) continue;
                if (Boolean.FALSE.equals(mt.getEnableFlag())) continue;
                JsonObject mo = new JsonObject();
                mo.addProperty("id", mt.getMaterialAccessId());
                mo.addProperty("name", mt.getMaterialName());
                mo.addProperty("info", mt.getMaterialIntroduce());
                mo.addProperty("recommended", Boolean.TRUE.equals(mt.getRecommendFlag()));
                JsonArray cols = new JsonArray();
                if (mt.getMaterialColorList() != null) for (var c : mt.getMaterialColorList()) {
                    JsonObject co = new JsonObject();
                    co.addProperty("id", c.getMaterialColorAccessId());
                    co.addProperty("name", c.getMaterialColor());
                    co.addProperty("hex", c.getMaterialColorValue());
                    cols.add(co);
                }
                mo.add("colors", cols);
                mats.add(mo);
            }
            o.add("materials", mats);
            json(ex, 200, o);
        } finally { Files.deleteIfExists(tmp); }
    }

    static FileAnalysisResultRequest analysisReq(String id) { var q = new FileAnalysisResultRequest(); q.setFileAccessId(id); return q; }

    // =============================== customer: price
    static void quote(HttpExchange ex) throws Exception {
        var s = settings;
        if (!s.quotesEnabled || jlc == null) { json(ex, 503, err("Online quotes are paused. Please request a quote on WhatsApp.")); return; }
        if (!allow(ip(ex), "q", s.maxQuotesPerHourPerIp)) { json(ex, 429, err("Too many requests. Please try again later.")); return; }
        JsonObject in = jsonBody(ex, 20_000);
        String fileId = str(in, "fileAccessId"), modelId = str(in, "modelAccessId"), fileName = str(in, "fileName"),
                matId = str(in, "materialAccessId"), colId = str(in, "colorAccessId");
        int qty = in.has("qty") ? in.get("qty").getAsInt() : 1;
        if (fileId.isBlank() || matId.isBlank() || colId.isBlank()) { json(ex, 400, err("Please choose a material and colour.")); return; }
        if (qty < 1 || qty > s.maxQty) { json(ex, 400, err("Quantity must be between 1 and " + s.maxQty + ".")); return; }
        if (s.disabledMaterials.contains(matId)) { json(ex, 400, err("This material is not available.")); return; }

        var q = new GoodsCalculatePriceRequest();
        q.setFileAccessId(fileId); q.setModelAccessId(modelId); q.setFileName(fileName); q.setItemName(fileName);
        q.setMaterialAccessId(matId); q.setMaterialColorAccessId(colId); q.setItemCount(qty);
        q.setCraftShoppingCartDTOList(new ArrayList<>());
        var addr = new CustomerAddress();
        addr.setCountry(CFG.getProperty("shipCountry", "Sri Lanka")); addr.setCity(CFG.getProperty("shipCity", "Colombo"));
        q.setShippingAddress(addr);

        GoodsCalculatePriceResponse res = jlc.calculationGoodsCosts(q);
        if (res == null || !res.isSuccessful() || res.getData() == null) {
            System.err.println("JLC calculate failed: " + (res == null ? "null" : res.getCode() + " " + res.getMessage()));
            json(ex, 502, err("Could not calculate a price for this selection. Please try another material or message us.")); return;
        }
        var d = res.getData();
        if (d.getErrorMessage() != null && !d.getErrorMessage().isBlank()) { json(ex, 422, err(d.getErrorMessage())); return; }
        BigDecimal base = d.getTdpTotalMoney();
        if (base == null || base.signum() <= 0) { json(ex, 422, err("This model cannot be priced online. Please message us on WhatsApp.")); return; }

        double markup = s.materialMarkup.getOrDefault(matId, s.markupPercent);
        double lkr = base.doubleValue() * s.usdToLkr * (1 + markup / 100.0) + s.fixedFeeLkr;
        lkr = Math.max(lkr, s.minOrderLkr) + s.flatShippingLkr;
        long total = roundTo(lkr, s.roundToLkr);
        String ref = "Q" + Long.toString(System.currentTimeMillis() / 1000, 36).toUpperCase() + String.format("%02X", RNG.nextInt(256));

        JsonObject log = new JsonObject();
        log.addProperty("ref", ref); log.addProperty("time", java.time.Instant.now().toString());
        log.addProperty("file", fileName); log.addProperty("material", str(in, "materialName")); log.addProperty("color", str(in, "colorName"));
        log.addProperty("qty", qty); log.addProperty("jlcCostUsd", base.doubleValue()); log.addProperty("quotedLkr", total); log.addProperty("ip", ip(ex));
        synchronized (LOCK) { Files.writeString(DATA.resolve("quotes.jsonl"), COMPACT.toJson(log) + "\n", StandardOpenOption.CREATE, StandardOpenOption.APPEND); }

        JsonObject o = new JsonObject();
        o.addProperty("ref", ref); o.addProperty("totalLkr", total); o.addProperty("unitLkr", roundTo((double) total / qty, s.roundToLkr));
        o.addProperty("qty", qty); o.addProperty("validityHours", s.validityHours); o.addProperty("note", s.customerNote);
        json(ex, 200, o);
    }

    // =============================== admin
    static final int LOGIN_MAX_FAILS = 8;
    static final long LOGIN_WINDOW_MS = 15 * 60_000L;

    static void adminLogin(HttpExchange ex) throws Exception {
        String ip = ip(ex);
        if (!adminIpAllowed(ex)) { json(ex, 404, err("Not found")); return; }
        if (recentCount("loginfail|" + ip, LOGIN_WINDOW_MS) >= LOGIN_MAX_FAILS || recentCount("loginfail|all", LOGIN_WINDOW_MS) >= 100) {
            json(ex, 429, err("Too many failed attempts. Try again in 15 minutes.")); return;
        }
        JsonObject in = jsonBody(ex, 2000);
        String pw = System.getenv().getOrDefault("ADMIN_PASSWORD", CFG.getProperty("adminPassword", ""));
        boolean good = pw.length() >= 12 && MessageDigest.isEqual(sha256(pw), sha256(str(in, "password")));
        if (!good) {
            record("loginfail|" + ip); record("loginfail|all");
            Thread.sleep(700); json(ex, 401, err("Wrong password.")); return;
        }
        byte[] b = new byte[32]; RNG.nextBytes(b);
        String tok = HexFormat.of().formatHex(b);
        long now = System.currentTimeMillis();
        cleanup();
        while (sessions.size() >= 20) {
            sessions.entrySet().stream().min(Comparator.comparingLong(e -> e.getValue()[0])).ifPresent(e -> sessions.remove(e.getKey()));
        }
        sessions.put(tok, new long[]{now + SESSION_MAX_MS, now});
        boolean secure = secureCookies(ex);
        ex.getResponseHeaders().add("Set-Cookie", cookieName(secure) + "=" + tok + "; Path=/; HttpOnly; SameSite=Strict; Max-Age=" + (SESSION_MAX_MS / 1000) + (secure ? "; Secure" : ""));
        json(ex, 200, ok());
    }

    static void adminLogout(HttpExchange ex) throws IOException {
        sessions.remove(token(ex));
        boolean secure = secureCookies(ex);
        ex.getResponseHeaders().add("Set-Cookie", cookieName(secure) + "=; Path=/; HttpOnly; SameSite=Strict; Max-Age=0" + (secure ? "; Secure" : ""));
        json(ex, 200, ok());
    }

    static String cookieName(boolean secure) { return secure ? "__Host-adm" : "adm"; }

    static String token(HttpExchange ex) {
        String c = ex.getRequestHeaders().getFirst("Cookie");
        if (c == null) return "";
        for (String p : c.split(";")) {
            p = p.trim();
            if (p.startsWith("__Host-adm=")) return p.substring(11);
            if (p.startsWith("adm=")) return p.substring(4);
        }
        return "";
    }

    static boolean admin(HttpExchange ex) throws IOException {
        long now = System.currentTimeMillis();
        String tok = token(ex);
        long[] session = tok.isEmpty() ? null : sessions.get(tok);
        if (session == null || session[0] < now || now - session[1] > SESSION_IDLE_MS || !adminIpAllowed(ex)) {
            if (session != null) sessions.remove(tok);
            json(ex, 401, err("Please log in.")); return false;
        }
        session[1] = now;
        return true;
    }

    static JsonObject withMaterials() {
        JsonObject o = COMPACT.toJsonTree(settings).getAsJsonObject();
        JsonArray arr = new JsonArray(); materialsSeen.values().forEach(arr::add);
        o.add("knownMaterials", arr);
        return o;
    }

    static final List<String> SETTINGS_NUMBERS = List.of("usdToLkr", "markupPercent", "fixedFeeLkr", "flatShippingLkr", "minOrderLkr", "roundToLkr",
            "maxQty", "maxFileMB", "maxUploadsPerHourPerIp", "maxQuotesPerHourPerIp", "validityHours", "printerCostLkrPerMinute",
            "estimatorPrintVolumeCm3PerMinute", "printingMargin", "fastenerMargin", "fastenerCostLkrPerPart", "labourMonthlySalaryLkr",
            "labourCostLkrPerMinute", "estimatorSetupFeeLkr", "estimatorMinJobLkr", "estimatorRoundToLkr");

    /** Write to a temp file, keep one backup, then swap - a crash can never leave a half-written file. */
    static void writeAtomic(Path target, String content) throws IOException {
        Path tmp = target.resolveSibling(target.getFileName() + ".tmp");
        Files.writeString(tmp, content, StandardCharsets.UTF_8);
        if (Files.exists(target)) Files.copy(target, target.resolveSibling(target.getFileName() + ".bak"), StandardCopyOption.REPLACE_EXISTING);
        try { Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE); }
        catch (AtomicMoveNotSupportedException e) { Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING); }
    }

    static void saveSettings(HttpExchange ex) throws Exception {
        JsonObject raw = jsonBody(ex, 100_000);
        // Gson fills anything missing with defaults, which would silently wipe live pricing - so demand every field.
        for (String key : SETTINGS_NUMBERS)
            if (!raw.has(key) || !raw.get(key).isJsonPrimitive() || !raw.get(key).getAsJsonPrimitive().isNumber()) { json(ex, 400, err("Missing or invalid value: " + key)); return; }
        if (!raw.has("quotesEnabled") || !raw.get("quotesEnabled").isJsonPrimitive() || !raw.get("quotesEnabled").getAsJsonPrimitive().isBoolean()
                || !raw.has("materialCostLkrPerGram") || !raw.get("materialCostLkrPerGram").isJsonObject()) { json(ex, 400, err("Missing or invalid settings.")); return; }
        Settings n = COMPACT.fromJson(raw, Settings.class);
        if (n == null) { json(ex, 400, err("Invalid settings.")); return; }
        if (n.customerNote == null) n.customerNote = "";
        if (n.customerNote.length() > 500) { json(ex, 400, err("The customer note is too long (500 characters max).")); return; }
        for (double v : new double[]{n.usdToLkr, n.markupPercent, n.fixedFeeLkr, n.flatShippingLkr, n.minOrderLkr})
            if (!Double.isFinite(v)) { json(ex, 400, err("One of the values is out of range.")); return; }
        if (n.maxQty > 10000 || n.validityHours < 0 || n.validityHours > 24 * 365 || n.maxUploadsPerHourPerIp < 0 || n.maxQuotesPerHourPerIp < 0) { json(ex, 400, err("One of the values is out of range.")); return; }
        if (n.usdToLkr <= 0 || n.markupPercent < 0 || n.markupPercent > 1000 || n.roundToLkr < 1 || n.maxQty < 1 || n.maxFileMB < 1 || n.maxFileMB > 200 || n.fixedFeeLkr < 0 || n.flatShippingLkr < 0 || n.minOrderLkr < 0) {
            json(ex, 400, err("One of the values is out of range.")); return;
        }
        Settings defaults = new Settings();
        if (n.materialMarkup == null) n.materialMarkup = new HashMap<>();
        if (n.disabledMaterials == null) n.disabledMaterials = new ArrayList<>();
        if (n.materialCostLkrPerGram == null) n.materialCostLkrPerGram = defaults.materialCostLkrPerGram;
        if (n.printingMargin < 0 || !Double.isFinite(n.printingMargin) || n.fastenerMargin < 0 || !Double.isFinite(n.fastenerMargin)
                || n.fastenerCostLkrPerPart < 0 || !Double.isFinite(n.fastenerCostLkrPerPart)
            || n.printerCostLkrPerMinute < 0 || !Double.isFinite(n.printerCostLkrPerMinute)
            || n.estimatorPrintVolumeCm3PerMinute < 0 || !Double.isFinite(n.estimatorPrintVolumeCm3PerMinute)
                || n.labourMonthlySalaryLkr < 0 || !Double.isFinite(n.labourMonthlySalaryLkr)
                || n.labourCostLkrPerMinute < 0 || !Double.isFinite(n.labourCostLkrPerMinute)
                || n.estimatorSetupFeeLkr < 0 || !Double.isFinite(n.estimatorSetupFeeLkr)
                || n.estimatorMinJobLkr < 0 || !Double.isFinite(n.estimatorMinJobLkr) || n.estimatorRoundToLkr < 1) {
            json(ex, 400, err("One of the estimator values is out of range.")); return;
        }
        for (double cost : n.materialCostLkrPerGram.values()) if (cost < 0 || !Double.isFinite(cost)) { json(ex, 400, err("Material cost must be zero or greater.")); return; }
        synchronized (LOCK) { writeAtomic(DATA.resolve("settings.json"), GSON.toJson(n)); settings = n; }
        json(ex, 200, ok());
    }

    static void recentQuotes(HttpExchange ex) throws IOException {
        Path f = DATA.resolve("quotes.jsonl");
        JsonArray arr = new JsonArray();
        if (Files.exists(f)) {
            List<String> lines = Files.readAllLines(f);
            for (int i = Math.max(0, lines.size() - 100); i < lines.size(); i++) arr.add(JsonParser.parseString(lines.get(i)));
        }
        json(ex, 200, arr);
    }

    static List<JsonObject> readQuoteRequests() throws IOException {
        Path f = DATA.resolve("quote-requests.jsonl");
        List<JsonObject> records = new ArrayList<>();
        if (!Files.exists(f)) return records;
        List<String> lines = Files.readAllLines(f, StandardCharsets.UTF_8);
        for (String line : lines) if (!line.isBlank()) records.add(JsonParser.parseString(line).getAsJsonObject());
        return records;
    }

    static void recentQuoteRequests(HttpExchange ex) throws IOException {
        List<JsonObject> records = readQuoteRequests();
        JsonArray result = new JsonArray();
        int start = Math.max(0, records.size() - 200);
        for (int i = records.size() - 1; i >= start; i--) {
            JsonObject request = records.get(i).deepCopy();
            JsonArray files = new JsonArray();
            if (request.has("files") && request.get("files").isJsonArray()) {
                JsonArray savedFiles = request.getAsJsonArray("files");
                for (int j = 0; j < savedFiles.size(); j++) {
                    JsonObject file = savedFiles.get(j).getAsJsonObject();
                    JsonObject view = new JsonObject();
                    view.addProperty("name", str(file, "name"));
                    view.addProperty("size", number(file, "size"));
                    view.addProperty("downloadUrl", "/api/admin/quote-files/" + str(request, "id") + "/" + j);
                    files.add(view);
                }
            }
            request.add("files", files);
            result.add(request);
        }
        json(ex, 200, result);
    }

    static void adminQuoteFile(HttpExchange ex, String path) throws IOException {
        String[] parts = path.substring("/api/admin/quote-files/".length()).split("/");
        if (parts.length != 2 || !parts[0].matches("[0-9a-fA-F-]{36}") || !parts[1].matches("\\d+")) {
            json(ex, 404, err("File not found.")); return;
        }
        int index;
        try { index = Integer.parseInt(parts[1]); } catch (NumberFormatException e) { json(ex, 404, err("File not found.")); return; }
        JsonObject request = null;
        for (JsonObject item : readQuoteRequests()) if (parts[0].equals(str(item, "id"))) { request = item; break; }
        if (request == null || !request.has("files") || !request.get("files").isJsonArray()) { json(ex, 404, err("File not found.")); return; }
        JsonArray files = request.getAsJsonArray("files");
        if (index < 0 || index >= files.size()) { json(ex, 404, err("File not found.")); return; }
        JsonObject file = files.get(index).getAsJsonObject();
        String stored = str(file, "storedName");
        if (!stored.matches(parts[0] + "-\\d+\\.[a-z0-9]+")) { json(ex, 404, err("File not found.")); return; }
        Path dir = DATA.resolve("quote-files").normalize();
        Path target = dir.resolve(stored).normalize();
        if (!target.startsWith(dir) || !Files.isRegularFile(target)) { json(ex, 404, err("File not found.")); return; }
        String extension = stored.substring(stored.lastIndexOf('.') + 1);
        String mime = switch (extension) {
            case "pdf" -> "application/pdf";
            case "png" -> "image/png";
            case "jpg", "jpeg" -> "image/jpeg";
            case "webp" -> "image/webp";
            default -> "application/octet-stream";
        };
        String name = str(file, "name").replace("\"", "_").replace("\r", "_").replace("\n", "_");
        ex.getResponseHeaders().add("Content-Type", mime);
        ex.getResponseHeaders().add("Content-Disposition", "attachment; filename=\"" + name + "\"");
        
        ex.getResponseHeaders().add("Cache-Control", "no-store");
        ex.sendResponseHeaders(200, Files.size(target));
        try (var out = ex.getResponseBody()) { Files.copy(target, out); }
    }

    static void adminPage(HttpExchange ex) throws IOException {
        secure(ex, true);
        String p = ex.getRequestURI().getPath();
        if (!adminIpAllowed(ex)) { send(ex, 404, "text/plain; charset=utf-8", "Not found".getBytes(StandardCharsets.UTF_8), "no-store", false); return; }
        String file = switch (p) {
            case "/admin", "/admin/" -> "admin.html";
            case "/admin/admin.css" -> "admin.css";
            case "/admin/admin.js" -> "admin.js";
            default -> null;
        };
        if (file == null) { send(ex, 404, "text/plain; charset=utf-8", "Not found".getBytes(StandardCharsets.UTF_8), "no-store", false); return; }
        String type = file.endsWith(".css") ? "text/css; charset=utf-8" : file.endsWith(".js") ? "text/javascript; charset=utf-8" : "text/html; charset=utf-8";
        ex.getResponseHeaders().set("X-Robots-Tag", "noindex, nofollow");
        send(ex, 200, type, Files.readAllBytes(SRV.resolve("admin").resolve(file)), "no-store", false);
    }

    // =============================== static website + SEO pages
    record CachedAsset(long modified, byte[] raw) {}
    static final Map<Path, CachedAsset> assetCache = new ConcurrentHashMap<>();

    static void staticFile(HttpExchange ex) throws IOException {
        secure(ex, false);
        String method = ex.getRequestMethod();
        if (!method.equals("GET") && !method.equals("HEAD")) {
            ex.getResponseHeaders().set("Allow", "GET, HEAD");
            send(ex, 405, "text/plain; charset=utf-8", "Method not allowed".getBytes(StandardCharsets.UTF_8), "no-store", false); return;
        }
        if (!allow(ip(ex), "page", 900, 60_000L)) { send(ex, 429, "text/plain; charset=utf-8", "Too many requests".getBytes(StandardCharsets.UTF_8), "no-store", false); return; }
        if (Boolean.parseBoolean(CFG.getProperty("forceHttps", "false")) && trustProxy()
                && "http".equalsIgnoreCase(String.valueOf(ex.getRequestHeaders().getFirst("X-Forwarded-Proto")))) {
            redirect(ex, site() + ex.getRequestURI().getRawPath() + (ex.getRequestURI().getRawQuery() == null ? "" : "?" + ex.getRequestURI().getRawQuery())); return;
        }
        String p = ex.getRequestURI().getPath();
        switch (p) {
            case "/robots.txt" -> { send(ex, 200, "text/plain; charset=utf-8", robotsTxt().getBytes(StandardCharsets.UTF_8), "public, max-age=3600", true); return; }
            case "/sitemap.xml" -> { send(ex, 200, "application/xml; charset=utf-8", sitemapXml().getBytes(StandardCharsets.UTF_8), "public, max-age=3600", true); return; }
            case "/favicon.ico" -> { send(ex, 200, "image/png", Files.readAllBytes(ROOT.resolve("images").resolve("logo-192.png")), "public, max-age=2592000", true); return; }
            case "/index.html" -> { redirect(ex, "/"); return; }
            default -> { }
        }
        if (p.startsWith("/product/")) { productPage(ex, p); return; }
        if (p.startsWith("/category/")) { categoryPage(ex, p); return; }
        if (p.equals("/")) p = "/index.html";
        if (p.length() > 200 || p.contains("\\") || p.contains(":") || p.contains("\0") || p.contains("//")) { notFound(ex); return; }
        Path f;
        try { f = ROOT.resolve("." + p).normalize(); } catch (InvalidPathException e) { notFound(ex); return; }
        if (!f.startsWith(ROOT)) { notFound(ex); return; }
        String rel = ROOT.relativize(f).toString().replace('\\', '/');
        String ext = rel.contains(".") ? rel.substring(rel.lastIndexOf('.') + 1).toLowerCase(Locale.ROOT) : "";
        String lowerRel = rel.toLowerCase(Locale.ROOT);
        boolean rootLevel = !rel.contains("/");
        boolean bad = rel.isEmpty() || lowerRel.startsWith("server") || lowerRel.equals("404.html")
                || Arrays.stream(rel.split("/")).anyMatch(x -> x.startsWith(".")) || !STATIC_EXT.contains(ext)
                || (rootLevel && !ext.equals("html") && !ext.equals("css") && !ext.equals("js") && !ext.equals("webmanifest") && !lowerRel.equals("logo.png"));
        if (bad || !Files.isRegularFile(f)) { notFound(ex); return; }

        String type = switch (ext) {
            case "html" -> "text/html; charset=utf-8"; case "css" -> "text/css; charset=utf-8"; case "js" -> "text/javascript; charset=utf-8";
            case "png" -> "image/png"; case "jpg", "jpeg" -> "image/jpeg"; case "webp" -> "image/webp"; case "svg" -> "image/svg+xml";
            case "ico" -> "image/x-icon"; case "webmanifest" -> "application/manifest+json"; default -> "text/plain; charset=utf-8";
        };
        if (ext.equals("html")) {
            String html = renderPage(Files.readString(f, StandardCharsets.UTF_8), rel);
            send(ex, 200, type, html.getBytes(StandardCharsets.UTF_8), "public, max-age=0, must-revalidate", true);
            return;
        }
        long modified = Files.getLastModifiedTime(f).toMillis();
        CachedAsset cached = assetCache.get(f);
        if (cached == null || cached.modified() != modified) {
            cached = new CachedAsset(modified, Files.readAllBytes(f));
            if (TEXT_EXT.contains(ext) || cached.raw().length < 400_000) assetCache.put(f, cached);
        }
        boolean versioned = ex.getRequestURI().getRawQuery() != null && ex.getRequestURI().getRawQuery().startsWith("v=");
        String cache = lowerRel.startsWith("images/") || lowerRel.startsWith("vendor/") ? "public, max-age=2592000"
                : versioned ? "public, max-age=31536000, immutable" : "public, max-age=3600";
        send(ex, 200, type, cached.raw(), cache, true);
    }

    static void notFound(HttpExchange ex) throws IOException {
        byte[] body;
        Path page = ROOT.resolve("404.html");
        try { body = renderPage(Files.readString(page, StandardCharsets.UTF_8), "404.html").getBytes(StandardCharsets.UTF_8); }
        catch (IOException e) { body = "Not found".getBytes(StandardCharsets.UTF_8); }
        ex.getResponseHeaders().set("X-Robots-Tag", "noindex");
        send(ex, 404, "text/html; charset=utf-8", body, "no-store", false);
    }

    static void redirect(HttpExchange ex, String location) throws IOException {
        ex.getResponseHeaders().set("Location", location);
        send(ex, 301, "text/plain; charset=utf-8", ("Moved to " + location).getBytes(StandardCharsets.UTF_8), "public, max-age=3600", false);
    }

    /** One place that writes responses: HEAD handling, gzip for text, ETag + 304 for cacheable pages. */
    static void send(HttpExchange ex, int code, String type, byte[] body, String cache, boolean etag) throws IOException {
        var h = ex.getResponseHeaders();
        h.set("Content-Type", type);
        h.set("Cache-Control", cache);
        boolean head = ex.getRequestMethod().equals("HEAD");
        if (etag && code == 200) {
            String tag = "\"" + HexFormat.of().formatHex(sha256(new String(body, StandardCharsets.ISO_8859_1))).substring(0, 24) + "\"";
            h.set("ETag", tag);
            String inm = ex.getRequestHeaders().getFirst("If-None-Match");
            if (inm != null && inm.contains(tag)) { ex.sendResponseHeaders(304, -1); ex.close(); return; }
        }
        String base = type.contains(";") ? type.substring(0, type.indexOf(';')) : type;
        boolean textual = base.startsWith("text/") || base.endsWith("xml") || base.endsWith("json") || base.equals("image/svg+xml") || base.endsWith("manifest+json");
        h.add("Vary", "Accept-Encoding");
        String accept = ex.getRequestHeaders().getFirst("Accept-Encoding");
        if (textual && body.length > 1024 && accept != null && accept.toLowerCase(Locale.ROOT).contains("gzip")) {
            var bos = new ByteArrayOutputStream(body.length / 3 + 64);
            try (var gz = new GZIPOutputStream(bos)) { gz.write(body); }
            body = bos.toByteArray();
            h.set("Content-Encoding", "gzip");
        }
        if (head) { h.set("Content-Length", String.valueOf(body.length)); ex.sendResponseHeaders(code, -1); ex.close(); return; }
        ex.sendResponseHeaders(code, body.length == 0 ? -1 : body.length);
        if (body.length > 0) try (var out = ex.getResponseBody()) { out.write(body); } else ex.close();
    }

    // ---- HTML includes: <!--#header-->, <!--#footer-->, {{SITE}}, {{YEAR}}, automatic asset cache-busting
    static final Pattern PAGE_ATTR = Pattern.compile("<body[^>]*data-page=\"([a-z-]+)\"");
    static final Pattern NAV_ATTR = Pattern.compile(" data-nav=\"([a-z-]+)\"");
    static final Pattern ASSET_REF = Pattern.compile("(href|src)=\"(/?)(style\\.css|script\\.js|stl-estimate\\.js)(\\?v=[^\"]*)?\"");

    static String partial(String name) {
        try { return Files.readString(SRV.resolve("templates").resolve(name), StandardCharsets.UTF_8); }
        catch (IOException e) { return ""; }
    }

    static String renderPage(String html, String rel) {
        Matcher pm = PAGE_ATTR.matcher(html);
        String page = pm.find() ? pm.group(1) : "";
        String header = NAV_ATTR.matcher(partial("header.html")).replaceAll(r -> r.group(1).equals(page) ? " aria-current=\"page\"" : "");
        if (html.contains("<!--#shop-grid-->")) html = html.replace("<!--#shop-grid-->", gridCards(allProducts(), 60));
        if (html.contains("<!--#featured-->")) html = html.replace("<!--#featured-->", featuredCards());
        if (html.contains("<!--#shop-jsonld-->")) html = html.replace("<!--#shop-jsonld-->", ldScript(itemListLd("3D printers, filament and parts in Sri Lanka", "/shop.html", allProducts())));
        html = html.replace("<!--#header-->", header).replace("<!--#footer-->", partial("footer.html"))
                .replace("{{SITE}}", site()).replace("{{YEAR}}", String.valueOf(Year.now().getValue()));
        return ASSET_REF.matcher(html).replaceAll(r -> {
            String v = "0";
            try { v = Long.toString(Files.getLastModifiedTime(ROOT.resolve(r.group(3))).toMillis() / 1000, 36); } catch (IOException ignored) { }
            return Matcher.quoteReplacement(r.group(1) + "=\"" + r.group(2) + r.group(3) + "?v=" + v + "\"");
        });
    }

    // ---- robots.txt / sitemap.xml
    static String robotsTxt() {
        return "User-agent: *\nAllow: /\nAllow: /api/shop/catalog.js\nDisallow: /admin\nDisallow: /api/\n\nSitemap: " + site() + "/sitemap.xml\n";
    }

    static String sitemapXml() throws IOException {
        StringBuilder x = new StringBuilder("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n<urlset xmlns=\"http://www.sitemaps.org/schemas/sitemap/0.9\" xmlns:image=\"http://www.google.com/schemas/sitemap-image/1.1\">\n");
        String base = site();
        try (var files = Files.list(ROOT)) {
            for (Path f : files.filter(f -> f.getFileName().toString().endsWith(".html")).sorted().toList()) {
                String name = f.getFileName().toString();
                String content = Files.readString(f, StandardCharsets.UTF_8);
                if (name.equals("404.html") || content.toLowerCase(Locale.ROOT).contains("noindex")) continue;
                String loc = name.equals("index.html") ? base + "/" : base + "/" + name;
                x.append("<url><loc>").append(esc(loc)).append("</loc><lastmod>")
                        .append(Files.getLastModifiedTime(f).toInstant().atZone(ZoneOffset.UTC).toLocalDate()).append("</lastmod></url>\n");
            }
        }
        String catalogDate = Files.exists(DATA.resolve("shop-catalog.json"))
                ? Files.getLastModifiedTime(DATA.resolve("shop-catalog.json")).toInstant().atZone(ZoneOffset.UTC).toLocalDate().toString() : LocalDate.now().toString();
        for (var e : shopCatalog.getAsJsonObject("categories").entrySet()) {
            x.append("<url><loc>").append(esc(base + "/category/" + slug(e.getKey()))).append("</loc><lastmod>").append(catalogDate).append("</lastmod></url>\n");
            for (JsonElement sub : e.getValue().getAsJsonArray())
                x.append("<url><loc>").append(esc(base + "/category/" + slug(e.getKey()) + "/" + slug(sub.getAsString()))).append("</loc><lastmod>").append(catalogDate).append("</lastmod></url>\n");
        }
        for (JsonObject pr : allProducts()) {
            x.append("<url><loc>").append(esc(base + productPath(pr))).append("</loc><lastmod>").append(catalogDate).append("</lastmod>");
            if (!str(pr, "img").isBlank())
                x.append("<image:image><image:loc>").append(esc(base + "/images/products/" + str(pr, "img"))).append("</image:loc><image:title>").append(esc(str(pr, "name"))).append("</image:title></image:image>");
            x.append("</url>\n");
        }
        return x.append("</urlset>\n").toString();
    }

    // ---- catalog helpers
    static List<JsonObject> allProducts() {
        List<JsonObject> list = new ArrayList<>();
        for (JsonElement e : shopCatalog.getAsJsonArray("products")) list.add(e.getAsJsonObject());
        return list;
    }

    static String esc(String v) {
        if (v == null) return "";
        return v.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;").replace("'", "&#39;");
    }

    static String slug(String v) {
        String t = java.text.Normalizer.normalize(v == null ? "" : v, java.text.Normalizer.Form.NFKD).replaceAll("\\p{M}", "")
                .toLowerCase(Locale.ROOT).replace("&", " and ").replaceAll("[^a-z0-9]+", "-").replaceAll("^-+|-+$", "");
        if (t.length() > 70) t = t.substring(0, 70).replaceAll("-+$", "");
        return t.isEmpty() ? "item" : t;
    }

    static String productPath(JsonObject p) { return "/product/" + p.get("id").getAsInt() + "-" + slug(str(p, "name")); }
    static String rupees(double v) { return String.format(Locale.US, "Rs. %,d", Math.round(v)); }
    static String clip(String v, int max) { return v.length() <= max ? v : v.substring(0, max - 1).replaceAll("\\s+\\S*$", "") + "…"; }

    static final Map<String, String> ICONS = Map.of(
        "3D Printers", "<svg viewBox=\"0 0 64 64\" aria-hidden=\"true\"><rect x=\"8\" y=\"6\" width=\"48\" height=\"52\" rx=\"4\"/><path d=\"M8 16h48M20 16v20M44 16v20M20 30h24\"/><rect x=\"24\" y=\"30\" width=\"16\" height=\"6\"/><path d=\"M14 50h36\"/></svg>",
        "Filaments", "<svg viewBox=\"0 0 64 64\" aria-hidden=\"true\"><circle cx=\"32\" cy=\"32\" r=\"24\"/><circle cx=\"32\" cy=\"32\" r=\"8\"/><path d=\"M12 22c8 4 32 4 40 0M12 42c8-4 32-4 40 0\"/></svg>",
        "Resins", "<svg viewBox=\"0 0 64 64\" aria-hidden=\"true\"><path d=\"M26 6h12v8l8 10v30a4 4 0 0 1-4 4H22a4 4 0 0 1-4-4V24l8-10z\"/><path d=\"M18 36h28\"/></svg>",
        "Printer Parts", "<svg viewBox=\"0 0 64 64\" aria-hidden=\"true\"><path d=\"M20 8h24v14l-6 6v10l-6 18-6-18V28l-6-6z\"/><path d=\"M26 16h12\"/></svg>",
        "3D Scanners", "<svg viewBox=\"0 0 64 64\" aria-hidden=\"true\"><rect x=\"8\" y=\"16\" width=\"48\" height=\"32\" rx=\"6\"/><circle cx=\"32\" cy=\"32\" r=\"9\"/><path d=\"M14 10h8M42 10h8\"/></svg>",
        "Tools & Accessories", "<svg viewBox=\"0 0 64 64\" aria-hidden=\"true\"><path d=\"M8 40l16-16M30 18l16 16-8 8-16-16zM40 10l14 14\"/><path d=\"M12 52h40\"/></svg>");

    static String cardHtml(JsonObject p, boolean lazy) {
        double price = number(p, "price"), old = p.has("old") ? number(p, "old") : 0;
        int off = old > price ? (int) Math.round((old - price) / old * 100) : 0;
        String url = productPath(p), name = str(p, "name"), img = str(p, "img"), stock = str(p, "stock");
        Set<String> tags = new HashSet<>();
        for (JsonElement t : p.getAsJsonArray("tags")) tags.add(t.getAsString());
        StringBuilder h = new StringBuilder("<article class=\"card\"><div class=\"thumb").append(img.isBlank() ? "" : " has-img").append("\"><a href=\"").append(url).append("\" tabindex=\"-1\" aria-hidden=\"true\">");
        if (img.isBlank()) h.append(ICONS.getOrDefault(str(p, "cat"), ICONS.get("Tools & Accessories")));
        else h.append("<img src=\"/images/products/").append(esc(img)).append("\" alt=\"").append(esc(name)).append("\"").append(lazy ? " loading=\"lazy\"" : "").append(" width=\"600\" height=\"600\">");
        h.append("</a>");
        if (tags.contains("new")) h.append("<span class=\"ribbon\">New</span>"); else if (tags.contains("best")) h.append("<span class=\"ribbon best\">Best seller</span>");
        if (off > 0) h.append("<span class=\"off\">-").append(off).append("%</span>");
        h.append("</div><div class=\"body\"><h3><a href=\"").append(url).append("\">").append(esc(name)).append("</a></h3><div class=\"price\">").append(rupees(price)).append("</div>");
        if (old > 0) h.append("<div class=\"was\"><s>").append(rupees(old)).append("</s><b>-").append(off).append("%</b></div>");
        if (!stock.equals("in")) h.append("<span class=\"stock ").append(esc(stock)).append("\">").append(stock.equals("out") ? "Out of stock" : "Pre-order").append("</span>");
        h.append("<button type=\"button\" data-add=\"").append(p.get("id").getAsInt()).append("\"").append(stock.equals("out") ? " disabled" : "").append(">")
                .append(stock.equals("out") ? "Out of stock" : stock.equals("pre") ? "Pre-order" : "Add to cart").append("</button></div></article>");
        return h.toString();
    }

    static String gridCards(List<JsonObject> products, int max) {
        StringBuilder h = new StringBuilder();
        for (int i = 0; i < products.size() && i < max; i++) h.append(cardHtml(products.get(i), i > 3));
        return h.toString();
    }

    static String featuredCards() {
        StringBuilder h = new StringBuilder();
        Set<Integer> ids = Set.of(1, 2, 3, 4, 5);
        for (JsonObject p : allProducts()) if (ids.contains(p.get("id").getAsInt())) h.append(cardHtml(p, true));
        return h.toString();
    }

    static String ldScript(JsonElement ld) {
        return "<script type=\"application/ld+json\">" + COMPACT.toJson(ld).replace("<", "\\u003c") + "</script>";
    }

    static JsonObject itemListLd(String name, String path, List<JsonObject> products) {
        JsonObject ld = new JsonObject(); ld.addProperty("@context", "https://schema.org"); ld.addProperty("@type", "ItemList");
        ld.addProperty("name", name); ld.addProperty("url", site() + path);
        JsonArray items = new JsonArray();
        int i = 1;
        for (JsonObject p : products) {
            JsonObject li = new JsonObject(); li.addProperty("@type", "ListItem"); li.addProperty("position", i++);
            li.addProperty("url", site() + productPath(p)); li.addProperty("name", str(p, "name"));
            items.add(li);
            if (i > 50) break;
        }
        ld.add("itemListElement", items);
        return ld;
    }

    static JsonObject breadcrumbLd(String[][] crumbs) {
        JsonObject ld = new JsonObject(); ld.addProperty("@context", "https://schema.org"); ld.addProperty("@type", "BreadcrumbList");
        JsonArray items = new JsonArray();
        for (int i = 0; i < crumbs.length; i++) {
            JsonObject li = new JsonObject(); li.addProperty("@type", "ListItem"); li.addProperty("position", i + 1); li.addProperty("name", crumbs[i][0]);
            if (crumbs[i][1] != null) li.addProperty("item", site() + crumbs[i][1]);
            items.add(li);
        }
        ld.add("itemListElement", items);
        return ld;
    }

    static String breadcrumbHtml(String[][] crumbs) {
        StringBuilder h = new StringBuilder("<nav class=\"crumbs\" aria-label=\"Breadcrumb\"><ol>");
        for (int i = 0; i < crumbs.length; i++) {
            h.append("<li>");
            if (crumbs[i][1] != null && i < crumbs.length - 1) h.append("<a href=\"").append(esc(crumbs[i][1])).append("\">").append(esc(crumbs[i][0])).append("</a>");
            else h.append("<span aria-current=\"page\">").append(esc(crumbs[i][0])).append("</span>");
            h.append("</li>");
        }
        return h.append("</ol></nav>").toString();
    }

    /** Full HTML document for server-rendered shop pages. */
    static String shell(String title, String desc, String path, String ogImage, String ogType, String jsonLd, boolean index, String main) {
        String url = site() + path, image = ogImage == null ? site() + "/images/hero-cad-to-real.webp" : ogImage;
        return "<!DOCTYPE html>\n<html lang=\"en\">\n<head>\n<meta charset=\"UTF-8\">\n<meta name=\"viewport\" content=\"width=device-width, initial-scale=1\">\n"
            + "<title>" + esc(title) + "</title>\n<meta name=\"description\" content=\"" + esc(desc) + "\">\n"
            + "<meta name=\"robots\" content=\"" + (index ? "index, follow, max-image-preview:large, max-snippet:-1" : "noindex, follow") + "\">\n"
            + "<link rel=\"canonical\" href=\"" + esc(url) + "\">\n<meta name=\"theme-color\" content=\"#F26F21\">\n"
            + "<link rel=\"icon\" href=\"/images/logo-192.png\" type=\"image/png\">\n<link rel=\"apple-touch-icon\" href=\"/images/logo-192.png\">\n"
            + "<meta property=\"og:site_name\" content=\"3DShop.lk\">\n<meta property=\"og:locale\" content=\"en_LK\">\n<meta property=\"og:type\" content=\"" + ogType + "\">\n"
            + "<meta property=\"og:title\" content=\"" + esc(title) + "\">\n<meta property=\"og:description\" content=\"" + esc(desc) + "\">\n"
            + "<meta property=\"og:url\" content=\"" + esc(url) + "\">\n<meta property=\"og:image\" content=\"" + esc(image) + "\">\n"
            + "<meta name=\"twitter:card\" content=\"summary_large_image\">\n<meta name=\"twitter:title\" content=\"" + esc(title) + "\">\n<meta name=\"twitter:description\" content=\"" + esc(desc) + "\">\n<meta name=\"twitter:image\" content=\"" + esc(image) + "\">\n"
            + jsonLd + "\n"
            + "<link rel=\"preconnect\" href=\"https://fonts.googleapis.com\">\n<link rel=\"preconnect\" href=\"https://fonts.gstatic.com\" crossorigin>\n"
            + "<link rel=\"stylesheet\" href=\"https://fonts.googleapis.com/css2?family=Raleway:wght@400;500;600;700;800&display=swap\">\n<link rel=\"stylesheet\" href=\"/style.css\">\n</head>\n"
            + "<body data-page=\"shop\">\n<a class=\"skip\" href=\"#main\">Skip to main content</a>\n<!--#header-->\n<main id=\"main\">\n" + main + "\n</main>\n<!--#footer-->\n"
            + "<script src=\"/api/shop/catalog.js\"></script>\n<script src=\"/script.js\"></script>\n</body>\n</html>\n";
    }

    static void productPage(HttpExchange ex, String path) throws IOException {
        String tail = path.substring("/product/".length());
        Matcher m = Pattern.compile("^(\\d{1,9})(?:-([a-z0-9-]*))?$").matcher(tail);
        JsonObject product = null;
        if (m.matches()) { int id = Integer.parseInt(m.group(1)); for (JsonObject pr : allProducts()) if (pr.get("id").getAsInt() == id) { product = pr; break; } }
        if (product == null) { notFound(ex); return; }
        if (!path.equals(productPath(product))) { redirect(ex, productPath(product)); return; }

        String name = str(product, "name"), cat = str(product, "cat"), sub = str(product, "sub"), desc = str(product, "desc"), img = str(product, "img"), stock = str(product, "stock");
        double price = number(product, "price"), old = product.has("old") ? number(product, "old") : 0;
        String imageUrl = img.isBlank() ? site() + "/images/logo-192.png" : site() + "/images/products/" + img;
        String title = clip(name, 42) + " | Buy in Sri Lanka – 3DShop.lk";
        String metaDesc = clip((desc.isBlank() ? name + "." : desc.endsWith(".") ? desc : desc + ".") + " Buy " + name + " in Sri Lanka for " + rupees(price) + ". Island-wide delivery, order on WhatsApp.", 158);

        String[][] crumbs = sub.isBlank()
            ? new String[][]{{"Home", "/"}, {"Shop", "/shop.html"}, {cat, "/category/" + slug(cat)}, {name, productPath(product)}}
            : new String[][]{{"Home", "/"}, {"Shop", "/shop.html"}, {cat, "/category/" + slug(cat)}, {sub, "/category/" + slug(cat) + "/" + slug(sub)}, {name, productPath(product)}};

        JsonObject ld = new JsonObject(); ld.addProperty("@context", "https://schema.org"); ld.addProperty("@type", "Product");
        ld.addProperty("name", name); ld.addProperty("description", desc.isBlank() ? name : desc); ld.addProperty("sku", "3DS-" + product.get("id").getAsInt());
        ld.addProperty("category", cat); ld.addProperty("url", site() + productPath(product));
        JsonArray images = new JsonArray(); images.add(imageUrl); ld.add("image", images);
        JsonObject offer = new JsonObject(); offer.addProperty("@type", "Offer"); offer.addProperty("url", site() + productPath(product));
        offer.addProperty("priceCurrency", "LKR"); offer.addProperty("price", String.format(Locale.US, "%.2f", price));
        offer.addProperty("itemCondition", "https://schema.org/NewCondition");
        offer.addProperty("availability", stock.equals("in") ? "https://schema.org/InStock" : stock.equals("pre") ? "https://schema.org/PreOrder" : "https://schema.org/OutOfStock");
        JsonObject seller = new JsonObject(); seller.addProperty("@type", "Organization"); seller.addProperty("name", "3DShop.lk"); offer.add("seller", seller);
        ld.add("offers", offer);
        JsonArray graph = new JsonArray(); graph.add(ld); graph.add(breadcrumbLd(crumbs));
        JsonObject root = new JsonObject(); root.addProperty("@context", "https://schema.org"); root.add("@graph", graph);
        graph.forEach(el -> el.getAsJsonObject().remove("@context"));

        List<JsonObject> related = new ArrayList<>();
        for (JsonObject other : allProducts()) if (other != product && str(other, "cat").equals(cat) && related.size() < 4) related.add(other);
        int off = old > price ? (int) Math.round((old - price) / old * 100) : 0;
        int id = product.get("id").getAsInt();
        String main = "<div class=\"wrap pdp-wrap\">" + breadcrumbHtml(crumbs)
            + "<article class=\"pdp\"><div class=\"pdp-img\">"
            + (img.isBlank() ? "<div class=\"thumb\">" + ICONS.getOrDefault(cat, ICONS.get("Tools & Accessories")) + "</div>"
                : "<img src=\"/images/products/" + esc(img) + "\" alt=\"" + esc(name) + "\" width=\"800\" height=\"800\" fetchpriority=\"high\">")
            + "</div><div class=\"pdp-info\"><p class=\"eyebrow dark\">" + esc(cat) + (sub.isBlank() ? "" : " &middot; " + esc(sub)) + "</p><h1>" + esc(name) + "</h1>"
            + "<div class=\"pdp-price\"><b>" + rupees(price) + "</b>" + (old > 0 ? " <s>" + rupees(old) + "</s> <em>-" + off + "%</em>" : "") + "</div>"
            + "<p class=\"pdp-stock " + esc(stock) + "\">" + (stock.equals("in") ? "In stock" : stock.equals("pre") ? "Pre-order" : "Out of stock") + "</p>"
            + (desc.isBlank() ? "" : "<p class=\"pdp-desc\">" + esc(desc) + "</p>")
            + "<div class=\"pdp-actions\"><button class=\"btn orange\" type=\"button\" data-add=\"" + id + "\"" + (stock.equals("out") ? " disabled" : "") + ">"
            + (stock.equals("out") ? "Out of stock" : stock.equals("pre") ? "Pre-order" : "Add to cart") + "</button>"
            + "<a class=\"btn ghost-dark\" data-wa href=\"https://wa.me/94774757677\" target=\"_blank\" rel=\"noopener\">Ask on WhatsApp</a></div>"
            + "<ul class=\"ticks pdp-points\"><li>Island-wide courier delivery across Sri Lanka</li><li>Order on WhatsApp &ndash; delivery cost confirmed when you order</li><li>Set-up help and support from a team that prints every day</li></ul>"
            + "</div></article>"
            + (related.isEmpty() ? "" : "<section class=\"pdp-related\"><h2>More in " + esc(cat) + "</h2><div class=\"grid\">" + gridCards(related, 4) + "</div></section>")
            + "</div>";
        send(ex, 200, "text/html; charset=utf-8", renderPage(shell(title, metaDesc, productPath(product), imageUrl, "product", ldScript(root), true, main), "product").getBytes(StandardCharsets.UTF_8),
                "public, max-age=0, must-revalidate", true);
    }

    static final Map<String, String> CATEGORY_INTRO = Map.of(
        "3D Printers", "Looking to buy a 3D printer in Sri Lanka? Browse FDM and resin 3D printers from 3DShop.lk. We run 3D printers every day in our own print shop, so we can help you choose a machine that suits your parts, materials and budget &ndash; and help you set it up on WhatsApp.",
        "Filaments", "Buy 3D printer filament in Sri Lanka: PLA, PLA+, PETG, ABS and flexible TPU on 1 kg and 500 g spools, priced in rupees with island-wide delivery. The same filament types we use in our own printing service.",
        "Resins", "Shop resin for LCD and DLP resin 3D printers in Sri Lanka, from standard grey resin for detailed models to tough engineering resin for functional parts.",
        "Printer Parts", "3D printer parts and upgrades in Sri Lanka: brass nozzles, all-metal hotends, flexible build plates and more for FDM printers.",
        "3D Scanners", "Buy a 3D scanner in Sri Lanka to turn real objects into 3D models you can edit, scale and print.",
        "Tools & Accessories", "Tools and accessories for 3D printing in Sri Lanka: calipers, tool kits, filament dry boxes and everything else you need around the printer.");

    static void categoryPage(HttpExchange ex, String path) throws IOException {
        String[] seg = path.substring("/category/".length()).split("/", -1);
        if (seg.length < 1 || seg.length > 2 || seg[0].isEmpty() || (seg.length == 2 && seg[1].isEmpty())) {
            if (seg.length == 2 && seg[1].isEmpty() && !seg[0].isEmpty()) { redirect(ex, "/category/" + seg[0]); return; }
            notFound(ex); return;
        }
        String cat = null, sub = null;
        JsonObject cats = shopCatalog.getAsJsonObject("categories");
        for (var e : cats.entrySet()) {
            if (!slug(e.getKey()).equals(seg[0])) continue;
            cat = e.getKey();
            if (seg.length == 2) for (JsonElement s : e.getValue().getAsJsonArray()) if (slug(s.getAsString()).equals(seg[1])) sub = s.getAsString();
        }
        if (cat == null || (seg.length == 2 && sub == null)) { notFound(ex); return; }

        List<JsonObject> list = new ArrayList<>();
        for (JsonObject pr : allProducts()) if (str(pr, "cat").equals(cat) && (sub == null || str(pr, "sub").equals(sub))) list.add(pr);
        String heading = sub == null ? "Buy " + cat + " in Sri Lanka" : "Buy " + sub + " " + cat + " in Sri Lanka";
        String canonical = "/category/" + slug(cat) + (sub == null ? "" : "/" + slug(sub));
        String intro = CATEGORY_INTRO.getOrDefault(cat, "Shop " + esc(cat) + " at 3DShop.lk. Prices in rupees, island-wide delivery and local support on WhatsApp.");
        String metaDesc = clip(intro.replaceAll("&ndash;", "-").replaceAll("<[^>]+>", "") + " " + list.size() + " products, island-wide delivery.", 158);
        String[][] crumbs = sub == null ? new String[][]{{"Home", "/"}, {"Shop", "/shop.html"}, {cat, canonical}}
                : new String[][]{{"Home", "/"}, {"Shop", "/shop.html"}, {cat, "/category/" + slug(cat)}, {sub, canonical}};
        JsonArray graph = new JsonArray(); JsonObject il = itemListLd(heading, canonical, list), bc = breadcrumbLd(crumbs);
        il.remove("@context"); bc.remove("@context"); graph.add(il); graph.add(bc);
        JsonObject root = new JsonObject(); root.addProperty("@context", "https://schema.org"); root.add("@graph", graph);

        StringBuilder subs = new StringBuilder();
        if (sub == null && cats.getAsJsonArray(cat).size() > 0) {
            subs.append("<ul class=\"chips cat-subs\">");
            for (JsonElement s : cats.getAsJsonArray(cat)) subs.append("<li><a href=\"/category/").append(slug(cat)).append("/").append(slug(s.getAsString())).append("\">").append(esc(s.getAsString())).append("</a></li>");
            subs.append("</ul>");
        }
        StringBuilder others = new StringBuilder("<nav class=\"cat-links\" aria-label=\"Shop categories\"><h2>Shop by category</h2><ul class=\"chips\">");
        for (String other : cats.keySet()) others.append("<li><a href=\"/category/").append(slug(other)).append("\">").append(esc(other)).append("</a></li>");
        others.append("</ul></nav>");
        String main = "<div class=\"wrap pdp-wrap\">" + breadcrumbHtml(crumbs) + "<header class=\"cat-head\"><h1>" + esc(heading) + "</h1><p class=\"sub\">" + intro + "</p>" + subs + "</header>"
            + (list.isEmpty() ? "<p class=\"empty\">No products in this category yet. <a href=\"/contact.html\">Ask us to source it</a>.</p>" : "<div class=\"grid\">" + gridCards(list, 100) + "</div>")
            + others + "<p class=\"cat-cta\">Cannot find what you need? <a href=\"/contact.html\">Tell us</a> and we will source it, or <a href=\"/instant-quote.html\">get a free 3D printing quote</a>.</p></div>";
        send(ex, 200, "text/html; charset=utf-8", renderPage(shell(clip(heading, 45) + " | 3DShop.lk", metaDesc, canonical, null, "website", ldScript(root), !list.isEmpty(), main), "category").getBytes(StandardCharsets.UTF_8),
                "public, max-age=0, must-revalidate", true);
    }

    // =============================== helpers
    record Part(String name, byte[] data) {}
    record FormPart(String fieldName, String fileName, byte[] data) {}

    static List<FormPart> parseMultipart(byte[] body, String boundary) throws IOException {
        List<FormPart> parts = new ArrayList<>();
        byte[] delimiter = ("--" + boundary).getBytes(StandardCharsets.ISO_8859_1);
        byte[] separator = "\r\n\r\n".getBytes(StandardCharsets.ISO_8859_1);
        byte[] nextBoundary = ("\r\n--" + boundary).getBytes(StandardCharsets.ISO_8859_1);
        int cursor = indexOf(body, delimiter, 0);
        while (cursor >= 0) {
            int headerStart = cursor + delimiter.length;
            if (headerStart + 1 < body.length && body[headerStart] == '-' && body[headerStart + 1] == '-') break;
            if (headerStart + 1 >= body.length || body[headerStart] != '\r' || body[headerStart + 1] != '\n') throw new IOException("Malformed multipart request.");
            headerStart += 2;
            int headerEnd = indexOf(body, separator, headerStart);
            if (headerEnd < 0) throw new IOException("Malformed multipart headers.");
            String header = new String(body, headerStart, headerEnd - headerStart, StandardCharsets.ISO_8859_1);
            int dataStart = headerEnd + separator.length;
            int dataEnd = indexOf(body, nextBoundary, dataStart);
            if (dataEnd < 0) throw new IOException("Malformed multipart body.");
            String disposition = header.lines().filter(line -> line.toLowerCase(Locale.ROOT).startsWith("content-disposition:")).findFirst().orElse("");
            String fieldName = dispositionParameter(disposition, "name");
            String fileName = dispositionParameter(disposition, "filename");
            if (!fieldName.isBlank()) parts.add(new FormPart(fieldName, fileName.isBlank() ? null : fileName, Arrays.copyOfRange(body, dataStart, dataEnd)));
            cursor = dataEnd + 2;
        }
        return parts;
    }

    static String dispositionParameter(String header, String parameter) {
        Matcher m = Pattern.compile("(?:^|;)\\s*" + Pattern.quote(parameter) + "=\"([^\"]*)\"", Pattern.CASE_INSENSITIVE).matcher(header);
        return m.find() ? m.group(1) : "";
    }

    static Part parseFile(byte[] body, String boundary) {
        byte[] delim = ("--" + boundary).getBytes(StandardCharsets.ISO_8859_1);
        int start = indexOf(body, delim, 0);
        while (start >= 0) {
            int hdrStart = start + delim.length + 2;
            int hdrEnd = indexOf(body, "\r\n\r\n".getBytes(), hdrStart);
            if (hdrEnd < 0) return null;
            String hdr = new String(body, hdrStart, hdrEnd - hdrStart, StandardCharsets.UTF_8);
            int dataStart = hdrEnd + 4;
            int next = indexOf(body, ("\r\n--" + boundary).getBytes(StandardCharsets.ISO_8859_1), dataStart);
            if (next < 0) return null;
            int fi = hdr.indexOf("filename=\"");
            if (fi >= 0) {
                String fn = hdr.substring(fi + 10, hdr.indexOf('"', fi + 10));
                fn = fn.substring(Math.max(fn.lastIndexOf('/'), fn.lastIndexOf('\\')) + 1);
                return new Part(fn, Arrays.copyOfRange(body, dataStart, next));
            }
            start = indexOf(body, delim, next);
        }
        return null;
    }

    static int indexOf(byte[] h, byte[] n, int from) {
        outer:
        for (int i = from; i <= h.length - n.length; i++) { for (int j = 0; j < n.length; j++) if (h[i + j] != n[j]) continue outer; return i; }
        return -1;
    }

    static byte[] readLimited(InputStream in, long max) throws IOException {
        var out = new ByteArrayOutputStream(); byte[] buf = new byte[65536]; long t = 0; int n;
        while ((n = in.read(buf)) > 0) { t += n; if (t > max) return null; out.write(buf, 0, n); }
        return out.toByteArray();
    }

    static boolean allow(String ip, String bucket, int perHour) { return allow(ip, bucket, perHour, 3600_000L); }

    static boolean allow(String ip, String bucket, int max, long windowMs) {
        long now = System.currentTimeMillis();
        var q = hits.computeIfAbsent(bucket + "|" + ip, k -> new ArrayDeque<>());
        synchronized (q) { while (!q.isEmpty() && now - q.peekFirst() > windowMs) q.pollFirst(); if (q.size() >= max) return false; q.addLast(now); return true; }
    }

    static int recentCount(String key, long windowMs) {
        long now = System.currentTimeMillis();
        var q = hits.computeIfAbsent(key, k -> new ArrayDeque<>());
        synchronized (q) { while (!q.isEmpty() && now - q.peekFirst() > windowMs) q.pollFirst(); return q.size(); }
    }

    static void record(String key) {
        var q = hits.computeIfAbsent(key, k -> new ArrayDeque<>());
        synchronized (q) { q.addLast(System.currentTimeMillis()); }
    }

    static String ip(HttpExchange ex) {
        if (Boolean.parseBoolean(CFG.getProperty("trustProxy", "false"))) {
            // the proxy appends the real client address last; earlier entries can be forged by the visitor
            String x = ex.getRequestHeaders().getFirst("X-Forwarded-For");
            if (x != null && !x.isBlank()) { String[] chain = x.split(","); return chain[chain.length - 1].trim(); }
        }
        return ex.getRemoteAddress().getAddress().getHostAddress();
    }

    static void rememberMaterial(JsonObject m) {
        if (materialsSeen.put(m.get("id").getAsString(), m) == null) {
            try { JsonObject all = new JsonObject(); materialsSeen.forEach(all::add); Files.writeString(DATA.resolve("materials.json"), GSON.toJson(all)); } catch (IOException ignored) {}
        }
    }

    static long roundTo(double v, int step) { return Math.round(v / step) * (long) step; }
    static double number(JsonObject o, String k) {
        try { return o.has(k) && !o.get(k).isJsonNull() ? o.get(k).getAsDouble() : Double.NaN; }
        catch (RuntimeException e) { return Double.NaN; }
    }
    static double num(BigDecimal b) { return b == null ? 0 : b.doubleValue(); }
    static String str(JsonObject o, String k) { return o.has(k) && !o.get(k).isJsonNull() ? o.get(k).getAsString() : ""; }
    static JsonObject ok() { var o = new JsonObject(); o.addProperty("ok", true); return o; }
    static JsonObject err(String m) { var o = new JsonObject(); o.addProperty("error", m); return o; }

    static void cors(HttpExchange ex) {
        String origin = CFG.getProperty("corsOrigin", "");     // only needed if the website is hosted on a different domain
        if (!origin.isBlank() && origin.equals(ex.getRequestHeaders().getFirst("Origin"))) {
            ex.getResponseHeaders().add("Access-Control-Allow-Origin", origin);
            ex.getResponseHeaders().add("Access-Control-Allow-Headers", "Content-Type");
            ex.getResponseHeaders().add("Access-Control-Allow-Methods", "GET,POST,PUT,OPTIONS");
        }
    }

    static void json(HttpExchange ex, int code, JsonElement o) throws IOException {
        cors(ex);
        byte[] b = COMPACT.toJson(o).getBytes(StandardCharsets.UTF_8);
        ex.getResponseHeaders().add("Content-Type", "application/json; charset=utf-8");
        ex.getResponseHeaders().add("Cache-Control", "no-store");
        ex.sendResponseHeaders(code, b.length);
        try (var out = ex.getResponseBody()) { out.write(b); }
    }
}
