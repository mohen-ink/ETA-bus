package com.mohen.etabus.storage

import android.content.Context
import android.icu.text.Transliterator
import com.google.gson.Gson
import com.mohen.etabus.model.CityConfig
import dagger.hilt.android.qualifiers.ApplicationContext
import java.util.Locale
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class CityCatalog @Inject constructor(@ApplicationContext context: Context) {
    val cities: List<CityConfig> = context.assets.open("cities.json").bufferedReader().use { reader ->
        Gson().fromJson(reader, Array<CityConfig>::class.java).toList()
    }

    private val byCode = cities.associateBy(CityConfig::code)
    private val transliterator by lazy { Transliterator.getInstance("Han-Latin; Latin-ASCII; Upper") }
    private val initials = mutableMapOf<String, String>()

    init {
        require(byCode.containsKey("0719")) { "城市配置缺少默认城市 0719" }
        require(cities.all { it.code.isNotBlank() && it.host.isNotBlank() && it.port.toIntOrNull() != null && it.dir.isNotBlank() }) {
            "城市配置不完整"
        }
    }

    fun find(code: String): CityConfig = byCode[code] ?: byCode.getValue("0719")

    @Synchronized
    fun initial(city: CityConfig): String = initials.getOrPut(city.code) {
        val name = city.name.trim()
        val reading = when {
            name.startsWith("重庆") -> "C"
            name.startsWith("厦门") -> "X"
            name.startsWith("长春") || name.startsWith("长沙") || name.startsWith("长治") -> "C"
            name.startsWith("六安") -> "L"
            else -> transliterator.transliterate(name).trim().take(1).uppercase(Locale.ROOT)
        }
        reading.takeIf { it.length == 1 && it[0] in 'A'..'Z' } ?: "#"
    }
}
