// SPDX-License-Identifier: MPL-2.0
package com.dsha.sidecar.net.http.native

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.nio.charset.StandardCharsets
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.util.Collections
import java.util.Locale
import java.util.zip.DeflaterOutputStream
import java.util.zip.GZIPInputStream
import java.util.zip.GZIPOutputStream
import java.util.zip.InflaterInputStream
import com.dsha.sidecar.net.http.HeaderTransforms
import com.dsha.sidecar.net.http.ResponseHeadersResult
import com.dsha.sidecar.net.rules.native.*

/**
 * Bounded entity executor. InputStream must contain exactly one framed/dechunked response entity,
 * not raw chunked wire bytes or a live keep-alive socket. Callers own streams and transport deadlines.
 */
class RuleActionExecutor(private val limits: ActionLimits = ActionLimits()) {
    fun executeRequest(plan: RequestPlan, request: RuleRequest, dateHeader: String? = null): RequestExecution {
        supportedPlan(plan)
        for (step in plan.requestSteps) when (val action = step.action) {
            is RequestAction.CacheLookup -> unsupported("cacheRequest", "Cache lookup requires a separate scheduler")
            is RequestAction.Retry -> if (action.descriptor != null) unsupported("retry", "Retry/body replay is not executed here")
            is RequestAction.Proxy -> if (action.backup != null) unsupported("backup", "Backup ranking is not executed here")
            is RequestAction.Unsupported -> unsupported(action.name, "Unsupported request action")
            else -> Unit
        }
        plan.localResponse?.let { response ->
            val headers = fromMap(response.headers).toMutableList()
            validateEntityFraming(headers)
            if (values(headers, "content-encoding").any { !it.equals("identity", true) }) {
                unsupported("static.content-encoding", "Static text is UTF-8; no static compression action exists")
            }
            if (response.addDateAtExecution) {
                val date = dateHeader ?: unsupported("options.Date", "Caller must supply a Date value; executor has no clock")
                try {
                    ZonedDateTime.parse(date, DateTimeFormatter.RFC_1123_DATE_TIME)
                } catch (failure: RuntimeException) {
                    invalid("options.Date", "Expected an RFC 1123 Date", failure)
                }
                set(headers, "Date", date)
            }
            if (response.body.length > limits.maximumOutputBytes) limit("static", "Static body exceeds output limit")
            val entity = response.body.toByteArray(StandardCharsets.UTF_8)
            if (entity.size > limits.maximumOutputBytes) limit("static", "Static body exceeds output limit")
            if (contentLength(headers)?.let { it != entity.size.toLong() } == true) {
                invalid("static.content-length", "Configured static length conflicts with UTF-8 entity")
            }
            val body = if (request.method == "HEAD") ByteArray(0) else entity
            return RequestExecution.Terminal(finalize(
                ActionResponseHead(response.status, headers), body,
                request.method, if (request.method == "HEAD") entity.size.toLong() else null,
            ))
        }
        val route = plan.routeOverride
        if (!(route?.host ?: request.host).matches(Regex("[A-Za-z0-9.:%_-]+")) ||
            request.method.any { it !in 'A'..'Z' }) invalid("request", "Invalid request host/method")
        val headers = fromMap(plan.requestHeaders)
        validateHeaders(headers)
        validateRequestFraming(headers)
        val originalHeaders = fromMap(request.headers)
        for (field in listOf("content-length", "transfer-encoding", "content-encoding")) {
            if (values(headers, field) != values(originalHeaders, field)) {
                unsupported("request.$field", "Request actions cannot change framing/encoding without a body executor")
            }
        }
        val targetPath = plan.requestPath
        if (!targetPath.startsWith("/") || targetPath.any { it <= ' ' || it == '\u007f' }) {
            invalid("request.path", "Expected a valid HTTP origin-form request target")
        }
        return RequestExecution.Forward(ResolvedRequest(
            route?.scheme ?: request.scheme, route?.host ?: request.host, route?.port ?: request.port,
            targetPath, request.method, Collections.unmodifiableList(headers.toList()), plan.tls, plan.echRequired,
        ))
    }

    /**
     * No body input or buffer quota: the transport streams using its existing framing/encoding.
     * Framing/encoding-changing header actions are rejected before an upstream body is needed.
     * responseInterceptorsEnabled is the actual setting.script.enabled gate from the source.
     */
    fun transformResponseHead(
        plan: RequestPlan,
        request: RuleRequest,
        upstream: ActionResponseHead,
        responseInterceptorsEnabled: Boolean,
    ): StreamingActionHead {
        supportedPlan(plan)
        if (plan.localResponse != null) invalid("response", "Use executeRequest for terminal plans")
        val actions = if (responseInterceptorsEnabled) plan.responseSteps else emptyList()
        for (step in actions) when (step.action) {
            is ResponseAction.Cache -> unsupported("cacheResponse", "Cache policy dates require a separate scheduler")
            is ResponseAction.ScriptInjection -> unsupported("script", "Script asset resolution/serving is not implemented")
            is ResponseAction.UnsupportedBody -> unsupported("responseReplace.body", "Pinned source has no body replacement executor")
            else -> Unit
        }
        validateHead(upstream)
        validateEntityFraming(upstream.headers)
        var headers = upstream.headers.toMutableList()
        for (step in actions) {
            when (val action = step.action) {
                is ResponseAction.Cors -> {
                    val changed = HeaderTransforms.afterOptionsHeaders(
                        request.method, plan.requestHeaders, raw(headers), action.crossOriginResourcePolicy,
                    )
                    headers = materialize(changed)
                }
                is ResponseAction.Replace -> if (upstream.status == action.onlyStatus) {
                    val replacements = action.headers.toMutableMap()
                    var diagnostic = ""
                    if (action.doDownload) {
                        val filename = Regex("^.*/([^/?]+)/?(\\?.*)?$").find(action.requestPath)?.groupValues?.get(1)
                            ?: "UNKNOWN_FILENAME"
                        replacements["content-disposition"] = "attachment; filename=\"${encodeComponent(filename)}\""
                        if (replacements["content-type"] == null) replacements["content-type"] = "application/octet-stream"
                        if (!action.hasCachePolicy) {
                            listOf("cache-control", "last-modified", "expires").forEach { replacements[it] = "[remove]" }
                        }
                        diagnostic = "download:$filename"
                    }
                    val changed = HeaderTransforms.replaceResponseHeaders(raw(headers), replacements)
                    if (changed.previousHeaders != null) diagnostic += if (diagnostic.isEmpty()) "headers" else ",headers"
                    headers = materialize(changed)
                    if (diagnostic.isNotEmpty()) set(headers, "DS-ResponseReplace-Interceptor", diagnostic)
                }
                else -> Unit
            }
        }
        validateHeaders(headers)
        validateEntityFraming(headers)
        for (field in listOf("content-length", "transfer-encoding", "content-encoding")) {
            if (values(headers, field) != values(upstream.headers, field)) {
                unsupported(field, "Header-only actions must preserve original framing and content encoding")
            }
        }
        return StreamingActionHead(upstream.copy(headers = Collections.unmodifiableList(headers.toList())))
    }

    /** append is resolved literal data; ScriptInjection descriptors remain unsupported. */
    fun prepareResponse(
        plan: RequestPlan,
        request: RuleRequest,
        upstream: ActionResponseHead,
        entity: InputStream,
        responseInterceptorsEnabled: Boolean,
        append: ResolvedHtmlAppend? = null,
    ): PreparedActionResponse {
        if (append != null && !responseInterceptorsEnabled) unsupported("append", "Response processor gate is disabled")
        val streaming = transformResponseHead(plan, request, upstream, responseInterceptorsEnabled)
        val headers = streaming.head.headers.toMutableList()
        val encoded = readBounded(entity, limits.maximumInputBytes, "response.input")
        val noBody = request.method == "HEAD" || upstream.status in listOf(204, 205, 304)
        if (noBody && encoded.isNotEmpty()) invalid("response.body", "Body forbidden for request method/status")
        val declared = contentLength(upstream.headers)
        if (!noBody && declared != null && declared != encoded.size.toLong()) {
            invalid("content-length", "Input body length differs from declared Content-Length")
        }
        if (!noBody) {
            val replacedLength = contentLength(headers)
            if (replacedLength != null && replacedLength != encoded.size.toLong()) {
                invalid("content-length", "Header replacement conflicts with encoded entity length")
            }
        }
        var body = encoded
        val insertion = append?.takeIf { it.head.isNotEmpty() || it.body.isNotEmpty() }
        val isHtml = first(headers, "content-type")?.let { Regex("text/html|application/xhtml\\+xml").containsMatchIn(it) } == true
        if (insertion != null && !noBody && isHtml && first(upstream.headers, "content-length") != "0") {
            val contentType = first(headers, "content-type").orEmpty()
            val charset = Regex("charset\\s*=\\s*[\"']?([^;\\s\"']+)", RegexOption.IGNORE_CASE)
                .find(contentType)?.groupValues?.get(1)?.lowercase(Locale.ROOT)
            if (charset != null && charset !in listOf("utf-8", "utf8", "us-ascii")) {
                unsupported("append.charset", "Only UTF-8/ASCII insertion is supported")
            }
            if (charset == "us-ascii" && (insertion.head + insertion.body).any { it.code > 127 }) {
                unsupported("append.charset", "Non-ASCII insertion conflicts with declared charset")
            }
            val encoding = first(headers, "content-encoding")?.lowercase(Locale.ROOT)
            val decoded = decode(encoded, encoding)
            body = inject(decoded, insertion)
            body = encode(body, encoding)
            // Source adjusts CSP only on the injection path; report-only CSP remains untouched.
            first(headers, "content-security-policy")?.let { set(headers, "content-security-policy", patchCsp(it)) }
        }
        if (body.size > limits.maximumOutputBytes) limit("response.output", "Encoded body exceeds output limit")
        return finalize(
            upstream.copy(headers = headers), body, request.method,
            if (noBody) contentLength(headers) else null,
        )
    }

    /** No sink interaction occurs until preparation succeeds completely. */
    fun executeResponse(
        plan: RequestPlan,
        request: RuleRequest,
        upstream: ActionResponseHead,
        entity: InputStream,
        sink: OutputStream,
        responseInterceptorsEnabled: Boolean,
        append: ResolvedHtmlAppend? = null,
    ) = prepareResponse(plan, request, upstream, entity, responseInterceptorsEnabled, append).writeHttp1(sink)

    private fun supportedPlan(plan: RequestPlan) {
        if (plan.hasBlockingIssues) unsupported(
            plan.issues.joinToString(",") { it.action }, "Plan has unsupported/conflicting enabled configuration",
        )
    }

    private fun finalize(head: ActionResponseHead, body: ByteArray, method: String, metadataLength: Long?): PreparedActionResponse {
        validateHead(head)
        val headers = head.headers.toMutableList()
        val nominated = values(headers, "connection").flatMap { it.split(',') }.map { it.trim().lowercase(Locale.ROOT) }
        if ("content-encoding" in nominated && values(headers, "content-encoding").isNotEmpty()) {
            invalid("connection", "Connection options cannot remove entity Content-Encoding")
        }
        val forbidden = hopByHop + nominated
        headers.removeAll { it.name.lowercase(Locale.ROOT) in forbidden || it.name.equals("content-length", true) }
        when {
            head.status == 204 -> Unit
            head.status == 205 -> set(headers, "Content-Length", "0")
            method == "HEAD" || head.status == 304 -> metadataLength?.let { set(headers, "Content-Length", it.toString()) }
            else -> set(headers, "Content-Length", body.size.toString())
        }
        if ((method == "HEAD" || head.status in listOf(204, 205, 304)) && body.isNotEmpty()) {
            invalid("response.body", "Response must not carry an entity")
        }
        validateHeaders(headers)
        return PreparedActionResponse(head.copy(headers = Collections.unmodifiableList(headers.toList())), body)
    }

    private fun validateHead(head: ActionResponseHead) {
        if (head.status !in 200..599) unsupported("response.status", "Informational/upgrade responses belong to the transport")
        if (head.reason.length > 1024 || head.reason.any { it.code !in 32..255 || it.code == 127 }) {
            invalid("response.reason", "Invalid reason phrase")
        }
        validateHeaders(head.headers)
    }

    private fun validateHeaders(headers: List<ActionHeader>) {
        if (headers.size > limits.maximumHeaderCount) throw RuleActionException(ActionFailure.HEADER_LIMIT, "headers", "Too many headers")
        var size = 0L
        for ((name, value) in headers) {
            if (!name.matches(Regex("[!#$%&'*+.^_`|~0-9A-Za-z-]+")) ||
                value.any { it.code > 255 || it == '\r' || it == '\n' || it.code == 127 || it.code < 32 && it != '\t' }) {
                invalid("headers", "Invalid HTTP field name/value")
            }
            size += name.length + value.length + 4
        }
        if (size > limits.maximumHeaderBytes) throw RuleActionException(ActionFailure.HEADER_LIMIT, "headers", "Headers exceed byte limit")
    }

    private fun validateRequestFraming(headers: List<ActionHeader>) = validateEntityFraming(headers)

    private fun validateEntityFraming(headers: List<ActionHeader>) {
        val transfer = values(headers, "transfer-encoding")
        if (transfer.isNotEmpty() && (transfer.size != 1 || !transfer.single().equals("chunked", true))) {
            unsupported("transfer-encoding", "Only an already dechunked single chunked entity is supported")
        }
        if (transfer.isNotEmpty() && values(headers, "content-length").isNotEmpty()) {
            invalid("framing", "Conflicting Transfer-Encoding and Content-Length")
        }
        if (values(headers, "trailer").isNotEmpty()) unsupported("trailer", "Trailers require transport-level handling")
        if (values(headers, "content-encoding").size > 1) unsupported("content-encoding", "Multiple content encodings are unsupported")
        contentLength(headers)
    }

    private fun contentLength(headers: List<ActionHeader>): Long? {
        val values = values(headers, "content-length")
        if (values.isEmpty()) return null
        if (values.size != 1 || !values.single().matches(Regex("[0-9]+"))) invalid("content-length", "Ambiguous Content-Length")
        return values.single().toLongOrNull() ?: invalid("content-length", "Content-Length exceeds supported range")
    }

    private fun fromMap(headers: Map<String, Any?>): List<ActionHeader> = buildList {
        for ((name, value) in headers) {
            val items = if (value is List<*>) value else listOf(value)
            for (item in items) {
                val text = when (item) {
                    is String -> item
                    is Number -> item.toString()
                    else -> invalid("headers", "Expected string/numeric header values")
                }
                add(ActionHeader(name, text))
            }
        }
    }

    private fun raw(headers: List<ActionHeader>): List<Any?> = headers.flatMap { listOf(it.name, it.value) }

    private fun materialize(result: ResponseHeadersResult): MutableList<ActionHeader> {
        val headers = result.rawHeaders.chunked(2).flatMap { pair ->
            fromMap(mapOf((pair[0] as String) to pair[1]))
        }.toMutableList()
        for ((name, value) in result.setHeaders) {
            headers.removeAll { it.name.equals(name, true) }
            headers.addAll(fromMap(mapOf(name to value)))
        }
        return headers
    }

    private fun set(headers: MutableList<ActionHeader>, name: String, value: String) {
        headers.removeAll { it.name.equals(name, true) }
        headers.add(ActionHeader(name, value))
    }

    private fun first(headers: List<ActionHeader>, name: String): String? = values(headers, name).firstOrNull()
    private fun values(headers: List<ActionHeader>, name: String): List<String> =
        headers.filter { it.name.equals(name, true) }.map { it.value }

    private fun readBounded(input: InputStream, maximum: Int, action: String): ByteArray {
        val output = LimitedOutput(maximum, action)
        val buffer = ByteArray(8192)
        while (true) {
            val count = input.read(buffer)
            if (count < 0) break
            if (count == 0) {
                val byte = input.read()
                if (byte < 0) break
                output.write(byte)
            } else output.write(buffer, 0, count)
        }
        return output.bytes()
    }

    private fun decode(encoded: ByteArray, encoding: String?): ByteArray {
        try {
            val stream = when (encoding) {
                null, "", "identity" -> ByteArrayInputStream(encoded)
                "gzip" -> GZIPInputStream(ByteArrayInputStream(encoded))
                "deflate" -> InflaterInputStream(ByteArrayInputStream(encoded))
                else -> throw RuleActionException(ActionFailure.UNSUPPORTED_ENCODING, "append", "Unsupported injection encoding: $encoding")
            }
            return stream.use { readBounded(it, limits.maximumDecodedBytes, "append.decoded") }
        } catch (failure: RuleActionException) {
            throw failure
        } catch (failure: java.io.IOException) {
            throw RuleActionException(ActionFailure.MALFORMED_BODY, "append", "Invalid compressed entity", failure)
        }
    }

    private fun encode(decoded: ByteArray, encoding: String?): ByteArray {
        val output = LimitedOutput(limits.maximumOutputBytes, "append.encoded")
        when (encoding) {
            null, "", "identity" -> output.write(decoded)
            "gzip" -> GZIPOutputStream(output).use { it.write(decoded) }
            "deflate" -> DeflaterOutputStream(output).use { it.write(decoded) }
            else -> throw RuleActionException(ActionFailure.UNSUPPORTED_ENCODING, "append", "Unsupported injection encoding: $encoding")
        }
        return output.bytes()
    }

    private fun inject(input: ByteArray, append: ResolvedHtmlAppend): ByteArray {
        if (append.head.length.toLong() + append.body.length > limits.maximumAppendBytes) limit("append", "Insertion exceeds byte limit")
        fun utf8(text: String): ByteArray = try {
            val encoded = StandardCharsets.UTF_8.newEncoder().encode(java.nio.CharBuffer.wrap(text))
            ByteArray(encoded.remaining()).also { encoded.get(it) }
        } catch (failure: java.nio.charset.CharacterCodingException) {
            invalid("append", "Insertion contains malformed Unicode", failure)
        }
        val head = utf8(append.head)
        val body = utf8(append.body)
        if (head.size.toLong() + body.size > limits.maximumAppendBytes) limit("append", "Insertion exceeds byte limit")
        fun insert(bytes: ByteArray, tags: List<String>, text: ByteArray): ByteArray {
            if (text.isEmpty()) return bytes
            val tag = tags.firstNotNullOfOrNull { tag ->
                val target = tag.toByteArray(StandardCharsets.US_ASCII)
                (0..bytes.size - target.size).firstOrNull { index ->
                    target.indices.all { bytes[index + it] == target[it] }
                }
            } ?: return bytes
            val result = LimitedOutput(limits.maximumDecodedBytes, "append.result")
            result.write(bytes, 0, tag)
            result.write(text)
            result.write(bytes, tag, bytes.size - tag)
            return result.bytes()
        }
        return insert(insert(input, listOf("</head>", "</HEAD>"), head), listOf("</body>", "</BODY>"), body)
    }

    private fun patchCsp(policy: String): String {
        val hasDirective = Regex("script-src(-elem)?\\s+", RegexOption.IGNORE_CASE).containsMatchIn(policy)
        val replaced = Regex("script-src(-elem)?\\s+([^;]*)", RegexOption.IGNORE_CASE).replace(policy) { match ->
            val value = match.groupValues[2].trim()
            if (value.contains("'self'")) match.value else "script-src${match.groupValues[1]} 'self' $value"
        }
        return if (hasDirective) replaced else "script-src 'self'; $replaced"
    }

    private fun encodeComponent(value: String): String = buildString {
        for (byte in value.toByteArray(StandardCharsets.UTF_8)) {
            val code = byte.toInt() and 255
            val char = code.toChar()
            if (char in 'a'..'z' || char in 'A'..'Z' || char in '0'..'9' || char in "-_.!~*'()") append(char)
            else append('%').append("0123456789ABCDEF"[code shr 4]).append("0123456789ABCDEF"[code and 15])
        }
    }

    private class LimitedOutput(private val maximum: Int, private val action: String) : OutputStream() {
        private val output = ByteArrayOutputStream(minOf(maximum, 8192))
        override fun write(value: Int) {
            reserve(1)
            output.write(value)
        }
        override fun write(bytes: ByteArray, offset: Int, length: Int) {
            reserve(length)
            output.write(bytes, offset, length)
        }
        private fun reserve(count: Int) {
            if (output.size().toLong() + count > maximum) {
                throw RuleActionException(ActionFailure.BODY_LIMIT, action, "Body exceeds configured limit")
            }
        }
        fun bytes(): ByteArray = output.toByteArray()
    }

    private fun unsupported(action: String, message: String): Nothing =
        throw RuleActionException(ActionFailure.UNSUPPORTED_ACTION, action, message)
    private fun invalid(action: String, message: String, cause: Throwable? = null): Nothing =
        throw RuleActionException(ActionFailure.INVALID_INPUT, action, message, cause)
    private fun limit(action: String, message: String): Nothing =
        throw RuleActionException(ActionFailure.BODY_LIMIT, action, message)

    companion object {
        private val hopByHop = setOf(
            "connection", "keep-alive", "proxy-connection", "transfer-encoding", "upgrade",
            "http2-settings", "te", "trailer", "proxy-authenticate", "proxy-authorization",
        )
    }
}
