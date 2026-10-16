package io.nekohasekai.sfa.profile

/** Shared validation order for imports, refreshes and subscription URL changes. */
object ProfileValidation {
    fun validate(raw: String, source: String, normalize: (String) -> String, check: (String) -> Unit): String {
        require(raw.toByteArray(Charsets.UTF_8).size <= ProfileDocument.MAX_BYTES)
        // ★ 16.10.2026: списки URI/Base64 сначала преобразуем; исходный JSON проверяем
        // до преобразования, чтобы не скрыть дубли ключей и подтверждённое истечение.
        if (ProfileDocument.isOwnSource(source) && raw.trimStart().startsWith("{")) {
            if (ProfileDocument.inspect(raw, source)) throw ProfileFailure(ProfileFailure.Kind.EMPTY)
        }
        val content = normalize(raw)
        if (ProfileDocument.inspect(content, source)) throw ProfileFailure(ProfileFailure.Kind.EMPTY)
        check(content)
        return content
    }
}
