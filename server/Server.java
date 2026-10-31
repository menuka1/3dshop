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
    static final Set<String> STATIC_EXT = Set.of("html", "css", "js", "png", "jpg", "jpeg", "webp", "svg", "ico", "txt");
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
    static final Map<String, Long> sessions = new ConcurrentHashMap<>();              // admin token -> expiry
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
    static void api(HttpExchange ex) throws IOException {
        try {
            String p = ex.getRequestURI().getPath(), m = ex.getRequestMethod();
            if (m.equals("OPTIONS")) { cors(ex); ex.sendResponseHeaders(204, -1); return; }
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
                case "POST /api/admin/logout" -> { sessions.remove(token(ex)); json(ex, 200, ok()); }
                case "GET /api/admin/settings" -> { if (admin(ex)) json(ex, 200, withMaterials()); }
                case "PUT /api/admin/settings" -> { if (admin(ex)) saveSettings(ex); }
                case "GET /api/admin/shop/catalog" -> { if (admin(ex)) json(ex, 200, shopCatalog); }
                case "PUT /api/admin/shop/catalog" -> { if (admin(ex)) saveShopCatalog(ex); }
                case "POST /api/admin/shop/image" -> { if (admin(ex)) uploadProductImage(ex); }
                case "GET /api/admin/quotes" -> { if (admin(ex)) recentQuotes(ex); }
                case "GET /api/admin/quote-requests" -> { if (admin(ex)) recentQuoteRequests(ex); }
                default -> json(ex, 404, err("Not found"));
            }
        } catch (Exception e) {
            e.printStackTrace();
            json(ex, 500, err("Server error. Please try again."));
        }
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
        ex.getResponseHeaders().add("Content-Type", "text/javascript; charset=utf-8");
        ex.getResponseHeaders().add("Cache-Control", "no-store");
        ex.getResponseHeaders().add("X-Content-Type-Options", "nosniff");
        ex.sendResponseHeaders(200, body.length);
        try (var out = ex.getResponseBody()) { out.write(body); }
    }

    static void saveShopCatalog(HttpExchange ex) throws IOException {
        byte[] body = readLimited(ex.getRequestBody(), 1_000_000);
        if (body == null) { json(ex, 413, err("Catalog data is too large.")); return; }
        JsonObject next;
        try { next = JsonParser.parseString(new String(body, StandardCharsets.UTF_8)).getAsJsonObject(); }
        catch (RuntimeException e) { json(ex, 400, err("Invalid catalog data.")); return; }
        String catalogError = shopCatalogValidationError(next);
        if (catalogError != null) { json(ex, 400, err(catalogError)); return; }
        synchronized (LOCK) {
            Files.writeString(DATA.resolve("shop-catalog.json"), GSON.toJson(next), StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
            shopCatalog = next;
        }
        json(ex, 200, ok());
    }

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
        byte[] body = readLimited(ex.getRequestBody(), 5L * 1024 * 1024 + 64 * 1024);
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
        byte[] body = readLimited(ex.getRequestBody(), 10_000);
        if (body == null) { json(ex, 413, err("Estimate request is too large.")); return; }
        JsonObject in = JsonParser.parseString(new String(body, StandardCharsets.UTF_8)).getAsJsonObject();
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
        if (!allow(ip(ex), "manual-quote", 10)) { json(ex, 429, err("Too many quote requests. Please try again later.")); return; }
        String contentType = ex.getRequestHeaders().getFirst("Content-Type");
        if (contentType == null || !contentType.toLowerCase(Locale.ROOT).startsWith("multipart/form-data") || !contentType.contains("boundary=")) {
            json(ex, 400, err("Expected a quote form submission.")); return;
        }
        String boundary = contentType.substring(contentType.indexOf("boundary=") + 9).replace("\"", "");
        if (boundary.isBlank() || boundary.length() > 200) { json(ex, 400, err("Invalid form boundary.")); return; }
        long bodyLimit = MAX_QUOTE_TOTAL_BYTES + 1024L * 1024;
        byte[] body = readLimited(ex.getRequestBody(), bodyLimit);
        if (body == null) { json(ex, 413, err("Quote request files exceed the 100 MB total limit.")); return; }
        List<FormPart> parts = parseMultipart(body, boundary);
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
            if (part.data().length > s.maxFileMB * 1024L * 1024L) { json(ex, 413, err("Each file must be no larger than " + s.maxFileMB + " MB.")); return; }
            totalFileBytes += part.data().length;
            if (totalFileBytes > MAX_QUOTE_TOTAL_BYTES) { json(ex, 413, err("Quote request files exceed the 100 MB total limit.")); return; }
            files.add(new FormPart("files", originalName, part.data()));
        }
        String name = fields.getOrDefault("name", "").trim();
        String phone = fields.getOrDefault("phone", "").trim();
        String details = fields.getOrDefault("details", "").trim();
        String type = fields.getOrDefault("type", "Print my model").trim();
        String material = fields.getOrDefault("material", "Not sure").trim();
        String colour = fields.getOrDefault("colour", "Not sure").trim();
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

    // =============================== customer: upload STL -> analysis -> materials
    static void upload(HttpExchange ex) throws Exception {
        var s = settings;
        if (!s.quotesEnabled || jlc == null) { json(ex, 503, err("Online quotes are paused. Please request a quote on WhatsApp.")); return; }
        if (!allow(ip(ex), "up", s.maxUploadsPerHourPerIp)) { json(ex, 429, err("Too many uploads. Please try again later or message us on WhatsApp.")); return; }
        long max = s.maxFileMB * 1024L * 1024L;
        String ct = ex.getRequestHeaders().getFirst("Content-Type");
        if (ct == null || !ct.startsWith("multipart/form-data") || !ct.contains("boundary=")) { json(ex, 400, err("Expected a file upload.")); return; }
        byte[] body = readLimited(ex.getRequestBody(), max + 64 * 1024);
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
        JsonObject in = JsonParser.parseString(new String(readLimited(ex.getRequestBody(), 20_000), StandardCharsets.UTF_8)).getAsJsonObject();
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
    static void adminLogin(HttpExchange ex) throws Exception {
        if (!allow(ip(ex), "login", 10)) { json(ex, 429, err("Too many attempts. Try again later.")); return; }
        JsonObject in = JsonParser.parseString(new String(readLimited(ex.getRequestBody(), 2000), StandardCharsets.UTF_8)).getAsJsonObject();
        String pw = System.getenv().getOrDefault("ADMIN_PASSWORD", CFG.getProperty("adminPassword", ""));
        if (pw.length() < 8 || !MessageDigest.isEqual(pw.getBytes(StandardCharsets.UTF_8), str(in, "password").getBytes(StandardCharsets.UTF_8))) {
            Thread.sleep(600); json(ex, 401, err("Wrong password.")); return;
        }
        byte[] b = new byte[32]; RNG.nextBytes(b);
        String tok = HexFormat.of().formatHex(b);
        sessions.put(tok, System.currentTimeMillis() + 8 * 3600_000L);
        boolean secure = Boolean.parseBoolean(CFG.getProperty("cookieSecure", "false"));
        ex.getResponseHeaders().add("Set-Cookie", "adm=" + tok + "; Path=/; HttpOnly; SameSite=Strict; Max-Age=28800" + (secure ? "; Secure" : ""));
        json(ex, 200, ok());
    }

    static String token(HttpExchange ex) {
        String c = ex.getRequestHeaders().getFirst("Cookie");
        if (c == null) return "";
        for (String p : c.split(";")) { p = p.trim(); if (p.startsWith("adm=")) return p.substring(4); }
        return "";
    }

    static boolean admin(HttpExchange ex) throws IOException {
        Long exp = sessions.get(token(ex));
        if (exp == null || exp < System.currentTimeMillis()) { json(ex, 401, err("Please log in.")); return false; }
        return true;
    }

    static JsonObject withMaterials() {
        JsonObject o = COMPACT.toJsonTree(settings).getAsJsonObject();
        JsonArray arr = new JsonArray(); materialsSeen.values().forEach(arr::add);
        o.add("knownMaterials", arr);
        return o;
    }

    static void saveSettings(HttpExchange ex) throws Exception {
        Settings n = COMPACT.fromJson(new String(readLimited(ex.getRequestBody(), 100_000), StandardCharsets.UTF_8), Settings.class);
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
        synchronized (LOCK) { Files.writeString(DATA.resolve("settings.json"), GSON.toJson(n)); settings = n; }
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
        ex.getResponseHeaders().add("X-Content-Type-Options", "nosniff");
        ex.getResponseHeaders().add("Cache-Control", "no-store");
        ex.sendResponseHeaders(200, Files.size(target));
        try (var out = ex.getResponseBody()) { Files.copy(target, out); }
    }

    static void adminPage(HttpExchange ex) throws IOException {
        Path f = SRV.resolve("admin").resolve("admin.html");
        byte[] b = Files.readAllBytes(f);
        ex.getResponseHeaders().add("Content-Type", "text/html; charset=utf-8");
        ex.getResponseHeaders().add("Cache-Control", "no-store");
        ex.getResponseHeaders().add("X-Robots-Tag", "noindex");
        ex.sendResponseHeaders(200, b.length);
        try (var o = ex.getResponseBody()) { o.write(b); }
    }

    // =============================== static website (whitelisted types only; never server/, .git, dotfiles)
    static void staticFile(HttpExchange ex) throws IOException {
        String p = ex.getRequestURI().getPath();
        if (p.equals("/")) p = "/index.html";
        Path f = ROOT.resolve("." + p).normalize();
        String rel = ROOT.relativize(f).toString().replace('\\', '/');
        String ext = rel.contains(".") ? rel.substring(rel.lastIndexOf('.') + 1).toLowerCase() : "";
        boolean bad = !f.startsWith(ROOT) || rel.startsWith("server") || Arrays.stream(rel.split("/")).anyMatch(x -> x.startsWith(".")) || !STATIC_EXT.contains(ext);
        if (bad || !Files.isRegularFile(f)) { byte[] nf = "Not found".getBytes(); ex.sendResponseHeaders(404, nf.length); try (var o = ex.getResponseBody()) { o.write(nf); } return; }
        String type = switch (ext) { case "html" -> "text/html; charset=utf-8"; case "css" -> "text/css"; case "js" -> "text/javascript"; case "png" -> "image/png"; case "jpg", "jpeg" -> "image/jpeg"; case "webp" -> "image/webp"; case "svg" -> "image/svg+xml"; case "ico" -> "image/x-icon"; default -> "text/plain; charset=utf-8"; };
        ex.getResponseHeaders().add("Content-Type", type);
        ex.getResponseHeaders().add("X-Content-Type-Options", "nosniff");
        ex.getResponseHeaders().add("Cache-Control", ext.equals("html") ? "no-cache" : "public, max-age=3600");
        long len = Files.size(f);
        ex.sendResponseHeaders(200, len);
        try (var o = ex.getResponseBody()) { Files.copy(f, o); }
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

    static boolean allow(String ip, String bucket, int perHour) {
        long now = System.currentTimeMillis();
        var q = hits.computeIfAbsent(bucket + ip, k -> new ArrayDeque<>());
        synchronized (q) { while (!q.isEmpty() && now - q.peekFirst() > 3600_000L) q.pollFirst(); if (q.size() >= perHour) return false; q.addLast(now); return true; }
    }

    static String ip(HttpExchange ex) {
        if (Boolean.parseBoolean(CFG.getProperty("trustProxy", "false"))) {
            String x = ex.getRequestHeaders().getFirst("X-Forwarded-For");
            if (x != null && !x.isBlank()) return x.split(",")[0].trim();
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
