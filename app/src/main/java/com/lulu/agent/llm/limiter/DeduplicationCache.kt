package com.lulu.agent.llm.limiter

import android.util.LruCache
import com.lulu.agent.llm.model.response.JDEvalResponse
import java.security.MessageDigest

/**
 * 岗位 JD 指纹去重缓存中心
 */
object DeduplicationCache {

    // 内存维护最多 500 个近期的岗位指纹评估结论
    private val memoryCache = LruCache<String, JDEvalResponse>(500)

    /**
     * 计算 JD 纯文本的唯一 MD5 指纹
     */
    fun computeFingerprint(rawJd: String): String {
        val cleaned = rawJd.trim().replace("\\s+".toRegex(), "")
        return md5(cleaned)
    }

    /**
     * 获取缓存中的评估结果
     */
    fun getCachedEvaluation(rawJd: String, scope: String): JDEvalResponse? {
        if (rawJd.isBlank()) return null
        val key = "$scope:${computeFingerprint(rawJd)}"
        return memoryCache.get(key)
    }

    /**
     * 写入缓存
     */
    fun putEvaluation(rawJd: String, response: JDEvalResponse, scope: String) {
        if (rawJd.isBlank()) return
        val key = "$scope:${computeFingerprint(rawJd)}"
        memoryCache.put(key, response)
    }

    fun contains(rawJd: String, scope: String): Boolean {
        if (rawJd.isBlank()) return false
        val key = "$scope:${computeFingerprint(rawJd)}"
        return memoryCache.get(key) != null
    }

    fun clear() {
        memoryCache.evictAll()
    }

    private fun md5(input: String): String {
        val md = MessageDigest.getInstance("MD5")
        val bytes = md.digest(input.toByteArray(Charsets.UTF_8))
        return bytes.joinToString("") { "%02x".format(it) }
    }
}
