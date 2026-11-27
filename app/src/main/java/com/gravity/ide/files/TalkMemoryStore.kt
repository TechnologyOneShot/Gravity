package com.gravity.ide.files

import android.content.Context
import com.gravity.ide.core.TalkMemory

/**
 * Where Yamini keeps what the user told it about themselves in live talks (core/TalkMemory): on
 * this phone only, in the app's private storage. The user sees and deletes it in Settings → Voice.
 * It is part of what the AI reads in live mode (so with a cloud AI, it is sent along with the talk).
 */
object TalkMemoryStore {
    private fun prefs(context: Context) = context.getSharedPreferences("talk_memory", Context.MODE_PRIVATE)

    /** On by default; off: nothing new is remembered and nothing is given to the AI. */
    fun enabled(context: Context): Boolean = prefs(context).getBoolean("on", true)
    fun setEnabled(context: Context, on: Boolean) = prefs(context).edit().putBoolean("on", on).apply()

    fun facts(context: Context): List<TalkMemory.Fact> = TalkMemory.decode(prefs(context).getString("facts", null))

    /** Remembers what [said] tells about the user. Returns the new facts (empty if none or off). */
    fun remember(context: Context, said: String): List<TalkMemory.Fact> {
        if (!enabled(context)) return emptyList()
        val new = TalkMemory.extract(said)
        if (new.isNotEmpty()) prefs(context).edit().putString("facts", TalkMemory.encode(TalkMemory.merge(facts(context), new))).apply()
        return new
    }

    fun forget(context: Context, fact: TalkMemory.Fact) =
        prefs(context).edit().putString("facts", TalkMemory.encode(facts(context).filter { it != fact })).apply()

    fun clear(context: Context) = prefs(context).edit().remove("facts").apply()

    /** The memory line for the AI ("" when off or empty). */
    fun forPrompt(context: Context): String = if (enabled(context)) TalkMemory.forPrompt(facts(context)) else ""
}
