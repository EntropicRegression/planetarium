package app.planetarium.studio

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.util.Base64
import android.util.Log
import app.planetarium.pack.PackStore
import app.planetarium.ui.ShaderPackImporter
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.HttpURLConnection
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.URL
import java.net.URLDecoder
import java.net.URLEncoder
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.UUID
import java.util.concurrent.Executors
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * The local helper behind the AI studio screen: the Kotlin counterpart of the editor's
 * `serve.py` (and of the desktop program's `server.rs`), kept route for route the same so the
 * editor's page does not care which one it talks to.
 *
 *  - serves the editor's files, which are packed into the app (assets/editor);
 *  - runs "Sign in with ChatGPT" (OpenAI sends the sign-in back to a loopback address) and keeps
 *    the tokens in the app's private storage; the page never sees them;
 *  - forwards a fixed list of requests to api.openai.com;
 *  - turns an exported pack into a wallpaper in the library (`/api/save`), and hands a library
 *    pack to the page for editing (`/api/app/pack`).
 *
 * It listens on 127.0.0.1 only and refuses API requests that do not come from its own page.
 * This is the only part of the app that uses the network; the wallpaper itself never does.
 */
object StudioServer {
    private const val TAG = "StudioServer"

    /** The usual port, so the page keeps one origin (and with it the autosave and settings). */
    private const val PORT = 8767
    private const val ASSETS = "editor"

    // OpenAI's sign-in for locally run, open-source apps ("Sign in with ChatGPT").
    private const val AUTH_HOST = "auth.openai.com"
    private const val AUTHORIZE_URL = "https://auth.openai.com/api/accounts/authorize"
    private const val TOKEN_URL = "https://auth.openai.com/api/accounts/oauth/token"
    private const val API_ORIGIN = "https://api.openai.com"
    private const val RESOURCE = "https://api.openai.com/v1"
    private const val SCOPE = "openid profile email offline_access resource.invoke chatgpt.tokens.use.direct"

    /** A first sign-in registers this app and gets its own client id back, which is used from then on. */
    private const val FIRST_CLIENT_ID = "dynamic_agent_client"
    private const val APP_NAME = "Planetarium Spatial Editor"

    /** The only things the page may ask the helper to forward. */
    private val FORWARDABLE = setOf(
        "POST /v1/responses",
        "POST /v1/images/generations",
        "POST /v1/images/edits",
        "GET /v1/models",
    )

    private const val MAX_HEADER_BYTES = 64 * 1024
    private const val MAX_BODY_BYTES = 256 * 1024 * 1024

    private val TYPES = mapOf(
        "html" to "text/html; charset=utf-8",
        "css" to "text/css; charset=utf-8",
        "js" to "text/javascript; charset=utf-8",
        "json" to "application/json",
        "png" to "image/png",
        "jpg" to "image/jpeg",
        "svg" to "image/svg+xml",
        "txt" to "text/plain; charset=utf-8",
    )

    /** The sign-in in progress. One at a time. */
    private class Pending(val state: String, val nonce: String, val verifier: String, val redirectUri: String, val clientId: String)

    private val workers = Executors.newCachedThreadPool { task -> Thread(task, "studio-http").apply { isDaemon = true } }
    private val random = SecureRandom()
    private val storeLock = Any()

    private lateinit var app: Context
    @Volatile private var port = 0
    @Volatile private var pending: Pending? = null

    /** Why the last sign-in failed, shown by the page; cleared when a new one starts. */
    @Volatile private var lastError = ""

    /** Starts the server once per process and returns the address of the editor's page. */
    @Synchronized
    fun start(context: Context): String {
        if (port == 0) {
            app = context.applicationContext
            val loopback = InetAddress.getByName("127.0.0.1")
            // If the usual port is taken, any free one will do (the page then starts with empty
            // settings, as a different origin).
            val socket = try {
                ServerSocket(PORT, 50, loopback)
            } catch (e: IOException) {
                ServerSocket(0, 50, loopback)
            }
            port = socket.localPort
            Thread({ accept(socket) }, "studio-accept").apply { isDaemon = true }.start()
        }
        return "http://127.0.0.1:$port/"
    }

    private fun accept(server: ServerSocket) {
        while (true) {
            val socket = try {
                server.accept()
            } catch (e: IOException) {
                Log.w(TAG, "the local server stopped", e)
                return
            }
            workers.execute {
                try {
                    socket.use { serve(it) }
                } catch (e: IOException) {
                    // The page stopped listening (the person pressed Stop, or left the screen).
                } catch (e: Exception) {
                    Log.w(TAG, "request failed", e)
                }
            }
        }
    }

    // ------------------------------------------------------------------------ HTTP plumbing

    private class Request(val method: String, val path: String, val query: String, val headers: Map<String, String>, val body: ByteArray) {
        fun header(name: String): String? = headers[name]
    }

    private fun readRequest(input: InputStream): Request? {
        val head = ByteArrayOutputStream()
        var matched = 0
        val end = byteArrayOf(13, 10, 13, 10)
        while (matched < 4) {
            val b = input.read()
            if (b < 0) return null
            head.write(b)
            matched = if (b.toByte() == end[matched]) matched + 1 else if (b == 13) 1 else 0
            if (head.size() > MAX_HEADER_BYTES) throw IOException("request headers too large")
        }
        val lines = head.toString("ISO-8859-1").split("\r\n")
        val start = lines.first().split(' ')
        if (start.size < 2) return null
        val headers = HashMap<String, String>()
        for (line in lines.drop(1)) {
            val colon = line.indexOf(':')
            if (colon > 0) headers[line.substring(0, colon).trim().lowercase()] = line.substring(colon + 1).trim()
        }
        val length = headers["content-length"]?.toIntOrNull() ?: 0
        if (length < 0 || length > MAX_BODY_BYTES) throw IOException("request body too large")
        val body = ByteArray(length)
        var read = 0
        while (read < length) {
            val n = input.read(body, read, length - read)
            if (n < 0) throw IOException("request body cut short")
            read += n
        }
        val target = start[1]
        val mark = target.indexOf('?')
        return Request(
            method = start[0].uppercase(),
            path = if (mark < 0) target else target.substring(0, mark),
            query = if (mark < 0) "" else target.substring(mark + 1),
            headers = headers,
            body = body,
        )
    }

    private fun reason(status: Int) = when (status) {
        200 -> "OK"; 400 -> "Bad Request"; 401 -> "Unauthorized"; 403 -> "Forbidden"; 404 -> "Not Found"
        500 -> "Internal Server Error"; 502 -> "Bad Gateway"; else -> "Status"
    }

    /** Writes the status line and headers. The body follows; the connection closes after it. */
    private fun head(out: OutputStream, status: Int, contentType: String, cache: String, length: Int? = null) {
        val text = StringBuilder("HTTP/1.1 $status ${reason(status)}\r\n")
            .append("Content-Type: $contentType\r\n")
            .append("Cache-Control: $cache\r\n")
            .append("Connection: close\r\n")
        if (length != null) text.append("Content-Length: $length\r\n")
        out.write(text.append("\r\n").toString().toByteArray(Charsets.ISO_8859_1))
    }

    private fun send(out: OutputStream, status: Int, contentType: String, cache: String, body: ByteArray) {
        head(out, status, contentType, cache, body.size)
        out.write(body)
        out.flush()
    }

    private fun sendJson(out: OutputStream, status: Int, body: JSONObject) =
        send(out, status, "application/json", "no-store", body.toString().toByteArray())

    private fun sendPage(out: OutputStream, status: Int, title: String, message: String, backLink: Boolean = false) {
        fun escape(s: String) = s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
        // A page in the browser cannot bring the app forward by itself; the link does, on a tap.
        val link = if (backLink) "<p><a href=\"planetarium://studio\" style=\"display:inline-block;padding:12px 20px;border-radius:10px;background:#8f7bff;color:#14151a;font-weight:600;text-decoration:none\">回到 Planetarium</a></p>" else ""
        val html = "<!doctype html><meta charset=\"utf-8\"><meta name=\"viewport\" content=\"width=device-width, initial-scale=1\"><title>${escape(title)}</title>" +
            "<body style=\"font:16px system-ui;background:#14151a;color:#e7e8ee;display:grid;place-items:center;height:100vh;margin:0\">" +
            "<div style=\"max-width:520px;padding:24px;text-align:center\"><h1 style=\"font-size:20px\">${escape(title)}</h1><p>${escape(message)}</p>$link</div>"
        send(out, status, "text/html; charset=utf-8", "no-store", html.toByteArray())
    }

    // ------------------------------------------------------------------------ guards

    /** Whether the request is addressed to, and (if it says) comes from, this helper's page. */
    private fun ownOrigin(request: Request): Boolean {
        val allowed = setOf("127.0.0.1:$port", "localhost:$port")
        if (request.header("host") !in allowed) return false // a different name pointing at 127.0.0.1 (DNS rebinding)
        val origin = request.header("origin") ?: return true
        return origin in allowed.map { "http://$it" }
    }

    /** A header a plain cross-site form or link cannot set. */
    private fun apiAllowed(request: Request) = ownOrigin(request) && request.header("x-wall-editor") == "1"

    // ------------------------------------------------------------------------ routes

    private fun serve(socket: Socket) {
        socket.soTimeout = 30_000
        val request = readRequest(socket.getInputStream()) ?: return
        socket.soTimeout = 0
        val out = socket.getOutputStream().buffered()
        val path = request.path

        if (request.method == "GET" && path == "/auth/callback") {
            if (!ownOrigin(request)) return sendPage(out, 403, "Refused", "This request did not come from the editor.")
            val error = try {
                finishSignIn(parseQuery(request.query))
            } catch (e: IOException) {
                "Could not reach OpenAI: ${e.message}"
            }
            lastError = error
            return if (error.isEmpty()) sendPage(out, 200, "登入完成", "回到 Planetarium 繼續。", backLink = true)
            else sendPage(out, 400, "登入失敗", error, backLink = true)
        }

        if (path.startsWith("/api/")) {
            if (!apiAllowed(request)) return sendJson(out, 403, JSONObject().put("error", "refused"))
            val openai = path.removePrefix("/api/openai").takeIf { path.startsWith("/api/openai/v1/") }
            return when {
                request.method == "GET" && path == "/api/status" ->
                    sendJson(out, 200, JSONObject().put("helper", true).put("app", true).put("openai", sessionStatus()))
                request.method == "POST" && path == "/api/openai/signin" -> {
                    val url = startSignIn()
                    sendJson(out, 200, JSONObject().put("url", url).put("opened", openInBrowser(url)))
                }
                request.method == "POST" && path == "/api/openai/signout" -> {
                    signOut()
                    sendJson(out, 200, JSONObject().put("ok", true))
                }
                request.method == "POST" && path == "/api/save" -> savePack(request, out)
                request.method == "GET" && path == "/api/app/pack" -> sendPack(parseQuery(request.query)["id"].orEmpty(), out)
                openai != null && (request.method == "GET" || request.method == "POST") -> forward(request, openai, out)
                else -> sendJson(out, 404, JSONObject().put("error", "not found"))
            }
        }

        if (request.method != "GET" && request.method != "HEAD") return sendJson(out, 403, JSONObject().put("error", "refused"))
        serveFile(path, out)
    }

    private fun parseQuery(query: String): Map<String, String> {
        val map = LinkedHashMap<String, String>()
        for (pair in query.split('&')) {
            if (pair.isEmpty()) continue
            val eq = pair.indexOf('=')
            val key = URLDecoder.decode(if (eq < 0) pair else pair.substring(0, eq), "UTF-8")
            val value = if (eq < 0) "" else URLDecoder.decode(pair.substring(eq + 1), "UTF-8")
            if (key !in map) map[key] = value
        }
        return map
    }

    // ------------------------------------------------------------------------ the editor's files

    private fun serveFile(path: String, out: OutputStream) {
        val decoded = try {
            URLDecoder.decode(path, "UTF-8")
        } catch (e: IllegalArgumentException) {
            path
        }
        val relative = decoded.trimStart('/').ifEmpty { "index.html" }
        // Hidden files and anything that climbs out of the folder are not served.
        val bad = relative.contains('\\') || relative.split('/').any { it.isEmpty() || it.startsWith(".") }
        val bytes = if (bad) null else try {
            app.assets.open("$ASSETS/$relative").use { it.readBytes() }
        } catch (e: IOException) {
            null
        }
        if (bytes == null) return send(out, 404, "text/plain", "no-store", "not found".toByteArray())
        val type = TYPES[relative.substringAfterLast('.', "").lowercase()] ?: "application/octet-stream"
        if (relative == "index.html") {
            // Tells the page it runs inside the phone app: it lays itself out for a small touch
            // screen, and exporting adds the wallpaper to the library instead of saving a file.
            val html = bytes.decodeToString().replaceFirst("</head>", "<script>window.WALL_APP = true;</script>\n</head>")
            return send(out, 200, type, "no-cache", html.toByteArray())
        }
        send(out, 200, type, "no-cache", bytes)
    }

    // ------------------------------------------------------------------------ library

    /** A pack exported by the page becomes a wallpaper in the library, and the one in use. */
    private fun savePack(request: Request, out: OutputStream) {
        val name = try {
            URLDecoder.decode(request.header("x-wall-filename").orEmpty(), "UTF-8")
        } catch (e: IllegalArgumentException) {
            ""
        }
        if (!name.endsWith(".zip", ignoreCase = true)) {
            return sendJson(out, 400, JSONObject().put("error", "Only wallpaper packs can be saved in the app."))
        }
        val store = PackStore(app)
        when (val outcome = ShaderPackImporter.importZipStream(app, store, ByteArrayInputStream(request.body))) {
            is ShaderPackImporter.Outcome.Imported -> {
                store.setActive(outcome.pack.id, PackStore.Target.HOME, PackStore.Target.LOCK)
                sendJson(out, 200, JSONObject().put("saved", true).put("applied", true).put("id", outcome.pack.id))
            }
            is ShaderPackImporter.Outcome.Rejected ->
                sendJson(out, 400, JSONObject().put("error", "${outcome.title}:${outcome.detail}"))
        }
    }

    /** A pack from the library as a zip, for the page to open and change. */
    private fun sendPack(id: String, out: OutputStream) {
        val pack = (if (id.matches(Regex("[A-Za-z0-9-]{1,64}"))) PackStore(app).load(id) else null)
            ?: return sendJson(out, 404, JSONObject().put("error", "no such wallpaper"))
        val zipped = ByteArrayOutputStream()
        ZipOutputStream(zipped).use { zip ->
            zip.setLevel(0) // pictures and video are compressed already
            pack.dir.walkTopDown().filter { it.isFile }.forEach { file ->
                zip.putNextEntry(ZipEntry(file.relativeTo(pack.dir).invariantSeparatorsPath))
                file.inputStream().use { it.copyTo(zip) }
                zip.closeEntry()
            }
        }
        send(out, 200, "application/zip", "no-store", zipped.toByteArray())
    }

    // ------------------------------------------------------------------------ stored session

    private fun storeFile() = File(File(app.filesDir, "studio").apply { mkdirs() }, "openai.json")

    private fun loadStore(): JSONObject = try {
        JSONObject(storeFile().readText())
    } catch (e: Exception) {
        JSONObject()
    }

    private fun saveStore(store: JSONObject) {
        val file = storeFile()
        val tmp = File(file.path + ".tmp")
        tmp.writeText(store.toString(2))
        if (!tmp.renameTo(file)) throw IOException("could not save the sign-in")
    }

    private fun forgetTokens(store: JSONObject) {
        for (key in listOf("access_token", "refresh_token", "id_token", "expires_at")) store.remove(key)
    }

    private fun keepTokens(store: JSONObject, tokens: JSONObject) {
        store.put("access_token", tokens.getString("access_token"))
        // Refresh tokens rotate: each use returns the next one, and the old one stops working.
        for (key in listOf("refresh_token", "id_token")) {
            tokens.optString(key).takeIf { it.isNotEmpty() }?.let { store.put(key, it) }
        }
        val lifetime = tokens.optDouble("expires_in", 3600.0).takeIf { it > 0 } ?: 3600.0
        store.put("expires_at", System.currentTimeMillis() / 1000.0 + lifetime)
        saveStore(store)
    }

    /** The payload of a JWT, without checking its signature. */
    private fun claimsOf(jwt: String): JSONObject = try {
        JSONObject(String(Base64.decode(jwt.split('.')[1], Base64.URL_SAFE or Base64.NO_PADDING or Base64.NO_WRAP)))
    } catch (e: Exception) {
        JSONObject()
    }

    private fun describe(body: JSONObject): String {
        val error = body.opt("error")
        if (error is JSONObject) return error.optString("message").ifEmpty { error.toString() }
        return body.optString("error_description").ifEmpty { (error as? String).orEmpty() }.ifEmpty { "unknown error" }
    }

    private fun form(fields: List<Pair<String, String>>) =
        fields.joinToString("&") { (k, v) -> URLEncoder.encode(k, "UTF-8") + "=" + URLEncoder.encode(v, "UTF-8") }

    /** POSTs a form to OpenAI's token endpoint. Returns (status, parsed JSON body). */
    private fun tokenRequest(fields: List<Pair<String, String>>): Pair<Int, JSONObject> {
        val connection = URL(TOKEN_URL).openConnection() as HttpURLConnection
        try {
            connection.connectTimeout = 30_000
            connection.readTimeout = 30_000
            connection.requestMethod = "POST"
            connection.doOutput = true
            connection.setRequestProperty("Content-Type", "application/x-www-form-urlencoded")
            connection.setRequestProperty("Accept", "application/json")
            connection.outputStream.use { it.write(form(fields).toByteArray()) }
            val status = connection.responseCode
            val raw = (if (status in 200..299) connection.inputStream else connection.errorStream)?.use { it.readBytes() } ?: ByteArray(0)
            val parsed = try {
                JSONObject(raw.decodeToString())
            } catch (e: Exception) {
                JSONObject().put("error", raw.decodeToString().take(300))
            }
            return status to parsed
        } finally {
            connection.disconnect()
        }
    }

    /** A usable access token, refreshed if it is about to expire. Null when not signed in. */
    private fun accessToken(forceRefresh: Boolean): String? = synchronized(storeLock) {
        val store = loadStore()
        val current = store.optString("access_token")
        if (current.isEmpty()) return null
        if (!forceRefresh && store.optDouble("expires_at", 0.0) - System.currentTimeMillis() / 1000.0 > 120) return current
        val refresh = store.optString("refresh_token")
        if (refresh.isEmpty()) return if (forceRefresh) null else current
        val (status, body) = try {
            tokenRequest(listOf("grant_type" to "refresh_token", "client_id" to store.optString("client_id"), "refresh_token" to refresh, "resource" to RESOURCE))
        } catch (e: IOException) {
            // OpenAI could not be reached: keep the sign-in, and let the request itself report it.
            return if (forceRefresh) null else current
        }
        if (status != 200 || body.optString("access_token").isEmpty()) {
            // The sign-in has lapsed (refresh tokens last 30 days) or was revoked.
            forgetTokens(store)
            saveStore(store)
            return null
        }
        keepTokens(store, body)
        store.getString("access_token")
    }

    private fun sessionStatus(): JSONObject {
        val store = loadStore()
        val claims = claimsOf(store.optString("id_token"))
        return JSONObject()
            .put("signedIn", store.optString("access_token").isNotEmpty())
            .put("account", claims.optString("email").ifEmpty { claims.optString("name") })
            .put("error", lastError)
    }

    // ------------------------------------------------------------------------ sign-in

    private fun randomToken(bytes: Int): String {
        val raw = ByteArray(bytes).also(random::nextBytes)
        return Base64.encodeToString(raw, Base64.URL_SAFE or Base64.NO_PADDING or Base64.NO_WRAP)
    }

    private fun startSignIn(): String = synchronized(storeLock) {
        val store = loadStore()
        // Identifies this installation to OpenAI: a UUID URN, chosen once and sent every time.
        if (!store.optString("host_id").startsWith("urn:uuid:")) {
            store.put("host_id", "urn:uuid:${UUID.randomUUID()}")
            saveStore(store)
        }
        val verifier = randomToken(48)
        val digest = MessageDigest.getInstance("SHA-256").digest(verifier.toByteArray())
        val challenge = Base64.encodeToString(digest, Base64.URL_SAFE or Base64.NO_PADDING or Base64.NO_WRAP)
        val clientId = store.optString("client_id").ifEmpty { FIRST_CLIENT_ID }
        val attempt = Pending(
            state = randomToken(24),
            nonce = randomToken(24),
            verifier = verifier,
            // OpenAI accepts a loopback redirect on 127.0.0.1 (not "localhost"); only the port may vary.
            redirectUri = "http://127.0.0.1:$port/auth/callback",
            clientId = clientId,
        )
        val params = mutableListOf(
            "response_type" to "code",
            "client_id" to clientId,
            "redirect_uri" to attempt.redirectUri,
            "scope" to SCOPE,
            "resource" to RESOURCE,
            "code_challenge" to challenge,
            "code_challenge_method" to "S256",
            "state" to attempt.state,
            "nonce" to attempt.nonce,
            "ext_agent_host_id" to store.getString("host_id"),
        )
        if (clientId == FIRST_CLIENT_ID) {
            params += "agent_name_hint" to APP_NAME // shown to the person; only sent when registering
        } else if (store.optString("id_token").isNotEmpty()) {
            // Signing in again: tells OpenAI which account this was, even if that token has expired.
            params += "id_token_hint" to store.getString("id_token")
        }
        pending = attempt
        lastError = ""
        "$AUTHORIZE_URL?${form(params)}"
    }

    /** The sign-in page opens in the phone's browser, which then calls back to this server. */
    private fun openInBrowser(url: String): Boolean = try {
        app.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        true
    } catch (e: Exception) {
        Log.w(TAG, "no browser for the sign-in", e)
        false
    }

    /** Handles OpenAI's redirect back. Returns an error message, or "" on success. */
    private fun finishSignIn(query: Map<String, String>): String {
        val attempt = synchronized(storeLock) {
            val current = pending
            // Only the redirect that carries this sign-in's state ends it; anything else that hits
            // this address leaves the sign-in in progress untouched.
            if (current == null || !MessageDigest.isEqual(query["state"].orEmpty().toByteArray(), current.state.toByteArray())) {
                return "This sign-in was not started from the editor, or it was started twice. Start again from the editor."
            }
            pending = null
            current
        }
        query["error"]?.takeIf { it.isNotEmpty() }?.let { return query["error_description"].orEmpty().ifEmpty { it } }
        val code = query["code"].orEmpty()
        if (code.isEmpty()) return "OpenAI sent no authorization code."
        val clientId = query["client_id"].orEmpty().ifEmpty { attempt.clientId }
        if (clientId == FIRST_CLIENT_ID) return "OpenAI did not issue this app a client id; the registration is incomplete. Try again."
        if (attempt.clientId != FIRST_CLIENT_ID && clientId != attempt.clientId) return "The sign-in came back for a different app than the one that asked."
        val (status, body) = tokenRequest(
            listOf(
                "grant_type" to "authorization_code",
                "client_id" to clientId,
                "code" to code,
                "code_verifier" to attempt.verifier,
                "redirect_uri" to attempt.redirectUri,
                "resource" to RESOURCE,
            ),
        )
        if (status != 200 || body.optString("access_token").isEmpty()) return "OpenAI refused the sign-in: ${describe(body)}"

        // The ID token came straight from OpenAI over TLS, which stands in for checking its signature
        // (OpenID Connect Core 3.1.3.7); its claims still have to be the ones this sign-in asked for.
        val claims = claimsOf(body.optString("id_token"))
        if (claims.length() > 0) {
            val audiences = when (val aud = claims.opt("aud")) {
                is JSONArray -> (0 until aud.length()).map { aud.optString(it) }
                else -> listOf(aud?.toString().orEmpty())
            }
            if (claims.optString("iss").trimEnd('/') != "https://$AUTH_HOST") return "The sign-in came from an unexpected issuer."
            if (clientId !in audiences) return "The sign-in was issued for a different app."
            if (claims.optString("nonce") != attempt.nonce) return "The sign-in does not match the request that started it."
        }
        val granted = body.optString("scope").ifEmpty { SCOPE }
        if ("chatgpt.tokens.use.direct" !in granted.split(' ')) return "This account did not grant use of its ChatGPT plan (it may not be eligible)."

        synchronized(storeLock) {
            val store = loadStore()
            store.put("client_id", clientId)
            keepTokens(store, body)
        }
        return ""
    }

    private fun signOut() = synchronized(storeLock) {
        val store = loadStore()
        forgetTokens(store)
        saveStore(store)
    }

    // ------------------------------------------------------------------------ forwarding

    private fun forward(request: Request, path: String, out: OutputStream) {
        if ("${request.method} $path" !in FORWARDABLE) {
            return sendJson(out, 404, JSONObject().put("error", "this endpoint is not forwarded"))
        }
        val useSession = request.header("x-wall-auth") == "session"

        fun attempt(forceRefresh: Boolean): HttpURLConnection? {
            val authorization = if (useSession) "Bearer ${accessToken(forceRefresh) ?: return null}" else request.header("authorization").orEmpty()
            val connection = URL(API_ORIGIN + path).openConnection() as HttpURLConnection
            connection.connectTimeout = 30_000
            connection.readTimeout = 600_000
            connection.requestMethod = request.method
            connection.setRequestProperty("Authorization", authorization)
            connection.setRequestProperty("Accept", request.header("accept") ?: "*/*")
            if (request.body.isNotEmpty()) {
                connection.doOutput = true
                connection.setRequestProperty("Content-Type", request.header("content-type") ?: "application/json")
                connection.setFixedLengthStreamingMode(request.body.size)
                connection.outputStream.use { it.write(request.body) }
            }
            return connection
        }

        val notSignedIn = JSONObject().put(
            "error",
            JSONObject().put("message", "Not signed in to ChatGPT. Sign in from the editor settings.").put("code", "not_signed_in"),
        )
        var connection: HttpURLConnection? = null
        try {
            val status: Int
            try {
                connection = attempt(false) ?: return sendJson(out, 401, notSignedIn)
                var code = connection.responseCode
                if (useSession && code == 401) {
                    // The token was rejected early: refresh it once and try again.
                    connection.disconnect()
                    connection = attempt(true) ?: return sendJson(out, 401, notSignedIn)
                    code = connection.responseCode
                }
                status = code
            } catch (e: IOException) {
                return sendJson(out, 502, JSONObject().put("error", JSONObject().put("message", "Could not reach api.openai.com: ${e.message}")))
            }
            head(out, status, connection.contentType ?: "application/json", "no-store")
            out.flush()
            // Passed on as it arrives, so a streamed reply reaches the page token by token.
            val upstream = (if (status in 200..299) connection.inputStream else connection.errorStream) ?: return
            upstream.use { stream ->
                val buffer = ByteArray(16 * 1024)
                while (true) {
                    val n = stream.read(buffer)
                    if (n < 0) break
                    out.write(buffer, 0, n)
                    out.flush()
                }
            }
        } finally {
            connection?.disconnect()
        }
    }
}
