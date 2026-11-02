package com.example.lookup

import com.example.data.Repository
import com.kite.di.runtime.Kite

/**
 * The other shape of the same demand, and the harder one: the only thing this module
 * wants from `:data` is resolved inside a function body, which KSP does not read. No
 * constructor names [Repository], so nothing this module exports can record the
 * dependency — the build has to notice that `:data` ships unmerged instead.
 */
class Lookup {
    fun text(): String = Kite.get<Repository>().load()
}
