package com.lulu.agent.data.repository

import com.lulu.agent.data.pref.AppSettings
import com.lulu.agent.data.pref.EncryptedDataStore
import com.lulu.agent.llm.config.LlmConfig

class ConfigRepository(
    private val encryptedDataStore: EncryptedDataStore,
    private val appSettings: AppSettings
) {
    fun getLlmConfig(): LlmConfig = encryptedDataStore.getLlmConfig()
    fun setLlmConfig(config: LlmConfig) = encryptedDataStore.setLlmConfig(config)
    fun hasValidApiKey(): Boolean = encryptedDataStore.hasValidApiKey()

    fun getResumeMarkdown(): String = encryptedDataStore.getResumeMarkdown()
    fun setResumeMarkdown(markdown: String) = encryptedDataStore.setResumeMarkdown(markdown)

    fun getSpeedFactor(): Float = appSettings.getSpeedFactor()
    fun getAdjustedDelay(originDelay: Long): Long = appSettings.getAdjustedDelay(originDelay)

    fun getMaxDailyGreetings(): Int = appSettings.getMaxDailyGreetings()
    fun setMaxDailyGreetings(count: Int) = appSettings.setMaxDailyGreetings(count)

    fun getMinSalaryFilterK(): Int = appSettings.getMinSalaryFilterK()
    fun setMinSalaryFilterK(k: Int) = appSettings.setMinSalaryFilterK(k)

    fun isFilterOutsourcing(): Boolean = appSettings.isFilterOutsourcing()
    fun setFilterOutsourcing(filter: Boolean) = appSettings.setFilterOutsourcing(filter)

    fun isPaused(): Boolean = appSettings.isPaused()
    fun setPaused(paused: Boolean) = appSettings.setPaused(paused)
}
